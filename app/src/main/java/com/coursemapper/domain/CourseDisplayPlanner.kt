package com.coursemapper.domain

import com.coursemapper.domain.model.RoutePoint
import javax.inject.Inject
import javax.inject.Singleton

/**
 * What to draw for several courses on one map.
 *
 * Courses share streets, and drawn as-is that's several GPS traces weaving
 * across each other. So where [CorridorAnalyzer] finds shared ground, only the
 * longest course supplies the geometry, drawn as a ribbon that cycles through
 * the participants' colours every [DEFAULT_CHUNK_METRES]. Stored geometry is
 * never touched - a snapped half wouldn't be 21.0975 km anymore.
 *
 * Solo stretches are extended to the ribbon's entry and exit so they meet.
 * Output is grouped per course so each course is one static-paint layer (see
 * `MapOverlayManager`), no data driven styling.
 */
@Singleton
class CourseDisplayPlanner @Inject constructor(
    private val calc: CumulativeDistanceCalculator,
    private val analyzer: CorridorAnalyzer
) {

    companion object {
        /** One colour's length in a ribbon, reads as road at placement zoom. */
        const val DEFAULT_CHUNK_METRES = 40.0

        /** Floor on short corridors so every course gets at least one chunk. */
        const val MIN_CHUNK_METRES = 10.0

        /** Overlap at chunk joins so no basemap shows between colours. */
        const val CHUNK_OVERLAP_METRES = 1.0

        /** Stretches shorter than this are not worth a path of their own. */
        const val MIN_DRAWN_METRES = 2.0
    }

    /** Everything drawn in one course's colour. Solo and ribbon are kept apart for different zoom bounds. */
    data class CoursePaths(
        val courseId: Long,
        /** Stretches this course runs alone. */
        val soloPaths: List<List<Pair<Double, Double>>>,
        /** This course's ribbon chunks at the finest length. */
        val ribbonPaths: List<List<Pair<Double, Double>>>,
        /** The same ribbons chunked at each coarser length, see [com.coursemapper.map.RibbonLod]. */
        val coarseRibbonBands: List<List<List<Pair<Double, Double>>>> = emptyList()
    ) {
        /** Everything drawn in this course's colour. */
        val paths: List<List<Pair<Double, Double>>> get() = soloPaths + ribbonPaths

        /** Every level of detail, finest first. */
        val ribbonBands: List<List<List<Pair<Double, Double>>>>
            get() = listOf(ribbonPaths) + coarseRibbonBands
    }

    /** A whole shared stretch with its courses, for the offset lanes at overview zoom. */
    data class SharedCorridor(
        val path: List<Pair<Double, Double>>,
        /** Participating course ids, in the order the ribbon cycles them. */
        val courseIds: List<Long>
    )

    /** The full picture for one map. */
    data class Plan(
        val coursePaths: List<CoursePaths>,
        /** Whole shared ribbons, for lanes and the backing at overview zoom. */
        val sharedCorridorPaths: List<SharedCorridor>,
        /** The corridors behind this plan; empty when nothing is shared. */
        val analysis: CorridorAnalyzer.Analysis
    ) {
        /** Just the geometry of [sharedCorridorPaths]. */
        val sharedCasings: List<List<Pair<Double, Double>>>
            get() = sharedCorridorPaths.map { it.path }

        val hasSharedGround: Boolean get() = sharedCorridorPaths.isNotEmpty()
    }

    /**
     * Plan the drawing of [courses].
     *
     * @param chunkMetres chunk lengths, finest first. More than one gives LOD
     *   copies, the corridor analysis still runs once
     * @param unifyShared false draws every course whole
     */
    fun plan(
        courses: List<CorridorAnalyzer.IndexedCourse>,
        toleranceMetres: Double = CorridorAnalyzer.ENTER_TOLERANCE_METRES,
        chunkMetres: List<Double> = listOf(DEFAULT_CHUNK_METRES),
        unifyShared: Boolean = true
    ): Plan {
        val chunkLengths = chunkMetres.ifEmpty { listOf(DEFAULT_CHUNK_METRES) }
        if (courses.isEmpty()) return Plan(emptyList(), emptyList(), CorridorAnalyzer.Analysis.EMPTY)

        // present and empty, bands are index-aligned with chunkLengths
        val emptyCoarseBands = List(chunkLengths.size - 1) { emptyList<List<Pair<Double, Double>>>() }
        if (!unifyShared || courses.size < 2) {
            return Plan(
                coursePaths = courses.map {
                    CoursePaths(it.courseId, listOf(it.index.wholePath()), emptyList(), emptyCoarseBands)
                },
                sharedCorridorPaths = emptyList(),
                analysis = CorridorAnalyzer.Analysis.EMPTY
            )
        }

        val analysis = analyzer.analyze(courses, toleranceMetres)
        if (analysis.corridors.isEmpty()) {
            return Plan(
                coursePaths = courses.map {
                    CoursePaths(it.courseId, listOf(it.index.wholePath()), emptyList(), emptyCoarseBands)
                },
                sharedCorridorPaths = emptyList(),
                analysis = analysis
            )
        }

        val byId = courses.associateBy { it.courseId }
        val solo = courses.associate { it.courseId to mutableListOf<List<Pair<Double, Double>>>() }
        // One bucket per course per level of detail.
        val ribbonBands = chunkLengths.map { _ ->
            courses.associate { it.courseId to mutableListOf<List<Pair<Double, Double>>>() }
        }
        val casings = mutableListOf<SharedCorridor>()

        for (course in courses) {
            val corridors = analysis.corridorsFor(course.courseId)

            // Everything not inside a corridor stays this course's own line.
            soloIntervals(course.index.lengthMetres, corridors).forEach { stretch ->
                val path = course.index.slice(stretch.from, stretch.to)
                if (path.size >= 2) {
                    solo.getValue(course.courseId).add(
                        stitchToRibbons(path, stretch, byId)
                    )
                }
            }

            // Corridors this course leads: chunk them out among the participants,
            // once per level of detail.  All bands cut the same corridor, so the
            // ground they cover is identical and only the piece length differs.
            corridors.filter { it.isPrimary }.forEach { corridor ->
                val whole = course.index.slice(corridor.fromMetres, corridor.toMetres)
                if (whole.size >= 2) casings.add(SharedCorridor(whole, corridor.courseIds))
                chunkLengths.forEachIndexed { band, length ->
                    chunk(course.index, corridor, length).forEach { (courseId, path) ->
                        if (path.size >= 2) ribbonBands[band].getValue(courseId).add(path)
                    }
                }
            }
            // corridors led by another course are drawn by that course's ribbon
        }

        return Plan(
            coursePaths = courses.map { course ->
                CoursePaths(
                    courseId = course.courseId,
                    soloPaths = solo.getValue(course.courseId),
                    ribbonPaths = ribbonBands.first().getValue(course.courseId),
                    coarseRibbonBands = ribbonBands.drop(1).map { it.getValue(course.courseId) }
                )
            },
            sharedCorridorPaths = casings,
            analysis = analysis
        )
    }

    /** A stretch of a course that no corridor covers. */
    private data class Solo(
        val from: Double,
        val to: Double,
        /** The corridor immediately before this stretch, if any. */
        val before: CorridorAnalyzer.Corridor?,
        /** The corridor immediately after this stretch, if any. */
        val after: CorridorAnalyzer.Corridor?
    )

    private fun soloIntervals(
        lengthMetres: Double,
        corridors: List<CorridorAnalyzer.Corridor>
    ): List<Solo> {
        if (corridors.isEmpty()) return listOf(Solo(0.0, lengthMetres, null, null))

        val out = mutableListOf<Solo>()
        var cursor = 0.0
        var previous: CorridorAnalyzer.Corridor? = null

        for (corridor in corridors) {
            if (corridor.fromMetres - cursor >= MIN_DRAWN_METRES) {
                out += Solo(cursor, corridor.fromMetres, previous, corridor)
            }
            cursor = corridor.toMetres
            previous = corridor
        }
        if (lengthMetres - cursor >= MIN_DRAWN_METRES) {
            out += Solo(cursor, lengthMetres, previous, null)
        }
        return out
    }

    /** Extend a solo stretch to meet ribbons led by other courses. */
    private fun stitchToRibbons(
        path: List<Pair<Double, Double>>,
        stretch: Solo,
        byId: Map<Long, CorridorAnalyzer.IndexedCourse>
    ): List<Pair<Double, Double>> {
        var out = path
        stretch.before?.takeIf { !it.isPrimary }?.let { corridor ->
            ribbonEndpoint(corridor, byId, atStart = false)?.let { out = listOf(it) + out }
        }
        stretch.after?.takeIf { !it.isPrimary }?.let { corridor ->
            ribbonEndpoint(corridor, byId, atStart = true)?.let { out = out + it }
        }
        return out
    }

    /** Where the ribbon for [corridor] starts or ends on the leading course. */
    private fun ribbonEndpoint(
        corridor: CorridorAnalyzer.Corridor,
        byId: Map<Long, CorridorAnalyzer.IndexedCourse>,
        atStart: Boolean
    ): Pair<Double, Double>? {
        val leaderId = leaderOf(corridor, byId) ?: return null
        val member = corridor.partners.firstOrNull { it.courseId == leaderId } ?: return null
        val leader = byId[leaderId] ?: return null
        // A member running the corridor backwards enters at its own high end.
        val enterAtFrom = atStart != member.reversed
        return leader.index.pointAt(if (enterAtFrom) member.fromMetres else member.toMetres)
    }

    /** The participant that draws [corridor] - the longest course taking part. */
    private fun leaderOf(
        corridor: CorridorAnalyzer.Corridor,
        byId: Map<Long, CorridorAnalyzer.IndexedCourse>
    ): Long? = corridor.courseIds.maxWithOrNull(
        compareBy({ byId[it]?.index?.lengthMetres ?: 0.0 }, { -it })
    )

    /** Cut a corridor into alternating chunks, shortened so every course gets one. */
    private fun chunk(
        index: PolylineIndex,
        corridor: CorridorAnalyzer.Corridor,
        chunkMetres: Double
    ): List<Pair<Long, List<Pair<Double, Double>>>> {
        val participants = corridor.courseIds
        val total = corridor.lengthMetres
        if (total < MIN_DRAWN_METRES || participants.isEmpty()) return emptyList()

        val step = minOf(chunkMetres, total / participants.size)
            .coerceAtLeast(MIN_CHUNK_METRES)
            .coerceAtMost(total)

        val out = mutableListOf<Pair<Long, List<Pair<Double, Double>>>>()
        var k = 0
        var cursor = corridor.fromMetres
        while (cursor < corridor.toMetres - 0.001) {
            val end = minOf(cursor + step, corridor.toMetres)
            // Overlap into the next chunk so the colour change has no seam.
            val drawnEnd = minOf(end + CHUNK_OVERLAP_METRES, corridor.toMetres)
            out += participants[k % participants.size] to index.slice(cursor, drawnEnd)
            cursor = end
            k++
        }
        return out
    }

    private fun PolylineIndex.wholePath(): List<Pair<Double, Double>> =
        points.map { it.lat to it.lon }

    /** Polyline between two distances with both ends interpolated, so chunks don't gap or overlap. */
    private fun PolylineIndex.slice(fromMetres: Double, toMetres: Double): List<Pair<Double, Double>> {
        val from = fromMetres.coerceIn(0.0, lengthMetres)
        val to = toMetres.coerceIn(0.0, lengthMetres)
        if (to - from < MIN_DRAWN_METRES) return emptyList()

        val out = mutableListOf<Pair<Double, Double>>()
        out += pointAt(from)
        for (i in points.indices) {
            val d = cumulativeAt(i)
            if (d > from && d < to) out += points[i].lat to points[i].lon
        }
        out += pointAt(to)
        return out.dedupeConsecutive()
    }

    private fun List<Pair<Double, Double>>.dedupeConsecutive(): List<Pair<Double, Double>> {
        if (size < 2) return this
        val out = ArrayList<Pair<Double, Double>>(size)
        out += this[0]
        for (i in 1 until size) {
            val previous = out.last()
            if (calc.haversineMetres(previous.first, previous.second, this[i].first, this[i].second) > 0.05) {
                out += this[i]
            }
        }
        return out
    }

    /** Convenience for callers holding raw geometry rather than indices. */
    fun index(courseId: Long, points: List<RoutePoint>): CorridorAnalyzer.IndexedCourse? =
        PolylineIndex.build(points, calc)?.let { CorridorAnalyzer.IndexedCourse(courseId, it) }
}
