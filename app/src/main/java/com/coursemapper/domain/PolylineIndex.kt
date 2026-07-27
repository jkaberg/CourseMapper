package com.coursemapper.domain

import com.coursemapper.domain.model.RoutePoint
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor

/**
 * A polyline prepared for repeated nearest-point queries.
 *
 * The corridor analyser and the per-fix snap query thousands of times, so the
 * cumulative distances are built once and segments bucketed in a grid with
 * square cells on the ground ([cellMetres]). Segments are registered in every
 * cell they cross. Large radii fall back to a linear scan.
 *
 * Immutable and thread safe.
 */
class PolylineIndex private constructor(
    /** The polyline this index was built over, in order. */
    val points: List<RoutePoint>,
    private val cumulative: DoubleArray,
    private val calc: CumulativeDistanceCalculator,
    private val cellMetres: Double,
    private val latCellDeg: Double,
    private val lonCellDeg: Double,
    private val buckets: Map<Long, IntArray>
) {

    /** Total length of the polyline in metres. */
    val lengthMetres: Double get() = cumulative.last()

    /** Number of segments (one less than the point count). */
    val segmentCount: Int get() = points.size - 1

    /** Cumulative distance from the start to vertex [index], in metres. */
    fun cumulativeAt(index: Int): Double = cumulative[index]

    /** Nearest point to [lat]/[lon], or null if nothing within [maxMetres]. */
    fun nearest(
        lat: Double,
        lon: Double,
        maxMetres: Double = Double.MAX_VALUE
    ): Match? {
        if (segmentCount < 1) return null

        val candidates = if (maxMetres > cellMetres * GRID_RADIUS_CELL_LIMIT) {
            null   // wider than the grid pays for - scan everything
        } else {
            collectCandidates(lat, lon, maxMetres)
        }

        var bestOffset = Double.MAX_VALUE
        var best: Match? = null

        fun consider(i: Int) {
            val foot = calc.closestPointOnSegment(
                points[i].lat, points[i].lon,
                points[i + 1].lat, points[i + 1].lon,
                lat, lon
            )
            val offset = calc.haversineMetres(lat, lon, foot.lat, foot.lon)
            if (offset < bestOffset) {
                bestOffset = offset
                best = Match(
                    lat = foot.lat,
                    lon = foot.lon,
                    cumulativeMetres = cumulative[i] + foot.t * (cumulative[i + 1] - cumulative[i]),
                    offsetMetres = offset,
                    segmentIndex = i
                )
            }
        }

        if (candidates == null) {
            for (i in 0 until segmentCount) consider(i)
        } else {
            candidates.forEach(::consider)
        }

        return best?.takeIf { it.offsetMetres <= maxMetres }
    }

    /**
     * Every pass of the polyline within [maxMetres], nearest first. For laps,
     * where [nearest] would pick one pass arbitrarily. Feet closer than
     * [minSeparationMetres] along the line count as one pass.
     */
    fun projectionsWithin(
        lat: Double,
        lon: Double,
        maxMetres: Double,
        minSeparationMetres: Double = DEFAULT_PASS_SEPARATION_METRES
    ): List<Match> {
        if (segmentCount < 1) return emptyList()

        val hits = ArrayList<Match>()
        for (i in 0 until segmentCount) {
            val foot = calc.closestPointOnSegment(
                points[i].lat, points[i].lon,
                points[i + 1].lat, points[i + 1].lon,
                lat, lon
            )
            val offset = calc.haversineMetres(lat, lon, foot.lat, foot.lon)
            if (offset > maxMetres) continue
            hits.add(
                Match(
                    lat = foot.lat,
                    lon = foot.lon,
                    cumulativeMetres = cumulative[i] + foot.t * (cumulative[i + 1] - cumulative[i]),
                    offsetMetres = offset,
                    segmentIndex = i
                )
            )
        }
        if (hits.isEmpty()) return emptyList()

        hits.sortBy { it.cumulativeMetres }
        val passes = ArrayList<Match>()
        for (hit in hits) {
            val last = passes.lastOrNull()
            if (last == null || hit.cumulativeMetres - last.cumulativeMetres > minSeparationMetres) {
                passes.add(hit)
            } else if (hit.offsetMetres < last.offsetMetres) {
                passes[passes.lastIndex] = hit
            }
        }
        return passes
    }

    /** Polyline from [fromMetres] to [toMetres], ends interpolated. Reversed if asked backwards. */
    fun slice(fromMetres: Double, toMetres: Double): List<Pair<Double, Double>> {
        val from = fromMetres.coerceIn(0.0, lengthMetres)
        val to = toMetres.coerceIn(0.0, lengthMetres)
        val lo = minOf(from, to)
        val hi = maxOf(from, to)

        val out = ArrayList<Pair<Double, Double>>()
        out.add(pointAt(lo))
        for (i in points.indices) {
            val at = cumulative[i]
            if (at > lo && at < hi) out.add(points[i].lat to points[i].lon)
        }
        out.add(pointAt(hi))

        val trimmed = out.filterConsecutiveDistinctPairs()
        return if (from <= to) trimmed else trimmed.asReversed()
    }

    /** As [Companion.filterConsecutiveDistinct], for the pairs [slice] emits. */
    private fun List<Pair<Double, Double>>.filterConsecutiveDistinctPairs():
        List<Pair<Double, Double>> {
        if (size < 2) return this
        val out = ArrayList<Pair<Double, Double>>(size)
        out.add(this[0])
        for (i in 1 until size) if (this[i] != out.last()) out.add(this[i])
        return out
    }

    /** Position at [metres] along the polyline, clamped to its ends. */
    fun pointAt(metres: Double): Pair<Double, Double> {
        val i = segmentIndexAt(metres)
        val segLen = cumulative[i + 1] - cumulative[i]
        val f = if (segLen > 0.0) ((metres - cumulative[i]) / segLen).coerceIn(0.0, 1.0) else 0.0
        return Pair(
            points[i].lat + f * (points[i + 1].lat - points[i].lat),
            points[i].lon + f * (points[i + 1].lon - points[i].lon)
        )
    }

    /** Bearing at [metres] over a [BEARING_WINDOW_METRES] window, one segment is mostly noise. */
    fun bearingAt(metres: Double): Double {
        val half = BEARING_WINDOW_METRES / 2.0
        val from = (metres - half).coerceIn(0.0, lengthMetres)
        val to   = (metres + half).coerceIn(0.0, lengthMetres)
        val (aLat, aLon) = pointAt(from)
        val (bLat, bLon) = pointAt(if (to > from) to else lengthMetres)
        return calc.bearingDegrees(aLat, aLon, bLat, bLon)
    }

    /** Samples every [stepMetres] of ground, including first and last. */
    fun sample(stepMetres: Double): List<Sample> {
        require(stepMetres > 0.0) { "stepMetres must be > 0" }
        val total = lengthMetres
        if (total <= 0.0) {
            val p = points.first()
            return listOf(Sample(0.0, p.lat, p.lon))
        }
        val count = ceil(total / stepMetres).toInt().coerceAtLeast(1)
        val out = ArrayList<Sample>(count + 1)
        for (k in 0..count) {
            val m = (k * stepMetres).coerceAtMost(total)
            val (lat, lon) = pointAt(m)
            out.add(Sample(m, lat, lon))
            if (m >= total) break
        }
        return out
    }

    /** A nearest-point hit on the polyline. */
    data class Match(
        val lat: Double,
        val lon: Double,
        /** Distance from the polyline start to the foot, in metres. */
        val cumulativeMetres: Double,
        /** Distance from the query position to the foot, in metres. */
        val offsetMetres: Double,
        /** Index of the segment the foot landed on. */
        val segmentIndex: Int
    )

    /** One evenly spaced point along the polyline. */
    data class Sample(
        val cumulativeMetres: Double,
        val lat: Double,
        val lon: Double
    )

    /** Index of the segment containing [metres]; binary search over [cumulative]. */
    private fun segmentIndexAt(metres: Double): Int {
        if (metres <= 0.0) return 0
        if (metres >= lengthMetres) return segmentCount - 1
        var lo = 0
        var hi = cumulative.lastIndex
        while (lo < hi - 1) {
            val mid = (lo + hi) / 2
            if (cumulative[mid] <= metres) lo = mid else hi = mid
        }
        return lo.coerceAtMost(segmentCount - 1)
    }

    /** Segment indices in every cell within [maxMetres] of the query point. */
    private fun collectCandidates(lat: Double, lon: Double, maxMetres: Double): IntArray {
        val ring = ceil(maxMetres / cellMetres).toInt().coerceAtLeast(1)
        val latIdx = floor(lat / latCellDeg).toInt()
        val lonIdx = floor(lon / lonCellDeg).toInt()

        val found = LinkedHashSet<Int>()
        for (dy in -ring..ring) {
            for (dx in -ring..ring) {
                buckets[key(latIdx + dy, lonIdx + dx)]?.forEach { found.add(it) }
            }
        }
        return found.toIntArray()
    }

    companion object {
        /** Default grid cell size on the ground, in metres. */
        const val DEFAULT_CELL_METRES = 64.0

        /** Averages out vertex jitter, still resolves a corner. */
        const val BEARING_WINDOW_METRES = 20.0

        /** Above this many cells [nearest] just scans. */
        private const val GRID_RADIUS_CELL_LIMIT = 8

        /** Along-line separation for separate passes, a hairpin still reads as two. */
        const val DEFAULT_PASS_SEPARATION_METRES = 30.0

        /** Null for fewer than two points. Consecutive duplicates are dropped. */
        fun build(
            points: List<RoutePoint>,
            calc: CumulativeDistanceCalculator,
            cellMetres: Double = DEFAULT_CELL_METRES
        ): PolylineIndex? {
            val pts = points.filterConsecutiveDistinct()
            if (pts.size < 2) return null

            val cumulative = DoubleArray(pts.size)
            for (i in 1 until pts.size) {
                cumulative[i] = cumulative[i - 1] +
                    calc.haversineMetres(pts[i - 1].lat, pts[i - 1].lon, pts[i].lat, pts[i].lon)
            }

            val midLat = pts[pts.size / 2].lat
            val latCellDeg = cellMetres / METRES_PER_DEGREE_LAT
            val lonCellDeg = cellMetres /
                (METRES_PER_DEGREE_LAT * cos(Math.toRadians(midLat)).coerceAtLeast(MIN_COS_LAT))

            val raw = HashMap<Long, MutableList<Int>>()
            for (i in 0 until pts.size - 1) {
                registerSegment(raw, i, pts[i], pts[i + 1], latCellDeg, lonCellDeg)
            }
            val buckets = raw.mapValues { (_, v) -> v.toIntArray() }

            return PolylineIndex(pts, cumulative, calc, cellMetres, latCellDeg, lonCellDeg, buckets)
        }

        private val METRES_PER_DEGREE_LAT =
            CumulativeDistanceCalculator.EARTH_RADIUS_M * Math.PI / 180.0

        /** Guard so a polar route cannot blow the longitude cell up to infinity. */
        private const val MIN_COS_LAT = 0.01

        private fun key(latIdx: Int, lonIdx: Int): Long =
            (latIdx.toLong() shl 32) xor (lonIdx.toLong() and 0xFFFF_FFFFL)

        /**
         * Register segment [i] in every cell it crosses, in half-cell steps.
         * Endpoints only would miss segments spanning a cell, common in sparse GPX.
         */
        private fun registerSegment(
            out: HashMap<Long, MutableList<Int>>,
            i: Int,
            a: RoutePoint,
            b: RoutePoint,
            latCellDeg: Double,
            lonCellDeg: Double
        ) {
            val steps = maxOf(
                abs(b.lat - a.lat) / (latCellDeg / 2.0),
                abs(b.lon - a.lon) / (lonCellDeg / 2.0)
            ).let { ceil(it).toInt().coerceIn(1, MAX_SEGMENT_CELL_STEPS) }

            var lastKey = Long.MIN_VALUE
            for (s in 0..steps) {
                val f = s.toDouble() / steps
                val lat = a.lat + f * (b.lat - a.lat)
                val lon = a.lon + f * (b.lon - a.lon)
                val k = key(floor(lat / latCellDeg).toInt(), floor(lon / lonCellDeg).toInt())
                if (k != lastKey) {
                    out.getOrPut(k) { mutableListOf() }.add(i)
                    lastKey = k
                }
            }
        }

        /** More cells than this is a GPS teleport, not geometry. */
        private const val MAX_SEGMENT_CELL_STEPS = 512

        private fun List<RoutePoint>.filterConsecutiveDistinct(): List<RoutePoint> {
            if (size < 2) return this
            val out = ArrayList<RoutePoint>(size)
            out.add(this[0])
            for (i in 1 until size) {
                val prev = out.last()
                if (this[i].lat != prev.lat || this[i].lon != prev.lon) out.add(this[i])
            }
            return out
        }
    }
}
