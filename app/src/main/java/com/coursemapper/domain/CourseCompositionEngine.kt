package com.coursemapper.domain

import com.coursemapper.domain.model.CourseBuildSpec
import com.coursemapper.domain.model.CourseLap
import com.coursemapper.domain.model.RoutePoint
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Composes a course from a shorter base route, see [CourseBuildSpec] for the
 * modes. A 10 km route with a 42.195 km target gives 4 laps plus 2.195 km.
 *
 * When the route is repeated the seam is closed by appending the first point,
 * and the lap length includes that leg - otherwise the gap left by stopping the
 * watch a few metres early ends up in the line but not in the total, and every
 * marker after lap one sits early. A single lap is never closed, point to point
 * courses stay open. Gaps over [LOOP_CLOSURE_TOLERANCE_METRES] are reported as
 * [LoopClosure.SuspectOpenRoute].
 */
@Singleton
class CourseCompositionEngine @Inject constructor(
    private val distanceCalculator: CumulativeDistanceCalculator
) {

    companion object {
        /** Endpoints closer than this already coincide; there is nothing to close. */
        const val CLOSED_LOOP_EPSILON_METRES = 0.5

        /**
         * Largest end to start gap still treated as a loop. From real traces: the
         * half is 62.8 m (same street), the 5 km 117.3 m and the 10 km 403.3 m.
         */
        const val LOOP_CLOSURE_TOLERANCE_METRES = 100.0
    }

    /** What repeating the base route required of its two endpoints. */
    sealed interface LoopClosure {
        /** Used once - the endpoints are never joined. */
        data object NotRepeated : LoopClosure

        /** Endpoints already coincide within [CLOSED_LOOP_EPSILON_METRES]. */
        data object AlreadyClosed : LoopClosure

        /** Seam closed across [gapMetres] of recording slop. */
        data class Closed(val gapMetres: Double) : LoopClosure

        /** Seam closed but [gapMetres] is over the tolerance, probably point to point. */
        data class SuspectOpenRoute(val gapMetres: Double) : LoopClosure
    }

    /**
     * What [compose] would report, without building the polyline. For live
     * previews, uses the same helpers so preview and saved course agree.
     */
    data class CompositionSummary(
        val fullLapCount: Int,
        /** One lap's length, including the closing leg when the seam is closed. */
        val lapDistanceMetres: Double,
        /** Length of the trailing partial lap; 0.0 when there is none. */
        val partialDistanceMetres: Double,
        val totalDistanceMetres: Double,
        val summaryText: String,
        val loopClosure: LoopClosure
    )

    data class CompositionResult(
        val laps: List<CourseLap>,
        /** Ordered smoothed points of the full composed course. */
        val composedPoints: List<RoutePoint>,
        /** Always equals the measured length of [composedPoints]. */
        val totalDistanceMetres: Double,
        /** Short human-readable summary, e.g. "10 km × 4 laps + 2.195 km". */
        val summaryText: String,
        /** One lap, loop-closed if repeated. The lap template for `MarkerPlacementEngine`. */
        val lapTemplatePoints: List<RoutePoint>,
        /** How the base route's endpoints were treated.  See [LoopClosure]. */
        val loopClosure: LoopClosure
    )

    /** Compose from [spec]. [basePoints] should be smoothed. */
    fun compose(
        basePoints: List<RoutePoint>,
        spec: CourseBuildSpec
    ): CompositionResult {
        val smoothed     = basePoints.filter { it.isSmoothed }
        val baseDistance = distanceCalculator.pathDistanceMetres(smoothed, smoothedOnly = false)

        // Resolve the lap template first: when the course repeats, one lap is
        // the loop-closed route, and every downstream lap count, partial, and
        // total is measured against that longer lap.
        val lap = resolveLapTemplate(smoothed, baseDistance, spec)
        val (lapCount, partial) = resolvePlan(lap.distanceMetres, spec)
        return composeWithPartial(lap, lapCount, partial)
    }

    /** See [CompositionSummary]. */
    fun summarize(
        basePoints: List<RoutePoint>,
        spec: CourseBuildSpec
    ): CompositionSummary {
        val smoothed     = basePoints.filter { it.isSmoothed }
        val baseDistance = distanceCalculator.pathDistanceMetres(smoothed, smoothedOnly = false)

        val metrics = resolveLapMetrics(smoothed, baseDistance, spec)
        val (lapCount, partial) = resolvePlan(metrics.distanceMetres, spec)

        return CompositionSummary(
            fullLapCount          = lapCount,
            lapDistanceMetres     = metrics.distanceMetres,
            partialDistanceMetres = partial,
            totalDistanceMetres   = metrics.distanceMetres * lapCount + partial,
            summaryText           = buildSummaryText(metrics.distanceMetres, lapCount, partial),
            loopClosure           = metrics.closure
        )
    }

    /** Full laps and partial for [spec]. Shared by [compose] and [summarize]. */
    private fun resolvePlan(
        lapDistance: Double,
        spec: CourseBuildSpec
    ): Pair<Int, Double> = when (spec) {
        is CourseBuildSpec.FixedLaps -> spec.lapCount to 0.0

        is CourseBuildSpec.TargetDistance -> {
            val fullLaps = calculateFullLaps(lapDistance, spec.totalMetres, spec.lapCount)
            fullLaps to (spec.totalMetres - lapDistance * fullLaps).coerceIn(0.0, lapDistance)
        }

        is CourseBuildSpec.Manual ->
            spec.lapCount to spec.finalLapDistanceMetres.coerceIn(0.0, lapDistance)
    }

    /** One lap of the course: its polyline, its length, and how it was closed. */
    private data class LapTemplate(
        val points: List<RoutePoint>,
        val distanceMetres: Double,
        val closure: LoopClosure
    )

    /**
     * Whether the route is laid down more than once. Only then is the seam
     * closed, a single lap can't be told apart from a loop that stopped early.
     */
    private fun CourseBuildSpec.repeatsBaseRoute(baseDistance: Double): Boolean = when (this) {
        is CourseBuildSpec.FixedLaps      -> lapCount >= 2
        is CourseBuildSpec.Manual         -> lapCount >= 2 || finalLapDistanceMetres > 0.0
        is CourseBuildSpec.TargetDistance ->
            lapCount >= 2 || totalMetres > baseDistance + CLOSED_LOOP_EPSILON_METRES
    }

    /** Lap length and closure without building the polyline. */
    private data class LapMetrics(
        val distanceMetres: Double,
        val closure: LoopClosure,
        val closeSeam: Boolean
    )

    private fun resolveLapMetrics(
        smoothed: List<RoutePoint>,
        baseDistance: Double,
        spec: CourseBuildSpec
    ): LapMetrics {
        if (smoothed.size < 2 || !spec.repeatsBaseRoute(baseDistance)) {
            return LapMetrics(baseDistance, LoopClosure.NotRepeated, closeSeam = false)
        }

        val gap = distanceCalculator.haversineMetres(
            smoothed.last().lat, smoothed.last().lon,
            smoothed.first().lat, smoothed.first().lon
        )
        if (gap <= CLOSED_LOOP_EPSILON_METRES) {
            return LapMetrics(baseDistance, LoopClosure.AlreadyClosed, closeSeam = false)
        }

        return LapMetrics(
            distanceMetres = baseDistance + gap,
            closure        = if (gap <= LOOP_CLOSURE_TOLERANCE_METRES) LoopClosure.Closed(gap)
                             else LoopClosure.SuspectOpenRoute(gap),
            closeSeam      = true
        )
    }

    private fun resolveLapTemplate(
        smoothed: List<RoutePoint>,
        baseDistance: Double,
        spec: CourseBuildSpec
    ): LapTemplate {
        val metrics = resolveLapMetrics(smoothed, baseDistance, spec)
        return LapTemplate(
            // Append the start rather than moving the recorded endpoint: the
            // connecting leg is ground the runner really covers, and no recorded
            // fix is discarded.
            points         = if (metrics.closeSeam) smoothed + smoothed.first() else smoothed,
            distanceMetres = metrics.distanceMetres,
            closure        = metrics.closure
        )
    }

    /**
     * Compose from a lap count and optional target. [targetDistanceMetres] of 0
     * gives exactly [lapCount] laps, a larger target adds laps and a partial.
     */
    fun compose(
        basePoints: List<RoutePoint>,
        lapCount: Int,
        targetDistanceMetres: Double
    ): CompositionResult {
        require(lapCount >= 1) { "lapCount must be at least 1" }
        val spec = when {
            targetDistanceMetres > 0.0 ->
                CourseBuildSpec.TargetDistance(
                    totalMetres = targetDistanceMetres,
                    lapCount    = lapCount
                )
            else ->
                CourseBuildSpec.FixedLaps(lapCount)
        }
        return compose(basePoints, spec)
    }

    /** Full laps needed for [targetMetres], at least [minLaps]. */
    private fun calculateFullLaps(baseDistance: Double, targetMetres: Double, minLaps: Int): Int {
        if (baseDistance <= 0.0) return minLaps
        val lapsNeeded = (targetMetres / baseDistance).toInt()
        return maxOf(minLaps, lapsNeeded)
    }

    private fun composeWithPartial(
        lap: LapTemplate,
        lapCount: Int,
        partialDistance: Double
    ): CompositionResult {
        val laps = buildList {
            repeat(lapCount) { i ->
                add(CourseLap(lapIndex = i, distanceMetres = lap.distanceMetres, isPartial = false))
            }
            if (partialDistance > 0.0) {
                add(CourseLap(lapIndex = lapCount, distanceMetres = partialDistance, isPartial = true))
            }
        }
        val composed = buildComposedPolyline(lap.points, lapCount, partialDistance, lap.distanceMetres)
        val total    = lap.distanceMetres * lapCount + partialDistance
        return CompositionResult(
            laps                = laps,
            composedPoints      = composed,
            totalDistanceMetres = total,
            summaryText         = buildSummaryText(lap.distanceMetres, lapCount, partialDistance),
            lapTemplatePoints   = lap.points,
            loopClosure         = lap.closure
        )
    }

    private fun buildComposedPolyline(
        baseSmoothed: List<RoutePoint>,
        lapCount: Int,
        partialDistance: Double,
        baseDistance: Double
    ): List<RoutePoint> {
        val result = ArrayList<RoutePoint>(baseSmoothed.size * lapCount + baseSmoothed.size)
        repeat(lapCount) { result.addAll(baseSmoothed) }
        if (partialDistance > 0.0 && baseSmoothed.isNotEmpty()) {
            result.addAll(trimToDistance(baseSmoothed, partialDistance, baseDistance))
        }
        return result
    }

    /** Prefix of [points] up to [targetM] with an interpolated end point. */
    private fun trimToDistance(
        points: List<RoutePoint>,
        targetM: Double,
        totalM: Double
    ): List<RoutePoint> {
        if (points.isEmpty() || targetM <= 0.0) return emptyList()
        if (targetM >= totalM) return points

        val cumDist = distanceCalculator.cumulativeDistances(points, smoothedOnly = false)
        val trimIdx = cumDist.indexOfLast { it <= targetM }.coerceAtLeast(0)

        val trimmed = ArrayList(points.subList(0, trimIdx + 1))

        // Interpolate a final point at exactly targetM.
        if (trimIdx < points.lastIndex) {
            val fraction  = (targetM - cumDist[trimIdx]) /
                            (cumDist[trimIdx + 1] - cumDist[trimIdx])
            val iLat = points[trimIdx].lat + fraction * (points[trimIdx + 1].lat - points[trimIdx].lat)
            val iLon = points[trimIdx].lon + fraction * (points[trimIdx + 1].lon - points[trimIdx].lon)
            trimmed.add(
                RoutePoint(lat = iLat, lon = iLon, altMetres = null,
                           accuracyMetres = null, timestampMs = -1L, isSmoothed = true)
            )
        }
        return trimmed
    }

    private fun buildSummaryText(
        baseDistM: Double,
        lapCount: Int,
        partialM: Double
    ): String {
        val baseKm    = "%.3f km".format(baseDistM / 1000.0)
        val lapsPart  = "$baseKm × $lapCount lap${if (lapCount > 1) "s" else ""}"
        return if (partialM > 0.0) {
            val partKm = "%.3f km".format(partialM / 1000.0)
            "$lapsPart + $partKm"
        } else {
            lapsPart
        }
    }
}
