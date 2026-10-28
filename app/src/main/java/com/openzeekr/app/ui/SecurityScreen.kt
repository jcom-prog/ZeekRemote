package com.openzeekr.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.openzeekr.app.guard.GuardAutomaton
import com.openzeekr.app.guard.GuardMode
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import com.openzeekr.app.Deps
import com.openzeekr.app.remote.CallResult
import com.openzeekr.app.remote.Command
import com.openzeekr.app.net.model.ServiceParameter
import com.openzeekr.app.ui.theme.Brand
import kotlinx.coroutines.launch

/**
 * Security & access: sentry guard (0.4.5: the switch follows the car's flag, re-read every time the tab comes
 * to the front; the automaton in Deps.guard sends the command, polls the car until it confirms and, in Auto,
 * switches the guard on after every lock), glovebox PIN, visitor mode, journey log.
 */
@Composable
fun SecurityScreen(deps: Deps, snackbar: (String) -> Unit, modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    val cfg by deps.config.config.collectAsState()
    // Visitor mode + glovebox PIN are owner-only server-side (a shared/Friend account gets 403), so
    // only surface them when we're logged in as the vehicle owner (vehicle-list `isOwner`).
    val owner = cfg.isOwner
    // 0.4.5: the guard row follows the automaton - the car's flag, the mode, one status line.
    val guard by deps.guard.view.collectAsState()
    var visitor by remember { mutableStateOf(false) }
    var gloveboxLocked by remember { mutableStateOf(false) }
    // A PIN entry is pending for one of these commands (glovebox / visitor need a code).
    var pinFor by remember { mutableStateOf<Command?>(null) }
    // Journey log takes over the whole screen (like the inbox) when opened.
    var showJourney by remember { mutableStateOf(false) }

    // Seed the real on/off state from getVehicleState (captured 2026-09-16): sentry/guard =
    // `vstdModeState` ("1"=armed), visitor = `visitorModeState`. So the toggles reflect the car,
    // not just the last in-app action. 0.4.5: read again on every ON_RESUME (the tab used to read once and
    // then show a stale "Armed"); the guard flag goes to the automaton, which notices a switch made elsewhere.
    val lifecycleOwner = LocalLifecycleOwner.current
    var resumes by remember { mutableStateOf(0) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) resumes++ }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(resumes) {
        if (resumes == 0) return@LaunchedEffect   // the observer delivers ON_RESUME itself
        when (val r = deps.control.controlState()) {
            is CallResult.Ok -> {
                deps.guard.onFlagRead(r.value["vstdModeState"]?.let { it == "1" })
                visitor = r.value["visitorModeState"] == "1"
                gloveboxLocked = r.value["gloveboxLocked"] == "1"
            }
            is CallResult.Err -> deps.guard.onFlagRead(null)
        }
    }

    if (showJourney) {
        JourneyScreen(deps, onBack = { showJourney = false }, snackbar = snackbar, modifier = modifier)
        return
    }

    fun fire(label: String, cmd: Command, extra: List<ServiceParameter> = emptyList()) {
        snackbar("$label…")
        scope.launch {
            when (val r = deps.control.send(cmd, extra)) {
                is CallResult.Ok -> snackbar("$label ✓"); is CallResult.Err -> snackbar("$label ✗ ${r.message}")
            }
        }
    }

    Column(modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = 16.dp)) {
        Label("Security & access")
        Column(Modifier.padding(horizontal = 20.dp)) {
            // 0.4.5: the switch shows the car's flag; the automaton sends the command and polls the car until it
            // confirms (about a minute), so the row can no longer stand still at a stale "Armed".
            SecRow(Icons.Filled.Shield, "Sentry guard", guard.status) {
                Switch(checked = guard.flag == true, enabled = !guard.pending && guard.mode != GuardMode.OFF,
                    onCheckedChange = { on -> deps.guard.onManualSwitch(on) },
                    colors = brandSwitchColors(Brand.good))
            }
            GuardSettingsRows(deps, guard.mode, cfg.guardSocThresholdPct, guard.flag, guard.flagReadAtMs, guard.lastSocPct)
            // Owner-only (shared accounts are refused server-side). Both need the user's PIN.
            if (owner) {
                SecRow(Icons.Filled.Inventory2, "Glovebox lock",
                    if (gloveboxLocked) "Locked · tap to unlock" else "Unlocked · tap to lock") {
                    Switch(checked = gloveboxLocked, onCheckedChange = { lock ->
                        // Enter PIN first; only flip once the command is confirmed.
                        pinFor = if (lock) Command.GLOVEBOX_LOCK else Command.GLOVEBOX_UNLOCK
                    }, colors = brandSwitchColors(Brand.good))
                }
                SecRow(Icons.Filled.Person, "Visitor mode", "Restricted access for a guest / valet") {
                    Switch(checked = visitor, onCheckedChange = { on ->
                        // Enter PIN first; only flip the toggle once the command is confirmed.
                        pinFor = if (on) Command.VISITOR_ON else Command.VISITOR_OFF
                    }, colors = brandSwitchColors(Brand.good))
                }
                // Journey log is also owner-only server-side (a shared/Friend account is refused —
                // confirmed 2026-09-16 against the stock app), so it lives inside the owner gate.
                SecRow(Icons.Filled.Timeline, "Journey log", "Trips · distance & energy · export",
                    onClick = { showJourney = true }) { Chevron() }
            }
        }

        pinFor?.let { cmd ->
            val title = when (cmd) {
                Command.GLOVEBOX_LOCK -> "Glovebox — lock with PIN"
                Command.GLOVEBOX_UNLOCK -> "Glovebox — unlock with PIN"
                Command.VISITOR_ON -> "Visitor mode — set PIN"
                Command.VISITOR_OFF -> "Visitor mode — enter PIN"
                else -> "Enter PIN"
            }
            PinDialog(title, onDismiss = { pinFor = null }, onConfirm = { pin ->
                fire(title.substringBefore(" —"), cmd, listOf(ServiceParameter("code", pin)))
                when (cmd) {
                    Command.VISITOR_ON -> visitor = true
                    Command.VISITOR_OFF -> visitor = false
                    Command.GLOVEBOX_LOCK -> gloveboxLocked = true
                    Command.GLOVEBOX_UNLOCK -> gloveboxLocked = false
                    else -> {}
                }
                pinFor = null
            })
        }
    }
}

/** 0.4.5: the guard mode (Off / Manual / Auto), the battery threshold and what the car last said. */
@Composable
private fun GuardSettingsRows(deps: Deps, mode: GuardMode, thresholdPct: Int, flag: Boolean?, flagReadAtMs: Long, lastSocPct: Int?) {
    var threshold by remember(thresholdPct) { mutableStateOf(thresholdPct.toString()) }
    Column(Modifier.padding(start = 46.dp, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (m in GuardMode.values()) {
                FilterChip(
                    selected = mode == m,
                    onClick = {
                        if (mode != m) { deps.config.update { it.copy(guardMode = m.key) }; deps.guard.onSettingsChanged() }
                    },
                    label = { Text(when (m) { GuardMode.OFF -> "Off"; GuardMode.MANUAL -> "Manual"; GuardMode.AUTO -> "Auto" }) },
                )
            }
        }
        if (mode == GuardMode.AUTO) {
            OutlinedTextField(
                value = threshold,
                onValueChange = { s ->
                    if (s.length <= 3 && s.all(Char::isDigit)) {
                        threshold = s
                        s.toIntOrNull()?.takeIf { it in 0..100 }?.let { pct ->
                            if (pct != thresholdPct) { deps.config.update { it.copy(guardSocThresholdPct = pct) }; deps.guard.onSettingsChanged() }
                        }
                    }
                },
                label = { Text("Battery threshold (%)") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
            )
            Text("Auto: ${GuardAutomaton.ARM_DELAY_MS / 60_000} min after a lock, with every door, the hood and the tailgate " +
                "closed and the battery above $thresholdPct%, the guard is switched on and the car is asked to confirm. " +
                "Below $thresholdPct% it is switched off again (back on above ${thresholdPct + GuardAutomaton.HYSTERESIS_PCT}% " +
                "or while charging). Your own switch, here or on the car's screen, wins until the next lock.",
                color = Brand.muted, fontSize = 11.sp)
        }
        Text(guardCarLine(flag, flagReadAtMs, lastSocPct), color = Brand.muted, fontSize = 11.sp)
    }
}

private fun guardCarLine(flag: Boolean?, readAtMs: Long, soc: Int?): String {
    val says = when (flag) { true -> "on"; false -> "off"; null -> "not read yet" }
    val read = if (readAtMs > 0L) " · read " + SimpleDateFormat("HH:mm", Locale.US).format(Date(readAtMs)) else ""
    val battery = soc?.let { " · battery $it%" } ?: ""
    return "Car says $says$read$battery"
}

@Composable
private fun PinDialog(title: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var pin by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = pin,
                onValueChange = { if (it.length <= 6 && it.all(Char::isDigit)) pin = it },
                label = { Text("PIN") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
            )
        },
        confirmButton = { TextButton(enabled = pin.length >= 4, onClick = { onConfirm(pin) }) { Text("Confirm") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun SecRow(icon: ImageVector, title: String, subtitle: String, onClick: (() -> Unit)? = null, trailing: @Composable () -> Unit) {
    Row(
        Modifier.fillMaxWidth().let { if (onClick != null) it.clickable(onClick = onClick) else it }.padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(34.dp).clip(RoundedCornerShape(10.dp)).background(Brand.surface2), contentAlignment = Alignment.Center) {
            Icon(icon, title, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(18.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Text(subtitle, color = Brand.muted, fontSize = 11.5.sp)
        }
        trailing()
    }
}

@Composable
private fun Chevron() = Text("›", color = Brand.faint, fontSize = 18.sp)

@Composable
private fun Label(text: String) =
    Text(text, color = Brand.faint, fontSize = 11.sp, fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(start = 22.dp, top = 20.dp, bottom = 6.dp))
