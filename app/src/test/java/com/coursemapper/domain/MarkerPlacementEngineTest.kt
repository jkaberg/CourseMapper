package com.coursemapper.domain

import com.coursemapper.domain.model.MarkerPreset
import com.coursemapper.domain.model.MarkerRule
import com.coursemapper.domain.model.MarkerType
import com.coursemapper.domain.model.RoutePoint
import com.coursemapper.domain.model.withEndpoints
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class MarkerPlacementEngineTest {

    private lateinit var calc: CumulativeDistanceCalculator
    private lateinit var compositionEngine: CourseCompositionEngine
    private lateinit var placementEngine: MarkerPlacementEngine
    private lateinit var placementPlanner: PlacementPlanner

    @Before
    fun setUp() {
        calc = CumulativeDistanceCalculator()
        compositionEngine = CourseCompositionEngine(calc)
        placementEngine = MarkerPlacementEngine(calc)
        placementPlanner = PlacementPlanner(calc)
    }

    private val kmPreset = MarkerPreset(
        id = 1L,
        name = "1 km markers",
        rules = listOf(MarkerRule(type = MarkerType.DISTANCE, intervalMetres = 1_000.0)),
        createdAt = 0L,
        updatedAt = 0L
    )

    /** The 3 km sign stands where the runner has covered 3 km, wherever the laps end. */
    @Test
    fun `interval markers land on true cumulative kilometres across laps`() {
        val baseRoute = loopRoute(lengthMetres = 2_600.0, pointsPerLeg = 14)
        val composition = compositionEngine.compose(
            basePoints = baseRoute,
            lapCount = 2,
            targetDistanceMetres = 0.0
        )

        val markers = placementEngine.place(
            composedPoints = composition.composedPoints,
            totalDistanceMetres = composition.totalDistanceMetres,
            preset = kmPreset,
            lapTemplatePoints = baseRoute,
            laps = composition.laps
        ).markers

        assertEquals(
            listOf(1_000.0, 2_000.0, 3_000.0, 4_000.0, 5_000.0),
            markers.map { roundedHundreds(it.cumulativeDistanceMetres) }
        )
    }

    /** A lap boundary is a km like any other, 2 km lap x3 still gets 2 and 4 km. */
    @Test
    fun `kilometres falling on a lap boundary are not skipped`() {
        val baseRoute = loopRoute(lengthMetres = 2_000.0, pointsPerLeg = 11)
        val composition = compositionEngine.compose(
            basePoints = baseRoute,
            lapCount = 3,
            targetDistanceMetres = 0.0
        )

        val markers = placementEngine.place(
            composedPoints = composition.composedPoints,
            totalDistanceMetres = composition.totalDistanceMetres,
            preset = kmPreset,
            lapTemplatePoints = baseRoute,
            laps = composition.laps
        ).markers

        assertEquals(
            listOf(1_000.0, 2_000.0, 3_000.0, 4_000.0, 5_000.0),
            markers.map { roundedHundreds(it.cumulativeDistanceMetres) }
        )
    }

    /** Same lap offset gives identical coordinates, one post with several signs. */
    @Test
    fun `markers sharing a lap offset reuse one physical position and cluster`() {
        val baseRoute = loopRoute(lengthMetres = 2_000.0, pointsPerLeg = 11)
        val composition = compositionEngine.compose(
            basePoints = baseRoute,
            lapCount = 3,
            targetDistanceMetres = 0.0
        )

        val markers = placementEngine.place(
            composedPoints = composition.composedPoints,
            totalDistanceMetres = composition.totalDistanceMetres,
            preset = kmPreset,
            lapTemplatePoints = baseRoute,
            laps = composition.laps
        ).markers

        // 1/3/5 km sit 1000 m into laps 0/1/2; 2/4 km sit on the lap boundary.
        assertSamePosition(markers[0], markers[2])
        assertSamePosition(markers[0], markers[4])
        assertSamePosition(markers[1], markers[3])

        val plannedStops = placementPlanner.plan(
            markers = markers,
            courseId = 1L,
            groupingThresholdMetres = 10.0
        ).stops

        assertEquals(2, plannedStops.size)
        assertEquals(
            listOf(1_000.0, 3_000.0, 5_000.0),
            plannedStops[0].markers.map { roundedHundreds(it.cumulativeDistanceMetres) }
        )
        assertEquals(
            listOf(2_000.0, 4_000.0),
            plannedStops[1].markers.map { roundedHundreds(it.cumulativeDistanceMetres) }
        )
    }

    /** Half marathon on a 5 km loop needs 21 markers, not 17. */
    @Test
    fun `half marathon on a five kilometre loop gets every kilometre`() {
        val baseRoute = loopRoute(lengthMetres = 5_000.0, pointsPerLeg = 26)
        val composition = compositionEngine.compose(
            basePoints = baseRoute,
            lapCount = 1,
            targetDistanceMetres = 21_097.5
        )

        val markers = placementEngine.place(
            composedPoints = composition.composedPoints,
            totalDistanceMetres = composition.totalDistanceMetres,
            preset = kmPreset,
            lapTemplatePoints = baseRoute,
            laps = composition.laps
        ).markers

        assertEquals(21, markers.size)
        assertEquals(
            (1..21).map { it * 1_000.0 },
            markers.map { roundedHundreds(it.cumulativeDistanceMetres) }
        )
    }

    /**
     * Closed out-and-back lap of [lengthMetres], north and back. Offset 0 is the
     * start and half the length the turnaround.
     */
    private fun loopRoute(lengthMetres: Double, pointsPerLeg: Int): List<RoutePoint> {
        val out  = straightRoute(lengthMetres / 2.0, pointsPerLeg)
        val back = out.dropLast(1).reversed()
        return (out + back).mapIndexed { i, p -> p.copy(timestampMs = i.toLong()) }
    }

    // optional start / finish markers, turning a toggle off removes the marker

    @Test
    fun `endpoints are placed only when their rules are present`() {
        val route = loopRoute(lengthMetres = 4_000.0, pointsPerLeg = 40)
        val composed = compositionEngine.compose(
            route, com.coursemapper.domain.model.CourseBuildSpec.FixedLaps(1)
        )

        fun place(includeStart: Boolean, includeFinish: Boolean) = placementEngine.place(
            composedPoints      = composed.composedPoints,
            totalDistanceMetres = composed.totalDistanceMetres,
            preset              = kmPreset.copy(
                rules = kmPreset.rules.withEndpoints(includeStart, includeFinish)
            ),
            lapTemplatePoints   = composed.lapTemplatePoints,
            laps                = composed.laps
        ).markers

        val both = place(includeStart = true, includeFinish = true)
        assertEquals(1, both.count { it.type == MarkerType.START })
        assertEquals(1, both.count { it.type == MarkerType.FINISH })

        val neither = place(includeStart = false, includeFinish = false)
        assertEquals(0, neither.count { it.type == MarkerType.START })
        assertEquals(0, neither.count { it.type == MarkerType.FINISH })
        // The kilometre markers are untouched by the endpoint choice.
        assertEquals(
            both.count { it.type == MarkerType.DISTANCE },
            neither.count { it.type == MarkerType.DISTANCE }
        )

        val startOnly = place(includeStart = true, includeFinish = false)
        assertEquals(1, startOnly.count { it.type == MarkerType.START })
        assertEquals(0, startOnly.count { it.type == MarkerType.FINISH })
    }

    @Test
    fun `a course can carry endpoints and nothing else`() {
        val route = loopRoute(lengthMetres = 4_000.0, pointsPerLeg = 40)
        val composed = compositionEngine.compose(
            route, com.coursemapper.domain.model.CourseBuildSpec.FixedLaps(1)
        )
        val endpointsOnly = emptyList<MarkerRule>().withEndpoints(true, true)

        val markers = placementEngine.place(
            composedPoints      = composed.composedPoints,
            totalDistanceMetres = composed.totalDistanceMetres,
            preset              = kmPreset.copy(rules = endpointsOnly),
            lapTemplatePoints   = composed.lapTemplatePoints,
            laps                = composed.laps
        ).markers

        assertEquals(2, markers.size)
        assertEquals(0.0, markers.first { it.type == MarkerType.START }.cumulativeDistanceMetres, 0.5)
        assertEquals(
            composed.totalDistanceMetres,
            markers.first { it.type == MarkerType.FINISH }.cumulativeDistanceMetres, 0.5
        )
    }

    /**
     * Due north line of [lengthMetres]. The degree scale must use the same WGS-84
     * radius as [CumulativeDistanceCalculator], 111 320 m/deg gives 1997.75 m for 2 km.
     */
    private fun straightRoute(lengthMetres: Double, points: Int): List<RoutePoint> {
        require(points >= 2)
        val metresPerDegree = CumulativeDistanceCalculator.EARTH_RADIUS_M * Math.PI / 180.0
        val latDelta = lengthMetres / metresPerDegree
        return (0 until points).map { index ->
            val t = index.toDouble() / (points - 1)
            RoutePoint(
                lat = t * latDelta,
                lon = 0.0,
                altMetres = null,
                accuracyMetres = null,
                timestampMs = index.toLong(),
                isSmoothed = true
            )
        }
    }

    private fun assertSamePosition(
        first: com.coursemapper.domain.model.DistanceMarker,
        second: com.coursemapper.domain.model.DistanceMarker
    ) {
        val distance = calc.haversineMetres(first.lat, first.lon, second.lat, second.lon)
        assertTrue("Expected markers to share the same physical position, got ${distance}m", distance < 0.5)
    }

    private fun roundedHundreds(value: Double): Double = kotlin.math.round(value / 100.0) * 100.0
}
