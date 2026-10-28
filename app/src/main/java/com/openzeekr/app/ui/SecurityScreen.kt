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
import androidx.compose.foundation.layout.width
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
                // 0.4.6: while a command is pending the switch shows the owner's hand (it springs back when the car
                // does not confirm within the polls); the line below keeps saying what the car last reported.
                Switch(checked = guard.wanted ?: (guard.flag == true), enabled = guard.mode != GuardMode.OFF,
                    onCheckedChange = { on -> deps.guard.onManualSwitch(on) },
                    colors = brandSwitchColors(Brand.good))
            }
            GuardSettingsRows(deps, cfg, guard, snackbar)
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

/** 0.4.5/0.4.6: the guard mode (Off / Manual / Auto), the battery threshold, the quiet zones, the daily check, the
 *  window warning and what the car last said. Zones stay on this phone (encrypted settings); nothing of them is logged. */
@Composable
private fun GuardSettingsRows(deps: Deps, cfg: com.openzeekr.app.config.SecretsConfig, guard: com.openzeekr.app.guard.GuardView, snackbar: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    val mode = guard.mode
    val thresholdPct = cfg.guardSocThresholdPct
    var threshold by remember(thresholdPct) { mutableStateOf(thresholdPct.toString()) }
    val dailyHour = cfg.guardDailyCheckHour
    var daily by remember(dailyHour) { mutableStateOf(if (dailyHour in 0..23) dailyHour.toString() else "") }
    var adding by remember { mutableStateOf(false) }
    fun change(transform: (com.openzeekr.app.config.SecretsConfig) -> com.openzeekr.app.config.SecretsConfig) {
        deps.config.update(transform); deps.guard.onSettingsChanged()
    }
    Column(Modifier.padding(start = 46.dp, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (m in GuardMode.values()) {
                FilterChip(
                    selected = mode == m,
                    onClick = { if (mode != m) change { it.copy(guardMode = m.key) } },
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
                        s.toIntOrNull()?.takeIf { it in 0..100 }?.let { pct -> if (pct != thresholdPct) change { it.copy(guardSocThresholdPct = pct) } }
                    }
                },
                label = { Text("Battery threshold (%)") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
            )
            Text("Auto: ${GuardAutomaton.ARM_DELAY_MS / 60_000} min after a lock, with every door, the bonnet and the tailgate " +
                "closed and the battery above $thresholdPct%, the guard is switched on and the car is asked to confirm. " +
                "Below $thresholdPct% it is switched off again (back on above ${thresholdPct + GuardAutomaton.HYSTERESIS_PCT}% " +
                "or while charging). Your own switch, here or on the car's screen, wins until the next lock.",
                color = Brand.muted, fontSize = 11.sp)
            // 0.4.6: quiet zones - up to three places where the automaton leaves the guard off.
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Quiet zones (max ${GuardAutomaton.MAX_ZONES})", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                Text("Skip in zones", color = Brand.muted, fontSize = 11.sp)
                Switch(checked = cfg.guardSkipInZones, onCheckedChange = { on -> change { it.copy(guardSkipInZones = on) } },
                    colors = brandSwitchColors(Brand.good))
            }
            cfg.guardZones.forEachIndexed { i, zone ->
                var label by remember(i, zone.label) { mutableStateOf(zone.label) }
                var radius by remember(i, zone.radiusM) { mutableStateOf(zone.radiusM.toString()) }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedTextField(
                        value = label,
                        onValueChange = { s ->
                            if (s.length <= 20) {
                                label = s
                                // Labels and radii are read at arm time; no settings line per keystroke.
                                deps.config.update { c -> c.copy(guardZones = c.guardZones.mapIndexed { j, z -> if (j == i) z.copy(label = s) else z }) }
                            }
                        },
                        label = { Text("Zone ${i + 1}") }, singleLine = true, modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = radius,
                        onValueChange = { s ->
                            if (s.length <= 4 && s.all(Char::isDigit)) {
                                radius = s
                                s.toIntOrNull()?.takeIf { it in 10..2000 }?.let { r ->
                                    deps.config.update { c -> c.copy(guardZones = c.guardZones.mapIndexed { j, z -> if (j == i) z.copy(radiusM = r) else z }) }
                                }
                            }
                        },
                        label = { Text("m") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.width(84.dp),
                    )
                    TextButton(onClick = { change { c -> c.copy(guardZones = c.guardZones.filterIndexed { j, _ -> j != i }) } }) { Text("Clear") }
                }
            }
            TextButton(
                enabled = cfg.guardZones.size < GuardAutomaton.MAX_ZONES && !adding,
                onClick = {
                    adding = true
                    scope.launch {
                        try {
                            val p = (deps.control.status() as? CallResult.Ok)?.value?.basicVehicleStatus?.position
                            val la = p?.latitude; val lo = p?.longitude
                            if (la == null || lo == null || p.posCanBeTrusted == false) snackbar("Car position unknown · try again later")
                            else {
                                change { c -> c.copy(guardZones = (c.guardZones + com.openzeekr.app.guard.GuardZone(la, lo)).take(GuardAutomaton.MAX_ZONES)) }
                                snackbar("Zone added (where the car is now)")
                            }
                        } finally { adding = false }
                    }
                },
            ) { Text(if (adding) "Reading the car's position…" else "Add zone here") }
            Text("Inside a zone the guard is left off after a lock; slide the switch to switch it on anyway for that parking. " +
                "Zones are kept on this phone only.", color = Brand.muted, fontSize = 11.sp)
        }
        // 0.4.6: the daily check and the window warning apply in every mode.
        OutlinedTextField(
            value = daily,
            onValueChange = { s ->
                if (s.length <= 2 && s.all(Char::isDigit)) {
                    daily = s
                    val h = s.toIntOrNull()
                    if (s.isEmpty()) { if (dailyHour != -1) change { it.copy(guardDailyCheckHour = -1) } }
                    else if (h != null && h in 0..23 && h != dailyHour) change { it.copy(guardDailyCheckHour = h) }
                }
            },
            label = { Text("Daily check at (hour 0-23, empty = off)") }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth(),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Warn when a window or the sunroof is open", color = Brand.muted, fontSize = 11.sp, modifier = Modifier.weight(1f))
            Switch(checked = cfg.guardWindowWarning, onCheckedChange = { on -> change { it.copy(guardWindowWarning = on) } },
                colors = brandSwitchColors(Brand.good))
        }
        Text(guardCarLine(guard.flag, guard.flagReadAtMs, guard.lastSocPct), color = Brand.muted, fontSize = 11.sp)
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
