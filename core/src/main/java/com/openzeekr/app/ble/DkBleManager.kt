package com.openzeekr.app.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanSettings
import android.os.ParcelUuid
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.util.Log
import com.openzeekr.app.util.Logx
import com.openzeekr.app.util.SteadyClock
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

/**
 * Owns BLE scan/connect to the vehicle and exposes a real [DkSession].
 *
 * Flow: scan (service UUID) or connect by MAC -> request MTU -> discover ->
 * enable notify on 2A11 & 2A13 -> (credential present) establish DK session.
 * Implements [DkTransport]: writes are GATT-fragmented (see [DkFragmenter]) and
 * notifications are reassembled + CRC-checked ([DkReassembler]) into DK frames.
 */
class DkBleManager(base: Context) : DkTransport, ProximityLink {

    // Attributed context (API 30+) so BLE scan/GATT ops carry the manifest-declared "proximity" tag and
    // AppOps stops logging "attributionTag not declared". Below R it's the plain context (no attribution).
    private val appContext: Context =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) base.createAttributionContext(ATTRIBUTION_TAG) else base

    enum class State { IDLE, SCANNING, CONNECTING, CONNECTED, SESSION_READY, ERROR }

    private val _state = MutableStateFlow(State.IDLE)
    override val state: StateFlow<State> = _state
    @Volatile var lastError: String? = null; private set
    /** Service-owned recovery hook: status 133 must return to the screen-off-safe offloaded route. */
    @Volatile var onStatus133Recovery: (() -> Unit)? = null

    /** Until this time, keep the phone's DK link warm after an unlock. The car may ask for the
     *  authenticated BLE key again when drive authorization starts; losing the link immediately after
     *  opening the door is what produced the intermittent "key not present" symptom. */
    @Volatile var driveAuthorizationUntilMs: Long = 0L
        private set

    override fun noteUnlockConfirmed() {
        driveAuthorizationUntilMs = SteadyClock.wallMs() + DRIVE_AUTHORIZATION_WINDOW_MS
        Logx.d("ble", "unlock confirmed - keeping DK presence ready for drive authorization")
    }

    val driveAuthorizationActive: Boolean
        get() = SteadyClock.wallMs() < driveAuthorizationUntilMs

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var inboundHandler: ((Int, ByteArray) -> Unit)? = null

    private val adapter: BluetoothAdapter? by lazy {
        (appContext.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
    }
    val bluetoothAvailable: Boolean get() = adapter?.isEnabled == true

    /**
     * Whether the Bluetooth adapter is currently ON. Driven by [btStateReceiver] (ACTION_STATE_CHANGED)
     * so retry/keep-alive loops can suspend while BT is off and resume when it comes back, instead of
     * hammering a dead stack. Seeded from the live adapter state.
     */
    private val _adapterEnabled = MutableStateFlow(bluetoothAvailable)
    val adapterEnabled: StateFlow<Boolean> = _adapterEnabled

    /**
     * Reacts to the user toggling Bluetooth. On OFF we tear the session down ONCE (the GATT binder is
     * already dead - Android won't reliably deliver onConnectionStateChange(DISCONNECTED) in this case)
     * and stop the offloaded scan, so nothing keeps retrying against a dead stack. On ON we just flip
     * the flag; keep-alive (already gated on [bluetoothAvailable]) reconnects on its own.
     */
    private val btStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, intent: Intent?) {
            if (intent?.action != BluetoothAdapter.ACTION_STATE_CHANGED) return
            when (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)) {
                BluetoothAdapter.STATE_TURNING_OFF, BluetoothAdapter.STATE_OFF -> {
                    if (_adapterEnabled.value) {
                        _adapterEnabled.value = false
                        Logx.d("ble", "bluetooth turned OFF - tearing down session, stopping retries")
                        runCatching { disconnect() }
                        runCatching { disarmPresenceScan() }
                    }
                }
                BluetoothAdapter.STATE_ON -> {
                    if (!_adapterEnabled.value) {
                        _adapterEnabled.value = true
                        Logx.d("ble", "bluetooth turned ON - keep-alive may reconnect")
                    }
                }
            }
        }
    }

    init {
        runCatching {
            val filter = IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                appContext.registerReceiver(btStateReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            else
                appContext.registerReceiver(btStateReceiver, filter)
        }.onFailure { Logx.w("ble", "bt state receiver register failed: ${it.message}") }
    }

    // Wear OS BLE: Samsung's watch stack advertises hardware scan-batching as supported but often
    // never flushes it (no onBatchScanResults, no onScanFailed) — the scan just silently finds
    // nothing. So on a watch we default to IMMEDIATE (unbatched) delivery. The phone keeps batched
    // low-power delivery for the background keep-alive. (Verified 2026-09-15: phone connects from a
    // spot where the watch's batched scan matched zero devices.)
    private val isWear: Boolean by lazy {
        appContext.packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_WATCH)
    }

    // ---- session (stable instance; reads the credential at establish() time) ----
    @Volatile private var credential: DkCredential? = null

    /** Per-car vehicle-cert pins (trust-on-first-use), keyed by VIN. The fingerprint is the car's
     *  PUBLIC cert hash (not secret), so plain prefs are fine. Binds the DK session to the specific
     *  car that paired first, so a fake/relay car with any genuine Geely cert can't harvest the key. */
    private val certPins = object : DkTrust.VehicleCertPinStore {
        private val prefs = appContext.getSharedPreferences("dk_cert_pins", Context.MODE_PRIVATE)
        override fun get(vin: String): String? = prefs.getString(vin, null)
        override fun put(vin: String, fingerprint: String) { prefs.edit().putString(vin, fingerprint).apply() }
    }

    /** Our own key module's advertised name, learned after a successful DK session (phone only). */
    /** Battery (0.3.1): Android's limit of 5 scan starts per 30 s, kept with one start of margin. */
    private val scanStarts = ScanStartLimiter()
    @Volatile private var staleScanCleared = false

    /**
     * Battery (0.3.1): an offloaded presence scan is registered with a PendingIntent and outlives the
     * process that armed it. A new process starts with [presenceArmed] false and never stopped it, so a
     * LOW_LATENCY scan from before a restart could keep running unseen. Stopped once per process.
     */
    @SuppressLint("MissingPermission")
    private fun clearStalePresenceScan(scanner: android.bluetooth.le.BluetoothLeScanner) {
        if (staleScanCleared) return
        staleScanCleared = true
        runCatching { scanner.stopScan(presencePendingIntent()) }
    }

    /** Plan H3 (0.3.1): another Zeekr whose handshake just failed waits behind other candidates. */
    private val failedHandshakes = FailedHandshakeDeferral()
    @Volatile private var scanStartedAtMs = 0L
    private val ownKey = OwnKeyNameFilter(object : OwnKeyNameFilter.Store {
        private val prefs = appContext.getSharedPreferences("dk_own_key", Context.MODE_PRIVATE)
        override fun load(): String? = prefs.getString("name", null)
        override fun save(name: String?) { prefs.edit().putString("name", name).apply() }
    })
    /** Name of the advertiser we are connecting to; learned as ours once its session is READY. */
    @Volatile private var connectingName: String? = null
    private val skippedOtherCars = mutableSetOf<String>()
    private val weakAdvertLogged = mutableSetOf<String>()

    /** Single stable session so Deps/controllers capture it once; needs a credential to establish(). */
    override val session: DkSession by lazy { RealDkSession(this, { credential }, certPins = certPins) }

    /** Provide provisioned key material (from cloud provisioning / import). */
    fun setCredential(cred: DkCredential) { credential = cred }

    /** True once a provisioned key is loaded, i.e. a DK session can be established. */
    val hasCredential: Boolean get() = credential != null

    // ---- live RSSI of the connected car (for the RPA proximity gate) ----
    private val rssiReads = PendingRssiRead()
    private val rssiPollLock = Mutex()

    /** Epoch-ms of the last inbound DK frame from the car. The car pushes status (0x121 VSTATUS_SYNC
     *  etc.) when the vehicle state CHANGES (movement/doors) — bursts with long silent gaps while
     *  parked-still — so a fresh frame after silence means "activity" (e.g. you're getting out). */
    @Volatile override var lastInboundMs: Long = 0L
        private set

    /** Fired on EVERY inbound frame from the car, on the BLE callback thread. The proximity
     *  controller uses it to wake instantly from its unlocked idle-wait the moment the car speaks. */
    @Volatile override var onInboundActivity: (() -> Unit)? = null

    /** Await this request's callback, never a scan result or the previous request's RSSI.
     * A timeout leaves the request pending until its late callback drains or GATT resets.
     */
    @SuppressLint("MissingPermission")
    override suspend fun pollRemoteRssi(): Int? = rssiPollLock.withLock {
        val current = gatt
        if (!bluetoothAvailable || current == null) {
            rssiReads.clear()
            return@withLock null
        }
        // Register before calling Android: a fast callback may run before readRemoteRssi returns.
        val request = rssiReads.begin(current) ?: return@withLock null
        val initiated = runCatching { current.readRemoteRssi() }.getOrDefault(false)
        if (!initiated) {
            rssiReads.rejected(request)
            return@withLock null
        }
        val sample = withTimeoutOrNull(600L) { request.reply.await() }
        if (gatt === current && bluetoothAvailable)
            sample?.valueAt(android.os.SystemClock.elapsedRealtime()) else null
    }

    // ---- GATT state ----
    @Volatile private var gatt: BluetoothGatt? = null
    private var chWrite1: BluetoothGattCharacteristic? = null
    private var chWrite2: BluetoothGattCharacteristic? = null
    private var chNotify1: BluetoothGattCharacteristic? = null
    private var chNotify2: BluetoothGattCharacteristic? = null
    private val reasm1 = DkReassembler()
    private val reasm2 = DkReassembler()
    private var maxChunk = 20
    private val writeLock = Mutex()
    private var writeAck: CompletableDeferred<Boolean>? = null
    private var notifyStep: CompletableDeferred<Boolean>? = null

    // The notify-enable + handshake coroutine kicked off in onServicesDiscovered. Tracked so a
    // mid-setup disconnect can CANCEL it — otherwise it blocks ~4 s on the notify-enable timeout and
    // then runs the DK handshake on an already-dead GATT ("write failed for 0x0101").
    private var setupJob: Job? = null

    // Unexpected mid-setup drops (status 19 — car/BLE-stack contention) are retried FAST via
    // reconnectLast rather than falling to the slow offloaded presence scan (~20 s). `deliberate`
    // marks a teardown WE initiated (forceReconnect/watch handover) so we don't fight the caller.
    @Volatile private var deliberate = false
    private var setupRetries = 0
    private val status133RecoveryPolicy = Status133RecoveryPolicy()

    // ---- scan state ----
    private var scanCb: ScanCallback? = null
    private var scanJob: Job? = null
    private val seenAdvertisers = mutableSetOf<String>()
    private val waitingRndLogged = mutableSetOf<String>()
    /** 8-byte broadcast-random from the matched car's advertisement (see [parseBroadcastRnd]). */
    @Volatile private var advBroadcastRnd: ByteArray? = null
    /** Per-MAC broadcast-random seen during this scan (the DK mfr-data advert is separate from
     *  the name advert and can arrive in a different PDU / be dropped at low RSSI). */
    private val rndByMac = mutableMapOf<String, ByteArray>()

    // Last successfully-matched device + its broadcast-random, cached so a forced reconnect can go
    // straight back to the car we just dropped (see [reconnectLast]) instead of re-scanning.
    private var lastDevice: BluetoothDevice? = null
    private var lastRnd: ByteArray? = null

    // ---------------- connect ----------------

    // ---- handshake-failure backoff ----
    // A car that accepts the GATT link but never completes the DK handshake (e.g. silently ignores
    // CONNECT_CONFIRM) otherwise causes an endless connect -> 8s stall -> status-19 drop -> reconnect
    // thrash (battery + log noise). After a few consecutive establish() failures we back off AUTO
    // reconnects for a short, growing window; a user-initiated connect calls [resetHandshakeBackoff].
    @Volatile private var handshakeFailStreak = 0
    @Volatile private var handshakeBackoffUntilMs = 0L

    private fun inHandshakeBackoff(): Boolean {
        val left = handshakeBackoffUntilMs - SteadyClock.wallMs()
        if (left > 0) { Logx.d("ble", "handshake backoff active (${left / 1000}s left) - not auto-connecting"); return true }
        return false
    }

    /** Clear the handshake-failure backoff so an explicit user connect isn't delayed. */
    fun resetHandshakeBackoff() { handshakeFailStreak = 0; handshakeBackoffUntilMs = 0L }

    @SuppressLint("MissingPermission")
    override fun connect(deviceMac: String?) {
        if (inHandshakeBackoff()) return
        // Idempotent: a second connect() while we're already scanning/connecting/connected
        // would start a *new* scan on the shared scanner — which resets state to SCANNING and
        // nulls advBroadcastRnd out from under the live session. That's what made proximity +
        // manual lock/unlock collide ("BT goes bunkers"). Only (re)connect from a resting state.
        when (_state.value) {
            State.SCANNING, State.CONNECTING, State.CONNECTED, State.SESSION_READY -> {
                Logx.d("ble", "connect() ignored — already ${_state.value}")
                return
            }
            else -> {}
        }
        Logx.d("ble", "connect(${deviceMac ?: "scan-by-service"}) credential=${if (credential != null) "present" else "none"}")
        val a = adapter ?: run { fail("no bluetooth adapter"); return }
        if (!a.isEnabled) { fail("bluetooth disabled"); return }
        lastError = null
        // Never hard-crash on a missing runtime permission (BLUETOOTH_SCAN/CONNECT
        // on API 31+) — surface it as an error state instead. The UI requests the
        // permission before calling this, but guard defensively.
        try {
            if (deviceMac != null) {
                _state.value = State.CONNECTING
                connectDevice(a.getRemoteDevice(deviceMac))
            } else {
                // IMMEDIATE (unbatched) delivery, always. Batched delivery (setReportDelay>0) does
                // NOT flush to a dozing app process when the screen is off — even with a filter that
                // makes the scan screen-off-legal and even holding a wakelock — so the batched
                // foreground re-scan matched nothing screen-off (confirmed 2026-09-15: offloaded
                // FIRST_MATCH fired, but the follow-up batched connect timed out at 20s every time).
                // Batching was only ever to avoid a per-advert log firehose, and the 0xFDFD/0x06FE
                // FILTER already solves that (only the car matches). So: immediate everywhere.
                startScan(a, useBatching = false)
            }
        } catch (e: SecurityException) {
            fail("missing Bluetooth permission (grant BLUETOOTH_SCAN/CONNECT): ${e.message}")
        }
    }

    /**
     * Reconnect straight to the LAST matched car — no scan. For a forced reconnect where we already
     * know exactly what we dropped: reuse the cached [BluetoothDevice] (it keeps the correct RANDOM
     * address type, unlike getRemoteDevice(mac)) and its broadcast-random (stable across the car's
     * RPA lifetime, so the DK connect-confirm key still derives). Returns false — so the caller can
     * fall back to [connect] with a scan — if there's no cached device, BT is off, or we're not at
     * rest. If the cached RPA has since rotated the direct connect just errors and the normal
     * error -> keep-alive scan path recovers.
     */
    @SuppressLint("MissingPermission")
    override fun reconnectLast(): Boolean {
        if (inHandshakeBackoff()) return false
        val dev = lastDevice ?: return false
        when (_state.value) {
            State.SCANNING, State.CONNECTING, State.CONNECTED, State.SESSION_READY -> {
                Logx.d("ble", "reconnectLast ignored — already ${_state.value}"); return false
            }
            else -> {}
        }
        val a = adapter ?: return false
        if (!a.isEnabled) return false
        lastError = null
        advBroadcastRnd = lastRnd
        Logx.d("ble", "reconnectLast -> ${dev.address} (no scan, rnd=${lastRnd?.joinToString("") { "%02x".format(it) } ?: "?"})")
        _state.value = State.CONNECTING
        connectDevice(dev)
        return true
    }

    /** During repeated 133 recovery, defer only weak offloaded hits and keep scanning. */
    fun shouldDeferWeakPresence(rssi: Int): Boolean =
        status133RecoveryPolicy.shouldDeferWeakPresence(rssi, android.os.SystemClock.elapsedRealtime())

    /**
     * Connect directly from the hardware-offloaded presence result that woke the process.
     *
     * Unlike a String MAC, [ScanResult.device] retains Android's RANDOM/RPA address type. The same
     * result can also contain the DK broadcast random, so the deep-sleep path must not discard it and
     * perform another 20-second scan. Returns false only when the split presence advert does not yet
     * contain a usable broadcastRnd; the caller then starts the normal per-MAC combining scan.
     */
    @SuppressLint("MissingPermission")
    fun connectFromPresence(result: ScanResult): Boolean {
        if (inHandshakeBackoff()) return false
        when (_state.value) {
            State.CONNECTING, State.CONNECTED, State.SESSION_READY -> {
                Logx.d("ble", "presence result ignored — already ${_state.value}")
                return true
            }
            else -> {}
        }
        if (WeakConnectGate.tooWeak(result.rssi)) {
            Logx.d("ble", "weak advert ${result.device.address} rssi=${result.rssi} - no direct connect at the edge of range")
            return false
        }
        val record = result.scanRecord
        val presenceName = record?.deviceName
        if (ownKey.decide(presenceName) == OwnKeyNameFilter.Decision.SKIP_OTHER_CAR) {
            Logx.d("ble", "skip other Zeekr ${result.device.address} (presence) - foreground scan for our key module")
            return false
        }
        val rnd = DkAdvertParser.parseBroadcastRnd(
            rawRecord = record?.bytes,
            manufacturerData = record?.manufacturerSpecificData?.get(DK_MFR_COMPANY_ID),
        ) ?: run {
            Logx.d("ble", "presence result ${result.device.address} has no broadcastRnd — foreground combining scan required")
            return false
        }
        if (adapter?.isEnabled != true) return false

        // A foreground scan may have started just before the offloaded PendingIntent arrived.
        // The preserved result is strictly better (correct device + rnd), so atomically stop that
        // scan before opening one and only one GATT client.
        if (_state.value == State.SCANNING) {
            adapter?.bluetoothLeScanner?.let { stopScanInternal(it) }
        }

        lastError = null
        advBroadcastRnd = rnd
        connectingName = presenceName
        lastDevice = result.device
        lastRnd = rnd
        // Advertising RSSI remains approach evidence; it must not masquerade as a GATT read.
        rssiReads.clear()
        Logx.d("ble", "presence direct-connect ${result.device.address} rssi=${result.rssi} " +
            "rnd=${rnd.joinToString("") { "%02x".format(it) }}")
        _state.value = State.CONNECTING
        connectDevice(result.device)
        return true
    }

    @SuppressLint("MissingPermission")
    private fun startScan(a: BluetoothAdapter, useBatching: Boolean = true) {
        val scanner = a.bluetoothLeScanner ?: run { fail("no LE scanner"); return }
        if (!scanStarts.tryStart(SteadyClock.wallMs())) {
            fail("scan start deferred: Android allows 5 scan starts per 30 s; the keep-alive retries")
            return
        }
        _state.value = State.SCANNING
        seenAdvertisers.clear(); waitingRndLogged.clear()
        skippedOtherCars.clear(); weakAdvertLogged.clear(); ownKey.onScanStart()
        scanStartedAtMs = SteadyClock.wallMs()
        rndByMac.clear()
        advBroadcastRnd = null
        // No device ScanFilter on the foreground connect: the car doesn't advertise the DK
        // service UUID and its name format can vary, so filtering risks missing it — matching
        // in-callback is faster/more reliable. To avoid the per-packet log firehose (the
        // framework logs one line per advertisement), request BATCHED delivery: results arrive
        // in periodic groups via onBatchScanResults instead of a continuous onScanResult stream.
        // If the device can't offload batching we fall back to immediate delivery (onScanFailed).
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .apply { if (useBatching) setReportDelay(REPORT_DELAY_MS) }
            .build()
        val target = UUID.fromString(DkProtocol.SERVICE_UUID)
        val advTarget = UUID.fromString(DK_ADV_SERVICE_UUID)  // the 16-bit UUID the car ADVERTISES

        // Handle one advertisement; returns true once it matched the car and started connecting.
        fun handleAdvert(result: ScanResult): Boolean {
            // Once we've matched and left the scanning state, ignore every further advert. The
            // batched scanner can deliver the car in several callbacks before stopScan takes
            // effect; without this guard we'd call connectGatt twice → two GATT clients to the
            // same device → status 133 (the connection never establishes). See onScanResult.
            if (_state.value != State.SCANNING) return true
            val dev = result.device
            val addr = dev.address ?: return false
            val rec = result.scanRecord
            val name = rec?.deviceName ?: runCatching { dev.name }.getOrNull()
            val uuids = rec?.serviceUuids
            // The DK broadcast-random rides a separate manufacturer-data PDU; capture it per-MAC
            // from every advert so it's ready whichever PDU triggers the name match.
            if (rndByMac[addr] == null) DkAdvertParser.parseBroadcastRnd(
                rawRecord = rec?.bytes,
                manufacturerData = rec?.manufacturerSpecificData?.get(DK_MFR_COMPANY_ID),
            )?.let { rndByMac[addr] = it }
            // Software match (the foreground scan is unfiltered): any ONE of these = our car.
            // Loosened from startsWith("Zeekr") to contains, and added service-data + DK mfr id, so a
            // car whose name/primary-UUID differ by region/firmware still matches.
            val matchesName = name?.contains("zeekr", ignoreCase = true) == true
            // target = DK GATT service (not advertised); advTarget = the 0xFDFD the car DOES advertise.
            // A screen-off scan can drop the scan-response name, so don't depend on it alone.
            val matchesUuid = uuids?.any { it.uuid == target || it.uuid == advTarget } == true
            val matchesSvcData = rec?.serviceData?.keys?.any { it.uuid == target || it.uuid == advTarget } == true
            val matchesMfr = (rec?.manufacturerSpecificData?.indexOfKey(DK_MFR_COMPANY_ID) ?: -1) >= 0
            if (matchesName || matchesUuid || matchesSvcData || matchesMfr) {
                // Every Zeekr advertises these identifiers; once we know our own key module's name,
                // another car nearby is skipped instead of costing a doomed handshake (field test).
                // An advert at the edge of range cannot carry a connection (field: every such
                // attempt ended in status 133). Keep scanning; the next, stronger advert connects.
                if (WeakConnectGate.tooWeak(result.rssi)) {
                    if (weakAdvertLogged.add(addr)) Logx.d("ble", "weak advert $addr rssi=${result.rssi} - waiting for a stronger one")
                    return false
                }
                if (ownKey.decide(name) == OwnKeyNameFilter.Decision.SKIP_OTHER_CAR) {
                    if (skippedOtherCars.add(addr)) Logx.d("ble", "skip other Zeekr $addr (not our key module)")
                    return false
                }
                if (failedHandshakes.deferNow(addr, SteadyClock.wallMs(), scanStartedAtMs)) {
                    if (skippedOtherCars.add(addr)) Logx.d("ble", "defer $addr (its handshake failed recently) - other cars first")
                    return false
                }
                // Only advertisers that match the car are logged (once per MAC per scan). Logging
                // every headset, TV and watch around filled the phone log within minutes (field
                // test: a key problem had already scrolled out of the log 15 min later).
                if (seenAdvertisers.add(addr)) {
                    val svcData = rec?.serviceData?.keys?.joinToString { it.uuid.toString() } ?: "none"
                    Logx.d("ble", "adv $addr rssi=${result.rssi} name=${name ?: "?"} " +
                        "uuids=${uuids?.joinToString { it.uuid.toString() } ?: "none"} svcData=[$svcData]")
                }
                val why = when {
                    matchesName -> "name '$name'"
                    matchesUuid -> "service uuid"
                    matchesSvcData -> "service-data uuid"
                    else -> "mfr 0x%04x".format(DK_MFR_COMPANY_ID)
                }
                val rnd = rndByMac[addr]
                if (rnd == null) {
                    if (waitingRndLogged.add(addr))
                        Logx.d("ble", "matched $why $addr but no broadcastRnd yet - waiting for the DK mfr-data advert…")
                    return false
                }
                advBroadcastRnd = rnd
                connectingName = name
                lastDevice = dev; lastRnd = rnd   // cache for a scan-free forced reconnect
                Logx.d("ble", "match by $why rnd=${rnd.joinToString("") { "%02x".format(it) }} -> connecting $addr")
                stopScanInternal(scanner)
                _state.value = State.CONNECTING
                connectDevice(dev)
                return true
            }
            return false
        }

        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) { handleAdvert(result) }
            override fun onBatchScanResults(results: MutableList<ScanResult>) {
                for (r in results) if (handleAdvert(r)) break
            }
            override fun onScanFailed(errorCode: Int) {
                stopScanInternal(scanner)
                if (useBatching && errorCode == ScanCallback.SCAN_FAILED_FEATURE_UNSUPPORTED) {
                    Logx.d("ble", "batched scan unsupported — retrying with immediate delivery")
                    startScan(a, useBatching = false)
                } else fail("scan failed: $errorCode")
            }
        }
        scanCb = cb
        // Foreground connect: with the screen on, scan UNFILTERED and match in software (see
        // handleAdvert), so the advert log can show a car whose region/firmware advertises different
        // identifiers. With the screen off Android refuses an unfiltered scan ("Cannot start unfiltered
        // scan in screen-off") without an error: every screen-off route that came here waited out the
        // whole scan timeout (plan H12, ~28 s) before the offloaded presence scan took over. Screen off
        // therefore uses the same 0xFDFD/0x06FE filter the presence scan has proven in the field.
        val screenOn = runCatching {
            (appContext.getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager)?.isInteractive
        }.getOrNull() ?: true
        val filters = if (screenOn) null else carScanFilters()
        Logx.d("ble", "scanning (${if (filters == null) "UNFILTERED" else "FILTERED 0xFDFD/0x06FE, screen off"}, " +
            "${if (useBatching) "batched ${REPORT_DELAY_MS}ms" else "immediate"}) " +
            "- match by name *zeekr*, adv-uuid 0xFDFD, DK service ${DkProtocol.SERVICE_UUID}, svcData or mfr 0x06FE. " +
            "Only matching advertisers are logged.")
        scanner.startScan(filters, settings, cb)
        scanJob = scope.launch {
            delay(SCAN_TIMEOUT_MS)
            if (_state.value == State.SCANNING) {
                stopScanInternal(scanner)
                if (ownKey.onScanTimeout())
                    Logx.w("ble", "own key module not seen in ${OwnKeyNameFilter.FORGET_AFTER_TIMEOUTS} scans, only other Zeekrs - learned name forgotten, next scan accepts any Zeekr")
                fail("no DK device matched in ${SCAN_TIMEOUT_MS / 1000}s — check the advertiser log above " +
                    "for the car's name/MAC, then connect by MAC")
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun stopScanInternal(scanner: android.bluetooth.le.BluetoothLeScanner) {
        scanJob?.cancel(); scanJob = null
        scanCb?.let { runCatching { scanner.stopScan(it) } }; scanCb = null
    }

    /**
     * The car's advert filter list. FILTERED scanning is mandatory for screen-off: Android blocks
     * UNFILTERED LE scans while the screen is off ("Cannot start unfiltered scan in screen-off").
     * The car advertises 16-bit service UUID 0xFDFD + manufacturer company id 0x06FE in its PRIMARY
     * packet (captured 2026-09-15) — filter on EITHER (OR-list) so we still catch it if one field is
     * ever absent. Note 0xFDFD is a shared Bluetooth-SIG 16-bit UUID (every Zeekr advertises it, not
     * unique per car); per-car identity is verified AFTER a match (name Zeekr<vin-suffix> +
     * broadcastRnd + the DK handshake, which only our provisioned key completes).
     */
    private fun carScanFilters(): List<ScanFilter> {
        val advTarget = UUID.fromString(DK_ADV_SERVICE_UUID)
        return listOf(
            ScanFilter.Builder().setServiceUuid(ParcelUuid(advTarget)).build(),
            ScanFilter.Builder().setManufacturerData(DK_MFR_COMPANY_ID, ByteArray(0)).build(),
        )
    }

    /** True while a hardware-offloaded presence scan (PendingIntent) is registered. */
    @Volatile var presenceArmed: Boolean = false
        private set
    @Volatile private var presenceApproachMode: Boolean = false
    @Volatile private var presenceLowLatency: Boolean = false

    /**
     * Arm a HARDWARE-OFFLOADED presence scan: the Bluetooth controller watches for the car's
     * advert (same [carScanFilters]) with the CPU asleep and wakes us via [BleScanReceiver] on
     * ALL_MATCHES. This is the zero-CPU idle path — no wakelock, no continuous foreground scan —
     * for "parked for hours". ALL_MATCHES is used because FIRST_MATCH can remain latched
     * across a long sleep/re-arm cycle and fail to wake the app when the user returns.
     *
     * Idempotent. Returns true if armed (or already armed). 0.3.21 (field 20/10, [PresenceScanArmPolicy]): a running
     * scan is stopped for a new duty cycle only when the ration can start the new one and keep a start in reserve;
     * otherwise it keeps running in its mode. False only when no scan runs and the ration is spent
     * ([scanStartWaitMs]) or Bluetooth is off.
     */
    @SuppressLint("MissingPermission")
    fun armPresenceScan(approachMode: Boolean = false, lowLatency: Boolean = false): Boolean {
        val useLowLatency = approachMode && lowLatency
        val sameMode = presenceApproachMode == approachMode && presenceLowLatency == useLowLatency
        val now = SteadyClock.wallMs()
        when (PresenceScanArmPolicy.decide(presenceArmed, sameMode, scanStarts.free(now))) {
            PresenceScanArmPolicy.Action.KEEP -> {
                if (!sameMode) Logx.d("ble", "presence scan kept (${presenceModeName(presenceApproachMode, presenceLowLatency)}): " +
                    "a restart for ${presenceModeName(approachMode, useLowLatency)} would leave no scan start in reserve")
                return true
            }
            PresenceScanArmPolicy.Action.DEFER -> {
                Logx.w("ble", "presence scan arm deferred (Android allows 5 scan starts per 30 s; " +
                    "next start in ${scanStarts.waitMs(now)} ms)")
                return false
            }
            PresenceScanArmPolicy.Action.RESTART, PresenceScanArmPolicy.Action.START -> {}
        }
        val scanner = adapter?.bluetoothLeScanner
        if (scanner == null || adapter?.isEnabled != true) { disarmPresenceScan(); return false }
        // Counted before the running scan stops: the ration can no longer refuse its successor.
        if (!scanStarts.tryStart(now)) {
            Logx.w("ble", "presence scan arm deferred (Android allows 5 scan starts per 30 s)")
            return presenceArmed
        }
        // Re-register when motion changes the desired duty cycle. This remains a filtered
        // PendingIntent scan handled by the Bluetooth controller; it does not hold a CPU wakelock.
        if (presenceArmed) disarmPresenceScan()
        clearStalePresenceScan(scanner)
        val settings = ScanSettings.Builder()
            // LOW_LATENCY is allowed only during ProximityService's bounded post-motion wake window.
            // Continued walking falls back to BALANCED; parked/still remains LOW_POWER. All three
            // routes stay hardware-filtered PendingIntent scans rather than foreground scan loops.
            .setScanMode(when {
                useLowLatency -> ScanSettings.SCAN_MODE_LOW_LATENCY
                approachMode -> ScanSettings.SCAN_MODE_BALANCED
                else -> ScanSettings.SCAN_MODE_LOW_POWER
            })
            .apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    // ALL_MATCHES is deliberate. FIRST_MATCH can remain latched across a long parked
                    // sleep/re-arm cycle and then never wake the app on return. ALL_MATCHES guarantees
                    // the next matching car advert is delivered; the receiver immediately disarms it.
                    setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
                    setNumOfMatches(ScanSettings.MATCH_NUM_ONE_ADVERTISEMENT)
                }
            }
            .build()
        val res = runCatching { scanner.startScan(carScanFilters(), settings, presencePendingIntent()) }
        return if (res.getOrDefault(-1) == 0) {
            presenceArmed = true
            presenceApproachMode = approachMode
            presenceLowLatency = useLowLatency
            Logx.d("ble", "presence scan ARMED (offloaded 0xFDFD/0x06FE, " +
                "${presenceModeName(approachMode, useLowLatency)}, ALL_MATCHES, CPU may sleep)")
            true
        } else {
            Logx.e("ble", "presence scan arm failed (${res.exceptionOrNull()?.message ?: "startScan!=0"})")
            false
        }
    }

    private fun presenceModeName(approachMode: Boolean, lowLatency: Boolean) = when {
        lowLatency -> "LOW_LATENCY motion wake"
        approachMode -> "BALANCED approach"
        else -> "LOW_POWER idle"
    }

    /** 0.3.21: scan starts the ration allows now, and how long until the next one when it is spent (0 = now). */
    fun scanStartsFree(): Int = scanStarts.free(SteadyClock.wallMs())
    fun scanStartWaitMs(): Long = scanStarts.waitMs(SteadyClock.wallMs())

    /** Stop the hardware-offloaded presence scan (e.g. once we're engaged with a live session). */
    @SuppressLint("MissingPermission")
    fun disarmPresenceScan() {
        if (!presenceArmed) { adapter?.bluetoothLeScanner?.let { clearStalePresenceScan(it) }; return }
        val scanner = adapter?.bluetoothLeScanner
        runCatching { scanner?.stopScan(presencePendingIntent()) }
        presenceArmed = false
        presenceApproachMode = false
        presenceLowLatency = false
        Logx.d("ble", "presence scan DISARMED")
    }

    /** Broadcast PendingIntent the offloaded scanner fires; delivered to [BleScanReceiver]. */
    private fun presencePendingIntent(): android.app.PendingIntent {
        val intent = android.content.Intent(appContext, BleScanReceiver::class.java)
            .setAction(BleScanReceiver.ACTION_SCAN_RESULT)
        // MUTABLE: the framework fills in the scan-result extras. FLAG_MUTABLE is required on API 31+.
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            android.app.PendingIntent.FLAG_MUTABLE
        else 0
        return android.app.PendingIntent.getBroadcast(
            appContext, PRESENCE_REQUEST_CODE, intent, flags
        )
    }

    @SuppressLint("MissingPermission")
    private fun connectDevice(device: BluetoothDevice) {
        // Hard dedupe: never open a second GATT client while one is live. Two concurrent
        // connectGatt() calls to the same peripheral is what produced the status-133 storm
        // (clientIf 7 AND 8 to the same MAC). The scan-match guard above should prevent a
        // second call, but this makes it impossible.
        if (gatt != null) {
            Logx.d("ble", "connectDevice ignored — a GATT client is already active (${device.address})")
            return
        }
        deliberate = false // a fresh connect attempt; a drop from here is unexpected → eligible for retry
        Logx.d("ble", "connectGatt ${device.address}")
        gatt = device.connectGatt(appContext, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
    }

    /** Abort any in-flight notify-enable/handshake setup: cancel the coroutine and unblock its
     *  pending GATT waits immediately (so a dropped link doesn't stall ~4 s on a notify timeout and
     *  then run the handshake on a dead GATT). */
    private fun abortSetup() {
        setupJob?.cancel(); setupJob = null
        notifyStep?.complete(false); writeAck?.complete(false)
    }

    @SuppressLint("MissingPermission")
    override fun disconnect() {
        deliberate = true // WE tore it down — the DISCONNECTED callback must not auto-retry
        adapter?.bluetoothLeScanner?.let { runCatching { stopScanInternal(it) } }
        abortSetup()
        // reset() (not close()) so the transport's inbound handler stays wired and a later
        // connect() on this reused session can re-handshake. close() would unwire it.
        (session as? RealDkSession)?.reset()
        runCatching { gatt?.disconnect(); gatt?.close() }
        gatt = null
        chWrite1 = null; chWrite2 = null; chNotify1 = null; chNotify2 = null
        reasm1.reset(); reasm2.reset()
        rssiReads.clear()
        _state.value = State.IDLE
    }

    /**
     * Bring up a FRESH DK session on demand - the programmatic equivalent of the user's manual
     * "tap Bluetooth off then on" on the Key tab. Used to recover a session that has gone STALE while
     * the GATT link stayed up: the car appears to time out its cert/handshake epoch after a while, so
     * commands are silently ignored even though we never left SESSION_READY. Dropping the link forces
     * the car to release the stale session; the reconnect runs a clean handshake (fresh rnd + GCM
     * keys). Returns true once SESSION_READY, false on timeout. Requires a provisioned credential.
     */
    suspend fun refreshSession(timeoutMs: Long = 15_000L): Boolean {
        if (!hasCredential) { Logx.w("ble", "refreshSession: no credential"); return false }
        Logx.d("ble", "refreshSession: dropping the link for a fresh DK session")
        disconnect()
        delay(800) // let the stack settle and the car release the stale session before reconnecting
        if (!reconnectLast()) connect(null)
        val deadline = SteadyClock.wallMs() + timeoutMs
        while (SteadyClock.wallMs() < deadline) {
            if (_state.value == State.SESSION_READY) { Logx.d("ble", "refreshSession: SESSION_READY"); return true }
            delay(200)
        }
        Logx.w("ble", "refreshSession: timed out in ${_state.value}")
        return _state.value == State.SESSION_READY
    }

    // ---------------- GATT callbacks ----------------

    private val gattCallback = object : android.bluetooth.BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            Logx.d("ble", "onConnectionStateChange status=$status newState=$newState")
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                _state.value = State.CONNECTED
                g.requestMtu(247)
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                // A dropped link invalidates the DK session: the derived GCM keys and the
                // car's per-connection pairing epoch cannot be reused. reset() (NOT close())
                // clears them while keeping the transport's inbound handler wired, so the
                // NEXT connect runs a fresh handshake instead of the stale isEstablished
                // path — the root cause of "must force-stop the app to reconnect".
                abortSetup() // stop any notify/handshake coroutine dead — don't let it run on a dead GATT
                (session as? RealDkSession)?.reset()
                chWrite1 = null; chWrite2 = null; chNotify1 = null; chNotify2 = null
                reasm1.reset(); reasm2.reset()
                rssiReads.clear()
                val wasReady = _state.value == State.SESSION_READY
                runCatching { g.close() }
                gatt = null
                when {
                    wasReady -> _state.value = State.IDLE   // ready-link drop: liveness/keep-alive decides
                    // Unexpected mid-setup drop (status 19, car/stack contention) with a known device:
                    // retry FAST via reconnectLast instead of the ~20 s offloaded presence scan.
                    // Status 133 can be either a transient Android-stack race or a stale RPA. Its
                    // separate bounded policy below permits one recent-route retry before reacquiring.
                    status != 133 && !deliberate && lastDevice != null && setupRetries < MAX_SETUP_RETRIES -> {
                        setupRetries++
                        _state.value = State.IDLE // reconnectLast requires a resting state
                        Logx.w("ble", "setup drop (status=$status) — fast reconnectLast retry #$setupRetries/$MAX_SETUP_RETRIES")
                        scope.launch { delay(SETUP_RETRY_DELAY_MS); reconnectLast() }
                    }
                    else -> {
                        setupRetries = 0
                        val recovery = if (status == 133 && !deliberate) {
                            status133RecoveryPolicy.onFailure(lastDevice != null && lastRnd != null,
                                android.os.SystemClock.elapsedRealtime())
                        } else null
                        if (recovery == Status133RecoveryPolicy.Route.RETRY_RECENT_ROUTE) {
                            // 0.1.30 fast test 2: this just-received RPA failed once with 133, yet
                            // the same MAC connected on the next presence event. Give that route one
                            // bounded stack-settle retry instead of paying for a complete scan cycle.
                            _state.value = State.IDLE
                            Logx.w("ble", "status 133 — one fast retry of recent presence route")
                            scope.launch {
                                delay(STATUS_133_DIRECT_RETRY_DELAY_MS)
                                if (_state.value == State.IDLE && !reconnectLast()) {
                                    val recover = onStatus133Recovery
                                    if (recover != null) recover() else connect(null)
                                }
                            }
                        } else {
                            if (status == 133) {
                                // No route exists or its one direct retry also failed. Only now forget
                                // it and reacquire a current RPA through the proven offloaded scanner.
                                lastDevice = null
                                lastRnd = null
                                advBroadcastRnd = null
                                Logx.w("ble", "status 133 — recent route exhausted; immediate fresh scan")
                            }
                            fail("disconnected (status=$status)")
                            if (status == 133 && !deliberate) scope.launch {
                                delay(STATUS_133_RESCAN_DELAY_MS)
                                if (_state.value == State.ERROR) {
                                    val recover = onStatus133Recovery
                                    if (recover != null) recover() else connect(null)
                                }
                            }
                        }
                    }
                }
            }
        }

        @SuppressLint("MissingPermission")
        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            maxChunk = (mtu - 3).coerceAtLeast(20)
            Logx.d("ble", "mtu=$mtu maxChunk=$maxChunk -> discoverServices")
            g.discoverServices()
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            val svc = g.getService(UUID.fromString(DkProtocol.SERVICE_UUID)) ?: run { fail("DK service not found"); return }
            chWrite1 = svc.getCharacteristic(UUID.fromString(DkProtocol.CHAR_CH1_WRITE))
            chNotify1 = svc.getCharacteristic(UUID.fromString(DkProtocol.CHAR_CH1_NOTIFY))
            chWrite2 = svc.getCharacteristic(UUID.fromString(DkProtocol.CHAR_CH2_WRITE))
            chNotify2 = svc.getCharacteristic(UUID.fromString(DkProtocol.CHAR_CH2_NOTIFY))
            Logx.d("ble", "services discovered: ch1w=${chWrite1 != null} ch1n=${chNotify1 != null} " +
                "ch2w=${chWrite2 != null} ch2n=${chNotify2 != null}")
            if (chWrite1 == null || chNotify1 == null) { fail("DK characteristics missing"); return }
            setupJob = scope.launch { setupNotificationsAndEstablish(g) }
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, ch: BluetoothGattCharacteristic, status: Int) {
            writeAck?.complete(status == BluetoothGatt.GATT_SUCCESS)
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            notifyStep?.complete(status == BluetoothGatt.GATT_SUCCESS)
        }

        override fun onReadRemoteRssi(g: BluetoothGatt, rssi: Int, status: Int) {
            if (g !== gatt) return
            rssiReads.response(g, if (status == BluetoothGatt.GATT_SUCCESS) rssi else null,
                android.os.SystemClock.elapsedRealtime())
        }

        // API < 33
        @Deprecated("Deprecated in API 33")
        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION") onNotify(ch.uuid, ch.value ?: ByteArray(0))
        }
        // API >= 33
        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic, value: ByteArray) {
            onNotify(ch.uuid, value)
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun setupNotificationsAndEstablish(g: BluetoothGatt) {
        if (!enableNotify(g, chNotify1!!)) { fail("enable notify 2A11 failed"); return }
        chNotify2?.let { if (!enableNotify(g, it)) Log.w(TAG, "enable notify 2A13 failed (continuing)") }
        // Bail if the link dropped during notify setup — never run the handshake on a dead GATT
        // (this is the "write failed for 0x0101" after a mid-setup status-19 drop).
        if (gatt !== g || _state.value != State.CONNECTED) {
            Logx.d("ble", "setup aborted — link no longer the active CONNECTED gatt (${_state.value})"); return
        }
        val cred = credential
        if (cred == null) {
            Logx.w("ble", "connected but no credential — provision a key first (session not established)")
            _state.value = State.CONNECTED; return
        }
        try {
            Logx.d("ble", "starting DK handshake …")
            (session as RealDkSession).establish()
            Logx.d("ble", "DK session READY")
            val before = ownKey.learnedName
            ownKey.onSessionReady(connectingName)
            lastDevice?.address?.let { failedHandshakes.onSuccess(it) }
            if (ownKey.learnedName != before) Logx.d("ble", "own key module name learned")
            setupRetries = 0 // clean session — clear the fast-retry budget
            status133RecoveryPolicy.onSessionReady()
            handshakeFailStreak = 0; handshakeBackoffUntilMs = 0L // handshake worked — clear the backoff
            _state.value = State.SESSION_READY
        } catch (e: kotlinx.coroutines.CancellationException) {
            // We were cancelled (abortSetup on a link drop, or scope shutdown) - NOT a handshake
            // failure. Don't set a bogus "DK handshake: ... was cancelled" error and don't trip the
            // backoff; the disconnect callback owns the resulting state. Rethrow to end the coroutine.
            throw e
        } catch (e: Exception) {
            // Count consecutive handshake failures and, past a threshold, back off AUTO reconnects for a
            // growing window so a car that won't complete the DK handshake can't cause an endless
            // connect/stall/drop/reconnect thrash. A user-initiated connect clears this (resetHandshakeBackoff).
            handshakeFailStreak++
            lastDevice?.address?.let { failedHandshakes.onFailure(it, SteadyClock.wallMs()) }
            if (handshakeFailStreak >= HANDSHAKE_FAIL_THRESHOLD) {
                val backoff = (HANDSHAKE_BACKOFF_BASE_MS shl (handshakeFailStreak - HANDSHAKE_FAIL_THRESHOLD))
                    .coerceAtMost(HANDSHAKE_BACKOFF_MAX_MS)
                handshakeBackoffUntilMs = SteadyClock.wallMs() + backoff
                Logx.w("ble", "DK handshake failed ${handshakeFailStreak}x - backing off auto-reconnect ${backoff / 1000}s")
            }
            fail("DK handshake: ${e.message}")
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun enableNotify(g: BluetoothGatt, ch: BluetoothGattCharacteristic): Boolean {
        g.setCharacteristicNotification(ch, true)
        val cccd = ch.getDescriptor(UUID.fromString(DkProtocol.CCCD_UUID)) ?: return false
        val step = CompletableDeferred<Boolean>(); notifyStep = step
        val enable = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeDescriptor(cccd, enable)
        } else {
            @Suppress("DEPRECATION") run { cccd.value = enable; g.writeDescriptor(cccd) }
        }
        return withTimeoutOrNull(4000) { step.await() } ?: false
    }

    private fun onNotify(uuid: UUID, bytes: ByteArray) {
        val reasm = if (uuid.toString().equals(DkProtocol.CHAR_CH2_NOTIFY, true)) reasm2 else reasm1
        val frameBytes = reasm.feed(bytes) ?: return
        try {
            val f = DkFrame.decode(frameBytes)
            lastInboundMs = SteadyClock.wallMs() // the car is talking = activity
            runCatching { onInboundActivity?.invoke() }
            Logx.d("ble", "<- frame cmd=0x${f.cmdId.toString(16)} ${DkProtocol.name(f.cmdId)} body=${f.body.size}B " +
                "hex=${f.body.take(64).joinToString("") { "%02x".format(it) }}")
            inboundHandler?.invoke(f.cmdId, f.body)
        } catch (e: DkFrameException) {
            Logx.w("ble", "bad inbound frame: ${e.message} raw=${frameBytes.take(48).joinToString("") { "%02x".format(it) }}")
        }
    }

    // ---------------- DkTransport ----------------

    @SuppressLint("MissingPermission")
    override suspend fun write(cmd: Int, framed: ByteArray): Boolean = writeLock.withLock {
        val g = gatt ?: return false
        val ch = (if (DkProtocol.isChannel2(cmd)) chWrite2 else chWrite1) ?: return false
        Logx.d("ble", "-> frame cmd=0x${cmd.toString(16)} ${DkProtocol.name(cmd)} ${framed.size}B")
        for (chunk in DkFragmenter.split(framed, maxChunk)) {
            val ack = CompletableDeferred<Boolean>(); writeAck = ack
            val ok = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                g.writeCharacteristic(ch, chunk, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) == BluetoothGatt.GATT_SUCCESS
            } else {
                @Suppress("DEPRECATION") run {
                    ch.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                    ch.value = chunk
                    g.writeCharacteristic(ch)
                }
            }
            if (!ok) return false
            if (withTimeoutOrNull(4000) { ack.await() } != true) return false
        }
        return true
    }

    override fun onInbound(handler: (Int, ByteArray) -> Unit) { inboundHandler = handler }

    override fun broadcastRnd(): ByteArray? = advBroadcastRnd

    override fun close() { inboundHandler = null }

    /**
     * Every failure ends with no GATT client left open (plan H13, 0.3.1). A failure during setup
     * (service not found, notify or handshake failed) used to leave [gatt] set: the next connect()
     * then ignored its own scan match ("a GATT client is already active") and stayed CONNECTING for
     * good - no working key, the wakelock held, and the car occupied for other keys.
     */
    private fun fail(msg: String) {
        lastError = msg; Logx.e("ble", msg)
        abortSetup()
        gatt?.let { g ->
            (session as? RealDkSession)?.reset()
            runCatching { g.disconnect(); g.close() }
            gatt = null
            chWrite1 = null; chWrite2 = null; chNotify1 = null; chNotify2 = null
            reasm1.reset(); reasm2.reset()
            rssiReads.clear()
        }
        _state.value = State.ERROR
    }

    companion object {
        private const val TAG = "DkBleManager"
        // Must match the <attribution android:tag> declared in the manifest.
        private const val ATTRIBUTION_TAG = "proximity"
        /** The 16-bit service UUID the car ADVERTISES (0xFDFD) — used to filter the scan so it's
         *  allowed to run screen-off, and to match without the scan-response name. NOT the DK GATT
         *  service ([DkProtocol.SERVICE_UUID] 0x02362A…), which the car does not advertise. */
        private const val DK_ADV_SERVICE_UUID = "0000fdfd-0000-1000-8000-00805f9b34fb"
        /** Manufacturer company id in the car's 0xFF advert block (LE `fe 06`); a scan-filter on it
         *  keeps the scan hardware-filtered/screen-off-legal. */
        private const val DK_MFR_COMPANY_ID = 0x06FE
        /** Stable request code for the offloaded presence-scan PendingIntent (arm/disarm must
         *  build an equal PendingIntent, so the request code + intent action are fixed). */
        private const val PRESENCE_REQUEST_CODE = 0x2ee5  // "ZEE(kr)"
        private const val SCAN_TIMEOUT_MS = 20_000L
        /** Batch window for foreground scan results — groups adverts so the framework doesn't
         *  log (and wake us for) every single advertisement packet. Sub-second, still snappy. */
        private const val REPORT_DELAY_MS = 500L
        // Fast recovery from an unexpected mid-setup drop (status 19): retry reconnectLast this many
        // times, waiting this long between (long enough for the car to release its side, short enough
        // to beat the ~20 s offloaded presence scan). Exhausted → fall back to the scan path.
        private const val MAX_SETUP_RETRIES = 3
        private const val SETUP_RETRY_DELAY_MS = 900L
        /** Short stack-settle delay before reacquiring the RPA after Android's generic GATT 133. */
        private const val STATUS_133_RESCAN_DELAY_MS = 400L
        private const val STATUS_133_DIRECT_RETRY_DELAY_MS = 350L
        // Auto-reconnect backoff after repeated DK-handshake failures (car won't complete the handshake).
        private const val HANDSHAKE_FAIL_THRESHOLD = 3
        private const val HANDSHAKE_BACKOFF_BASE_MS = 30_000L
        private const val HANDSHAKE_BACKOFF_MAX_MS = 120_000L
        private const val DRIVE_AUTHORIZATION_WINDOW_MS = 3 * 60_000L
        @Volatile private var INSTANCE: DkBleManager? = null
        fun get(context: Context): DkBleManager =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: DkBleManager(context.applicationContext).also { INSTANCE = it }
            }
    }
}
