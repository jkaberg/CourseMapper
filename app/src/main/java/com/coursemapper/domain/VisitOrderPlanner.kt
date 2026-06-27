package com.coursemapper.domain

import com.coursemapper.domain.model.RoutePoint
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Visit order for the stops of a run.
 *
 * - [spineOrder] / [axisOrder] along a spine course (usually the longest) on a
 *   [CourseAxis]. Predictable, follows the main route.
 * - [optimizedOrder] 2-opt over straight-line distances. Shorter, but legs are
 *   estimates so the UI shows them dashed, never as turn-by-turn.
 *
 * Both run in milliseconds for a few hundred stops.
 */
@Singleton
class VisitOrderPlanner @Inject constructor(
    private val distanceCalc: CumulativeDistanceCalculator,
    private val axisBuilder: CourseAxisBuilder
) {

    companion object {
        /** Upper bound on 2-opt improvement passes (normally converges in < 10). */
        const val MAX_TWO_OPT_PASSES = 40

        /**
         * Further off the spine than this and the projection stops meaning much
         * (the 5 km's 4 km sign projected next to the start). Those stops are
         * checked against their own course, see [axisOrder].
         */
        const val ON_AXIS_TOLERANCE_METRES = CorridorAnalyzer.EXIT_TOLERANCE_METRES

        /**
         * How far before the spine's start a start line can be and still count as
         * the same plaza, see [seamRank]. The Trondheim starts span 93 m, the 10 km
         * start 506 m along the spine has to stay where it is.
         */
        const val START_PLAZA_METRES = 250.0

        /** A marker at this course distance or less is that course's start. */
        const val START_DISTANCE_EPSILON_METRES = 0.5
    }

    data class StopPoint(val lat: Double, val lon: Double)

    /** A stop with its distance along each course that has a marker here. */
    data class AxisStop(
        val lat: Double,
        val lon: Double,
        /** courseId → that course's own distance to this stop, in metres. */
        val courseDistances: Map<Long, Double>
    )

    /** Order along [spine] for callers without per-course distances. */
    fun spineOrder(stops: List<StopPoint>, spine: List<RoutePoint>): List<Int> {
        if (stops.size <= 1 || spine.size < 2) return stops.indices.toList()
        val axis = axisBuilder.build(spine) ?: return stops.indices.toList()
        return axisOrder(stops.map { AxisStop(it.lat, it.lon, emptyMap()) }, axis)
    }

    /**
     * Order [stops] along [axis], returns indices.
     *
     * Stops within [ON_AXIS_TOLERANCE_METRES] rank by position. Stops further out
     * keep their projection if it agrees with their own course's km order (see
     * [projectionAgreesWithOwnCourses]), otherwise they're placed next to the
     * nearest stop of their own course that is on the axis.
     *
     * Tolerance alone can't decide this - start lines sit 32-55 m off the marathon
     * line and were driven ninth and eleventh instead of first.
     */
    fun axisOrder(stops: List<AxisStop>, axis: CourseAxis): List<Int> {
        if (stops.size <= 1) return stops.indices.toList()

        val rank = DoubleArray(stops.size)
        val tie = DoubleArray(stops.size)
        val onAxis = BooleanArray(stops.size)
        val resolved = BooleanArray(stops.size)

        // The seam, first: a start line standing just BEFORE the spine's own
        // start is on the road the rider drives in to reach it, and belongs at
        // the front of the run in the order it is passed.  See [seamRank].
        stops.forEachIndexed { i, stop ->
            val seam = seamRank(stop, axis) ?: return@forEachIndexed
            rank[i] = seam
            onAxis[i] = true
            resolved[i] = true
        }

        stops.forEachIndexed { i, stop ->
            if (resolved[i]) return@forEachIndexed
            val pos = axis.positionOf(stop.lat, stop.lon, ON_AXIS_TOLERANCE_METRES)
            if (pos != null) {
                rank[i] = pos.metres
                onAxis[i] = true
            }
        }

        stops.forEachIndexed { i, stop ->
            if (onAxis[i] || resolved[i]) return@forEachIndexed

            // Unbounded on purpose: how far off the line the stop lies is not
            // what decides whether its projection is trustworthy - whether the
            // projection contradicts the stop's own course is.
            val projection = axis.positionOf(stop.lat, stop.lon)?.metres
            if (projection != null &&
                projectionAgreesWithOwnCourses(stops, i, projection, rank, onAxis)
            ) {
                rank[i] = projection
                return@forEachIndexed
            }

            val anchor = ownCourseAnchor(stops, i, onAxis)
            if (anchor != null) {
                rank[i] = rank[anchor.index]
                tie[i] = anchor.ownDelta
            } else {
                // Nothing of this stop's own course is on the axis either, so
                // the projection is all there is.  An arbitrary rank still beats
                // dropping the stop out of the plan.
                rank[i] = projection ?: Double.MAX_VALUE
            }
        }

        return stops.indices.sortedWith(
            compareBy({ rank[it] }, { tie[it] }, { it })
        )
    }

    /**
     * Rank for a start line just before the axis origin, or null.
     *
     * The axis can't express "before the start", so the half (93 m) and 5 km (55 m)
     * starts all read 0.00 and ended up in insertion order. Ranked at minus the
     * distance to the origin instead, from [CourseAxis.metresToOrigin].
     * Only for stops carrying a course start within [START_PLAZA_METRES].
     */
    private fun seamRank(stop: AxisStop, axis: CourseAxis): Double? {
        if (stop.courseDistances.none { it.value <= START_DISTANCE_EPSILON_METRES }) return null
        val toOrigin = axis.metresToOrigin(stop.lat, stop.lon) ?: return null
        if (toOrigin <= 0.0 || toOrigin > START_PLAZA_METRES) return null
        return -toOrigin
    }

    /**
     * Whether ranking [target] at [projection] keeps each of its courses in km
     * order, ie it falls between its own neighbours on the axis. Rejected stops
     * go to [ownCourseAnchor].
     */
    private fun projectionAgreesWithOwnCourses(
        stops: List<AxisStop>,
        target: Int,
        projection: Double,
        rank: DoubleArray,
        onAxis: BooleanArray
    ): Boolean {
        val distances = stops[target].courseDistances
        if (distances.isEmpty()) return false      // nothing to check it against

        for ((courseId, own) in distances) {
            val (predecessor, successor) = onAxisNeighbours(stops, target, courseId, own, onAxis)
            if (predecessor < 0 && successor < 0) return false
            if (predecessor >= 0 && projection < rank[predecessor]) return false
            if (successor >= 0 && projection > rank[successor]) return false
        }
        return true
    }

    /** The on-axis neighbours of [own] on [courseId], -1 when there is none. */
    private fun onAxisNeighbours(
        stops: List<AxisStop>,
        target: Int,
        courseId: Long,
        own: Double,
        onAxis: BooleanArray
    ): Pair<Int, Int> {
        var predecessor = -1
        var successor = -1
        var predecessorOwn = Double.NEGATIVE_INFINITY
        var successorOwn = Double.POSITIVE_INFINITY

        stops.forEachIndexed { j, candidate ->
            if (j == target || !onAxis[j]) return@forEachIndexed
            val theirs = candidate.courseDistances[courseId] ?: return@forEachIndexed
            if (theirs <= own && theirs > predecessorOwn) {
                predecessorOwn = theirs
                predecessor = j
            } else if (theirs > own && theirs < successorOwn) {
                successorOwn = theirs
                successor = j
            }
        }
        return predecessor to successor
    }

    /** An on-axis stop to hang an off-axis stop next to. */
    private data class Anchor(val index: Int, val ownDelta: Double)

    /**
     * Where to hang an off-axis stop. Only its own course's neighbours are
     * eligible (nearest on the ground gave 0, 1, 4, 2, 3, 5 km), and between those
     * the nearer one wins.
     */
    private fun ownCourseAnchor(
        stops: List<AxisStop>,
        target: Int,
        onAxis: BooleanArray
    ): Anchor? {
        val stop = stops[target]
        if (stop.courseDistances.isEmpty()) return null

        var best: Anchor? = null
        var bestGround = Double.MAX_VALUE

        for ((courseId, own) in stop.courseDistances) {
            val (predecessor, successor) = onAxisNeighbours(stops, target, courseId, own, onAxis)

            for (j in listOf(predecessor, successor)) {
                if (j < 0) continue
                val ground = distanceCalc.haversineMetres(
                    stop.lat, stop.lon, stops[j].lat, stops[j].lon
                )
                if (ground < bestGround) {
                    bestGround = ground
                    best = Anchor(j, own - stops[j].courseDistances.getValue(courseId))
                }
            }
        }
        return best
    }

    /**
     * Shortest path order, returns indices into [stops].
     *
     * @param start tour starts at the stop nearest to this
     * @param seedOrder also improved and the better one kept - pass the axis
     *   order. Nearest neighbour alone got stuck 374 m longer than just following
     *   the route.
     */
    fun optimizedOrder(
        stops: List<StopPoint>,
        start: StopPoint? = null,
        seedOrder: List<Int>? = null,
        firstIndex: Int? = null
    ): List<Int> {
        val n = stops.size
        if (n <= 2) return stops.indices.toList()

        // Pre-compute the symmetric distance matrix once.
        val dist = Array(n) { DoubleArray(n) }
        for (i in 0 until n) {
            for (j in i + 1 until n) {
                val d = haversine(stops[i], stops[j])
                dist[i][j] = d
                dist[j][i] = d
            }
        }

        val candidates = mutableListOf(twoOpt(nearestNeighbourTour(stops, dist, start, firstIndex), dist))
        // A fixed first stop is a hard constraint from navigation ("go to the
        // one ahead of me"), so a seed that starts elsewhere is not a candidate.
        if (seedOrder != null && seedOrder.size == n && seedOrder.toSet().size == n &&
            (firstIndex == null || seedOrder.first() == firstIndex)
        ) {
            candidates.add(twoOpt(ArrayList(seedOrder), dist))
        }
        return candidates.minByOrNull { tourLength(it, dist) } ?: stops.indices.toList()
    }

    private fun nearestNeighbourTour(
        stops: List<StopPoint>,
        dist: Array<DoubleArray>,
        start: StopPoint?,
        firstIndex: Int? = null
    ): ArrayList<Int> {
        val n = stops.size
        val visited = BooleanArray(n)
        val order = ArrayList<Int>(n)
        var current = firstIndex?.takeIf { it in 0 until n }
            ?: if (start != null) {
                stops.indices.minByOrNull { haversine(start, stops[it]) } ?: 0
            } else 0
        visited[current] = true
        order.add(current)
        repeat(n - 1) {
            var best = -1
            var bestD = Double.MAX_VALUE
            for (j in 0 until n) {
                if (!visited[j] && dist[current][j] < bestD) {
                    bestD = dist[current][j]
                    best = j
                }
            }
            visited[best] = true
            order.add(best)
            current = best
        }
        return order
    }

    /** 2-opt with the first stop fixed, O(1) delta per reversal. */
    private fun twoOpt(order: ArrayList<Int>, dist: Array<DoubleArray>): List<Int> {
        val n = order.size
        var improvedInPass = true
        var passes = 0
        while (improvedInPass && passes < MAX_TWO_OPT_PASSES) {
            improvedInPass = false
            passes++
            for (i in 1 until n - 1) {
                for (k in i + 1 until n) {
                    val a = order[i - 1]
                    val b = order[i]
                    val c = order[k]
                    val before = dist[a][b] + if (k < n - 1) dist[c][order[k + 1]] else 0.0
                    val after = dist[a][c] + if (k < n - 1) dist[b][order[k + 1]] else 0.0
                    if (after + 1e-9 < before) {
                        order.subList(i, k + 1).reverse()
                        improvedInPass = true
                    }
                }
            }
        }
        return order
    }

    private fun tourLength(order: List<Int>, dist: Array<DoubleArray>): Double {
        var total = 0.0
        for (i in 1 until order.size) total += dist[order[i - 1]][order[i]]
        return total
    }

    /** Total straight-line path length of visiting [stops] in [order], metres. */
    fun pathLengthMetres(stops: List<StopPoint>, order: List<Int>): Double {
        var total = 0.0
        for (i in 1 until order.size) {
            total += haversine(stops[order[i - 1]], stops[order[i]])
        }
        return total
    }

    private fun haversine(a: StopPoint, b: StopPoint): Double =
        distanceCalc.haversineMetres(a.lat, a.lon, b.lat, b.lon)
}
