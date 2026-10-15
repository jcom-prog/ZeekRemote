package com.openzeekr.app.ble

/**
 * Remembers the advertised name of OUR key module and skips other Zeekr cars while scanning.
 *
 * Every Zeekr advertises the same 0xFDFD / 0x06FE identifiers, so the scan used to accept the first
 * Zeekr it heard. Field: the app spent ~35 s connecting to another car nearby (its DK
 * handshake can never succeed) before it reached ours. The key module rotates its address but keeps
 * its name, so after a successful DK session the name identifies our car.
 *
 * Safety: an advert without a name is always accepted (screen-off scans often drop the scan
 * response). [FORGET_AFTER_TIMEOUTS] scans in a row that time out having seen only other Zeekrs forget
 * the learned name, so a renamed or re-provisioned key module can never lock the app out; a single
 * timeout does not (another car parked nearby while ours is elsewhere is ordinary). The name is
 * stored on the phone only. Pure Kotlin; no Android.
 */
internal class OwnKeyNameFilter(private val store: Store) {
    interface Store {
        fun load(): String?
        fun save(name: String?)
    }

    enum class Decision { ACCEPT, SKIP_OTHER_CAR }

    private var learned: String? = store.load()?.trim()?.takeIf { it.isNotEmpty() }
    private var skippedThisScan = 0
    private var skipTimeoutsInARow = 0

    val learnedName: String? get() = learned

    fun onScanStart() { skippedThisScan = 0 }

    fun decide(advertisedName: String?): Decision {
        val own = learned ?: return Decision.ACCEPT
        val name = advertisedName?.trim()?.takeIf { it.isNotEmpty() } ?: return Decision.ACCEPT
        if (name.equals(own, ignoreCase = true)) return Decision.ACCEPT
        skippedThisScan++
        return Decision.SKIP_OTHER_CAR
    }

    /** A DK session with our credential completed, so [connectedName] is our key module. */
    fun onSessionReady(connectedName: String?) {
        skipTimeoutsInARow = 0
        val name = connectedName?.trim()?.takeIf { it.isNotEmpty() } ?: return
        if (name == learned) return
        learned = name
        store.save(name)
    }

    /** Returns true when the learned name was forgotten (repeatedly only other Zeekrs were seen). */
    fun onScanTimeout(): Boolean {
        if (learned == null) return false
        if (skippedThisScan == 0) { skipTimeoutsInARow = 0; return false }
        skippedThisScan = 0
        if (++skipTimeoutsInARow < FORGET_AFTER_TIMEOUTS) return false
        learned = null
        skipTimeoutsInARow = 0
        store.save(null)
        return true
    }

    companion object {
        const val FORGET_AFTER_TIMEOUTS = 3
    }
}
