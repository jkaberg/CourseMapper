package com.coursemapper.ui.format

import com.coursemapper.data.prefs.UserPreferencesRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Formats distances in the user's unit.
 *
 * - [format] fixed decimals, "42.20 km"
 * - [formatCompact] no trailing zeros, "10 km", "1.5 km"
 * - [label] just "km" or "mi"
 */
@Singleton
class DistanceFormatter @Inject constructor(
    prefs: UserPreferencesRepository
) {
    /** Emits "km" or "mi" whenever the preference changes. */
    val unitFlow: Flow<String> = prefs.distanceUnit

    /** Emits [DistanceUnit.KM] or [DistanceUnit.MI]. */
    val unitEnumFlow: Flow<DistanceUnit> = prefs.distanceUnit.map { DistanceUnit.from(it) }

    enum class DistanceUnit(val symbol: String, val metresPerUnit: Double) {
        KM("km", 1_000.0),
        MI("mi", 1_609.344);

        companion object {
            fun from(pref: String): DistanceUnit = if (pref == "mi") MI else KM
        }
    }

    /** `format(42_195.0, DistanceUnit.KM, 2)` gives `"42.20 km"`. */
    fun format(metres: Double, unit: DistanceUnit, decimals: Int = 2): String {
        val value = metres / unit.metresPerUnit
        return "%.${decimals}f ${unit.symbol}".format(value)
    }

    /** Drops trailing zeros: "10 km", "1.5 km", "42.19 km". */
    fun formatCompact(metres: Double, unit: DistanceUnit): String {
        val value = metres / unit.metresPerUnit
        return when {
            value == kotlin.math.floor(value) -> "${value.toLong()} ${unit.symbol}"
            (value * 10) == kotlin.math.floor(value * 10) -> "%.1f ${unit.symbol}".format(value)
            else -> "%.2f ${unit.symbol}".format(value)
        }
    }

    /**
     * Return the unit symbol for use in field labels, e.g. "Target distance (km)".
     */
    fun label(unit: DistanceUnit): String = unit.symbol

    /** User input in [unit] to metres, null if it doesn't parse. */
    fun parseToMetres(input: String, unit: DistanceUnit): Double? {
        val value = input.toDoubleOrNull() ?: return null
        return value * unit.metresPerUnit
    }

    /** Metres as a number in [unit], without suffix. */
    fun toDisplayValue(metres: Double, unit: DistanceUnit): Double =
        metres / unit.metresPerUnit
}
