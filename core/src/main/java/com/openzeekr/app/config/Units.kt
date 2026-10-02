package com.openzeekr.app.config

/**
 * Display-unit conversion + formatting (UI only — the wire/API always uses the car's
 * native units). Inputs are the raw vehicle-status units: tyre pressure in kPa,
 * temperature in °C, distance/range in km. The unit strings come from [SecretsConfig]
 * (`pressureUnit` / `tempUnit` / `distanceUnit`).
 */
object Units {

    /** Tyre pressure from kPa. */
    fun pressure(kpa: Double, unit: String): String = when (unit.lowercase()) {
        "psi" -> "%.0f psi".format(kpa * 0.1450377)
        "kpa" -> "%.0f kPa".format(kpa)
        else -> "%.2f bar".format(kpa / 100.0)   // bar
    }

    /** Numeric-only pressure value (for gauges / comparisons), in the chosen unit. */
    fun pressureValue(kpa: Double, unit: String): Double = when (unit.lowercase()) {
        "psi" -> kpa * 0.1450377
        "kpa" -> kpa
        else -> kpa / 100.0
    }

    fun pressureSuffix(unit: String): String = when (unit.lowercase()) {
        "psi" -> "psi"; "kpa" -> "kPa"; else -> "bar"
    }

    /** Temperature from °C. */
    fun temp(celsius: Double, unit: String): String = when (unit.lowercase()) {
        "f" -> "%.0f°F".format(celsius * 9.0 / 5.0 + 32.0)
        else -> "%.0f°C".format(celsius)
    }

    /** Distance / range from km. */
    fun distance(km: Double, unit: String): String = when (unit.lowercase()) {
        "mi" -> "%.0f mi".format(km * 0.6213712)
        else -> "%.0f km".format(km)
    }

    fun distanceSuffix(unit: String): String = if (unit.lowercase() == "mi") "mi" else "km"
    fun distanceValue(km: Double, unit: String): Double = if (unit.lowercase() == "mi") km * 0.6213712 else km
    /** Car-native km/h to the user's unit (mph when distance is in miles). */
    fun speedValue(kmh: Int, unit: String): Int =
        if (unit.lowercase() == "mi") Math.round(kmh * 0.6213712f) else kmh
    fun speedUnitLabel(unit: String): String = if (unit.lowercase() == "mi") "mph" else "km/h"
}
