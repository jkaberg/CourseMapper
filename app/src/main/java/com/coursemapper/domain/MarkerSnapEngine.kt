package com.coursemapper.domain

import com.coursemapper.domain.model.RoutePoint
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

/**
 * Projects a position onto the nearest point of a polyline. Geometry goes
 * through [CumulativeDistanceCalculator] so it agrees with every other distance.
 */
@Singleton
class MarkerSnapEngine @Inject constructor(
    private val distanceCalculator: CumulativeDistanceCalculator
) {

    companion object {
        /**
         * Added to segments outside the progress window, so overlapping laps and
         * out-and-back legs snap to the leg matching progress.
         */
        const val OFF_WINDOW_PENALTY_M = 25.0
    }

    data class SnapResult(
        /** Snapped latitude on the polyline. */
        val lat: Double,
        /** Snapped longitude on the polyline. */
        val lon: Double,
        /** Cumulative distance along the route to the snapped point, in metres. */
        val cumulativeDistanceMetres: Double,
        /** Straight-line distance from the input position to the snapped point. */
        val offsetMetres: Double
    )

    /**
     * Snap [lat]/[lon] onto [routePoints].
     *
     * @param nearCumulative expected distance from prior progress, segments more
     *   than [searchWindowMetres] away are penalised (not excluded)
     */
    fun snap(
        routePoints: List<RoutePoint>,
        lat: Double,
        lon: Double,
        nearCumulative: Double = -1.0,
        searchWindowMetres: Double = 300.0
    ): SnapResult? {
        val pts = routePoints.filter { it.isSmoothed }.ifEmpty { routePoints }
        if (pts.size < 2) return null

        val cumDists = distanceCalculator.cumulativeDistances(pts, smoothedOnly = false)

        var bestDist = Double.MAX_VALUE
        var bestLat  = lat
        var bestLon  = lon
        var bestCum  = 0.0

        for (i in 0 until pts.lastIndex) {
            val aLat = pts[i].lat;     val aLon = pts[i].lon
            val bLat = pts[i + 1].lat; val bLon = pts[i + 1].lon
            val aCum = cumDists[i];    val bCum = cumDists[i + 1]

            // Segment cumulative midpoint for bias check.
            val segMid = (aCum + bCum) / 2.0

            // Project (lat, lon) onto segment [a, b].  The projection is metric
            // (east-north frame), not raw degrees - see
            // [CumulativeDistanceCalculator.closestPointOnSegment].
            val foot = distanceCalculator.closestPointOnSegment(aLat, aLon, bLat, bLon, lat, lon)
            val fLat = foot.lat
            val fLon = foot.lon
            val footCum = aCum + foot.t * (bCum - aCum)
            val distToFoot = distanceCalculator.haversineMetres(lat, lon, fLat, fLon)

            // additive on purpose, a multiplier does nothing when the foot
            // distance is ~0 on every overlapping segment
            val bias = if (nearCumulative >= 0.0 && abs(segMid - nearCumulative) > searchWindowMetres)
                distToFoot * 3.0 + OFF_WINDOW_PENALTY_M
            else
                distToFoot

            if (bias < bestDist) {
                bestDist = bias
                bestLat  = fLat
                bestLon  = fLon
                bestCum  = footCum
            }
        }

        val actualOffset = distanceCalculator.haversineMetres(lat, lon, bestLat, bestLon)
        return SnapResult(bestLat, bestLon, bestCum, actualOffset)
    }
}
