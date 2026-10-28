package com.openzeekr.app.guard

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 0.4.5: the Sentry guard automaton (owner request 28/10). The car never switches its guard on by itself: after
 * every parking the owner had to do it by hand. In AUTO the app does it: [ARM_DELAY_MS] after a confirmed Lock,
 * when the car is locked with every door, the hood and the tailgate closed and the battery above the owner's
 * threshold, it sends the guard-on command and reads the car's flag back until the car confirms (the cloud takes
 * about a minute). The owner's own switch - in the app or on the car's screen - always wins until the next Lock.
 * Below the threshold the guard is switched off (and back on [HYSTERESIS_PCT] points higher); MANUAL leaves
 * everything to the switch; OFF makes the app leave the guard alone. Pure logic: the reads, commands and
 * notifications come through [GuardIo]; every step goes to the log as a content-free "guard:" line.
 */
enum class GuardMode {
    OFF, MANUAL, AUTO;

    /** The persisted spelling. */
    val key: String get() = name.lowercase()

    companion object {
        fun parse(s: String?): GuardMode = values().firstOrNull { it.key == s?.lowercase() } ?: MANUAL
    }
}

/** What the automaton needs to know about the car (null = unknown). */
data class GuardCar(
    val locked: Boolean?,
    val doorsClosed: Boolean?,
    val hoodClosed: Boolean?,
    val trunkClosed: Boolean?,
    val socPct: Int?,
    val charging: Boolean,
)

data class GuardSettings(val mode: GuardMode, val socThresholdPct: Int)

/** What the Security tab shows. */
data class GuardView(
    val mode: GuardMode = GuardMode.MANUAL,
    /** The guard flag as last read from the cloud; null = never read. */
    val flag: Boolean? = null,
    val flagReadAtMs: Long = 0L,
    /** A command is on its way and not yet confirmed by the car. */
    val pending: Boolean = false,
    /** One line for the owner. */
    val status: String = "",
    val lastSocPct: Int? = null,
)

/** The car, the cloud and the phone as the automaton sees them (implemented in Deps on the repositories). */
interface GuardIo {
    /** The guard flag of the car's control state; null when the read failed. */
    suspend fun readFlag(): Boolean?
    /** The car's state from the cloud status; null when the read failed. */
    suspend fun readCar(): GuardCar?
    /** Sends the guard on/off command; true when the cloud accepted it. */
    suspend fun send(on: Boolean): Boolean
    /** A notification to the owner. */
    fun notify(title: String, text: String)
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
    private val confirmStepMs: Long = CONFIRM_STEP_MS,
    private val socCheckMs: Long = SOC_CHECK_MS,
) {
    private val _view = MutableStateFlow(GuardView(mode = settings().mode, status = initialStatus(settings().mode)))
    val view: StateFlow<GuardView> = _view.asStateFlow()

    /** A parking episode: from a confirmed Lock to the next Unlock. */
    private var parked = false
    /** The owner switched the guard off (app or car) in this episode: the automaton stays away until the next Lock. */
    private var ownerOffThisEpisode = false
    /** Our picture of the car: the guard is on. */
    private var believedOn = false
    /** We switched it off for the battery; back on above threshold + hysteresis. */
    private var lowSocOff = false
    private var armAttempts = 0
    private var lastSocCheckMs = 0L
    private var episodeJob: Job? = null
    private var socJob: Job? = null
    private var commandJob: Job? = null
    private var holding = false

    // ---- events ----

    /** The engine confirmed a Lock (key, car self-lock, walk-away, cloud). */
    fun onLockConfirmed(source: String) {
        parked = true; ownerOffThisEpisode = false; lowSocOff = false; armAttempts = 0
        log("guard: episode started (${clean(source)})")
        episodeJob?.cancel(); episodeJob = null; socJob?.cancel(); socJob = null
        val s = settings()
        _view.update { it.copy(mode = s.mode) }
        when (s.mode) {
            GuardMode.OFF -> { status("Off · the app leaves the guard alone"); return }
            GuardMode.MANUAL -> { status("Manual · use the switch"); return }
            GuardMode.AUTO -> {}
        }
        status("Auto · switching on ${armDelayMs / 60_000} min after the lock")
        episodeJob = scope.launch {
            hold(true)
            try { delay(armDelayMs); arm() } finally { hold(false) }
        }
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
            _view.update { it.copy(pending = true) }
            status(if (on) "Switching on…" else "Switching off…")
            if (!on) {
                ownerOffThisEpisode = true
                episodeJob?.cancel(); episodeJob = null; socJob?.cancel(); socJob = null; hold(false)
            }
            val ok = io.send(on)
            log("guard: manual ${if (on) "on" else "off"} sent")
            if (!ok) {
                _view.update { it.copy(pending = false) }
                status("The cloud refused the command · try again")
                log("guard: manual command refused")
                return@launch
            }
            val confirmed = awaitFlag(on, polls = CONFIRM_POLLS)
            _view.update { it.copy(pending = false) }
            when {
                confirmed && on -> onConfirmedOn(byUs = false, how = "manual")
                confirmed -> {
                    believedOn = false
                    status(if (parked && settings().mode == GuardMode.AUTO) "Off by hand until the next lock" else "Off")
                    log("guard: manual off confirmed")
                }
                else -> {
                    status("Not confirmed by the car yet · read again later")
                    log("guard: manual ${if (on) "on" else "off"} not confirmed")
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

    /** A cheap hint from the service's keep-alive round: the hourly battery check when it is due. */
    fun onKeepAliveRound() {
        if (!parked || settings().mode != GuardMode.AUTO || socJob == null) return
        if (clock() - lastSocCheckMs < socCheckMs) return
        scope.launch { checkSoc() }
    }

    /** Mode or threshold changed in the Security tab. */
    fun onSettingsChanged() {
        val s = settings()
        _view.update { it.copy(mode = s.mode) }
        log("guard: mode ${s.mode.key} threshold ${s.socThresholdPct}%")
        when (s.mode) {
            GuardMode.OFF, GuardMode.MANUAL -> {
                episodeJob?.cancel(); episodeJob = null; socJob?.cancel(); socJob = null; hold(false)
                status(initialStatus(s.mode))
            }
            GuardMode.AUTO -> when {
                parked && believedOn -> { status("Auto · on since ${hhmm()}"); startSocWatch() }
                parked && !ownerOffThisEpisode && episodeJob == null -> {
                    status("Auto · switching on…")
                    episodeJob = scope.launch { hold(true); try { arm() } finally { hold(false) } }
                }
                parked -> status("Off by hand until the next lock")
                else -> status("Auto · from the next lock on")
            }
        }
    }

    // ---- the automaton ----

    private suspend fun arm() {
        if (ownerOffThisEpisode || !parked) return
        armAttempts++
        val s = settings()
        val car = io.readCar()
        if (car == null) { skip("the car's status could not be read", "status unavailable"); return }
        if (car.locked == false) { skip("the car is not locked", "car not locked"); return }
        car.socPct?.let { soc -> _view.update { it.copy(lastSocPct = soc) } }
        if (car.doorsClosed == false || car.hoodClosed == false || car.trunkClosed == false) {
            if (armAttempts < 2) {
                status("Auto · a door, the hood or the tailgate is open · checking again in ${armDelayMs / 60_000} min")
                log("guard: postponed (door hood or tailgate open)")
                delay(armDelayMs); arm(); return
            }
            skip("a door, the hood or the tailgate is open", "door hood or tailgate open"); return
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

    private suspend fun switchOn() {
        _view.update { it.copy(pending = true) }
        status("Auto · switching on…")
        var confirmed = false
        for (attempt in 1..2) {
            val ok = io.send(true)
            log("guard: on sent (attempt $attempt)")
            if (!ok) { log("guard: on refused"); break }
            confirmed = awaitFlag(true, polls = CONFIRM_POLLS)
            if (confirmed) break
            log("guard: not confirmed after ${CONFIRM_POLLS * confirmStepMs / 1000} s")
        }
        _view.update { it.copy(pending = false) }
        if (confirmed) { onConfirmedOn(byUs = true, how = "confirmed"); return }
        believedOn = false
        status("Auto · not confirmed by the car · switch it on by hand or check the car")
        io.notify("Sentry guard not confirmed", "The car did not confirm the guard. Switch it on by hand or check the car.")
        log("guard: not confirmed -> notified")
    }

    /** Reads the flag every [confirmStepMs] until it equals [want]; the view follows every read. */
    private suspend fun awaitFlag(want: Boolean, polls: Int): Boolean {
        var waited = 0L
        repeat(polls) {
            delay(confirmStepMs); waited += confirmStepMs
            val f = io.readFlag()
            _view.update { it.copy(flag = f ?: it.flag, flagReadAtMs = clock()) }
            if (f == want) { log("guard: ${if (want) "on" else "off"} confirmed after ${waited / 1000} s"); return true }
        }
        return false
    }

    private fun onConfirmedOn(byUs: Boolean, how: String) {
        believedOn = true; lowSocOff = false
        _view.update { it.copy(flag = true, flagReadAtMs = clock(), pending = false) }
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

    private fun skip(statusText: String, logText: String) {
        status("Auto · skipped: $statusText")
        log("guard: skipped ($logText)")
    }

    private fun status(text: String) { _view.update { it.copy(status = text) } }

    private fun hold(active: Boolean) {
        if (holding == active) return
        holding = active
        runCatching { io.hold(active) }
    }

    private fun hhmm(): String = SimpleDateFormat("HH:mm", Locale.US).format(Date(clock()))

    /** A source name as the journal may carry it: letters, spaces and hyphens only. */
    private fun clean(source: String): String =
        source.lowercase().filter { it in 'a'..'z' || it == ' ' || it == '-' }.trim().take(40).ifBlank { "unknown" }

    companion object {
        const val ARM_DELAY_MS = 120_000L
        const val CONFIRM_STEP_MS = 30_000L
        const val CONFIRM_POLLS = 3
        const val SOC_CHECK_MS = 3_600_000L
        const val HYSTERESIS_PCT = 5

        fun initialStatus(mode: GuardMode): String = when (mode) {
            GuardMode.OFF -> "Off · the app leaves the guard alone"
            GuardMode.MANUAL -> "Manual · use the switch"
            GuardMode.AUTO -> "Auto · from the next lock on"
        }
    }
}
