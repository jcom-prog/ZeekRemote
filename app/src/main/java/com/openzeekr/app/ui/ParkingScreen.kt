package com.openzeekr.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.LocalParking
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.openzeekr.app.Deps
import com.openzeekr.app.ble.CalibrationTestController
import com.openzeekr.app.ble.rpa.RpaController
import com.openzeekr.app.ble.rpa.RpaReq
import com.openzeekr.app.ui.theme.Brand

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun ParkingScreen(deps: Deps, modifier: Modifier = Modifier) {
    val state by deps.rpa.state.collectAsState()
    val cfg by deps.config.config.collectAsState()
    var showCalib by remember { mutableStateOf(false) }
    Column(
        modifier = modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // Where's my car — MapLibre location + navigate deeplink. Always shown.
        CarLocationSection(deps)

        // Remote Parking + Smart calibration are IN-DEVELOPMENT tools, gated behind developer mode
        // (Settings -> tap the version 10x). Normal users only see "where's my car" above, so we don't
        // have to hide/unhide these between releases.
        if (cfg.devMode) {
            // Remote parking — the car engages RPA over the ordinary BLE DK session (REQ_MODE ACKed
            // 0x1000, streams 0x117 SYNC, issues 0x115 challenges); NOT DK-3.0/SE tier-walled. To
            // actually maneuver, Remote Parking must be ARMED on the car's centre screen first.
            RemoteParkingControls(deps.rpa, state)

            // Smart-calibration test harness (0x0190-0x0199): teaches the car THIS phone's ranging
            // model, which passive entry AND remote parking depend on.
            CalibrationCard { showCalib = true }
        }
    }
    if (showCalib) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(onDismissRequest = { showCalib = false }, sheetState = sheetState) {
            CalibrationPanel(deps.calibTest)
        }
    }
}

@Composable
private fun CalibrationCard(onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Brand.surface2)
            .clickable(onClick = onClick).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        Box(Modifier.size(38.dp).clip(RoundedCornerShape(11.dp)).background(Brand.energy.copy(alpha = 0.16f)), contentAlignment = Alignment.Center) {
            Icon(Icons.Filled.LocalParking, null, tint = Brand.energy, modifier = Modifier.size(21.dp))
        }
        Column(Modifier.weight(1f)) {
            Text("Smart calibration (test)", fontWeight = FontWeight.Bold, fontSize = 16.sp)
            Text("Teach the car this phone's ranging — the missing piece for passive entry / RPA localization",
                color = Brand.muted, fontSize = 12.sp)
        }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = Brand.muted)
    }
}

@Composable
private fun CalibrationPanel(calib: CalibrationTestController) {
    val cs by calib.state.collectAsState()
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("Smart calibration (test)", fontWeight = FontWeight.Bold, fontSize = 18.sp)
        Text("Teaches the car THIS phone's BLE ranging model. Without it a non-stock phone can't be " +
            "localized, so passive entry and remote parking never converge. You walk to 4 positions once; " +
            "the car computes a table we store and re-send on later connects.",
            color = Brand.muted, fontSize = 12.5.sp)

        if (cs.message.isNotBlank()) {
            Text(cs.message,
                color = if (cs.phase == CalibrationTestController.Phase.ERROR) Brand.crit else Brand.accent,
                fontSize = 13.sp, fontWeight = FontWeight.Medium)
        }
        // While walking, the current step prompt is in cs.message; offer the advance button.
        if (cs.step in 1..cs.totalSteps) {
            PrimaryButton("I'm in position — Continue", Modifier.fillMaxWidth()) { calib.advanceStep() }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            PrimaryButton(
                if (cs.busy && cs.phase == CalibrationTestController.Phase.RUNNING) "Running…" else "Start calibration",
                Modifier.weight(1f), enabled = !cs.busy,
            ) { calib.runCalibration() }
            GhostButton("Replay table", Modifier.weight(1f), enabled = cs.hasTable && !cs.busy) { calib.replayCalibration() }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            GhostButton("BLE Lock", Modifier.weight(1f), enabled = !cs.busy) { calib.lock() }
            GhostButton("BLE Unlock", Modifier.weight(1f), enabled = !cs.busy) { calib.unlock() }
        }
        GhostButton("Cancel", Modifier.fillMaxWidth(), tint = Brand.crit) { calib.cancel() }

        Text("⚠️ Diagnostic. Watch `logcat -s dk,carprox`. If 0x0190 gets a 0x0191 reply the car engages " +
            "calibration for our key; total silence = a real car-side ranging gate. Keep the area clear.",
            color = Brand.faint, fontSize = 11.sp)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RemoteParkingControls(rpa: RpaController, state: RpaController.UiState) {
    val phase = state.phase
    val connecting = phase == RpaController.Phase.CONNECTING
    val canConnect = phase == RpaController.Phase.IDLE || phase == RpaController.Phase.ERROR || phase == RpaController.Phase.DONE
    val active = phase.sessionActive
    // Plan K4: leaving the screen or the app (screen off, another app on top) ends a hold-to-move.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, rpa) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP && rpa.state.value.phase == RpaController.Phase.MOVING) rpa.releaseMove()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            if (rpa.state.value.phase == RpaController.Phase.MOVING) rpa.releaseMove()
        }
    }

    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
            Box(Modifier.size(38.dp).clip(RoundedCornerShape(11.dp)).background(Brand.accent.copy(alpha = 0.16f)), contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.LocalParking, null, tint = Brand.accent, modifier = Modifier.size(21.dp))
            }
            Text("Remote Parking", fontWeight = FontWeight.Bold, fontSize = 18.sp, modifier = Modifier.weight(1f))
            PhasePill(phase, connecting)
        }
        state.message?.let {
            Text(it, color = if (phase == RpaController.Phase.ERROR) Brand.crit else Brand.muted, fontSize = 12.5.sp)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            PrimaryButton(if (connecting) "Connecting…" else "Connect", Modifier.weight(1f), enabled = canConnect) { rpa.begin() }
            GhostButton("Stop", Modifier.weight(1f), enabled = active, tint = Brand.crit) { rpa.stop() }
        }

        // 0.3.4 (user decision 15/10): no "blind probe" any more, and the park/move controls stay hidden
        // until a measurement shows that the car opens a remote-parking session for this key at all.
        if (!SHOW_MANOEUVRE_CONTROLS) {
            Text("Park and move controls are hidden until a measurement shows that the car opens a " +
                "remote-parking session for this key.", color = Brand.faint, fontSize = 11.sp)
        } else {
            SectionHeader("Park in")
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Tile("Search", Modifier.weight(1f)) { rpa.searchSlot() }
                Tile("Park in", Modifier.weight(1f)) { rpa.startParkIn() }
                Tile("Continue", Modifier.weight(1f)) { rpa.continueParking() }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Tile("Pause", Modifier.weight(1f)) { rpa.pause() }
                Tile("Undo", Modifier.weight(1f)) { rpa.undo() }
                Spacer(Modifier.weight(1f))
            }

            SectionHeader("Park out")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(
                    "Parallel R" to RpaReq.PARALLEL_RIGHT_OUT,
                    "Parallel L" to RpaReq.PARALLEL_LEFT_OUT,
                    "Head-Perp R" to RpaReq.HEAD_PERPENDICULAR_RIGHT_OUT,
                    "Head-Perp L" to RpaReq.HEAD_PERPENDICULAR_LEFT_OUT,
                    "Tail-Perp R" to RpaReq.TAIL_PERPENDICULAR_RIGHT_OUT,
                    "Tail-Perp L" to RpaReq.TAIL_PERPENDICULAR_LEFT_OUT,
                ).forEach { (label, dir) -> Chip(label) { rpa.startParkOut(dir) } }
            }

            SectionHeader("Hold to move")
            Text("Press and hold; release stops instantly (the car halts the moment the 500 ms heartbeat drops). " +
                "A hold ends by itself after ${RpaController.MAX_HOLD_MS / 1000} s, and when you leave this screen.",
                color = Brand.muted, fontSize = 12.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                HoldPad("Forward", Icons.Filled.ArrowUpward, Modifier.weight(1f), onHold = { rpa.holdMove(true) }, onRelease = { rpa.releaseMove() })
                HoldPad("Backward", Icons.Filled.ArrowDownward, Modifier.weight(1f), onHold = { rpa.holdMove(false) }, onRelease = { rpa.releaseMove() })
            }
        }
    }
}

@Composable
private fun PhasePill(phase: RpaController.Phase, connecting: Boolean) {
    val (label, color) = when (phase) {
        RpaController.Phase.READY -> "Ready" to Brand.good
        RpaController.Phase.MOVING, RpaController.Phase.PARKING_IN, RpaController.Phase.PARKING_OUT -> "Active" to Brand.accent
        RpaController.Phase.PAUSED -> "Paused" to Brand.energy
        RpaController.Phase.DONE -> "Done" to Brand.good
        RpaController.Phase.ERROR -> "Error" to Brand.crit
        RpaController.Phase.CONNECTING -> "Connecting" to Brand.accent
        else -> "Idle" to Brand.faint
    }
    Row(
        Modifier.clip(CircleShape).background(color.copy(alpha = 0.16f)).padding(horizontal = 11.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (connecting) CircularProgressIndicator(Modifier.size(11.dp), color = color, strokeWidth = 1.5.dp)
        else Box(Modifier.size(7.dp).clip(CircleShape).background(color))
        Text(label, color = color, fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun Tile(label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier.clip(RoundedCornerShape(12.dp)).background(Brand.surface2).clickable(onClick = onClick).padding(vertical = 13.dp),
        contentAlignment = Alignment.Center,
    ) { Text(label, color = MaterialTheme.colorScheme.onSurface, fontSize = 13.sp, fontWeight = FontWeight.SemiBold) }
}

@Composable
private fun Chip(label: String, onClick: () -> Unit) {
    Box(
        Modifier.clip(CircleShape).background(Brand.surface2).clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 9.dp),
        contentAlignment = Alignment.Center,
    ) { Text(label, color = Brand.accent, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold) }
}

@Composable
private fun HoldPad(label: String, icon: ImageVector, modifier: Modifier, onHold: () -> Unit, onRelease: () -> Unit) {
    Column(
        modifier.height(100.dp).clip(RoundedCornerShape(16.dp)).background(Brand.accent.copy(alpha = 0.14f))
            // Plan K4: the release runs even when the gesture is cancelled (finally), not only on finger-up.
            .pointerInput(Unit) { detectTapGestures(onPress = { onHold(); try { tryAwaitRelease() } finally { onRelease() } }) },
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
    ) {
        Box(Modifier.size(40.dp).clip(CircleShape).background(Brand.accent.copy(alpha = 0.22f)), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = Brand.accent)
        }
        Text(label, color = Brand.accent, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 6.dp))
    }
}

/** Park in/out and hold-to-move send manoeuvre commands; hidden until a remote-parking session is proven. */
private const val SHOW_MANOEUVRE_CONTROLS = false
