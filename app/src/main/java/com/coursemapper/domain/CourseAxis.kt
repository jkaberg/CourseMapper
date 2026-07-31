package com.coursemapper.domain

import com.coursemapper.domain.model.RoutePoint
import javax.inject.Inject
import javax.inject.Singleton

/**
 * A course reduced to one "how far along" number.
 *
 * Plain projection breaks on courses that repeat. The marathon drives the same
 * 21.4 km loop twice, so every street has two readings and nearest point picks
 * one by GPS jitter - two stops on the same street came out 20 km apart and the
 * 1 km sign sorted last. This detects the repeat and folds the axis onto one lap.
 *
 * Detection is geometric since the repeat is baked into the recording (that
 * marathon has `lapCount = 1`). A candidate lap is only accepted if the tail
 * actually retraces the head, [RETRACE_COVERAGE] within
 * [RETRACE_TOLERANCE_METRES] - a figure eight through the start plaza returns to
 * the start without repeating.
 */
class CourseAxis internal constructor(
    /** One lap when the course repeats, the whole course otherwise. */
    private val index: PolylineIndex,
    /**
     * The whole course unfolded. Lap detection can come out short (see
     * [CourseAxisBuilder.detectLapMetres]), so questions relative to the origin
     * are answered here.
     */
    private val full: PolylineIndex,
    private val calc: CumulativeDistanceCalculator,
    /** Detected lap length, or null when the course never repeats itself. */
    val lapMetres: Double?
) {

    /** Where the course passes its origin, metres along [full]. */
    private val originPasses: List<Double> by lazy {
        full.projectionsWithin(
            full.points.first().lat,
            full.points.first().lon,
            ORIGIN_MATCH_METRES
        ).map { it.cumulativeMetres }.ifEmpty { listOf(0.0) }
    }

    /** Length of the axis: one lap when lapped, the whole course otherwise. */
    val lengthMetres: Double = lapMetres ?: index.lengthMetres

    /** True when the course repeats itself and the axis has been folded. */
    val isLapped: Boolean get() = lapMetres != null

    /** A position expressed on the axis. */
    data class Position(
        /** Distance along the axis, always in `[0, lengthMetres)`. */
        val metres: Double,
        /** How far the queried point lay off the line, in metres. */
        val offsetMetres: Double
    )

    /**
     * Position on the axis, or null if further than [maxOffsetMetres] away. Pass
     * a bound when it matters whether the point is actually on this course.
     */
    fun positionOf(
        lat: Double,
        lon: Double,
        maxOffsetMetres: Double = Double.MAX_VALUE
    ): Position? {
        val match = index.nearest(lat, lon, maxOffsetMetres) ?: return null

        // the fold makes the axis a circle cut at the start line, so a stop on
        // that line reads either 0 or a full lap by millimetres (the start gantry
        // sorted last). If the axis start is at least as close, it's the start.
        val toStart = calc.haversineMetres(lat, lon, index.points.first().lat, index.points.first().lon)
        if (toStart <= match.offsetMetres && toStart <= maxOffsetMetres) {
            return Position(0.0, toStart)
        }
        return Position(fold(match.cumulativeMetres), match.offsetMetres)
    }

    /** Fold a raw cumulative distance onto the axis. */
    fun fold(cumulativeMetres: Double): Double {
        if (!isLapped) return cumulativeMetres.coerceIn(0.0, lengthMetres)
        val m = cumulativeMetres % lengthMetres
        return if (m < 0.0) m + lengthMetres else m
    }

    /**
     * Distance from [fromMetres] to [toMetres] in the given direction. Wraps on a
     * lapped axis, null on an open axis when the target isn't ahead.
     */
    fun gapMetres(fromMetres: Double, toMetres: Double, forward: Boolean): Double? {
        val raw = if (forward) toMetres - fromMetres else fromMetres - toMetres
        if (isLapped) {
            val m = raw % lengthMetres
            return if (m < 0.0) m + lengthMetres else m
        }
        return if (raw < 0.0) null else raw
    }

    /**
     * Course left between [lat]/[lon] and the origin going forward, 0.0 at the
     * origin, null when off course or the origin isn't reachable from there.
     *
     * [positionOf] can't express "just before the start" on a folded axis - the
     * half and 5 km start 93 m and 55 m before the marathon's gantry. Measured on
     * the full course since that's the part the fold may have lost.
     */
    fun metresToOrigin(
        lat: Double,
        lon: Double,
        maxOffsetMetres: Double = ORIGIN_MATCH_METRES
    ): Double? {
        val here = full.projectionsWithin(lat, lon, maxOffsetMetres)
        if (here.isEmpty()) return null

        // Smallest forward gap over every pass of this point and every pass of
        // the origin: a course that drives the same street twice offers several
        // readings, and the one that matters is the soonest arrival.
        var best: Double? = null
        for (h in here) {
            for (o in originPasses) {
                val gap = o - h.cumulativeMetres
                // A hair negative means "standing on the origin"; anything
                // properly behind it is a different pass, not this one.
                if (gap < -ORIGIN_MATCH_METRES) continue
                val forward = gap.coerceAtLeast(0.0)
                if (best == null || forward < best) best = forward
            }
        }
        return best
    }

    /** Direction of the course itself at [metres] along the axis, degrees from north. */
    fun bearingAt(metres: Double): Double = index.bearingAt(fold(metres))

    /** Whether [headingDegrees] at [metres] goes the same way as the course line. */
    fun isTravellingForward(metres: Double, headingDegrees: Double): Boolean =
        CumulativeDistanceCalculator.bearingDeltaDegrees(bearingAt(metres), headingDegrees) <= 90.0

    companion object {
        /** How close counts as on the course for [metresToOrigin], starts sit at the kerb. */
        const val ORIGIN_MATCH_METRES = 25.0

        /** How near the course must come to its own start to suggest a lap. */
        const val RETURN_TOLERANCE_METRES = 40.0

        /** How near a tail sample must lie to the first lap to count as retracing it. */
        const val RETRACE_TOLERANCE_METRES = 25.0

        /** Fraction of tail samples that must retrace before a lap is accepted. */
        const val RETRACE_COVERAGE = 0.9

        /** Spacing of the retrace samples. */
        const val RETRACE_STEP_METRES = 25.0

        /** Shorter laps are ignored, a near pass of the start would fold away real ground. */
        const val MIN_LAP_FRACTION = 0.25

        /** Longer laps leave too little tail to tell from a loop that just ends at its start. */
        const val MAX_LAP_FRACTION = 0.75
    }
}

/** Builds [CourseAxis] instances, including the lap detection. */
@Singleton
class CourseAxisBuilder @Inject constructor(
    private val calc: CumulativeDistanceCalculator
) {

    /** Null when [points] has no line in it to measure along. */
    fun build(points: List<RoutePoint>): CourseAxis? {
        val full = PolylineIndex.build(points, calc) ?: return null
        val lap = detectLapMetres(full) ?: return CourseAxis(full, full, calc, null)
        // Measure along the first lap alone: the later passes retrace it, so
        // they need no representation of their own, and leaving them out means
        // no reading can ever exceed one lap in the first place.
        val head = PolylineIndex.build(headPoints(full, lap), calc)
            ?: return CourseAxis(full, full, calc, null)
        // The whole course travels with it - see [CourseAxis.full] for why the
        // fold alone is not enough.
        return CourseAxis(head, full, calc, head.lengthMetres)
    }

    private fun headPoints(index: PolylineIndex, lapMetres: Double): List<RoutePoint> =
        index.points.filterIndexed { i, _ -> index.cumulativeAt(i) <= lapMetres }

    /**
     * One lap length, or null when the course doesn't repeat. Stage one picks
     * the closest return to the start, stage two checks that the tail retraces.
     *
     * Can come out short: in a start plaza the course passes near the gantry
     * before reaching it (125.6 m early on the marathon). That stretch clamps to
     * 0.0 and is treated as off-axis, which happens to be the right place in visit
     * order. [CourseAxis.metresToOrigin] and [VisitOrderPlanner.axisOrder] handle
     * the seam - tightening this alone would move such stops to the back.
     */
    private fun detectLapMetres(index: PolylineIndex): Double? {
        val total = index.lengthMetres
        if (total <= 0.0) return null

        val start = index.points.first()
        var candidate = -1.0
        var closest = Double.MAX_VALUE
        for (sample in index.sample(CourseAxis.RETRACE_STEP_METRES)) {
            val fraction = sample.cumulativeMetres / total
            if (fraction < CourseAxis.MIN_LAP_FRACTION) continue
            if (fraction > CourseAxis.MAX_LAP_FRACTION) break
            val d = calc.haversineMetres(start.lat, start.lon, sample.lat, sample.lon)
            if (d < closest) {
                closest = d
                candidate = sample.cumulativeMetres
            }
        }
        if (candidate < 0.0 || closest > CourseAxis.RETURN_TOLERANCE_METRES) return null

        // Stage two: does the tail actually retrace the head?
        val head = PolylineIndex.build(headPoints(index, candidate), calc) ?: return null

        var tested = 0
        var onHead = 0
        var walked = candidate
        while (walked <= total) {
            val (lat, lon) = index.pointAt(walked)
            tested++
            if (head.nearest(lat, lon, CourseAxis.RETRACE_TOLERANCE_METRES) != null) onHead++
            walked += CourseAxis.RETRACE_STEP_METRES
        }
        if (tested == 0) return null
        return candidate.takeIf { onHead.toDouble() / tested >= CourseAxis.RETRACE_COVERAGE }
    }
}
