package com.coursemapper.domain

import com.coursemapper.domain.model.RoutePoint
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.*

/**
 * Distance helpers (haversine, WGS-84 mean radius). All distance math in the
 * app goes through here so placement, navigation and export always agree.
 */
@Singleton
class CumulativeDistanceCalculator @Inject constructor() {

    /**
     * Haversine great-circle distance between two lat/lon points, in metres.
     */
    fun haversineMetres(
        lat1: Double, lon1: Double,
        lat2: Double, lon2: Double
    ): Double {
        val r = EARTH_RADIUS_M
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).pow(2) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
                sin(dLon / 2).pow(2)
        return r * 2 * asin(sqrt(a))
    }

    /** Initial bearing in degrees clockwise from north. */
    fun bearingDegrees(
        fromLat: Double, fromLon: Double,
        toLat: Double, toLon: Double
    ): Double {
        val lat1 = Math.toRadians(fromLat)
        val lat2 = Math.toRadians(toLat)
        val dLon = Math.toRadians(toLon - fromLon)
        val y = sin(dLon) * cos(lat2)
        val x = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(dLon)
        return (Math.toDegrees(atan2(y, x)) + 360.0) % 360.0
    }

    /** Path length in metres, only smoothed points unless [smoothedOnly] is false. */
    fun pathDistanceMetres(points: List<RoutePoint>, smoothedOnly: Boolean = true): Double {
        val pts = if (smoothedOnly) points.filter { it.isSmoothed } else points
        if (pts.size < 2) return 0.0
        var total = 0.0
        for (i in 1 until pts.size) {
            total += haversineMetres(pts[i - 1].lat, pts[i - 1].lon, pts[i].lat, pts[i].lon)
        }
        return total
    }

    /** Cumulative distance per point, first is 0.0 and last is [pathDistanceMetres]. */
    fun cumulativeDistances(points: List<RoutePoint>, smoothedOnly: Boolean = true): List<Double> {
        val pts = if (smoothedOnly) points.filter { it.isSmoothed } else points
        if (pts.isEmpty()) return emptyList()
        val result = ArrayList<Double>(pts.size)
        result.add(0.0)
        for (i in 1 until pts.size) {
            result.add(
                result[i - 1] + haversineMetres(
                    pts[i - 1].lat, pts[i - 1].lon,
                    pts[i].lat, pts[i].lon
                )
            )
        }
        return result
    }

    /** Position at [targetMetres] along the polyline, null if empty or past the end. */
    fun interpolateAt(
        points: List<RoutePoint>,
        targetMetres: Double,
        smoothedOnly: Boolean = true
    ): Pair<Double, Double>? {
        val pts = if (smoothedOnly) points.filter { it.isSmoothed } else points
        if (pts.isEmpty()) return null
        val cumDist = cumulativeDistances(pts, smoothedOnly = false)
        val total = cumDist.last()
        if (targetMetres >= total) return Pair(pts.last().lat, pts.last().lon)
        if (targetMetres <= 0.0) return Pair(pts.first().lat, pts.first().lon)

        // Binary search for the segment containing targetMetres.
        var lo = 0
        var hi = cumDist.lastIndex
        while (lo < hi - 1) {
            val mid = (lo + hi) / 2
            if (cumDist[mid] <= targetMetres) lo = mid else hi = mid
        }
        val segLen = cumDist[hi] - cumDist[lo]
        val fraction = if (segLen > 0.0) (targetMetres - cumDist[lo]) / segLen else 0.0
        val lat = pts[lo].lat + fraction * (pts[hi].lat - pts[lo].lat)
        val lon = pts[lo].lon + fraction * (pts[hi].lon - pts[lo].lon)
        return Pair(lat, lon)
    }

    /** Project [lat]/[lon] onto the polyline, returns distance along and the projected point. */
    fun projectOntoPolyline(
        points: List<RoutePoint>,
        lat: Double,
        lon: Double,
        smoothedOnly: Boolean = true
    ): ProjectionResult? {
        val pts = if (smoothedOnly) points.filter { it.isSmoothed } else points
        if (pts.isEmpty()) return null
        val cumDist = cumulativeDistances(pts, smoothedOnly = false)

        var bestDist = Double.MAX_VALUE
        var bestCum  = 0.0
        var bestLat  = pts.first().lat
        var bestLon  = pts.first().lon

        for (i in 0 until pts.lastIndex) {
            val foot = closestPointOnSegment(
                pts[i].lat, pts[i].lon,
                pts[i + 1].lat, pts[i + 1].lon,
                lat, lon
            )
            val d = haversineMetres(lat, lon, foot.lat, foot.lon)
            if (d < bestDist) {
                bestDist = d
                bestCum  = cumDist[i] + foot.t * (cumDist[i + 1] - cumDist[i])
                bestLat  = foot.lat
                bestLon  = foot.lon
            }
        }
        return ProjectionResult(bestCum, bestLat, bestLon, bestDist)
    }

    data class ProjectionResult(
        val cumulativeMetres: Double,
        val lat: Double,
        val lon: Double,
        /** Distance from the input point to the projection on the polyline, in metres. */
        val distanceToPolylineMetres: Double
    )

    /**
     * Closest point on AB to P as (lat, lon, t), t in [0, 1].
     *
     * Done in a local metric frame, in degrees the foot slides along the segment
     * (2.24:1 skew at 63.4°N). Use this, don't re-derive it.
     */
    fun closestPointOnSegment(
        aLat: Double, aLon: Double,
        bLat: Double, bLon: Double,
        pLat: Double, pLon: Double
    ): SegmentProjection {
        val lonScale = cos(Math.toRadians(aLat))
        val dEast  = (bLon - aLon) * lonScale
        val dNorth = bLat - aLat
        val lenSq  = dEast * dEast + dNorth * dNorth
        val t = if (lenSq == 0.0) 0.0 else {
            (((pLon - aLon) * lonScale) * dEast + (pLat - aLat) * dNorth) / lenSq
        }.coerceIn(0.0, 1.0)
        // t is a fraction along the segment, so it applies to the raw degree
        // deltas unchanged - only the *solve* for t needed the metric frame.
        return SegmentProjection(aLat + t * (bLat - aLat), aLon + t * (bLon - aLon), t)
    }

    /** Perpendicular foot on a segment: its position and the fraction along. */
    data class SegmentProjection(
        val lat: Double,
        val lon: Double,
        /** Fraction along the segment, clamped to [0, 1]. */
        val t: Double
    )

    companion object {
        const val EARTH_RADIUS_M = 6_371_008.8

        /** Smallest difference between two bearings, 0-180. */
        fun bearingDeltaDegrees(a: Double, b: Double): Double =
            abs((a - b + 540.0) % 360.0 - 180.0)
    }

    /**
     * The part of [points] between [fromMetres] and [toMetres], ends interpolated.
     * Full range returns the input, a degenerate range returns empty.
     */
    fun slicePolyline(
        points: List<RoutePoint>,
        fromMetres: Double,
        toMetres: Double,
        smoothedOnly: Boolean = false
    ): List<RoutePoint> {
        val pts = if (smoothedOnly) points.filter { it.isSmoothed } else points
        if (pts.size < 2) return pts
        val cumDist = cumulativeDistances(pts, smoothedOnly = false)
        val totalM  = cumDist.last()

        val clampedFrom = fromMetres.coerceIn(0.0, totalM)
        val clampedTo   = toMetres.coerceIn(0.0, totalM)
        if (clampedFrom >= clampedTo) return emptyList()
        if (clampedFrom <= 0.0 && clampedTo >= totalM) return pts

        val result = mutableListOf<RoutePoint>()

        // Interpolated start point
        val startPt = interpolateAtInternal(pts, cumDist, clampedFrom)
        if (startPt != null) result.add(startPt)

        // All intermediate points strictly inside the range
        for (i in pts.indices) {
            val d = cumDist[i]
            if (d > clampedFrom && d < clampedTo) result.add(pts[i])
        }

        // Interpolated end point (avoid duplicate if it coincides with a vertex)
        val endPt = interpolateAtInternal(pts, cumDist, clampedTo)
        if (endPt != null && (result.isEmpty() ||
                haversineMetres(result.last().lat, result.last().lon, endPt.lat, endPt.lon) > 0.1)) {
            result.add(endPt)
        }
        return result
    }

    private fun interpolateAtInternal(
        pts: List<RoutePoint>,
        cumDist: List<Double>,
        targetM: Double
    ): RoutePoint? {
        if (pts.isEmpty()) return null
        if (targetM <= cumDist.first()) return pts.first()
        if (targetM >= cumDist.last())  return pts.last()
        var lo = 0
        var hi = cumDist.lastIndex
        while (lo < hi - 1) {
            val mid = (lo + hi) / 2
            if (cumDist[mid] <= targetM) lo = mid else hi = mid
        }
        val segLen   = cumDist[hi] - cumDist[lo]
        val fraction = if (segLen > 0.0) (targetM - cumDist[lo]) / segLen else 0.0
        val lat = pts[lo].lat + fraction * (pts[hi].lat - pts[lo].lat)
        val lon = pts[lo].lon + fraction * (pts[hi].lon - pts[lo].lon)
        return RoutePoint(lat = lat, lon = lon, altMetres = null, accuracyMetres = null,
            timestampMs = 0L, isSmoothed = true)
    }
}
