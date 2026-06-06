package com.coursemapper.domain

import com.coursemapper.domain.model.CourseBuildSpec
import com.coursemapper.domain.model.RoutePoint
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class CourseCompositionEngineTest {

    private lateinit var calc: CumulativeDistanceCalculator
    private lateinit var engine: CourseCompositionEngine

    @Before
    fun setUp() {
        calc   = CumulativeDistanceCalculator()
        engine = CourseCompositionEngine(calc)
    }

    @Test
    fun `single lap with no target equals base distance`() {
        val base = straightRoute(0.0, 0.0, 0.0, 1.0, 10) // ~111 km
        val result = engine.compose(base, lapCount = 1, targetDistanceMetres = 0.0)
        assertEquals(1, result.laps.size)
        assertFalse(result.laps[0].isPartial)
    }

    @Test
    fun `four full laps quadruples distance`() {
        val base    = loopRoute(0.0, 0.0, 0.0, 0.01, 5)
        val baseLen = calc.pathDistanceMetres(base, smoothedOnly = false)
        val result  = engine.compose(base, lapCount = 4, targetDistanceMetres = 0.0)

        assertEquals(4, result.laps.size)
        assertTrue(result.laps.all { !it.isPartial })
        assertEquals(baseLen * 4, result.totalDistanceMetres, 1.0)
    }

    @Test
    fun `partial lap added when target exceeds full laps`() {
        val base    = loopRoute(0.0, 0.0, 0.0, 0.1, 10)
        val baseLen = calc.pathDistanceMetres(base, smoothedOnly = false)
        val target  = baseLen * 2 + baseLen / 2   // 2 full + 0.5 partial

        val result = engine.compose(base, lapCount = 2, targetDistanceMetres = target)

        assertEquals(3, result.laps.size)
        assertTrue(result.laps.last().isPartial)
        assertTrue(result.laps.last().distanceMetres < baseLen)
    }

    @Test
    fun `no partial lap when target exactly equals full laps total`() {
        val base    = loopRoute(0.0, 0.0, 0.0, 0.1, 10)
        val baseLen = calc.pathDistanceMetres(base, smoothedOnly = false)

        val result = engine.compose(base, lapCount = 3, targetDistanceMetres = baseLen * 3)

        assertEquals(3, result.laps.size)
        assertTrue(result.laps.all { !it.isPartial })
    }

    @Test
    fun `no partial lap when target is less than full laps total`() {
        val base    = loopRoute(0.0, 0.0, 0.0, 0.1, 10)
        val baseLen = calc.pathDistanceMetres(base, smoothedOnly = false)

        val result = engine.compose(base, lapCount = 3, targetDistanceMetres = baseLen * 2)

        assertEquals(3, result.laps.size)
        assertTrue(result.laps.all { !it.isPartial })
    }

    @Test
    fun `summary text includes lap count`() {
        val base   = loopRoute(0.0, 0.0, 0.0, 0.01, 3)
        val result = engine.compose(base, lapCount = 4, targetDistanceMetres = 0.0)
        assertTrue(result.summaryText.contains("4"))
    }

    @Test
    fun `summary text includes partial indicator when present`() {
        val base    = loopRoute(0.0, 0.0, 0.0, 0.1, 5)
        val baseLen = calc.pathDistanceMetres(base, smoothedOnly = false)
        val result  = engine.compose(base, lapCount = 2, targetDistanceMetres = baseLen * 2.5)
        assertTrue(result.summaryText.contains("+"))
    }

    @Test
    fun `composed polyline has more points than base for multi-lap`() {
        val base   = loopRoute(0.0, 0.0, 0.0, 0.01, 5)
        val result = engine.compose(base, lapCount = 3, targetDistanceMetres = 0.0)
        assertTrue(result.composedPoints.size >= base.size * 3)
    }

    @Test
    fun `lapCount must be at least 1`() {
        val base = straightRoute(0.0, 0.0, 0.0, 0.01, 3)
        try {
            engine.compose(base, lapCount = 0, targetDistanceMetres = 0.0)
            fail("Expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) { /* expected */ }
    }

    @Test
    fun `final distance matches laps sum within tolerance`() {
        val base    = loopRoute(0.0, 0.0, 0.0, 0.05, 20)
        val baseLen = calc.pathDistanceMetres(base, smoothedOnly = false)
        val result  = engine.compose(base, lapCount = 3, targetDistanceMetres = baseLen * 3.4)

        val expectedTotal = baseLen * 3 + result.laps.last().distanceMetres
        assertEquals(expectedTotal, result.totalDistanceMetres, 2.0)
    }

    // ─── Regression: multi-extra-lap composition ────────────────────────────────
    // A 10 km base route targeting 42.195 km must produce 4 full laps + one
    // partial lap - not silently cap at 1 partial lap beyond lapCount.

    @Test
    fun `target requiring more than one extra lap is composed correctly`() {
        // ~111.2 km per degree lat; 0.05 deg out and back → ~11.12 km loop
        val base    = loopRoute(0.0, 0.0, 0.05, 0.0, 20)
        val baseLen = calc.pathDistanceMetres(base, smoothedOnly = false)

        // Target = 4.5 laps: 4 full + 1 partial (half a lap)
        val target = baseLen * 4.5
        val result = engine.compose(base, lapCount = 1, targetDistanceMetres = target)

        // Must have 4 full laps + 1 partial = 5 laps total
        assertEquals(5, result.laps.size)
        assertTrue("Last lap must be partial", result.laps.last().isPartial)
        assertTrue("Non-last laps must be full", result.laps.dropLast(1).all { !it.isPartial })
        assertEquals(target, result.totalDistanceMetres, baseLen * 0.01)
    }

    @Test
    fun `target shorter than single lap produces single full lap`() {
        val base    = straightRoute(0.0, 0.0, 0.1, 0.0, 20)
        val baseLen = calc.pathDistanceMetres(base, smoothedOnly = false)

        // Target < baseLen - engine must produce exactly 1 full lap, no partial
        val result = engine.compose(base, lapCount = 1, targetDistanceMetres = baseLen * 0.5)

        assertEquals(1, result.laps.size)
        assertFalse(result.laps[0].isPartial)
        assertEquals(baseLen, result.totalDistanceMetres, 1.0)
    }

    @Test
    fun `marathon from 10k base uses spec with lapCount 1 and target 42195`() {
        // Simulate the concrete marathon use-case: 10 km base, 42.195 km target.
        // With lapCount=1 the engine should compute 4 full laps + 2.195 km partial.
        val base    = loopRoute(0.0, 0.0, 0.0449, 0.0, 50) // ~10 km loop
        val baseLen = calc.pathDistanceMetres(base, smoothedOnly = false)

        val marathonM = 42_195.0
        val result = engine.compose(base, lapCount = 1, targetDistanceMetres = marathonM)

        val expectedFullLaps = (marathonM / baseLen).toInt()   // should be 4
        assertEquals(expectedFullLaps + 1, result.laps.size)   // 4 full + 1 partial
        assertTrue(result.laps.last().isPartial)
        assertEquals(marathonM, result.totalDistanceMetres, baseLen * 0.02)
    }

    @Test
    fun `declared total always equals the measured composed polyline`() {
        for (gapDegrees in listOf(0.0, 0.00005, 0.0002, 0.001)) {   // ~0 m .. ~111 m
            val base   = loopRoute(0.0, 0.0, 0.0, 0.01, 20, endGapDegrees = gapDegrees)
            val result = engine.compose(base, lapCount = 4, targetDistanceMetres = 0.0)
            val actual = calc.pathDistanceMetres(result.composedPoints, smoothedOnly = false)
            assertEquals(
                "gap=$gapDegrees deg: declared total must match the drawn polyline",
                actual, result.totalDistanceMetres, 0.5
            )
        }
    }

    @Test
    fun `a loop recorded with slop is closed and every lap is the same length`() {
        val base   = loopRoute(0.0, 0.0, 0.0, 0.01, 20, endGapDegrees = 0.0001) // ~11 m
        val result = engine.compose(base, lapCount = 3, targetDistanceMetres = 0.0)

        val closure = result.loopClosure
        assertTrue("expected Closed, got $closure", closure is CourseCompositionEngine.LoopClosure.Closed)
        assertEquals(1, result.laps.map { it.distanceMetres }.distinct().size)
        assertEquals(result.lapTemplatePoints.last().lat, result.lapTemplatePoints.first().lat, 1e-12)
        assertEquals(result.lapTemplatePoints.last().lon, result.lapTemplatePoints.first().lon, 1e-12)
    }

    @Test
    fun `an already closed loop is left untouched`() {
        val base   = loopRoute(0.0, 0.0, 0.0, 0.01, 20)
        val result = engine.compose(base, lapCount = 3, targetDistanceMetres = 0.0)
        assertEquals(
            CourseCompositionEngine.LoopClosure.AlreadyClosed,
            result.loopClosure
        )
        assertEquals(base.size, result.lapTemplatePoints.size)
    }

    @Test
    fun `a point-to-point route repeated is reported as suspect`() {
        val base   = straightRoute(0.0, 0.0, 0.0, 0.01, 20)   // ~1.1 km, endpoints far apart
        val result = engine.compose(base, lapCount = 2, targetDistanceMetres = 0.0)

        val closure = result.loopClosure
        assertTrue("expected SuspectOpenRoute, got $closure",
            closure is CourseCompositionEngine.LoopClosure.SuspectOpenRoute)
        // Still well-formed: the declared total matches what will be drawn.
        assertEquals(
            calc.pathDistanceMetres(result.composedPoints, smoothedOnly = false),
            result.totalDistanceMetres, 0.5
        )
    }

    /** A city race with start and finish a block apart is lappable, point to point is flagged. */
    @Test
    fun `loop closure tolerance separates a block-apart start from an open route`() {
        val metresPerDegree = CumulativeDistanceCalculator.EARTH_RADIUS_M * Math.PI / 180.0
        val under = 0.8 * CourseCompositionEngine.LOOP_CLOSURE_TOLERANCE_METRES / metresPerDegree
        val over  = 1.25 * CourseCompositionEngine.LOOP_CLOSURE_TOLERANCE_METRES / metresPerDegree

        val quiet = engine.compose(
            loopRoute(0.0, 0.0, 0.0, 0.01, 20, endGapDegrees = under),
            lapCount = 2, targetDistanceMetres = 0.0
        ).loopClosure
        assertTrue("a gap inside tolerance must stay quiet, got $quiet",
            quiet is CourseCompositionEngine.LoopClosure.Closed)

        val warns = engine.compose(
            loopRoute(0.0, 0.0, 0.0, 0.01, 20, endGapDegrees = over),
            lapCount = 2, targetDistanceMetres = 0.0
        ).loopClosure
        assertTrue("a gap beyond tolerance must warn, got $warns",
            warns is CourseCompositionEngine.LoopClosure.SuspectOpenRoute)
    }

    @Test
    fun `a single lap point-to-point course is never closed`() {
        val base   = straightRoute(0.0, 0.0, 0.0, 0.01, 20)
        val result = engine.compose(base, lapCount = 1, targetDistanceMetres = 0.0)

        assertEquals(CourseCompositionEngine.LoopClosure.NotRepeated, result.loopClosure)
        assertEquals(base.size, result.lapTemplatePoints.size)
        assertEquals(
            calc.pathDistanceMetres(base, smoothedOnly = false),
            result.totalDistanceMetres, 0.001
        )
    }

    // summarize() must report exactly what compose() would, for every mode

    @Test
    fun `summarize matches compose for fixed laps`() {
        val base = loopRoute(0.0, 0.0, 0.0, 0.01, 5)
        assertSummaryMatchesCompose(base, CourseBuildSpec.FixedLaps(4))
    }

    @Test
    fun `summarize matches compose for a target distance with a partial`() {
        val base    = loopRoute(0.0, 0.0, 0.0, 0.05, 10)
        val baseLen = calc.pathDistanceMetres(base, smoothedOnly = false)
        assertSummaryMatchesCompose(
            base,
            CourseBuildSpec.TargetDistance(totalMetres = baseLen * 2.5, lapCount = 1)
        )
    }

    @Test
    fun `summarize matches compose for manual laps plus partial`() {
        val base    = loopRoute(0.0, 0.0, 0.0, 0.05, 10)
        val baseLen = calc.pathDistanceMetres(base, smoothedOnly = false)
        assertSummaryMatchesCompose(
            base,
            CourseBuildSpec.Manual(lapCount = 2, finalLapDistanceMetres = baseLen / 3)
        )
    }

    @Test
    fun `summarize matches compose for an unclosed route`() {
        // 0.005 degrees of latitude ≈ 555 m - well beyond the closure tolerance.
        val base = loopRoute(0.0, 0.0, 0.0, 0.05, 10, endGapDegrees = 0.005)
        assertSummaryMatchesCompose(base, CourseBuildSpec.FixedLaps(3))
    }

    @Test
    fun `summarize matches compose for a single unrepeated lap`() {
        val base = straightRoute(0.0, 0.0, 0.0, 0.01, 20)
        assertSummaryMatchesCompose(base, CourseBuildSpec.FixedLaps(1))
    }

    @Test
    fun `summarize reports the extra laps a target distance forces`() {
        // The case the feature exists for: one 10 km lap imported from GPX,
        // composed to a marathon.
        val base    = loopRoute(0.0, 0.0, 0.0, 0.045, 40)
        val baseLen = calc.pathDistanceMetres(base, smoothedOnly = false)
        val summary = engine.summarize(
            base,
            CourseBuildSpec.TargetDistance(totalMetres = 42_195.0, lapCount = 1)
        )

        // More laps than the caller asked for, and the total lands on the target.
        assertTrue(
            "expected several laps of a ${baseLen}m loop, got ${summary.fullLapCount}",
            summary.fullLapCount > 1
        )
        assertEquals(42_195.0, summary.totalDistanceMetres, 0.5)
        assertTrue(summary.partialDistanceMetres > 0.0)
    }

    private fun assertSummaryMatchesCompose(
        base: List<RoutePoint>,
        spec: CourseBuildSpec
    ) {
        val composed = engine.compose(base, spec)
        val summary  = engine.summarize(base, spec)

        assertEquals(
            "full lap count", composed.laps.count { !it.isPartial }, summary.fullLapCount
        )
        assertEquals(
            "partial distance",
            composed.laps.lastOrNull()?.takeIf { it.isPartial }?.distanceMetres ?: 0.0,
            summary.partialDistanceMetres, 0.001
        )
        assertEquals(
            "total distance", composed.totalDistanceMetres, summary.totalDistanceMetres, 0.001
        )
        assertEquals("summary text", composed.summaryText, summary.summaryText)
        assertEquals("loop closure", composed.loopClosure, summary.loopClosure)
        assertEquals(
            "lap distance",
            calc.pathDistanceMetres(composed.lapTemplatePoints, smoothedOnly = false),
            summary.lapDistanceMetres, 0.001
        )
    }

    /** Build a straight-line route from (lat1,lon1) to (lat2,lon2) with [n] points. */
    private fun straightRoute(
        lat1: Double, lon1: Double,
        lat2: Double, lon2: Double,
        n: Int
    ): List<RoutePoint> {
        require(n >= 2)
        return (0 until n).map { i ->
            val t = i.toDouble() / (n - 1)
            RoutePoint(
                lat            = lat1 + t * (lat2 - lat1),
                lon            = lon1 + t * (lon2 - lon1),
                altMetres      = null,
                accuracyMetres = null,
                timestampMs    = i.toLong() * 1_000,
                isSmoothed     = true
            )
        }
    }

    /**
     * Out-and-back loop, total is twice the one-way distance. [endGapDegrees]
     * moves the last point to simulate stopping short of the start.
     */
    private fun loopRoute(
        lat1: Double, lon1: Double,
        lat2: Double, lon2: Double,
        n: Int,
        endGapDegrees: Double = 0.0
    ): List<RoutePoint> {
        val out  = straightRoute(lat1, lon1, lat2, lon2, n)
        val back = straightRoute(lat2, lon2, lat1 + endGapDegrees, lon1, n).drop(1)
        return (out + back).mapIndexed { i, p -> p.copy(timestampMs = i.toLong() * 1_000) }
    }
}
