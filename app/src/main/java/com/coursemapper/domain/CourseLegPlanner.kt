package com.coursemapper.domain

import kotlin.math.abs
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Guidance to the next stop along the recorded course, for "Follow main route"
 * runs.
 *
 * Stops sit on a course that was already driven, so road routing between them
 * is usually worse - one 581 m hop came back as 1829 m by car. This is local
 * geometry on indexes navigation already has, so no routing requests.
 *
 * Returns null (fall back to road routing) when the rider isn't on a course, no
 * course carries both rider and stop, or the path would be a lap wrap.
 */
@Singleton
class CourseLegPlanner @Inject constructor(
    private val distanceCalc: CumulativeDistanceCalculator
) {

    companion object {
        /** Close to [RerouteDecisionPolicy.OFF_ROUTE_METRES], past this the rider needs a route back. */
        const val RIDER_TOLERANCE_METRES = 40.0

        /** Tighter than the rider's, stops are snapped to the course when the run is built. */
        const val STOP_TOLERANCE_METRES = 20.0

        /**
         * Along-course over straight line above this is a lap wrap. Real run was
         * 1.02x at median and 1.32x at p90.
         */
        const val MAX_DETOUR_FACTOR = 4.0

        /** Below this the detour factor means nothing, bends alone can do 4x. */
        const val DETOUR_FLOOR_METRES = 150.0
    }

    /** A course to plan over, and its prepared geometry. */
    data class Course(val courseId: Long, val index: PolylineIndex)

    /** The path along the course from the rider to the stop. */
    data class CourseLeg(
        /** (lat, lon) from the rider's own position onto and along the course. */
        val points: List<Pair<Double, Double>>,
        /** Distance left to the stop, measured along [points]. */
        val remainingMetres: Double,
        /** The course this was taken from. */
        val courseId: Long,
        /** How far the rider is off that course. */
        val riderOffsetMetres: Double
    )

    /**
     * Plan the way from ([riderLat],[riderLon]) to ([stopLat],[stopLon]) along
     * whichever of [courses] joins them most directly, or null when none does.
     */
    fun plan(
        courses: List<Course>,
        riderLat: Double,
        riderLon: Double,
        stopLat: Double,
        stopLon: Double
    ): CourseLeg? {
        if (courses.isEmpty()) return null

        val straightMetres = distanceCalc.haversineMetres(riderLat, riderLon, stopLat, stopLon)
        var best: CourseLeg? = null
        // rank on the course distance between the feet, remainingMetres also
        // includes the hop onto the course which differs per course
        var bestAlongMetres = Double.MAX_VALUE

        for (course in courses) {
            val riderPasses = course.index.projectionsWithin(
                riderLat, riderLon, RIDER_TOLERANCE_METRES
            )
            if (riderPasses.isEmpty()) continue
            val stopPasses = course.index.projectionsWithin(
                stopLat, stopLon, STOP_TOLERANCE_METRES
            )
            if (stopPasses.isEmpty()) continue

            // A lap course passes both points more than once.  The pairing that
            // joins them over the least course is the one the rider is on; any
            // other pairing is a lap they are not riding right now.
            for (from in riderPasses) {
                for (to in stopPasses) {
                    val alongMetres = abs(to.cumulativeMetres - from.cumulativeMetres)
                    if (alongMetres >= bestAlongMetres) continue
                    if (isWrapAround(alongMetres, straightMetres)) continue

                    val path = course.index.slice(from.cumulativeMetres, to.cumulativeMetres)
                    if (path.size < 2) continue

                    // The line starts where the rider actually is, not at their
                    // foot on the course: a line that begins 30 m sideways of
                    // the puck reads as the wrong line.
                    val points = listOf(riderLat to riderLon) + path
                    bestAlongMetres = alongMetres
                    best = CourseLeg(
                        points           = points,
                        remainingMetres  = pathLengthMetres(points),
                        courseId         = course.courseId,
                        riderOffsetMetres = from.offsetMetres
                    )
                }
            }
        }
        return best
    }

    /** Whether [alongMetres] for [straightMetres] of ground is a lap wrap. */
    private fun isWrapAround(alongMetres: Double, straightMetres: Double): Boolean =
        alongMetres > DETOUR_FLOOR_METRES &&
            alongMetres > straightMetres * MAX_DETOUR_FACTOR

    private fun pathLengthMetres(points: List<Pair<Double, Double>>): Double {
        var total = 0.0
        for (i in 1 until points.size) {
            total += distanceCalc.haversineMetres(
                points[i - 1].first, points[i - 1].second,
                points[i].first, points[i].second
            )
        }
        return total
    }
}
