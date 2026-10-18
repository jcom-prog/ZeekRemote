package com.openzeekr.app.ui

import android.app.TimePickerDialog
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openzeekr.app.Deps
import com.openzeekr.app.net.model.BookingTravelSetting
import com.openzeekr.app.remote.CallResult
import com.openzeekr.app.remote.CarWrite
import com.openzeekr.app.remote.DepartureDraft
import com.openzeekr.app.remote.Departures
import com.openzeekr.app.remote.ScheduleWrites
import com.openzeekr.app.ui.theme.Brand
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Scheduled charging + departure ("booking travel") — both stored car-side on the TSP
 * `ms-charge-manage` service (see [com.openzeekr.app.remote.ScheduleRepository]).
 *
 *  - Charging schedule: off-peak charge windows (serviceId "ZAZ"). A single wholesale
 *    setting (priority-to-SOC + a list of start/end windows) you edit and save.
 *  - Departure schedule: precondition-by-departure-time plans (serviceId "ZAO", CRUD via
 *    start/edit/stop). Each has a name, a departure time and recurring weekdays (Mon..Sun), plus
 *    what the official app offers: climate with a target temperature, steering wheel heating, front
 *    seat heating and battery temperature maintenance. Tap a plan to edit it. The car's unused plan
 *    slots are hidden, and a new plan only goes into a free slot (see [Departures]).
 *
 * Self-contained: expose [ScheduleScreen] and wire a nav entry in AppRoot (owned by the parent).
 */
@Composable
fun ScheduleScreen(deps: Deps, snackbar: (String) -> Unit, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current

    // ---- charging schedule state (V1: a single daily window per timerId) ----
    var chargeEnabled by remember { mutableStateOf(false) }
    var chargeStart by remember { mutableStateOf("22:00") }
    var chargeEnd by remember { mutableStateOf("06:00") }
    // The "charging will continue if the limit isn't reached at end time" option (wire target 1/2).
    var keepCharging by remember { mutableStateOf(false) }
    // Reused from the read-back so an edit targets the car's existing plan slot / trigger.
    var chargeTimerId by remember { mutableStateOf("2") }
    var chargeScheduledTime by remember { mutableStateOf("") }
    var chargeLoading by remember { mutableStateOf(true) }

    // ---- departure schedule state ----
    val departures = remember { mutableStateListOf<BookingTravelSetting>() }
    var depLoading by remember { mutableStateOf(true) }
    // The departure editor: creating a new plan, or editing [editing].
    var creating by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<BookingTravelSetting?>(null) }
    // Durations and levels the official app uses, read from a plan it made (else 15 min defaults).
    val template = Departures.template(departures)
    // The write in flight (the app waits up to a minute for the car) and a new departure the cloud took
    // that the car has not shown yet (0.3.8, see ScheduleWrites).
    val writes = deps.schedule.writes
    val busy by writes.busy.collectAsState()
    val awaiting by writes.awaiting.collectAsState()

    suspend fun reloadCharge() {
        chargeLoading = true
        when (val r = deps.schedule.chargePlan()) {
            is CallResult.Ok -> {
                val p = r.value
                chargeEnabled = p.command == "start"
                p.startTime?.takeIf { it.isNotBlank() }?.let { chargeStart = it }
                p.endTime?.takeIf { it.isNotBlank() }?.let { chargeEnd = it }
                keepCharging = p.target == "1"
                p.timerId?.takeIf { it.isNotBlank() }?.let { chargeTimerId = it }
                p.scheduledTime?.takeIf { it.isNotBlank() }?.let { chargeScheduledTime = it }
            }
            is CallResult.Err -> snackbar("Couldn't load charging schedule: ${r.message}")
        }
        chargeLoading = false
    }

    /** [quiet]: a background look (after a write, or while a new departure waits): no spinner, no error. */
    suspend fun reloadDepartures(quiet: Boolean = false) {
        if (!quiet) depLoading = true
        when (val r = deps.schedule.departures()) {
            is CallResult.Ok -> {
                departures.clear(); departures.addAll(r.value)
                if (writes.readBack(r.value)) snackbar("Departure stored on the car ✓")
            }
            is CallResult.Err -> if (!quiet) snackbar("Couldn't load departures: ${r.message}")
        }
        depLoading = false
    }

    LaunchedEffect(Unit) { reloadCharge(); reloadDepartures() }
    // After a write ends (also one started before this tab opened), show what the car holds now.
    var sawBusy by remember { mutableStateOf(false) }
    LaunchedEffect(busy) {
        if (busy != null) sawBusy = true
        else if (sawBusy) { sawBusy = false; reloadDepartures(quiet = true) }
    }
    // A new departure waiting for the car: look again every 30 s while this tab is open, so it shows up
    // once the car has it. The wait ends after ScheduleWrites.HOLD_MS (the cloud may have dropped it).
    LaunchedEffect(awaiting) {
        while (writes.stillAwaiting() != null) {
            delay(RECHECK_MS)
            if (writes.busy.value == null) reloadDepartures(quiet = true)
        }
    }

    /**
     * Sends a schedule write in the app scope, so it finishes and reports even when you leave the tab:
     * [done] when the car confirmed it, [notConfirmed] when the cloud took it but the car did not show it
     * within a minute, and "[failed] ✗ reason" when the cloud refused it.
     */
    fun write(done: String, notConfirmed: String, failed: String, call: suspend () -> CallResult<CarWrite>) {
        deps.appScope.launch {
            when (val r = call()) {
                is CallResult.Ok -> snackbar(if (r.value == CarWrite.CONFIRMED) done else notConfirmed)
                is CallResult.Err -> snackbar("$failed ✗ ${r.message}")
            }
        }
    }

    Column(
        modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // ============================ CHARGING ============================
        SectionHeader("Charging schedule")
        CockpitCard {
            Text(
                "A daily off-peak charge window — the car charges inside these times.",
                color = Brand.muted, fontSize = 12.sp,
            )
            if (chargeLoading) {
                LoadingRow()
            } else {
                // The single window: start → end + an on/off switch.
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Brand.surface2).padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Icon(Icons.Filled.Bolt, null, tint = Brand.accent, modifier = Modifier.size(20.dp))
                    TimeChip(chargeStart) { pickTime(ctx, chargeStart, withSeconds = false) { chargeStart = it } }
                    Text("→", color = Brand.muted)
                    TimeChip(chargeEnd) { pickTime(ctx, chargeEnd, withSeconds = false) { chargeEnd = it } }
                    Spacer(Modifier.weight(1f))
                    Switch(checked = chargeEnabled, onCheckedChange = { chargeEnabled = it }, colors = brandSwitchColors(Brand.good))
                }
                // "Keep charging past end time" — stock: "Charging will continue, if limit is not reached at end time."
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text("Keep charging past end time", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        Text("Charging will continue if the limit isn't reached at the end time.", color = Brand.muted, fontSize = 11.5.sp)
                    }
                    Switch(checked = keepCharging, onCheckedChange = { keepCharging = it }, colors = brandSwitchColors())
                }
                val chargeWaiting = busy == ScheduleWrites.Kind.CHARGE
                PrimaryButton(if (chargeWaiting) "Waiting for the car…" else "Save", Modifier.fillMaxWidth(), enabled = busy == null) {
                    // The screen keeps the values just saved: the car's read-back lags behind a write.
                    val on = chargeEnabled; val start = chargeStart; val end = chargeEnd
                    val keep = keepCharging; val timerId = chargeTimerId; val trigger = chargeScheduledTime
                    write(done = "Charging schedule saved on the car ✓", notConfirmed = NOT_CONFIRMED_CHARGE, failed = "Save failed") {
                        deps.schedule.setChargePlan(
                            enabled = on, startTime = start, endTime = end,
                            keepCharging = keep, timerId = timerId, scheduledTime = trigger,
                        )
                    }
                }
                if (chargeWaiting) WaitingRow(WAITING_TEXT)
            }
        }

        // ============================ DEPARTURE ============================
        SectionHeader("Departure schedule")
        CockpitCard {
            Text(
                "Have the cabin (and battery) preconditioned by your departure time on the days you pick.",
                color = Brand.muted, fontSize = 12.sp,
            )
            val shown = Departures.visible(departures)
            val freeSlots = departures.count(Departures::isEmptySlot)
            if (depLoading) {
                LoadingRow()
            } else if (shown.isEmpty()) {
                EmptyRow("No departure schedules yet.")
            } else {
                shown.forEach { d ->
                    DepartureRow(
                        setting = d,
                        enabled = busy == null,
                        onEdit = { editing = d },
                        onToggle = { on ->
                            // The whole plan with the new state: sending it twice is harmless. The list shows
                            // the car's read-back after the write, so an unconfirmed switch shows as it was.
                            val updated = Departures.sanitizedForWrite(d.copy(sts = if (on) 1 else 0), template)
                            write(
                                done = if (on) "Departure switched on ✓" else "Departure switched off ✓",
                                notConfirmed = NOT_CONFIRMED_CHANGE, failed = "Update failed",
                            ) { deps.schedule.updateDeparture(updated, ScheduleWrites.Kind.SWITCH) }
                        },
                        onDelete = {
                            // "Delete" frees the car-side slot (command stop); confirmed once the slot reads back empty.
                            val target = Departures.sanitizedForWrite(d, template)
                            write(done = "Departure deleted ✓", notConfirmed = NOT_CONFIRMED_DELETE, failed = "Delete failed") {
                                deps.schedule.deleteDeparture(target)
                            }
                        },
                    )
                }
            }
            awaiting?.let { AwaitingRow(it.plan, confirming = busy == ScheduleWrites.Kind.CREATE) }
            if (busy == ScheduleWrites.Kind.EDIT || busy == ScheduleWrites.Kind.SWITCH || busy == ScheduleWrites.Kind.DELETE) {
                WaitingRow(WAITING_TEXT)
            }
            if (!depLoading && departures.isNotEmpty()) {
                Text(
                    if (freeSlots > 0) "$freeSlots free place${if (freeSlots == 1) "" else "s"} on the car for departures. Tap a departure to change it."
                    else "Every place on the car holds a departure: delete one to add another.",
                    color = Brand.faint, fontSize = 11.5.sp,
                )
            }
            PrimaryButton("Add departure", Modifier.fillMaxWidth(), enabled = busy == null && awaiting == null) {
                when {
                    !writes.canCreate() -> snackbar(ScheduleWrites.CREATE_WAITS)
                    Departures.freeSlotId(departures) == null -> snackbar("No free place on the car: delete a departure first.")
                    else -> creating = true
                }
            }
            if (awaiting != null) Text(ScheduleWrites.CREATE_WAITS, color = Brand.faint, fontSize = 11.5.sp)
        }

        Text(
            "Schedules are stored on the car (via ms-charge-manage), and the car must be awake to take a change. " +
                "A change only counts once the car shows it. If it does not within a minute, the app says so: the " +
                "change may still arrive when the car wakes, or the cloud may drop it, so check the list later.",
            color = Brand.faint, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
        )
        Spacer(Modifier.size(8.dp))
    }

    val original = editing
    if (creating || original != null) {
        DepartureEditor(
            ctx = ctx,
            initial = original?.let(Departures::draftOf) ?: DepartureDraft(),
            minutesBefore = template.climateMinutes,
            isNew = original == null,
            onDismiss = { creating = false; editing = null },
            onSave = { draft ->
                creating = false; editing = null
                if (original == null) {
                    // A new plan only ever goes into a free slot, never onto a plan in use, and not while an
                    // earlier new plan still waits for the car (a second one would be queued too).
                    val slot = Departures.freeSlotId(departures)
                    when {
                        slot == null -> snackbar("No free place on the car: delete a departure first.")
                        !writes.canCreate() -> snackbar(ScheduleWrites.CREATE_WAITS)
                        else -> {
                            // 0.3.14: written over the free slot as the car reported it (see Departures.fromSlot);
                            // built from scratch only when the car reported no slots at all.
                            val plan = departures.firstOrNull { it.btId == slot && Departures.isEmptySlot(it) }
                                ?.let { Departures.fromSlot(it, draft, template) } ?: Departures.newPlan(draft, slot, template)
                            write(done = "Departure stored on the car ✓", notConfirmed = NOT_CONFIRMED_CREATE, failed = "Create failed") {
                                deps.schedule.createDeparture(plan)
                            }
                        }
                    }
                } else {
                    val updated = Departures.applied(original, draft, template)
                    write(done = "Departure saved on the car ✓", notConfirmed = NOT_CONFIRMED_CHANGE, failed = "Save failed") {
                        deps.schedule.updateDeparture(updated)
                    }
                }
            },
        )
    }
}

// ---------------------------------------------------------------- departure rows

@Composable
private fun DepartureRow(
    setting: BookingTravelSetting,
    enabled: Boolean,
    onEdit: () -> Unit,
    onToggle: (Boolean) -> Unit,
    onDelete: () -> Unit,
) {
    val d = Departures.draftOf(setting)
    val rawTime = setting.cycleTimes.firstOrNull { it.sts == 1 }?.time ?: setting.temporaryTime
    val time = if (rawTime.isBlank()) "—" else hhmm(rawTime)
    val extras = buildList {
        if (d.climate) add("climate ${Departures.temp(d.tempC)}°C")
        if (d.wheelHeat) add("wheel")
        if (d.seatHeat) add("seats")
        if (d.batteryMaintenance) add("battery")
    }
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Brand.surface2).clickable(enabled = enabled, onClick = onEdit).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(Icons.Filled.Schedule, null, tint = Brand.accent, modifier = Modifier.size(20.dp))
        Column(Modifier.weight(1f)) {
            Text(setting.name.ifBlank { "Departure" }, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Text(
                buildString {
                    append(time.take(5))
                    if (d.days.isNotEmpty()) {
                        append("  ·  ")
                        append(d.days.sorted().joinToString("") { DAY_LABELS[(it - 1).coerceIn(0, 6)] })
                    }
                },
                color = Brand.muted, fontSize = 11.5.sp,
            )
            if (extras.isNotEmpty()) Text(extras.joinToString(" · "), color = Brand.faint, fontSize = 11.sp)
        }
        Switch(checked = setting.sts == 1, onCheckedChange = onToggle, enabled = enabled, colors = brandSwitchColors(Brand.good))
        Icon(
            Icons.Filled.Delete, "Delete", tint = Brand.faint,
            modifier = Modifier.size(30.dp).clip(CircleShape).clickable(enabled = enabled, onClick = onDelete).padding(6.dp),
        )
    }
}

/**
 * New or edited departure, with what the official app offers: days Monday to Sunday, the departure
 * time, climate with a target temperature, steering wheel and front seat heating, and battery
 * temperature maintenance. Saving needs a name and at least one day.
 */
@Composable
private fun DepartureEditor(
    ctx: Context,
    initial: DepartureDraft,
    minutesBefore: Int,
    isNew: Boolean,
    onDismiss: () -> Unit,
    onSave: (DepartureDraft) -> Unit,
) {
    var name by remember { mutableStateOf(initial.name) }
    var time by remember { mutableStateOf(initial.time) }
    val days = remember { mutableStateListOf<Int>().apply { addAll(initial.days.sorted()) } }
    var climate by remember { mutableStateOf(initial.climate) }
    var temp by remember { mutableStateOf(initial.tempC) }
    var wheel by remember { mutableStateOf(initial.wheelHeat) }
    var seats by remember { mutableStateOf(initial.seatHeat) }
    var battery by remember { mutableStateOf(initial.batteryMaintenance) }
    val canSave = days.isNotEmpty() && name.isNotBlank()

    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isNew) "New departure" else "Edit departure") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                androidx.compose.material3.OutlinedTextField(
                    value = name, onValueChange = { name = it }, singleLine = true, label = { Text("Name") },
                )
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Departure time", fontSize = 13.sp, modifier = Modifier.weight(1f))
                    TimeChip(time.take(5)) { pickTime(ctx, time, withSeconds = true) { time = it } }
                }
                Text("Repeat on", color = Brand.muted, fontSize = 12.sp)
                // day is 1-based on the wire (1 = Monday .. 7 = Sunday); all seven share the row.
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    for (d in 1..7) {
                        val on = d in days
                        DayChip(DAY_LABELS[d - 1], on, Modifier.weight(1f)) { if (on) days.remove(d) else days.add(d) }
                    }
                }
                if (days.isEmpty()) Text("Pick at least one day to save.", color = Brand.energy, fontSize = 12.sp)

                Text("Cabin comfort", fontWeight = FontWeight.SemiBold, fontSize = 14.sp, modifier = Modifier.padding(top = 4.dp))
                Text("Switches on automatically $minutesBefore minutes before departure.", color = Brand.muted, fontSize = 12.sp)
                EditorSwitch("Climate", climate) { climate = it }
                if (climate) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("Target temperature", color = Brand.muted, fontSize = 13.sp, modifier = Modifier.weight(1f))
                        StepChip("−") { temp = (temp - 0.5).coerceAtLeast(Departures.MIN_TEMP) }
                        Text("${Departures.temp(temp)} °C", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                        StepChip("+") { temp = (temp + 0.5).coerceAtMost(Departures.MAX_TEMP) }
                    }
                }
                EditorSwitch("Steering wheel heating", wheel) { wheel = it }
                EditorSwitch("Seat heating (front seats)", seats) { seats = it }

                Text("Other", fontWeight = FontWeight.SemiBold, fontSize = 14.sp, modifier = Modifier.padding(top = 4.dp))
                EditorSwitch("Battery temperature maintenance", battery) { battery = it }
                Text(
                    "Brings the battery to temperature before departure for more range, which can cost extra " +
                        "energy. Recommended below 0 °C or above 40 °C.",
                    color = Brand.faint, fontSize = 11.5.sp,
                )
            }
        },
        confirmButton = {
            androidx.compose.material3.TextButton(enabled = canSave, onClick = {
                onSave(DepartureDraft(name.trim(), time, days.toSet(), climate, temp, wheel, seats, battery))
            }) { Text(if (isNew) "Create" else "Save") }
        },
        dismissButton = { androidx.compose.material3.TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun DayChip(label: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Box(
        modifier.height(36.dp).clip(RoundedCornerShape(10.dp))
            .background(if (selected) Brand.accent.copy(alpha = 0.18f) else Brand.surface2)
            .border(1.dp, if (selected) Brand.accent.copy(alpha = 0.5f) else Brand.line, RoundedCornerShape(10.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = if (selected) Brand.accent else Brand.muted, fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold, maxLines = 1, softWrap = false)
    }
}

@Composable
private fun EditorSwitch(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontSize = 13.sp, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange, colors = brandSwitchColors())
    }
}

@Composable
private fun StepChip(text: String, onClick: () -> Unit) {
    Box(
        Modifier.size(32.dp).clip(CircleShape).background(Brand.surface3).border(1.dp, Brand.line, CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Text(text, color = Brand.accent, fontSize = 16.sp, fontWeight = FontWeight.Bold) }
}

// ---------------------------------------------------------------- small shared bits

@Composable
private fun TimeChip(text: String, onClick: () -> Unit) {
    Box(
        Modifier.clip(RoundedCornerShape(10.dp)).background(Brand.surface3)
            .border(1.dp, Brand.line, RoundedCornerShape(10.dp))
            .clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 8.dp),
    ) { Text(text, color = Brand.accent, fontSize = 14.sp, fontWeight = FontWeight.SemiBold) }
}

@Composable
private fun LoadingRow() =
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.Center) {
        CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp, color = Brand.accent)
    }

/** A write waiting for the car's confirmation (up to a minute, 0.3.8). */
@Composable
private fun WaitingRow(text: String) =
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = Brand.accent)
        Text(text, color = Brand.muted, fontSize = 12.sp, modifier = Modifier.weight(1f))
    }

/**
 * A new departure the cloud took that the car has not shown yet (0.3.8): its name, time and days, and
 * that it is not confirmed. No controls: it is not on the car (yet).
 */
@Composable
private fun AwaitingRow(plan: BookingTravelSetting, confirming: Boolean) {
    val d = Departures.draftOf(plan)
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).border(1.dp, Brand.line, RoundedCornerShape(12.dp)).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (confirming) CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = Brand.accent)
        else Icon(Icons.Filled.Schedule, null, tint = Brand.faint, modifier = Modifier.size(20.dp))
        Column(Modifier.weight(1f)) {
            Text(plan.name.ifBlank { "Departure" }, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Brand.muted)
            Text(
                d.time.take(5) + if (d.days.isEmpty()) "" else "  ·  " +
                    d.days.sorted().joinToString("") { DAY_LABELS[(it - 1).coerceIn(0, 6)] },
                color = Brand.faint, fontSize = 11.5.sp,
            )
            Text(if (confirming) WAITING_TEXT else AWAITING_TEXT, color = Brand.energy, fontSize = 11.5.sp)
        }
    }
}

@Composable
private fun EmptyRow(text: String) =
    Text(text, color = Brand.faint, fontSize = 12.sp, modifier = Modifier.padding(vertical = 6.dp))

/** How often the list is looked at again while a new departure waits for the car. */
private const val RECHECK_MS = 30_000L

private const val WAITING_TEXT = "Waiting for the car to confirm… This can take up to a minute."
private const val AWAITING_TEXT =
    "Sent, not confirmed: the car has not shown it. It may still arrive when the car wakes, or the cloud may drop it."
private const val NOT_CONFIRMED_CREATE =
    "Not confirmed: the car did not show the new departure within a minute. It may still arrive when the car " +
        "wakes, or the cloud may drop it. Check the list later before adding it again."
private const val NOT_CONFIRMED_CHANGE =
    "Not confirmed: the car did not show the change within a minute. It may still arrive when the car wakes, " +
        "or the cloud may drop it. Check the list later."
private const val NOT_CONFIRMED_DELETE =
    "Not confirmed: the car still shows the departure. The delete may still arrive when the car wakes, or the " +
        "cloud may drop it. Check the list later."
private const val NOT_CONFIRMED_CHARGE =
    "Not confirmed: the car did not show the charging schedule within a minute. It may still arrive when the car " +
        "wakes, or the cloud may drop it. Check again later."

/** Day labels indexed by (stock `CycleTime.day` − 1). `day` is 1-based on the wire (1..7),
 *  day 1 = Monday … day 7 = Sunday, so DAY_LABELS[day-1] is the label. */
private val DAY_LABELS = arrayOf("Mo", "Tu", "We", "Th", "Fr", "Sa", "Su")

/** Extract "HH:mm" from either a CycleTime "HH:mm:ss" or a temporaryTime "yyyy:MM:dd:HH:mm:ss". */
private fun hhmm(raw: String): String {
    val p = raw.split(":")
    return when {
        p.size >= 6 -> "${p[3]}:${p[4]}"   // yyyy:MM:dd:HH:mm:ss
        p.size >= 2 -> "${p[0]}:${p[1]}"   // HH:mm[:ss]
        else -> raw.take(5)
    }
}

/** Fire the platform time picker; returns "HH:mm" (or "HH:mm:00" when [withSeconds]). */
private fun pickTime(ctx: Context, initial: String, withSeconds: Boolean, onPicked: (String) -> Unit) {
    val parts = initial.split(":")
    val h = parts.getOrNull(0)?.toIntOrNull()?.coerceIn(0, 23) ?: 8
    val m = parts.getOrNull(1)?.toIntOrNull()?.coerceIn(0, 59) ?: 0
    TimePickerDialog(
        ctx,
        { _, hh, mm -> onPicked("%02d:%02d".format(hh, mm) + if (withSeconds) ":00" else "") },
        h, m, true,
    ).show()
}
