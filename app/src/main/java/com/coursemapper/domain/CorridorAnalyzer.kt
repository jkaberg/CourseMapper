package com.coursemapper.domain

import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

/**
 * Finds stretches where courses run along the same ground.
 *
 * Shared means within [ENTER_TOLERANCE_METRES], same heading (either way, so
 * out-and-backs count and get [CorridorMember.reversed]), for at least
 * [MIN_RUN_METRES]. Distance alone would call every crossing a corridor.
 * Closes at [EXIT_TOLERANCE_METRES] (hysteresis) and gaps under
 * [MAX_GAP_METRES] are bridged.
 *
 * Results are per course: intervals along that course, each with the exact set
 * of courses sharing it. Merging pairwise results into per-corridor objects
 * over-reports. The longest participant is [Corridor.isPrimary] so each ribbon
 * is drawn once.
 *
 * Geometry only, never changes courses. A course is never matched against
 * itself, laps would make it all "shared".
 */
@Singleton
class CorridorAnalyzer @Inject constructor(
    private val calc: CumulativeDistanceCalculator
) {

    companion object {
        /** Spacing of the walk along each course, in metres. */
        const val SAMPLE_STEP_METRES = 5.0

        /** Offset at which a corridor opens. Matches the marker clustering default. */
        const val ENTER_TOLERANCE_METRES = 10.0

        /** Offset at which an open corridor closes.  Must exceed the enter value. */
        const val EXIT_TOLERANCE_METRES = 15.0

        /** Largest heading disagreement still called "the same direction". */
        const val HEADING_TOLERANCE_DEG = 35.0

        /** About 11 s at ATV speed, shorter is usually a course angling across another. */
        const val MIN_RUN_METRES = 60.0

        /**
         * Sized so a corridor survives a corner - bearings are measured over
         * [PolylineIndex.BEARING_WINDOW_METRES], so a turn reads non-parallel for
         * about that on the way out and back.
         */
        const val MAX_GAP_METRES = 60.0

        /** Shorter sub-intervals get absorbed into a neighbour. */
        const val MIN_SLIVER_METRES = 15.0

        /**
         * Max jump along the partner between samples, in [SAMPLE_STEP_METRES].
         * On a lapped partner consecutive samples can land on different laps.
         */
        const val CONTINUITY_STEP_FACTOR = 3.0
    }

    /** A course polyline prepared for analysis. */
    data class IndexedCourse(
        val courseId: Long,
        val index: PolylineIndex
    )

    /** Another course on a [Corridor], with its own range so it can be sliced directly. */
    data class CorridorMember(
        val courseId: Long,
        val fromMetres: Double,
        val toMetres: Double,
        /** True when this course runs the corridor against the owner's direction. */
        val reversed: Boolean
    ) {
        val lengthMetres: Double get() = toMetres - fromMetres
    }

    /** Stretch of [courseId] shared with other courses, [fromMetres]/[toMetres] along it. */
    data class Corridor(
        /** The course whose geometry this interval is expressed on. */
        val courseId: Long,
        val fromMetres: Double,
        val toMetres: Double,
        /** The other courses sharing this ground.  Never empty. */
        val partners: List<CorridorMember>,
        /** True on the longest participant only, so each ribbon is drawn once. */
        val isPrimary: Boolean
    ) {
        val lengthMetres: Double get() = toMetres - fromMetres

        /** How many courses run here, including [courseId]. */
        val courseCount: Int get() = partners.size + 1

        /** Every course running here, including [courseId], ascending. */
        val courseIds: List<Long>
            get() = (partners.map { it.courseId } + courseId).sorted()
    }

    /** Everything [analyze] found. */
    data class Analysis(val corridors: List<Corridor>) {

        /** Intervals expressed on [courseId]'s own geometry. */
        fun corridorsFor(courseId: Long): List<Corridor> =
            corridors.filter { it.courseId == courseId }

        /** One entry per shared stretch - the set a renderer should draw. */
        val primaryCorridors: List<Corridor> get() = corridors.filter { it.isPrimary }

        companion object {
            val EMPTY = Analysis(emptyList())
        }
    }

    /**
     * Every corridor among [courses]. [toleranceMetres] overrides
     * [ENTER_TOLERANCE_METRES], exit scales with it.
     */
    fun analyze(
        courses: List<IndexedCourse>,
        toleranceMetres: Double = ENTER_TOLERANCE_METRES
    ): Analysis {
        if (courses.size < 2) return Analysis.EMPTY

        val enter = toleranceMetres.coerceAtLeast(0.5)
        val exit = enter * (EXIT_TOLERANCE_METRES / ENTER_TOLERANCE_METRES)

        // Ranking decides which participant draws a shared stretch: longest
        // course first, course id as a deterministic tie-break so two courses of
        // identical length cannot swap roles between runs.
        val lengthById = courses.associate { it.courseId to it.index.lengthMetres }
        val byRank = compareBy<Long>({ lengthById[it] ?: 0.0 }, { -it })

        val out = mutableListOf<Corridor>()
        for (course in courses) {
            val partners = courses.filter { it.courseId != course.courseId }
            val runsByPartner = partners.associate { partner ->
                partner.courseId to partnerRuns(course, partner, enter, exit)
            }.filterValues { it.isNotEmpty() }

            if (runsByPartner.isEmpty()) continue

            out += decompose(course.courseId, runsByPartner)
                .map { corridor ->
                    val leader = corridor.courseIds.maxWithOrNull(byRank)
                    corridor.copy(isPrimary = leader == course.courseId)
                }
        }
        return Analysis(out.sortedWith(compareBy({ it.courseId }, { it.fromMetres })))
    }

    /** A stretch of the owner course that coincides with one partner. */
    private class PartnerRun(
        val partnerCourseId: Long,
        val ownFrom: Double,
        partnerStart: Double
    ) {
        var ownTo: Double = ownFrom
        var partnerFirst: Double = partnerStart
        var partnerLast: Double = partnerStart

        fun extend(ownMetres: Double, partnerMetres: Double) {
            ownTo = ownMetres
            partnerLast = partnerMetres
        }

        val ownLength: Double get() = ownTo - ownFrom
        val reversed: Boolean get() = partnerLast < partnerFirst
        val partnerFrom: Double get() = minOf(partnerFirst, partnerLast)
        val partnerTo: Double get() = maxOf(partnerFirst, partnerLast)
    }

    /** Stretches of [course] along [partner] after hysteresis, bridging and length filter. */
    private fun partnerRuns(
        course: IndexedCourse,
        partner: IndexedCourse,
        enter: Double,
        exit: Double
    ): List<PartnerRun> {
        val continuityLimit = SAMPLE_STEP_METRES * CONTINUITY_STEP_FACTOR
        val closed = mutableListOf<PartnerRun>()
        var current: PartnerRun? = null

        for (sample in course.index.sample(SAMPLE_STEP_METRES)) {
            // An engaged partner is tested at the wider exit tolerance: the
            // point of the hysteresis is that leaving takes more than arriving.
            val tolerance = if (current != null) exit else enter
            val hit = partner.index.nearest(sample.lat, sample.lon, tolerance)
                ?.takeIf { headingsAgree(course, sample.cumulativeMetres, partner, it.cumulativeMetres) }

            val active = current
            when {
                hit == null -> {
                    active?.let { closed += it }
                    current = null
                }
                active != null &&
                    abs(hit.cumulativeMetres - active.partnerLast) <= continuityLimit -> {
                    active.extend(sample.cumulativeMetres, hit.cumulativeMetres)
                }
                else -> {
                    active?.let { closed += it }
                    current = PartnerRun(
                        partner.courseId, sample.cumulativeMetres, hit.cumulativeMetres
                    )
                }
            }
        }
        current?.let { closed += it }

        return bridgeAndFilter(closed)
    }

    /** Do the two courses point the same way (or exactly opposite) here? */
    private fun headingsAgree(
        course: IndexedCourse,
        ownMetres: Double,
        partner: IndexedCourse,
        partnerMetres: Double
    ): Boolean {
        val delta = CumulativeDistanceCalculator.bearingDeltaDegrees(
            course.index.bearingAt(ownMetres),
            partner.index.bearingAt(partnerMetres)
        )
        // Same way, or exactly opposite (an out-and-back leg shares the road).
        return delta <= HEADING_TOLERANCE_DEG || delta >= 180.0 - HEADING_TOLERANCE_DEG
    }

    /**
     * Bridge short interruptions and drop what's still too short.
     *
     * The gap must be short on both courses. A course taking a link road and
     * rejoining the partner kilometres later has a short own gap but not a
     * partner gap - bridging that turned 70 m of the 10 km into a corridor with
     * 21 km of the marathon and hid parts of the 10 km.
     */
    private fun bridgeAndFilter(runs: List<PartnerRun>): List<PartnerRun> {
        val merged = mutableListOf<PartnerRun>()
        for (run in runs.sortedBy { it.ownFrom }) {
            val previous = merged.lastOrNull()
            if (previous != null &&
                run.ownFrom - previous.ownTo <= MAX_GAP_METRES &&
                run.reversed == previous.reversed &&
                abs(run.partnerFirst - previous.partnerLast) <= MAX_GAP_METRES
            ) {
                previous.ownTo = maxOf(previous.ownTo, run.ownTo)
                previous.partnerLast = run.partnerLast
            } else {
                merged += run
            }
        }
        return merged.filter { it.ownLength >= MIN_RUN_METRES }
    }

    /** Per-partner runs to non-overlapping intervals with their exact partner sets. */
    private fun decompose(
        courseId: Long,
        runsByPartner: Map<Long, List<PartnerRun>>
    ): List<Corridor> {
        val allRuns = runsByPartner.values.flatten()
        val cuts = sortedSetOf<Double>()
        allRuns.forEach { cuts.add(it.ownFrom); cuts.add(it.ownTo) }

        val bounds = cuts.toList()
        val pieces = mutableListOf<Corridor>()

        for (i in 0 until bounds.lastIndex) {
            val from = bounds[i]
            val to = bounds[i + 1]
            if (to - from <= 0.0) continue
            val midpoint = (from + to) / 2.0

            val partners = allRuns
                .filter { midpoint > it.ownFrom && midpoint < it.ownTo }
                .map { run -> run.memberFor(from, to) }
                .sortedBy { it.courseId }

            if (partners.isEmpty()) continue

            val previous = pieces.lastOrNull()
            if (previous != null &&
                previous.toMetres == from &&
                previous.partners.map { it.courseId } == partners.map { it.courseId }
            ) {
                // Same membership either side of a cut that belonged to someone
                // else's interval - one entry, not two.
                pieces[pieces.lastIndex] = previous.copy(
                    toMetres = to,
                    partners = previous.partners.zip(partners) { a, b ->
                        a.copy(
                            fromMetres = minOf(a.fromMetres, b.fromMetres),
                            toMetres = maxOf(a.toMetres, b.toMetres)
                        )
                    }
                )
            } else {
                pieces += Corridor(courseId, from, to, partners, isPrimary = false)
            }
        }

        return absorbSlivers(pieces)
    }

    /** Partner range over [from]..[to], linear is fine since they're near parallel. */
    private fun PartnerRun.memberFor(from: Double, to: Double): CorridorMember {
        val span = ownLength
        val startFraction = if (span > 0.0) ((from - ownFrom) / span).coerceIn(0.0, 1.0) else 0.0
        val endFraction = if (span > 0.0) ((to - ownFrom) / span).coerceIn(0.0, 1.0) else 1.0
        val partnerSpan = partnerTo - partnerFrom

        return if (reversed) {
            CorridorMember(
                courseId = partnerCourseId,
                fromMetres = partnerTo - endFraction * partnerSpan,
                toMetres = partnerTo - startFraction * partnerSpan,
                reversed = true
            )
        } else {
            CorridorMember(
                courseId = partnerCourseId,
                fromMetres = partnerFrom + startFraction * partnerSpan,
                toMetres = partnerFrom + endFraction * partnerSpan,
                reversed = false
            )
        }
    }

    /**
     * Fold slivers under [MIN_SLIVER_METRES] into the previous interval, or the
     * next one if there's a gap before. Isolated slivers are dropped.
     */
    private fun absorbSlivers(pieces: List<Corridor>): List<Corridor> {
        val out = mutableListOf<Corridor>()
        var pending: Corridor? = null

        for (piece in pieces) {
            if (piece.lengthMetres < MIN_SLIVER_METRES) {
                val previous = out.lastOrNull()
                if (previous != null && previous.toMetres == piece.fromMetres) {
                    out[out.lastIndex] = previous.copy(toMetres = piece.toMetres)
                } else {
                    val held = pending
                    pending = if (held != null && held.toMetres == piece.fromMetres) {
                        held.copy(toMetres = piece.toMetres)
                    } else {
                        piece
                    }
                }
                continue
            }

            val held = pending
            out += if (held != null && held.toMetres == piece.fromMetres) {
                piece.copy(fromMetres = held.fromMetres)
            } else {
                piece
            }
            pending = null
        }
        return out
    }
}
