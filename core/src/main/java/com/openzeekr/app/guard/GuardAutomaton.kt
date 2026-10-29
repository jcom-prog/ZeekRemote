package com.openzeekr.app.guard

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * 0.4.5: the Sentry guard automaton (owner request 28/10). The car never switches its guard on by itself: after
 * every parking the owner had to do it by hand. In AUTO the app does it: [ARM_DELAY_MS] after a confirmed Lock,
 * when the car is locked with every door, the bonnet and the tailgate closed and the battery above the owner's
 * threshold, it sends the guard-on command and reads the car's flag back until the car confirms (the cloud takes
 * about a minute). The owner's own switch - in the app or on the car's screen - always wins until the next Lock.
 * Below the threshold the guard is switched off (and back on [HYSTERESIS_PCT] points higher); MANUAL leaves
 * everything to the switch; OFF makes the app leave the guard alone.
 *
 * 0.4.6: quiet zones (up to [MAX_ZONES] places, kept on the phone only) where the automaton does not switch the
 * guard on by itself - the owner's switch still does, for that parking; an unknown car position arms on the safe
 * side. A daily check at the owner's hour: a guard that is off while it should be on is armed, an open door,
 * bonnet, tailgate or window and a low battery are reported, otherwise nothing. At arm time an open window is
 * reported with a "Close windows" action (never closed by itself). The switch follows the owner's
 * hand while a command is pending ([GuardView.wanted]) and the car is polled after [FIRST_POLL_MS], then every
 * [POLL_STEP_MS]. Pure logic: the reads, commands and notifications come through [GuardIo]; every step goes to
 * the log as a content-free "guard:" line (never a position or a zone label).
 */
enum class GuardMode {
    OFF, MANUAL, AUTO;

    /** The persisted spelling. */
    val key: String get() = name.lowercase()

    companion object {
        fun parse(s: String?): GuardMode = values().firstOrNull { it.key == s?.lowercase() } ?: MANUAL
    }
}

/** 0.4.6: a quiet zone - a place where the automaton leaves the guard off. Stored on the phone only. */
@Serializable
data class GuardZone(val lat: Double, val lon: Double, val radiusM: Int = 50, val label: String = "")

/** What the automaton needs to know about the car (null = unknown). */
data class GuardCar(
    val locked: Boolean?,
    val doorsClosed: Boolean?,
    val bonnetClosed: Boolean?,
    val trunkClosed: Boolean?,
    val socPct: Int?,
    val charging: Boolean,
    /** 0.4.6: the car's position from the cloud status, for the quiet zones; null = unknown. */
    val lat: Double? = null,
    val lon: Double? = null,
    val positionTrusted: Boolean? = null,
    /** 0.4.6: any window not fully closed (Deps folds in the opening in the top of a car that reports one); null = not reported. */
    val windowsOpen: Boolean? = null,
)

data class GuardSettings(
    val mode: GuardMode,
    val socThresholdPct: Int,
    /** 0.4.6 */
    val zones: List<GuardZone> = emptyList(),
    val skipInZones: Boolean = true,
    /** Hour of the daily check (0-23); anything else = off. */
    val dailyCheckHour: Int = 8,
    val windowWarning: Boolean = true,
)

/** What the Security tab shows. */
data class GuardView(
    val mode: GuardMode = GuardMode.MANUAL,
    /** The guard flag as last read from the cloud; null = never read. */
    val flag: Boolean? = null,
    val flagReadAtMs: Long = 0L,
    /** A command is on its way and not yet confirmed by the car. */
    val pending: Boolean = false,
    /** 0.4.6: the owner's hand while a manual command is pending (the switch shows this, not the stale flag). */
    val wanted: Boolean? = null,
    /** One line for the owner. */
    val status: String = "",
    val lastSocPct: Int? = null,
)

/** 0.4.6: a button on a notification. */
enum class GuardAction { CLOSE_WINDOWS }

/** The car, the cloud and the phone as the automaton sees them (implemented in Deps on the repositories). */
interface GuardIo {
    /** The guard flag of the car's control state; null when the read failed. */
    suspend fun readFlag(): Boolean?
    /** The car's state from the cloud status; null when the read failed. */
    suspend fun readCar(): GuardCar?
    /** 0.4.8: the phone is in or at the car (strong signal, or riding in a vehicle); null = unknown, which counts as away. */
    fun atCar(): Boolean? = null
    /** 0.4.8: withdraws the automaton's notice (the car confirmed after all). */
    fun cancelNotice() {}
    /** Sends the guard on/off command; true when the cloud accepted it. */
    suspend fun send(on: Boolean): Boolean
    /** 0.4.6: sends the close-windows command; true when the cloud accepted it. */
    suspend fun closeWindows(): Boolean = false
    /** A notification to the owner, with an optional button. */
    fun notify(title: String, text: String, action: GuardAction? = null)
    /** Holds (true) or releases (false) the CPU while the automaton has timed work to do. */
    fun hold(active: Boolean) {}
}

class GuardAutomaton(
    private val scope: CoroutineScope,
    private val io: GuardIo,
    private val settings: () -> GuardSettings,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val log: (String) -> Unit = {},
    private val armDelayMs: Long = ARM_DELAY_MS,
    private val firstPollMs: Long = FIRST_POLL_MS,
    private val pollStepMs: Long = POLL_STEP_MS,
    private val atCarPollMs: Long = AT_CAR_POLL_MS,
    private val lateStepMs: Long = LATE_STEP_MS,
    private val socCheckMs: Long = SOC_CHECK_MS,
    /** 0.4.9: how often the flag is re-read while the app is in the foreground (the Sentry tile, the "Car says" line). */
    private val foregroundReadMs: Long = FOREGROUND_READ_MS,
    /** The next moment (wall ms) the daily check runs, given now and the owner's hour; default = local clock. */
    private val nextDaily: (nowMs: Long, hour: Int) -> Long = ::nextLocalHour,
) {
    private val _view = MutableStateFlow(GuardView(mode = settings().mode, status = initialStatus(settings().mode)))
    val view: StateFlow<GuardView> = _view.asStateFlow()

    /** A parking episode: from a confirmed Lock to the next Unlock. */
    private var parked = false
    /** The owner switched the guard off (app or car) in this episode: the automaton stays away until the next Lock. */
    private var ownerOffThisEpisode = false
    /** 0.4.6: the automaton stood down for a quiet zone in this episode. */
    private var zoneSkippedThisEpisode = false
    /** 0.4.6: the open-window notice went out in this episode. */
    private var windowsWarnedThisEpisode = false
    /** Our picture of the car: the guard is on. */
    private var believedOn = false
    /** We switched it off for the battery; back on above threshold + hysteresis. */
    private var lowSocOff = false
    private var armAttempts = 0
    private var lastSocCheckMs = 0L
    private var lastCarReadOkMs = 0L
    private var dailyDueMs = 0L
    private var episodeJob: Job? = null
    private var socJob: Job? = null
    private var commandJob: Job? = null
    private var dailyJob: Job? = null
    private var windowsJob: Job? = null
    private var foregroundJob: Job? = null
    private var episodeHold = false
    private var dailyHold = false
    private var ioHeld = false

    // ---- events ----

    /** The engine confirmed a Lock (key, car self-lock, walk-away, cloud). */
    fun onLockConfirmed(source: String) {
        parked = true; ownerOffThisEpisode = false; zoneSkippedThisEpisode = false; windowsWarnedThisEpisode = false
        lowSocOff = false; armAttempts = 0
        log("guard: episode started (${clean(source)})")
        episodeJob?.cancel(); episodeJob = null; socJob?.cancel(); socJob = null
        val s = settings()
        _view.update { it.copy(mode = s.mode) }
        when (s.mode) {
            GuardMode.OFF -> { status("Off · the app leaves the guard alone"); return }
            GuardMode.MANUAL -> { status("Manual · use the switch"); return }
            GuardMode.AUTO -> {}
        }
        episodeJob = scope.launch {
            try { awaitParked(); hold(true); delay(armDelayMs); arm() } finally { hold(false) }
        }
    }

    /**
     * 0.4.8: a lock with the phone still in or at the car - the doors locking at drive-off, the owner still inside - is
     * not a parking yet (field 29/10: the guard command went out while driving). Wait until the phone has left, looking
     * every [atCarPollMs]; an unknown position counts as left.
     */
    private suspend fun awaitParked() {
        if (io.atCar() != true) { status("Auto · switching on ${armDelayMs / 60_000} min after the lock"); return }
        status("Auto · waiting until the car is parked and you have left")
        log("guard: waiting (phone at the car)")
        while (io.atCar() == true) delay(atCarPollMs)
        log("guard: phone away -> arm delay")
        status("Auto · switching on ${armDelayMs / 60_000} min after you left")
    }

    /** The engine confirmed an Unlock: the episode ends; the car ends its own guard session when opened. */
    fun onUnlockConfirmed(source: String) {
        parked = false
        episodeJob?.cancel(); episodeJob = null; socJob?.cancel(); socJob = null; hold(false)
        log("guard: episode ended (${clean(source)})")
        if (settings().mode != GuardMode.OFF) status("Car open · the guard rests until the next lock")
    }

    /** The owner used the switch in the app. */
    fun onManualSwitch(on: Boolean) {
        commandJob?.cancel()
        commandJob = scope.launch {
            _view.update { it.copy(pending = true, wanted = on) }
            status(if (on) "Switching on… waiting for the car" else "Switching off… waiting for the car")
            if (!on) {
                ownerOffThisEpisode = true
                episodeJob?.cancel(); episodeJob = null; socJob?.cancel(); socJob = null; hold(false)
            }
            val ok = io.send(on)
            log("guard: manual ${if (on) "on" else "off"} sent")
            if (!ok) {
                _view.update { it.copy(pending = false, wanted = null) }
                status("The cloud refused the command · try again")
                log("guard: manual command refused")
                return@launch
            }
            val confirmed = awaitFlag(on, firstPollMs, pollStepMs, MANUAL_POLLS) == Read.CONFIRMED
            _view.update { it.copy(pending = false, wanted = null) }
            when {
                confirmed && on -> onConfirmedOn(byUs = false, how = "manual")
                confirmed -> {
                    believedOn = false
                    status(if (parked && settings().mode == GuardMode.AUTO) "Off by hand until the next lock" else "Off")
                    log("guard: manual off confirmed")
                }
                else -> {
                    status("Not confirmed by the car within ${manualPollMs() / 1000} s · still checking")
                    log("guard: manual ${if (on) "on" else "off"} not confirmed")
                    // 0.4.8: the car may still follow; watch it a while longer without bothering the owner.
                    if (awaitFlag(on, lateStepMs, lateStepMs, MANUAL_LATE_POLLS) == Read.CONFIRMED) {
                        log("guard: manual ${if (on) "on" else "off"} confirmed late")
                        if (on) onConfirmedOn(byUs = false, how = "manual late") else { believedOn = false; status("Off") }
                    }
                }
            }
        }
    }

    /** Anyone read the flag (the Security tab on resume, a poll): keeps the view fresh and sees a switch made elsewhere. */
    fun onFlagRead(flag: Boolean?, atMs: Long = clock()) {
        if (flag == null) { log("guard: flag read failed"); return }
        val before = believedOn
        _view.update { it.copy(flag = flag, flagReadAtMs = atMs) }
        if (_view.value.pending) return          // a command's own polls decide
        if (flag && !before) {
            believedOn = true
            if (parked && settings().mode == GuardMode.AUTO) {
                log("guard: on detected"); status("Auto · on (switched on elsewhere)"); startSocWatch()
            } else status("On")
        } else if (!flag && before) {
            believedOn = false
            log("guard: off detected")
            if (parked && settings().mode == GuardMode.AUTO) {
                ownerOffThisEpisode = true; socJob?.cancel(); socJob = null
                status("Off (changed on the car or elsewhere) until the next lock")
            } else status("Off")
        }
    }

    /** Re-read the flag (the Security tab coming to the front). */
    fun refresh() { scope.launch { onFlagRead(io.readFlag()) } }

    /**
     * 0.4.9: while the app is in the foreground the flag is read at once and then every [foregroundReadMs], so the Sentry
     * tile on the Vehicle tab and the "Car says" line follow the car without a tap. A failed read is skipped quietly (the
     * tile keeps the last state; the line shows when it was read); nothing runs in the background.
     */
    fun onForeground(active: Boolean) {
        foregroundJob?.cancel(); foregroundJob = null
        if (!active) return
        foregroundJob = scope.launch {
            while (true) {
                io.readFlag()?.let { onFlagRead(it) }
                delay(foregroundReadMs)
            }
        }
    }

    /** A cheap hint from the service's keep-alive round: the hourly battery check and the daily check when due. */
    fun onKeepAliveRound() {
        val now = clock()
        val s = settings()
        if (s.dailyCheckHour in 0..23) {
            if (dailyDueMs == 0L) dailyDueMs = nextDaily(now, s.dailyCheckHour)
            if (now >= dailyDueMs && dailyJob?.isActive != true) {
                dailyDueMs = nextDaily(now, s.dailyCheckHour)
                dailyJob = scope.launch { holdDaily(true); try { dailyCheck() } finally { holdDaily(false) } }
            }
        } else dailyDueMs = 0L
        if (!parked || s.mode != GuardMode.AUTO || socJob == null) return
        if (now - lastSocCheckMs < socCheckMs) return
        scope.launch { checkSoc() }
    }

    /** Mode, threshold, zones or the daily hour changed in the Security tab. */
    fun onSettingsChanged() {
        val s = settings()
        _view.update { it.copy(mode = s.mode) }
        dailyDueMs = 0L
        log("guard: mode ${s.mode.key} threshold ${s.socThresholdPct}% zones ${s.zones.size.coerceAtMost(9)}" +
            (if (s.skipInZones) "" else " (zones ignored)") +
            (if (s.dailyCheckHour in 0..23) " daily ${s.dailyCheckHour}h" else " daily off") +
            (if (s.windowWarning) "" else " (no window warning)"))
        when (s.mode) {
            GuardMode.OFF, GuardMode.MANUAL -> {
                episodeJob?.cancel(); episodeJob = null; socJob?.cancel(); socJob = null; hold(false)
                status(initialStatus(s.mode))
            }
            GuardMode.AUTO -> when {
                parked && believedOn -> { status("Auto · on since ${hhmm()}"); startSocWatch() }
                parked && !ownerOffThisEpisode && episodeJob == null -> {
                    zoneSkippedThisEpisode = false
                    status("Auto · switching on…")
                    episodeJob = scope.launch { try { awaitParked(); hold(true); arm() } finally { hold(false) } }
                }
                parked -> status("Off by hand until the next lock")
                else -> status("Auto · from the next lock on")
            }
        }
    }

    /** 0.4.6: the "Close windows" button on the open-window notice. */
    fun closeWindowsFromNotification() {
        windowsJob?.cancel()
        windowsJob = scope.launch {
            val ok = io.closeWindows()
            log(if (ok) "guard: windows close sent" else "guard: windows close refused")
            if (!ok) { io.notify("Windows", "The cloud refused the close command · try the Vehicle tab."); return@launch }
            delay(WINDOWS_CHECK_MS)
            val car = io.readCar()
            val open = car?.windowsOpen == true
            log("guard: windows ${if (open) "still open" else "closed"}")
            io.notify("Windows", if (open) "A window is still open · check the car." else "Windows closed.")
        }
    }

    // ---- the automaton ----

    private suspend fun arm() {
        if (ownerOffThisEpisode || !parked) return
        armAttempts++
        val s = settings()
        val car = io.readCar()
        if (car == null) { skip("the car's status could not be read", "status unavailable"); return }
        lastCarReadOkMs = clock()
        if (car.locked == false) { skip("the car is not locked", "car not locked"); return }
        car.socPct?.let { soc -> _view.update { it.copy(lastSocPct = soc) } }
        warnWindows(car, s)
        if (car.doorsClosed == false || car.bonnetClosed == false || car.trunkClosed == false) {
            if (armAttempts < 2) {
                status("Auto · a door, the bonnet or the tailgate is open · checking again in ${armDelayMs / 60_000} min")
                log("guard: postponed (door bonnet or tailgate open)")
                delay(armDelayMs); arm(); return
            }
            skip("a door, the bonnet or the tailgate is open", "door bonnet or tailgate open"); return
        }
        zoneOf(car, s)?.let { n ->
            zoneSkippedThisEpisode = true
            val label = s.zones[n - 1].label.trim().ifBlank { "zone $n" }
            skip("in quiet zone $label · slide the switch to switch on anyway", "zone $n"); return
        }
        if (car.socPct != null && !car.charging && car.socPct < s.socThresholdPct) {
            lowSocOff = true
            skip("battery ${car.socPct}% below ${s.socThresholdPct}% · on again above ${s.socThresholdPct + HYSTERESIS_PCT}%",
                "low soc ${car.socPct}%")
            startSocWatch(); return
        }
        log("guard: soc ${car.socPct ?: -1}% at arm")
        if (io.readFlag() == true) { onConfirmedOn(byUs = false, how = "already on"); return }
        switchOn()
    }

    /** 0.4.6: the quiet zone the car stands in (1-based), or null; an unknown position never counts as inside. */
    private fun zoneOf(car: GuardCar, s: GuardSettings): Int? {
        if (!s.skipInZones || s.zones.isEmpty()) return null
        val lat = car.lat; val lon = car.lon
        if (lat == null || lon == null || car.positionTrusted == false) { log("guard: position unknown -> arming"); return null }
        s.zones.take(MAX_ZONES).forEachIndexed { i, z ->
            if (distanceM(lat, lon, z.lat, z.lon) <= z.radiusM.coerceIn(10, 2000)) return i + 1
        }
        return null
    }

    /** 0.4.6: an open window at arm time - reported once per episode, with a close button. */
    private fun warnWindows(car: GuardCar, s: GuardSettings) {
        if (!s.windowWarning || windowsWarnedThisEpisode) return
        if (car.windowsOpen != true) return
        windowsWarnedThisEpisode = true
        io.notify("Sentry guard: a window is open", "A window is open while the car is parked.", GuardAction.CLOSE_WINDOWS)
        log("guard: windows open -> notified")
    }

    private suspend fun switchOn() {
        _view.update { it.copy(pending = true) }
        status("Auto · switching on…")
        val ok1 = io.send(true)
        log("guard: on sent (attempt 1)")
        if (!ok1) { log("guard: on refused"); giveUp(unreadable = false); return }
        val first = awaitFlag(true, firstPollMs, pollStepMs, CONFIRM_POLLS)
        if (first == Read.CONFIRMED) { _view.update { it.copy(pending = false) }; onConfirmedOn(byUs = true, how = "confirmed"); return }
        log("guard: not confirmed after ${confirmWindowMs() / 1000} s")
        status("Auto · not confirmed yet · asking again")
        val ok2 = io.send(true)
        log("guard: on sent (attempt 2)")
        val second = if (ok2) awaitFlag(true, lateStepMs, lateStepMs, FOLLOW_UP_POLLS) else Read.OFF
        if (second == Read.CONFIRMED) { _view.update { it.copy(pending = false) }; onConfirmedOn(byUs = true, how = "confirmed late"); return }
        giveUp(unreadable = first == Read.UNREADABLE && second == Read.UNREADABLE)
    }

    /** 0.4.8: after the two attempts: tell the owner (a read failure is told apart from a car that says "off"), then keep
     *  watching; a late confirmation withdraws the notice. */
    private suspend fun giveUp(unreadable: Boolean) {
        _view.update { it.copy(pending = false) }
        believedOn = false
        if (unreadable) {
            status("Auto · the car's guard state could not be read · it may well be on")
            io.notify("Sentry guard: state not readable", "The car's guard state could not be read. The guard may well be on; check the data connection or the car.")
            log("guard: flag unreadable -> notified")
        } else {
            status("Auto · not confirmed by the car · switch it on by hand or check the car")
            io.notify("Sentry guard not confirmed", "The car did not confirm the guard. Switch it on by hand or check the car.")
            log("guard: not confirmed -> notified")
        }
        if (awaitFlag(true, lateStepMs, lateStepMs, LATE_WATCH_POLLS) == Read.CONFIRMED) {
            io.cancelNotice()
            log("guard: on confirmed late")
            onConfirmedOn(byUs = true, how = "late")
        }
    }

    private enum class Read { CONFIRMED, OFF, UNREADABLE }

    /** Reads the flag after [firstMs], then every [stepMs], [polls] times, until it equals [want]; UNREADABLE when more
     *  than half of the reads failed. */
    private suspend fun awaitFlag(want: Boolean, firstMs: Long, stepMs: Long, polls: Int): Read {
        var waited = 0L; var failed = 0
        repeat(polls) { i ->
            val step = if (i == 0) firstMs else stepMs
            delay(step); waited += step
            val f = io.readFlag()
            if (f == null) failed++
            _view.update { it.copy(flag = f ?: it.flag, flagReadAtMs = clock()) }
            if (f == want) { log("guard: ${if (want) "on" else "off"} confirmed after ${waited / 1000} s"); return Read.CONFIRMED }
        }
        return if (failed * 2 > polls) Read.UNREADABLE else Read.OFF
    }

    private fun confirmWindowMs(): Long = firstPollMs + (CONFIRM_POLLS - 1) * pollStepMs
    private fun manualPollMs(): Long = firstPollMs + (MANUAL_POLLS - 1) * pollStepMs

    private fun onConfirmedOn(byUs: Boolean, how: String) {
        believedOn = true; lowSocOff = false
        _view.update { it.copy(flag = true, flagReadAtMs = clock(), pending = false, wanted = null) }
        val auto = settings().mode == GuardMode.AUTO
        status((if (auto) "Auto · " else "") + "on since ${hhmm()}" + (if (byUs) "" else " (by hand)"))
        log("guard: on ($how)")
        if (auto && parked) startSocWatch()
    }

    private fun startSocWatch() {
        socJob?.cancel()
        socJob = scope.launch { while (true) { delay(socCheckMs); checkSoc() } }
    }

    private suspend fun checkSoc() {
        if (!parked || settings().mode != GuardMode.AUTO) return
        lastSocCheckMs = clock()
        val car = io.readCar() ?: return
        lastCarReadOkMs = clock()
        val s = settings()
        car.socPct?.let { soc -> _view.update { it.copy(lastSocPct = soc) } }
        val soc = car.socPct ?: return
        if (believedOn && !car.charging && soc < s.socThresholdPct) {
            val ok = io.send(false)
            log("guard: off sent (low soc ${soc}%)")
            if (!ok) return
            believedOn = false; lowSocOff = true
            _view.update { it.copy(flag = false, flagReadAtMs = clock()) }
            status("Auto · off: battery ${soc}% below ${s.socThresholdPct}% · back on above ${s.socThresholdPct + HYSTERESIS_PCT}%")
            io.notify("Sentry guard switched off", "Battery ${soc}% is below your ${s.socThresholdPct}% threshold. It comes back on above ${s.socThresholdPct + HYSTERESIS_PCT}%.")
        } else if (lowSocOff && !believedOn && !ownerOffThisEpisode &&
            (car.charging || soc >= s.socThresholdPct + HYSTERESIS_PCT)) {
            log("guard: battery recovered (${soc}%)")
            switchOn()
        }
    }

    /**
     * 0.4.6: the daily check. Reads the car and the flag once. In AUTO a guard that is off while the car is locked
     * (and nothing stood the automaton down) is switched on through the normal arm path; an open door, bonnet,
     * tailgate or window and a battery below the threshold are reported; a day without a readable status
     * is reported; otherwise only a log line.
     */
    private suspend fun dailyCheck() {
        log("guard: daily check")
        val s = settings()
        val car = io.readCar()
        if (car == null) {
            log("guard: daily check status unavailable")
            if (lastCarReadOkMs > 0L && clock() - lastCarReadOkMs >= DAY_MS)
                io.notify("Sentry guard daily check", "The car's status could not be read for a day. Check the sign-in and the car.")
            return
        }
        lastCarReadOkMs = clock()
        car.socPct?.let { soc -> _view.update { it.copy(lastSocPct = soc) } }
        val issues = mutableListOf<String>()
        if (car.doorsClosed == false || car.bonnetClosed == false || car.trunkClosed == false) issues += "a door, the bonnet or the tailgate is open"
        if (s.windowWarning && car.windowsOpen == true) issues += "a window is open"
        val soc = car.socPct
        if (soc != null && !car.charging && soc < s.socThresholdPct) issues += "the battery (${soc}%) is below your ${s.socThresholdPct}% threshold"
        val flag = io.readFlag()
        if (flag != null) _view.update { it.copy(flag = flag, flagReadAtMs = clock()) }
        if (issues.isNotEmpty()) {
            io.notify("Sentry guard daily check", issues.joinToString("; ").replaceFirstChar { it.uppercase() } + ".")
            log("guard: daily check issues ${issues.size}")
        }
        val armable = s.mode == GuardMode.AUTO && car.locked == true && flag == false &&
            !ownerOffThisEpisode && !_view.value.pending && issues.isEmpty()
        if (armable) {
            log("guard: daily check -> arming")
            parked = true; believedOn = false; armAttempts = 1
            arm()
        } else if (issues.isEmpty()) {
            log("guard: daily check ok (${when (flag) { true -> "on"; false -> "off"; null -> "flag unknown" }})")
        }
    }

    private fun skip(statusText: String, logText: String) {
        status("Auto · skipped: $statusText")
        log("guard: skipped ($logText)")
    }

    private fun status(text: String) { _view.update { it.copy(status = text) } }

    /** The episode's hold (the arm delay, the polls); the daily check has its own, so neither releases the other's. */
    private fun hold(active: Boolean) { episodeHold = active; applyHold() }
    private fun holdDaily(active: Boolean) { dailyHold = active; applyHold() }
    private fun applyHold() {
        val want = episodeHold || dailyHold
        if (ioHeld == want) return
        ioHeld = want
        runCatching { io.hold(want) }
    }

    private fun hhmm(): String = SimpleDateFormat("HH:mm", Locale.US).format(Date(clock()))

    /** A source name as the journal may carry it: letters, spaces and hyphens only. */
    private fun clean(source: String): String =
        source.lowercase().filter { it in 'a'..'z' || it == ' ' || it == '-' }.trim().take(40).ifBlank { "unknown" }

    companion object {
        const val ARM_DELAY_MS = 120_000L
        /** 0.4.6/0.4.8: the car is polled after 10 s, then every 20 s - 15 times (about 5 min) for the automaton, 5 times
         *  (90 s) for the switch; then every minute: a second attempt for 5 min, a late watch for 10 min (5 for the switch). */
        const val FIRST_POLL_MS = 10_000L
        const val POLL_STEP_MS = 20_000L
        const val CONFIRM_POLLS = 15
        const val MANUAL_POLLS = 5
        const val LATE_STEP_MS = 60_000L
        const val FOLLOW_UP_POLLS = 5
        const val LATE_WATCH_POLLS = 10
        const val MANUAL_LATE_POLLS = 5
        /** 0.4.8: while the phone is in or at the car after a lock, look again this often. */
        const val AT_CAR_POLL_MS = 30_000L
        const val SOC_CHECK_MS = 3_600_000L
        /** 0.4.9 */
        const val FOREGROUND_READ_MS = 60_000L
        const val HYSTERESIS_PCT = 5
        const val MAX_ZONES = 3
        const val WINDOWS_CHECK_MS = 20_000L
        const val DAY_MS = 24 * 3_600_000L

        /** 0.4.9: the Sentry tile on the Vehicle tab - the car's flag as last read; null = never read. */
        fun tileLabel(flag: Boolean?): String = when (flag) { true -> "Sentry on"; false -> "Sentry off"; null -> "Sentry" }

        fun initialStatus(mode: GuardMode): String = when (mode) {
            GuardMode.OFF -> "Off · the app leaves the guard alone"
            GuardMode.MANUAL -> "Manual · use the switch"
            GuardMode.AUTO -> "Auto · from the next lock on"
        }

        /** Great-circle distance in metres (haversine); good to a metre at zone scale. */
        fun distanceM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
            val r = 6_371_000.0
            val dLat = Math.toRadians(lat2 - lat1); val dLon = Math.toRadians(lon2 - lon1)
            val a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
                Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) * Math.sin(dLon / 2) * Math.sin(dLon / 2)
            return 2 * r * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
        }

        /** The next [hour]:00 in the device's time zone strictly after [nowMs]. */
        fun nextLocalHour(nowMs: Long, hour: Int): Long {
            val c = Calendar.getInstance().apply {
                timeInMillis = nowMs
                set(Calendar.HOUR_OF_DAY, hour.coerceIn(0, 23)); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            }
            if (c.timeInMillis <= nowMs) c.add(Calendar.DAY_OF_MONTH, 1)
            return c.timeInMillis
        }
    }
}
