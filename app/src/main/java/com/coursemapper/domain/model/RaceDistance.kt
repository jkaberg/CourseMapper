package com.coursemapper.domain.model

/**
 * Standard race distances, used as one-tap targets. Always in metres, a half
 * marathon is 21.0975 km regardless of display unit. Each one sets a
 * [CourseBuildSpec.TargetDistance].
 */
enum class RaceDistance(val label: String, val metres: Double) {
    FIVE_K("5K", 5_000.0),
    TEN_K("10K", 10_000.0),
    HALF_MARATHON("Half", 21_097.5),
    MARATHON("Marathon", 42_195.0);

    companion object {
        /** Largest difference still counted as "this chip is selected". */
        private const val MATCH_TOLERANCE_METRES = 1.0

        /** The preset matching [metres], or null when the target is custom. */
        fun matching(metres: Double): RaceDistance? =
            entries.firstOrNull { kotlin.math.abs(it.metres - metres) <= MATCH_TOLERANCE_METRES }

        /** As above, for a target stored in centimetres. */
        fun matchingCentimetres(cm: Long): RaceDistance? =
            if (cm <= 0L) null else matching(cm.targetCentimetresToMetres())
    }
}
