package com.coursemapper.domain

import com.coursemapper.domain.model.CourseGeometryEdit
import com.coursemapper.domain.model.RoutePoint
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Applies a [CourseGeometryEdit] to a route. The only place an edit becomes
 * points, so the editor preview and the built course can't disagree.
 */
@Singleton
class CourseGeometryEditor @Inject constructor(
    private val calc: CumulativeDistanceCalculator
) {

    companion object {
        /** An edit leaving less than this is ignored and the route returned whole. */
        const val MIN_RESULT_METRES = 10.0

        /** Same tolerance [CourseCompositionEngine] uses for lappable routes. */
        const val LOOP_TOLERANCE_METRES = CourseCompositionEngine.LOOP_CLOSURE_TOLERANCE_METRES
    }

    /** What an edit would produce, without producing it. */
    data class Description(
        /** Length of the base route as recorded. */
        val baseLengthMetres: Double,
        /** Length of one lap after the edit. */
        val resultLengthMetres: Double,
        /** Distance from the base route's last point back to its first. */
        val endpointGapMetres: Double,
        /** True when the base route's ends meet closely enough to lap it. */
        val isLoop: Boolean,
        /** True when the kept window runs past the end and back to the start. */
        val wraps: Boolean
    ) {
        /** Metres the edit removes from the recording. */
        val trimmedMetres: Double get() = (baseLengthMetres - resultLengthMetres).coerceAtLeast(0.0)
    }

    /** Apply [edit]. Unchanged for identity edits, empty input or less than [MIN_RESULT_METRES] left. */
    fun apply(points: List<RoutePoint>, edit: CourseGeometryEdit): List<RoutePoint> {
        if (points.size < 2 || edit.isIdentity) return points

        val oriented = if (edit.reversed) points.reversed() else points
        val total = calc.pathDistanceMetres(oriented, smoothedOnly = false)
        if (total <= 0.0) return points

        val window = window(oriented, total, edit) ?: return points
        val kept = sliceWindow(oriented, total, window)
        if (kept.size < 2) return points
        if (calc.pathDistanceMetres(kept, smoothedOnly = false) < MIN_RESULT_METRES) return points

        return if (edit.closeLoop) kept.closed() else kept
    }

    /** Describe what [edit] would do to [points], for the editor's readouts. */
    fun describe(points: List<RoutePoint>, edit: CourseGeometryEdit): Description {
        val baseLength = calc.pathDistanceMetres(points, smoothedOnly = false)
        val gap = endpointGap(points)
        val result = apply(points, edit)
        val oriented = if (edit.reversed) points.reversed() else points
        val window = if (points.size < 2) null else window(oriented, baseLength, edit)

        return Description(
            baseLengthMetres = baseLength,
            resultLengthMetres = calc.pathDistanceMetres(result, smoothedOnly = false),
            endpointGapMetres = gap,
            isLoop = gap <= LOOP_TOLERANCE_METRES && points.size >= 2,
            wraps = window?.wraps == true
        )
    }

    /** Distance from a route's last point back to its first. */
    fun endpointGap(points: List<RoutePoint>): Double {
        if (points.size < 2) return 0.0
        val first = points.first()
        val last = points.last()
        return calc.haversineMetres(last.lat, last.lon, first.lat, first.lon)
    }

    private data class Window(val from: Double, val to: Double, val wraps: Boolean)

    /** The kept window. No wrapping unless the route's ends meet. */
    private fun window(
        oriented: List<RoutePoint>,
        total: Double,
        edit: CourseGeometryEdit
    ): Window? {
        val start = edit.startOffsetMetres.coerceIn(0.0, total)
        val isLoop = endpointGap(oriented) <= LOOP_TOLERANCE_METRES
        // "keep everything" is the rest of the recording on an open route, and the
        // whole lap from the new start on a loop
        val requested = when {
            edit.keepLengthMetres > 0.0 -> edit.keepLengthMetres
            isLoop -> total
            else -> total - start
        }

        val end = start + requested
        return when {
            end <= total -> Window(start, end, wraps = false)
            isLoop -> Window(start, (end - total).coerceAtMost(start), wraps = true)
            // Not a loop: keep what is actually there rather than inventing it.
            else -> Window(start, total, wraps = false)
        }
    }

    private fun sliceWindow(
        oriented: List<RoutePoint>,
        total: Double,
        window: Window
    ): List<RoutePoint> = if (!window.wraps) {
        calc.slicePolyline(oriented, window.from, window.to, smoothedOnly = false)
    } else {
        // Past the end and round again: the tail, then the head.  The seam is
        // where the recording's own ends meet, which on a loop is the same
        // ground, so the two slices join without inventing a leg.
        val tail = calc.slicePolyline(oriented, window.from, total, smoothedOnly = false)
        val head = calc.slicePolyline(oriented, 0.0, window.to, smoothedOnly = false)
        (tail + head).dedupeSeam()
    }

    /** Drop a duplicated point where two slices meet. */
    private fun List<RoutePoint>.dedupeSeam(): List<RoutePoint> {
        if (size < 2) return this
        val out = ArrayList<RoutePoint>(size)
        out += this[0]
        for (i in 1 until size) {
            val previous = out.last()
            if (calc.haversineMetres(previous.lat, previous.lon, this[i].lat, this[i].lon) > 0.05) {
                out += this[i]
            }
        }
        return out
    }

    /** Append the first point so the finish meets the start. */
    private fun List<RoutePoint>.closed(): List<RoutePoint> {
        val first = first()
        val last = last()
        if (calc.haversineMetres(last.lat, last.lon, first.lat, first.lon) <=
            CourseCompositionEngine.CLOSED_LOOP_EPSILON_METRES
        ) {
            return this
        }
        return this + first.copy(timestampMs = last.timestampMs)
    }
}
