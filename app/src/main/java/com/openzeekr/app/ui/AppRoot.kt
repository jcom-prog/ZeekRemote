package com.openzeekr.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.NotificationsNone
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.content.Intent
import android.net.Uri
import com.openzeekr.app.Deps
import com.openzeekr.app.ble.DkBleManager
import com.openzeekr.app.ble.DkProvisioning
import com.openzeekr.app.ui.theme.Brand
import kotlinx.coroutines.launch

private enum class Tab(val label: String, val icon: ImageVector) {
    VEHICLE("Vehicle", Icons.Filled.DirectionsCar),
    PARKING("Location", Icons.Filled.LocationOn),
    SECURITY("Security", Icons.Filled.Shield),
    SCHEDULE("Schedule", Icons.Filled.CalendarMonth),
    KEY("Key", Icons.Filled.VpnKey),
    SETTINGS("Settings", Icons.Filled.Settings),
    // Sentry footage/live-view stays hidden (sentinel-monitoring-service is CN-only /
    // unrouted on EU). SentryScreen is kept in the tree for when a workaround is found.
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppRoot(deps: Deps) {
    val tabs = remember { Tab.entries.toTypedArray() }
    val snackbarHost = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val snackbar: (String) -> Unit = { msg -> scope.launch { snackbarHost.showSnackbar(msg) } }

    // Flow (mirrors the stock app): logged out -> Settings/login; logged in but no
    // key yet -> Key provisioning; once provisioned -> Controls, and the foreground
    // key service runs to keep BLE connected.
    val cfg by deps.config.config.collectAsState()
    val prov by deps.provisioning.state.collectAsState()
    val loggedIn = cfg.accessToken.isNotBlank()
    val provisioned = remember(prov.step) { deps.dkIdentity.isProvisioned } ||
        prov.step == DkProvisioning.Step.DONE

    // First run: guided wizard (login → key). Skip straight to the app once done.
    if (!cfg.onboardingDone) {
        OnboardingScreen(deps, onDone = { deps.config.update { it.copy(onboardingDone = true) } })
        return
    }

    // A revoked/ended share clears VIN; never leave the BLE key service active for a car that is
    // no longer attached to this account.
    AppBootstrap(deps, serviceEnabled = loggedIn && provisioned && cfg.vin.isNotBlank())

    // Account taken over on another device (TSP 079021): the interceptor already cleared the
    // token (so we're now on the signed-out flow) — just explain why. Mirrors the stock app,
    // which also signs you out when the account goes active elsewhere.
    val loggedInElsewhere by com.openzeekr.app.net.SessionSignal.loggedInElsewhere.collectAsState()
    LaunchedEffect(loggedInElsewhere) {
        if (loggedInElsewhere) {
            snackbar("Signed out — your account was opened on another device (e.g. the Zeekr app). Sign in again to reconnect.")
            com.openzeekr.app.net.SessionSignal.loggedInElsewhere.value = false
        }
    }

    // The moment a key is provisioned, push it to any paired watch so it's armed without the
    // user opening the watch app (the watch caches it; it also pulls on open as a fallback).
    val pushCtx = androidx.compose.ui.platform.LocalContext.current
    LaunchedEffect(prov.step) {
        if (prov.step == DkProvisioning.Step.DONE) com.openzeekr.app.wear.PhoneKeyPush.pushToWatches(pushCtx)
    }

    val bleState by deps.ble.state.collectAsState()
    val bleReady = bleState == DkBleManager.State.SESSION_READY || bleState == DkBleManager.State.CONNECTED
    val carName = cfg.carNickname.ifBlank { "My Zeekr" }
    var renaming by remember { mutableStateOf(false) }
    var draftName by remember { mutableStateOf("") }

    // Message center (charging done, abnormal parking, alarms, OTA, …) — a bell in the
    // top bar with an unread badge; opening it takes over the screen.
    var showInbox by remember { mutableStateOf(false) }
    var unread by remember { mutableIntStateOf(0) }
    val refreshUnread: () -> Unit = {
        if (loggedIn) deps.appScope.launch {
            when (val r = deps.inbox.unreadCount()) {
                is com.openzeekr.app.remote.CallResult.Ok -> unread = r.value
                is com.openzeekr.app.remote.CallResult.Err -> {}
            }
            deps.refreshInvites()
        }
    }
    LaunchedEffect(loggedIn) { refreshUnread() }

    val invites by deps.pendingInvites.collectAsState()
    var inviteBusy by remember { mutableStateOf(false) }

    if (showInbox) {
        InboxScreen(deps, onBack = { showInbox = false; refreshUnread() }, snackbar = snackbar)
        return
    }

    var tab by remember { mutableIntStateOf(Tab.SETTINGS.ordinal) }
    LaunchedEffect(loggedIn, provisioned) {
        tab = when {
            !loggedIn -> Tab.SETTINGS.ordinal
            !provisioned -> Tab.KEY.ordinal
            else -> Tab.VEHICLE.ordinal
        }
    }

    Scaffold(
        topBar = {
            val connText = when { bleReady && loggedIn -> "BLE · Cloud"; bleReady -> "BLE"; loggedIn -> "Cloud"; else -> "Offline" }
            val connColor = when { bleReady -> Brand.good; loggedIn -> Brand.accent; else -> Brand.faint }
            Row(
                Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface)
                    .statusBarsPadding().padding(start = 16.dp, end = 12.dp, top = 6.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BrandBadge(size = 36)
                Spacer(Modifier.width(11.dp))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(carName, fontWeight = FontWeight.Bold, fontSize = 18.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Icon(Icons.Filled.Edit, "Rename car", tint = Brand.faint,
                            modifier = Modifier.size(15.dp).clickable { draftName = carName; renaming = true })
                    }
                    Text(if (cfg.vin.isNotBlank()) "VIN ••••${cfg.vin.takeLast(3)}" else "Tap the pencil to name your car",
                        color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                // Notifications bell with an always-readable count badge.
                Box {
                    Icon(
                        Icons.Filled.NotificationsNone, "Messages", tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(38.dp).clip(CircleShape).clickable { showInbox = true }.padding(7.dp),
                    )
                    if (unread > 0) {
                        Box(
                            Modifier.align(Alignment.TopEnd)
                                .offset(x = 3.dp, y = (-1).dp)
                                .defaultMinSize(minWidth = 18.dp, minHeight = 18.dp)
                                .clip(RoundedCornerShape(9.dp))
                                .background(Brand.crit)
                                .border(1.5.dp, MaterialTheme.colorScheme.surface, RoundedCornerShape(9.dp))
                                .padding(horizontal = 4.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                if (unread > 99) "99+" else "$unread",
                                color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold, maxLines = 1,
                            )
                        }
                    }
                }
                Spacer(Modifier.width(6.dp))
                Row(
                    Modifier.clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant)
                        .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape).padding(horizontal = 11.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp),
                ) {
                    Box(Modifier.size(7.dp).clip(CircleShape).background(connColor))
                    Text(connText, fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        },
        bottomBar = {
            // Custom, horizontally-scrollable bar: with 6+ tabs the stock NavigationBar squeezes labels
            // until they wrap ("Schedul\ne"). Fixed-width, single-line items that scroll sideways keep
            // every label intact on any screen width.
            Column {
                Box(Modifier.fillMaxWidth().height(1.dp).background(Brand.line))
                Row(
                    Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface)
                        .horizontalScroll(rememberScrollState())
                        .navigationBarsPadding()
                        .padding(horizontal = 6.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    tabs.forEachIndexed { i, t ->
                        val selected = tab == i
                        val tint = if (selected) Brand.accent else Brand.muted
                        Column(
                            Modifier.width(76.dp).clip(RoundedCornerShape(14.dp))
                                .clickable { tab = i }.padding(vertical = 6.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(3.dp),
                        ) {
                            Icon(t.icon, t.label, tint = tint, modifier = Modifier.size(24.dp))
                            Text(
                                t.label, color = tint, fontSize = 11.sp, maxLines = 1,
                                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                            )
                        }
                    }
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbarHost) },
    ) { pad ->
        val m = Modifier.padding(pad)
        when (tabs[tab]) {
            Tab.VEHICLE -> VehicleScreen(deps, snackbar, m)
            Tab.PARKING -> ParkingScreen(deps, m)
            Tab.SECURITY -> SecurityScreen(deps, snackbar, m)
            Tab.SCHEDULE -> ScheduleScreen(deps, snackbar, m)
            Tab.KEY -> androidx.compose.foundation.layout.Box(m) {
                SetupScreen(
                    provisioning = deps.provisioning,
                    ble = deps.ble,
                    lock = deps.lock,
                    config = deps.config,
                    isProvisioned = { deps.dkIdentity.isProvisioned },
                    dkId = remember(provisioned) { runCatching { deps.dkIdentity.credential()?.dkId }.getOrNull() },
                    onRemoveKey = {
                        // Revoke cloud-side (car forgets it) + wipe ALL local material + purge the watch.
                        scope.launch {
                            deps.provisioning.removeKey()
                            com.openzeekr.app.wear.PhoneKeyPush.purgeWatches(pushCtx)
                        }
                    },
                    snackbar = snackbar,
                )
            }
            Tab.SETTINGS -> SettingsScreen(deps, m)
        }
    }

    if (renaming) {
        AlertDialog(
            onDismissRequest = { renaming = false },
            title = { Text("Name your car") },
            text = {
                OutlinedTextField(
                    value = draftName, onValueChange = { draftName = it },
                    singleLine = true, label = { Text("Car name") },
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val n = draftName.trim()
                    deps.config.update { it.copy(carNickname = n) }
                    if (n.isNotBlank()) deps.appScope.launch { runCatching { deps.control.renameVehicle(n) } }
                    renaming = false
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { renaming = false }) { Text("Cancel") } },
        )
    }

    invites.firstOrNull()?.let { invite ->
        val respond: (Boolean) -> Unit = { accept ->
            inviteBusy = true
            deps.appScope.launch {
                when (val result = deps.share.respond(invite, accept)) {
                    is com.openzeekr.app.remote.CallResult.Ok -> {
                        snackbar(if (accept) "Shared car accepted" else "Invitation declined")
                        deps.pendingInvites.value = deps.pendingInvites.value.drop(1)
                        if (accept) deps.vehicleState.refresh()
                        deps.refreshInvites()
                    }
                    is com.openzeekr.app.remote.CallResult.Err ->
                        snackbar("Couldn't respond: ${result.message}")
                }
                inviteBusy = false
            }
        }
        AlertDialog(
            onDismissRequest = { if (!inviteBusy) deps.pendingInvites.value = deps.pendingInvites.value.drop(1) },
            title = { Text("Car shared with you") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text(
                        buildString {
                            append(invite.model ?: "A Zeekr")
                            invite.vin?.let { append(" · VIN …").append(it.takeLast(4)) }
                        },
                        fontWeight = FontWeight.SemiBold,
                    )
                    invite.ownerName?.let { Text("Shared by $it", fontSize = 13.sp, color = Brand.muted) }
                    invite.functionNames?.let { Text("Access: $it", fontSize = 13.sp, color = Brand.muted) }
                    invite.endTime?.let { Text("Until ${formatShareDate(it)}", fontSize = 13.sp, color = Brand.muted) }
                    Text(
                        "Accepting makes this the active ZeekRemote car. The Bluetooth Digital Key is set up separately.",
                        fontSize = 12.sp, color = Brand.muted,
                    )
                }
            },
            confirmButton = { TextButton(enabled = !inviteBusy, onClick = { respond(true) }) { Text("Accept") } },
            dismissButton = { TextButton(enabled = !inviteBusy, onClick = { respond(false) }) { Text("Decline") } },
        )
    }

    // One-time "this is a passion project" note. Shown once after onboarding; dismissing it (either
    // button) sets the persisted flag so it never appears again.
    if (!cfg.supportNoteShown) {
        val dismiss = { deps.config.update { it.copy(supportNoteShown = true) } }
        AlertDialog(
            onDismissRequest = dismiss,
            title = { Text("A quick hello 👋") },
            text = {
                Text(
                    "ZeekRemote is a free, non-commercial passion project — not affiliated with Zeekr. " +
                        "Yes, a lot of it is vibe-coded, but it also took a solid week of sleepless nights " +
                        "to make your key connect and unlock cleanly.\n\n" +
                        "If it's useful to you, a small tip keeps development going. Totally optional — " +
                        "either way, enjoy the app. 💚",
                    fontSize = 14.sp,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    runCatching {
                        pushCtx.startActivity(
                            Intent(Intent.ACTION_VIEW, Uri.parse(REVOLUT_URL))
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    }.onFailure { snackbar("Couldn't open the link") }
                    dismiss()
                }) { Text("Support ❤️") }
            },
            dismissButton = { TextButton(onClick = dismiss) { Text("Maybe later") } },
        )
    }
}

// Revolut tip link shown in the one-time support note. Replace with the real revolut.me handle.
private const val REVOLUT_URL = "https://revolut.me/REPLACE_ME"

private fun formatShareDate(epoch: Long): String {
    val millis = if (epoch < 100_000_000_000L) epoch * 1000 else epoch
    return java.text.SimpleDateFormat("d MMM yyyy", java.util.Locale.getDefault())
        .format(java.util.Date(millis))
}
