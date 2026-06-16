package com.coursemapper.domain

import com.coursemapper.domain.model.DistanceMarker
import com.coursemapper.domain.model.PlacementStop
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Groups markers at the same physical spot into [PlacementStop]s.
 *
 * Markers within [groupingThresholdMetres] of each other share a stop, and it's
 * transitive (single linkage). Position is the centroid, stops are ordered by
 * their lowest sequence index.
 *
 * Single linkage because greedy centroid grouping was order dependent and not
 * monotonic - the Trondheim finish gantry has four finish markers over 11.3 m and
 * ended up as two stops. Chaining can't really happen here since markers along
 * a course are an interval apart and only coincide where courses meet.
 */
@Singleton
class PlacementPlanner @Inject constructor(
    private val distanceCalc: CumulativeDistanceCalculator
) {

    companion object {
        const val DEFAULT_GROUPING_THRESHOLD_METRES = 10.0
    }

    data class PlanResult(val stops: List<PlacementStop>)

    /** Plan stops for a composed course from [MarkerPlacementEngine] markers. */
    fun plan(
        markers: List<DistanceMarker>,
        courseId: Long = 0L,
        groupingThresholdMetres: Double = DEFAULT_GROUPING_THRESHOLD_METRES
    ): PlanResult {
        if (markers.isEmpty()) return PlanResult(emptyList())

        // Single-linkage clustering: union every pair within the threshold, then
        // read off the connected components.  Pairwise and symmetric, so neither
        // the input order nor an intermediate centroid can influence the result.
        val parent = IntArray(markers.size) { it }

        fun find(start: Int): Int {
            var root = start
            while (parent[root] != root) root = parent[root]
            var node = start
            while (parent[node] != root) {          // path compression
                val next = parent[node]
                parent[node] = root
                node = next
            }
            return root
        }

        fun union(a: Int, b: Int) {
            val ra = find(a)
            val rb = find(b)
            // Always keep the lower index as the root so component membership - 
            // and therefore the emitted stop order - is fully determined by the
            // marker list, never by the order the pairs happened to be visited.
            if (ra == rb) return
            if (ra < rb) parent[rb] = ra else parent[ra] = rb
        }

        for (i in markers.indices) {
            for (j in i + 1 until markers.size) {
                if (find(i) == find(j)) continue
                val d = distanceCalc.haversineMetres(
                    markers[i].lat, markers[i].lon,
                    markers[j].lat, markers[j].lon
                )
                if (d <= groupingThresholdMetres) union(i, j)
            }
        }

        // Collect components, keyed by root, in ascending order of first member
        // so the group list itself is deterministic before sorting.
        val byRoot = LinkedHashMap<Int, MutableList<DistanceMarker>>()
        for (i in markers.indices) {
            byRoot.getOrPut(find(i)) { mutableListOf() }.add(markers[i])
        }
        val groups = byRoot.values.toList()

        // Sort groups by the lowest sequenceIndex in each group so stops
        // are in the order the organiser first encounters them on one lap.
        val sortedGroups = groups.sortedBy { g -> g.minOf { it.sequenceIndex } }

        return PlanResult(
            sortedGroups.mapIndexed { idx, group ->
                val (sLat, sLon) = centroid(group)
                PlacementStop(
                    id          = 0L,
                    courseId    = courseId,
                    stopIndex   = idx,
                    lat         = sLat,
                    lon         = sLon,
                    markers     = group.sortedBy { it.sequenceIndex },
                    isCompleted = false
                )
            }
        )
    }

    private fun centroid(markers: List<DistanceMarker>): Pair<Double, Double> {
        val lat = markers.map { it.lat }.average()
        val lon = markers.map { it.lon }.average()
        return lat to lon
    }
}
