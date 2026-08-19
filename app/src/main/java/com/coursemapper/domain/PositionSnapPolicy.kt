package com.coursemapper.domain

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Where to draw the rider. Pulls the puck onto the course line while driving it,
 * like car navigation does.
 *
 * Drawing only - [DwellEngine], distances and routing keep the raw fix, otherwise
 * a rider parked 8 m off the line would shift a good part of the dwell radius.
 *
 * Gated four ways:
 * - radius, with [RELEASE_MULTIPLIER] hysteresis so it doesn't strobe
 * - moving faster than [MIN_SPEED_MPS], a stopped rider is usually beside the course
 * - heading roughly along the course, rejects crossing roads
 * - never inside the active stop's dwell radius, the map and banner would disagree
 *
 * Caller keeps [State].
 */
@Singleton
class PositionSnapPolicy @Inject constructor() {

    companion object {
        /** Default snap radius in metres.  0 disables snapping entirely. */
        const val DEFAULT_RADIUS_M = 10.0

        /** An engaged snap holds until this multiple of the radius. */
        const val RELEASE_MULTIPLIER = 1.5

        /** Below this speed the rider is working, not driving; nothing snaps. */
        const val MIN_SPEED_MPS = 1.5f

        /** Generous since it's an ATV on rough ground, still rejects angled crossings. */
        const val HEADING_TOLERANCE_DEG = 45.0
    }

    /** Course the puck is held to, it gets the wider release radius so shared roads don't flip. */
    data class State(
        val snappedCourseId: Long? = null,
        val snappedCumulativeMetres: Double = -1.0
    ) {
        val isSnapped: Boolean get() = snappedCourseId != null

        companion object { val IDLE = State() }
    }

    /** Where to draw the rider, and what that position means. */
    data class Snap(
        val lat: Double,
        val lon: Double,
        /** The course the puck was pulled onto. */
        val courseId: Long,
        /** How far the raw fix was from the line, in metres. */
        val offsetMetres: Double,
        /** Distance along that course to the snapped point. */
        val cumulativeMetres: Double
    )

    /**
     * @param travelBearingDeg null skips the heading check, a rider who just
     *   started moving has no bearing yet
     * @param speedMetresPerSecond null counts as stopped
     * @param dwellRadiusMetres no snapping inside this around the active stop
     * @param radiusMetres 0 or less disables snapping
     * @return the snap to draw (null draws the raw fix) and the state to keep
     */
    fun snap(
        courses: List<CorridorAnalyzer.IndexedCourse>,
        lat: Double,
        lon: Double,
        travelBearingDeg: Float?,
        speedMetresPerSecond: Float?,
        distanceToActiveStopMetres: Double?,
        dwellRadiusMetres: Double,
        radiusMetres: Double = DEFAULT_RADIUS_M,
        state: State = State.IDLE
    ): Pair<Snap?, State> {
        if (radiusMetres <= 0.0 || courses.isEmpty()) return null to State.IDLE
        if ((speedMetresPerSecond ?: 0f) < MIN_SPEED_MPS) return null to State.IDLE
        if (distanceToActiveStopMetres != null && distanceToActiveStopMetres <= dwellRadiusMetres) {
            return null to State.IDLE
        }

        val releaseRadius = radiusMetres * RELEASE_MULTIPLIER

        // The course already held gets first refusal, at the wider radius.
        val incumbent = state.snappedCourseId
            ?.let { id -> courses.firstOrNull { it.courseId == id } }
            ?.let { course -> match(course, lat, lon, travelBearingDeg, releaseRadius) }

        val chosen = incumbent ?: courses
            .mapNotNull { match(it, lat, lon, travelBearingDeg, radiusMetres) }
            .minByOrNull { it.offsetMetres }

        if (chosen == null) return null to State.IDLE
        return chosen to State(chosen.courseId, chosen.cumulativeMetres)
    }

    /** Nearest point on one course, if it passes the radius and heading gates. */
    private fun match(
        course: CorridorAnalyzer.IndexedCourse,
        lat: Double,
        lon: Double,
        travelBearingDeg: Float?,
        radiusMetres: Double
    ): Snap? {
        val hit = course.index.nearest(lat, lon, radiusMetres) ?: return null

        if (travelBearingDeg != null) {
            val delta = CumulativeDistanceCalculator.bearingDeltaDegrees(
                travelBearingDeg.toDouble(),
                course.index.bearingAt(hit.cumulativeMetres)
            )
            // Driving the course backwards is normal while placing signs, so
            // the opposite direction is as acceptable as the same one.
            val alongTheCourse =
                delta <= HEADING_TOLERANCE_DEG || delta >= 180.0 - HEADING_TOLERANCE_DEG
            if (!alongTheCourse) return null
        }

        return Snap(
            lat = hit.lat,
            lon = hit.lon,
            courseId = course.courseId,
            offsetMetres = hit.offsetMetres,
            cumulativeMetres = hit.cumulativeMetres
        )
    }
}
