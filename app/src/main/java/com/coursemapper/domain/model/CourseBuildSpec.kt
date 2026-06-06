package com.coursemapper.domain.model

/**
 * How a [ComposedCourse] is built from its base route, persisted so a reload
 * keeps the intent and not just the result.
 *
 * - [FixedLaps] exactly [lapCount] laps
 * - [TargetDistance] full laps plus a partial lap to hit the target
 * - [Manual] lap count and final partial as entered
 */
sealed interface CourseBuildSpec {

    /** Kind discriminator used for database storage. */
    val kind: String

    /** Exactly [lapCount] full laps, no partial. */
    data class FixedLaps(val lapCount: Int) : CourseBuildSpec {
        init { require(lapCount >= 1) { "lapCount must be ≥ 1" } }
        override val kind: String get() = KIND
        companion object { const val KIND = "fixed_laps" }
    }

    /**
     * Full laps until [totalMetres], then a partial lap for the remainder.
     * [lapCount] is the minimum.
     */
    data class TargetDistance(val totalMetres: Double, val lapCount: Int = 1) : CourseBuildSpec {
        init {
            require(lapCount >= 1)    { "lapCount must be ≥ 1" }
            require(totalMetres > 0.0) { "totalMetres must be > 0" }
        }
        override val kind: String get() = KIND
        companion object { const val KIND = "target_distance" }
    }

    /** [lapCount] laps plus [finalLapDistanceMetres], as entered. */
    data class Manual(
        val lapCount: Int,
        val finalLapDistanceMetres: Double = 0.0
    ) : CourseBuildSpec {
        init { require(lapCount >= 1) { "lapCount must be ≥ 1" } }
        override val kind: String get() = KIND
        companion object { const val KIND = "manual" }
    }

    companion object {
        /** From the stored columns, unknown [kind] gives [Manual]. */
        fun fromDb(
            kind: String,
            lapCount: Int,
            targetMetres: Double
        ): CourseBuildSpec = when (kind) {
            FixedLaps.KIND      -> FixedLaps(lapCount)
            TargetDistance.KIND -> TargetDistance(totalMetres = targetMetres, lapCount = lapCount)
            Manual.KIND         -> Manual(lapCount, targetMetres)
            else                -> Manual(lapCount.coerceAtLeast(1), targetMetres.coerceAtLeast(0.0))
        }
    }
}
