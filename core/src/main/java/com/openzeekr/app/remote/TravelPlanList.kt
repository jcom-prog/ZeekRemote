package com.openzeekr.app.remote

import com.openzeekr.app.net.model.BookingTravelSetting
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * 0.3.18: the car's departure list, read plan by plan. Field 18/10: a plan made in the official app did not show here,
 * and the diagnosis could not tell whether ZeekRemote had read the list at all - a list that failed to read left no
 * trace, and a single plan it could not read lost the whole list. Now each plan is read on its own. A plan with a
 * missing or empty field is read with that field empty, and one that still cannot be read becomes a placeholder; both
 * are [Read.readOnly]: shown, never written, and never taken for a free slot.
 */
object TravelPlanList {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    data class Read(
        val plans: List<BookingTravelSetting>,
        /** Slots of plans ZeekRemote could only read in part (or not at all): shown, never written. */
        val readOnly: Set<Int> = emptySet(),
        /** How many plans needed empty fields to be read. */
        val filled: Int = 0,
        /** How many plans could not be read at all (placeholders). */
        val unreadable: Int = 0,
    )

    fun read(data: JsonArray?): Read {
        val plans = mutableListOf<BookingTravelSetting>()
        val readOnly = mutableSetOf<Int>()
        var filled = 0; var unreadable = 0
        (data ?: JsonArray(emptyList())).forEachIndexed { i, e ->
            val strict = decode(e)
            if (strict != null) { plans += strict; return@forEachIndexed }
            // An entry without a slot number gets one that no real slot has, so it can never be written.
            val id = (e as? JsonObject)?.get("btId")?.let { it as? JsonPrimitive }?.intOrNull ?: -(i + 1)
            val partial = (e as? JsonObject)?.let { decode(withDefaults(it, PLAN, id)) }
            if (partial != null) { plans += partial; filled++ } else { plans += placeholder(e, id); unreadable++ }
            readOnly += id
        }
        return Read(plans, readOnly, filled, unreadable)
    }

    private fun decode(e: JsonElement): BookingTravelSetting? =
        runCatching { json.decodeFromJsonElement(BookingTravelSetting.serializer(), e) }.getOrNull()

    /** A plan that cannot be read at all, with what can be told from it: it holds its slot and shows as "Departure". */
    private fun placeholder(e: JsonElement, id: Int): BookingTravelSetting {
        val o = e as? JsonObject
        fun text(k: String) = runCatching { o?.get(k)?.jsonPrimitive?.content }.getOrNull()
        val name = text("name")?.takeIf { it.isNotBlank() && !it.equals(Departures.EMPTY_SLOT_NAME, ignoreCase = true) } ?: "Departure"
        val sts = runCatching { o?.get("sts")?.jsonPrimitive?.intOrNull }.getOrNull() ?: 1
        return BookingTravelSetting(btId = id, name = name, sts = sts, displaySts = 1, bookingType = Departures.BOOKING_RECURRING,
            temporaryTime = "", cycleTimes = emptyList(), preHeatSts = 0)
    }

    // The fields the plan needs, with the value an absent or null one is read as. 0.3.20: a plan whose display state is
    // unknown is read as shown - off and hidden is a plan the official app deleted (Departures.isHidden).
    private val SEAT = mapOf("duration" to 0, "level" to 0, "location" to 0, "sts" to 0)
    private val PLAN = mapOf("name" to "", "sts" to 0, "displaySts" to 1, "bookingType" to Departures.BOOKING_RECURRING,
        "temporaryTime" to "", "cycleTimes" to emptyList<Any>(), "preHeatSts" to 0)
    private val NESTED = mapOf(
        "cycleTimes" to mapOf("day" to 0, "sts" to 0, "time" to ""),
        "csSetting" to mapOf("duration" to 0, "sts" to 0, "temp" to ""),
        "ventiSettings" to SEAT, "heatSettings" to SEAT,
        "whlSetting" to mapOf("duration" to 0, "sts" to 0),
        "fragSetting" to mapOf("channel" to 0, "duration" to 0, "level" to 0, "sts" to 0),
    )

    private fun element(v: Any): JsonElement = when (v) {
        is Int -> JsonPrimitive(v)
        is String -> JsonPrimitive(v)
        else -> JsonArray(emptyList())
    }

    private fun withDefaults(o: JsonObject, defaults: Map<String, Any>, id: Int? = null): JsonObject {
        val m = o.toMutableMap()
        if (id != null) m["btId"] = JsonPrimitive(id)
        for ((k, v) in defaults) if (m[k] == null || m[k] is JsonNull) m[k] = element(v)
        for ((k, d) in NESTED) {
            when (val x = m[k]) {
                is JsonObject -> m[k] = withDefaults(x, d)
                is JsonArray -> m[k] = JsonArray(x.mapNotNull { (it as? JsonObject)?.let { y -> withDefaults(y, d) } })
                else -> {}
            }
        }
        return JsonObject(m)
    }
}
