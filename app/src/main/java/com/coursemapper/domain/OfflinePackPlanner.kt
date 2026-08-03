package com.coursemapper.domain

import com.coursemapper.domain.model.GeoBounds
import com.coursemapper.domain.model.RoutePoint
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min

/**
 * Area a pack should cover. Failure is a value so it ends up on the pack as
 * [com.coursemapper.domain.model.OfflineFailureReason.NO_GEOMETRY] instead of
 * nothing happening.
 */
@Singleton
class OfflinePackPlanner @Inject constructor() {

    sealed interface PlanResult {
        data class Planned(val bounds: GeoBounds) : PlanResult

        /** No course in the request had geometry to plan from. */
        data object NoGeometry : PlanResult
    }

    /**
     * Bounding box around [points] plus [bufferMetres]. Only smoothed points,
     * so outliers can't drag the area across the county.
     */
    fun planForPoints(
        points: List<RoutePoint>,
        bufferMetres: Double = DEFAULT_BUFFER_METRES
    ): PlanResult {
        val usable = points.filter { it.isSmoothed }
        if (usable.isEmpty()) return PlanResult.NoGeometry

        var minLat = usable.first().lat
        var maxLat = usable.first().lat
        var minLon = usable.first().lon
        var maxLon = usable.first().lon
        for (p in usable) {
            minLat = min(minLat, p.lat)
            maxLat = max(maxLat, p.lat)
            minLon = min(minLon, p.lon)
            maxLon = max(maxLon, p.lon)
        }

        return PlanResult.Planned(
            buffer(
                GeoBounds(south = minLat, north = maxLat, west = minLon, east = maxLon),
                bufferMetres
            )
        )
    }

    /**
     * One area for all [pointsPerCourse], so an event's shared ground is
     * downloaded once. Courses without geometry are skipped.
     */
    fun planForCourses(
        pointsPerCourse: List<List<RoutePoint>>,
        bufferMetres: Double = DEFAULT_BUFFER_METRES
    ): PlanResult {
        val boxes = pointsPerCourse.mapNotNull { points ->
            (planForPoints(points, bufferMetres) as? PlanResult.Planned)?.bounds
        }
        val union = GeoBounds.unionOf(boxes) ?: return PlanResult.NoGeometry
        return PlanResult.Planned(union)
    }

    /** Expand [bounds] by [metres] on all four sides. */
    private fun buffer(bounds: GeoBounds, metres: Double): GeoBounds {
        val centreLat = (bounds.south + bounds.north) / 2.0
        val latDelta = metres / METRES_PER_DEGREE_LAT
        // Longitude degrees shrink toward the poles; at 60° N a degree is half
        // the ground distance it covers at the equator, so a fixed degree
        // buffer would be half the intended width in Norway.
        val lonScale = cos(Math.toRadians(centreLat)).coerceAtLeast(MIN_COS_LAT)
        val lonDelta = metres / (METRES_PER_DEGREE_LAT * lonScale)

        return GeoBounds(
            south = (bounds.south - latDelta).coerceIn(-85.0, 85.0),
            north = (bounds.north + latDelta).coerceIn(-85.0, 85.0),
            west = (bounds.west - lonDelta).coerceIn(-180.0, 180.0),
            east = (bounds.east + lonDelta).coerceIn(-180.0, 180.0)
        )
    }

    companion object {
        /** Side buffer added to the tight bounding box, in metres. */
        const val DEFAULT_BUFFER_METRES = 2_000.0

        const val METRES_PER_DEGREE_LAT = 111_320.0

        /** Floor on cos(lat) so a polar course cannot divide by zero. */
        private const val MIN_COS_LAT = 0.01
    }
}
