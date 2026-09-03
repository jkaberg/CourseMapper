package com.coursemapper.ui.run

import android.location.Location
import androidx.lifecycle.SavedStateHandle
import com.coursemapper.data.prefs.UserPreferencesRepository
import com.coursemapper.data.repository.RouteRepository
import com.coursemapper.domain.CourseLegPlanner
import com.coursemapper.domain.CorridorAnalyzer
import com.coursemapper.domain.CumulativeDistanceCalculator
import com.coursemapper.domain.DirectionalRetargetPolicy
import com.coursemapper.domain.DwellEngine
import com.coursemapper.domain.GpsReadinessEvaluator
import com.coursemapper.domain.LocationFilter
import com.coursemapper.domain.NavigationMotionTracker
import com.coursemapper.domain.NavigationSequenceEngine
import com.coursemapper.domain.NavigationZoomPolicy
import com.coursemapper.domain.PlacementGuidancePolicy
import com.coursemapper.domain.PolylineIndex
import com.coursemapper.domain.PositionSnapPolicy
import com.coursemapper.domain.RerouteDecisionPolicy
import com.coursemapper.domain.model.PlacementRun
import com.coursemapper.domain.model.RoutePoint
import com.coursemapper.domain.model.RunOrdering
import com.coursemapper.domain.model.RunSign
import com.coursemapper.domain.model.RunStop
import com.coursemapper.domain.model.RunStopState
import com.coursemapper.domain.model.TravelProfile
import com.coursemapper.location.LocationProvider
import com.coursemapper.routing.RouteDirectionsService
import com.coursemapper.ui.format.DistanceFormatter
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RunNavigationViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val locations = MutableSharedFlow<Location>(extraBufferCapacity = 16)
    private val repository = mockk<RouteRepository>(relaxed = true)
    private val locationProvider = mockk<LocationProvider>()
    private val locationFilter = mockk<LocationFilter>(relaxed = true)
    private val gpsEvaluator = mockk<GpsReadinessEvaluator>()
    private val directionsService = mockk<RouteDirectionsService>()
    private val preferences = mockk<UserPreferencesRepository>()
    private val savedStateHandle = SavedStateHandle(mapOf("runId" to 42L))
    private val distanceCalculator = CumulativeDistanceCalculator()
    private var snapshot = runWithStops(1)

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        every { preferences.dwellRadiusMetres } returns flowOf(25.0)
        every { preferences.dwellSeconds } returns flowOf(2.0)
        every { preferences.positionSnapRadiusMetres } returns flowOf(0.0)
        every { preferences.largeControls } returns flowOf(false)
        every { preferences.courseUpCamera } returns flowOf(true)
        every { preferences.autoRetarget } returns flowOf(true)
        every { preferences.distanceUnit } returns flowOf("km")
        every { locationProvider.locationUpdates(LocationProvider.Cadence.NAVIGATION) } returns locations
        every { locationFilter.evaluate(any()) } answers {
            LocationFilter.FilterResult(firstArg(), true, null, true)
        }
        every {
            gpsEvaluator.evaluate(any(), GpsReadinessEvaluator.Context.NAVIGATION)
        } returns GpsReadinessEvaluator.GpsReadiness.Green(5f, 0L)
        coEvery { directionsService.route(any(), any(), any(), any(), any()) } returns null
        coEvery { repository.getRun(42L) } answers { snapshot }
        coEvery { repository.setRunStopState(42L, any(), any()) } answers {
            val stopId = secondArg<Long>()
            val state = thirdArg<RunStopState>()
            snapshot = snapshot.copy(stops = snapshot.stops.map {
                if (it.id == stopId) it.copy(state = state) else it
            })
        }
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `cancel remains suppressed through inaccurate fixes and rearms after exit`() = runTest(dispatcher) {
        val viewModel = createViewModel()
        advanceUntilIdle()

        locations.emit(location(0L, lat = 0.0, accuracy = 5f, speed = 0f))
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.autoCompleteStatus is AutoCompleteStatus.Counting)

        viewModel.cancelAutoComplete()
        assertEquals(AutoCompleteStatus.Canceled, viewModel.uiState.value.autoCompleteStatus)

        locations.emit(location(1_000L, lat = 0.001, accuracy = 40f, speed = 0f))
        advanceUntilIdle()
        assertEquals(AutoCompleteStatus.Canceled, viewModel.uiState.value.autoCompleteStatus)

        locations.emit(location(2_000L, lat = 0.001, accuracy = 5f, speed = 0f))
        locations.emit(location(4_000L, lat = 0.001, accuracy = 5f, speed = 0f))
        advanceUntilIdle()
        assertEquals(AutoCompleteStatus.Idle, viewModel.uiState.value.autoCompleteStatus)

        locations.emit(location(5_000L, lat = 0.0, accuracy = 5f, speed = 0f))
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.autoCompleteStatus is AutoCompleteStatus.Counting)
    }

    @Test
    fun `manual mark now works after cancellation and resets next target status`() = runTest(dispatcher) {
        snapshot = runWithStops(2)
        val viewModel = createViewModel()
        advanceUntilIdle()
        locations.emit(location(0L, lat = 0.0, accuracy = 5f, speed = 0f))
        advanceUntilIdle()

        viewModel.cancelAutoComplete()
        viewModel.markActiveDone()
        advanceUntilIdle()

        assertEquals(RunStopState.DONE, snapshot.stops.first().state)
        assertEquals(2L, viewModel.uiState.value.activeStop?.id)
        assertEquals(AutoCompleteStatus.Idle, viewModel.uiState.value.autoCompleteStatus)
    }

    @Test
    fun `take me there next continues after selected marker when completed`() = runTest(dispatcher) {
        snapshot = runWithStops(4)
        coEvery { repository.makeStopNext(42L, any()) } answers {
            snapshot = snapshot.copy(
                stops = NavigationSequenceEngine.takeNextThenContinue(
                    snapshot.stops,
                    secondArg<Long>()
                )
            )
        }
        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.makeStopNext(snapshot.stops.first { it.id == 3L })
        advanceUntilIdle()
        assertEquals(3L, viewModel.uiState.value.activeStop?.id)

        viewModel.markActiveDone()
        advanceUntilIdle()
        assertEquals(4L, viewModel.uiState.value.activeStop?.id)
    }

    @Test
    fun `automatic confirmation writes and adds undo only once`() = runTest(dispatcher) {
        val viewModel = createViewModel()
        advanceUntilIdle()

        locations.emit(location(0L, speed = 0f))
        locations.emit(location(1_000L, speed = 0f))
        locations.emit(location(2_000L, speed = 0f))
        locations.emit(location(3_000L, speed = 0f))
        advanceUntilIdle()

        coVerify(exactly = 1) { repository.setRunStopState(42L, 1L, RunStopState.DONE) }
        assertEquals(1, viewModel.uiState.value.undoCount)
        assertEquals("Course 1 km completed", viewModel.uiState.value.lastActionLabel)
    }

    @Test
    fun `repeated confirmations are deduplicated while Room write is in flight`() = runTest(dispatcher) {
        val writeGate = CompletableDeferred<Unit>()
        val dwellEngine = mockk<DwellEngine>()
        every {
            dwellEngine.process(any(), any(), any(), any(), any(), any(), any(), any(), any())
        } returns (DwellEngine.Result.Confirmed(2_000L) to
            DwellEngine.State(armed = true, accruedMs = 2_000L, confirmed = true))
        coEvery { repository.setRunStopState(42L, 1L, RunStopState.DONE) } coAnswers {
            writeGate.await()
            snapshot = snapshot.copy(stops = snapshot.stops.map {
                if (it.id == 1L) it.copy(state = RunStopState.DONE) else it
            })
        }
        createViewModel(dwellEngine)
        advanceUntilIdle()

        locations.tryEmit(location(1_000L, speed = 0f))
        locations.tryEmit(location(2_000L, speed = 0f))
        runCurrent()

        coVerify(exactly = 1) { repository.setRunStopState(42L, 1L, RunStopState.DONE) }
        writeGate.complete(Unit)
        advanceUntilIdle()
    }

    @Test
    fun `navigation starts followed and gesture latch blocks future auto engagement`() = runTest(dispatcher) {
        val viewModel = createViewModel()
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.isFollowMode)

        locations.emit(location(0L, lat = 0.02, speed = 0f))
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.isFollowMode)
        assertEquals(
            NavigationZoomPolicy.MOVING_TILT_DEGREES,
            viewModel.uiState.value.cameraTiltDeg,
            0.0001
        )
        assertEquals(180.0, viewModel.uiState.value.cameraBearing!!, 1.0)

        locations.emit(location(1_000L, lat = 0.0199, speed = 3f, bearing = 180f))
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.isFollowMode)
        assertEquals(
            NavigationZoomPolicy.MOVING_TILT_DEGREES,
            viewModel.uiState.value.cameraTiltDeg,
            0.0001
        )
        assertEquals(180.0, viewModel.uiState.value.travelBearingDeg!!.toDouble(), 1.0)

        viewModel.onUserGesture()
        locations.emit(location(2_000L, lat = 0.0199, speed = 0f))
        locations.emit(location(3_000L, lat = 0.0199, speed = 0f))
        locations.emit(location(4_000L, lat = 0.0198, speed = 3f, bearing = 180f))
        advanceUntilIdle()
        assertFalse(viewModel.uiState.value.isFollowMode)

        viewModel.resumeFollow()
        assertTrue(viewModel.uiState.value.isFollowMode)
    }

    @Test
    fun `moving near marker uses close zoom and a flatter camera`() = runTest(dispatcher) {
        val viewModel = createViewModel()
        advanceUntilIdle()

        locations.emit(location(1_000L, lat = 0.0001, speed = 3f, bearing = 180f))
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.isFollowMode)
        assertEquals(
            NavigationZoomPolicy.MAX_ZOOM,
            viewModel.uiState.value.navigationZoom,
            0.0001
        )
        assertEquals(
            NavigationZoomPolicy.NEAR_MARKER_TILT_DEGREES,
            viewModel.uiState.value.cameraTiltDeg,
            0.0001
        )
    }

    @Test
    fun `a held turn away hands the plan to the marker now ahead`() = runTest(dispatcher) {
        snapshot = runWithStopsAt(1L to 500.0, 2L to -600.0)
        val viewModel = createViewModel()
        advanceUntilIdle()

        // Heading south at 8 m/s, away from stop 1 (500 m north) and towards
        // stop 2 (600 m south).
        locations.emit(drivingSouth(elapsedMs = 0L, metresNorth = 0.0))
        advanceUntilIdle()
        coVerify(exactly = 0) { repository.resequenceFromDirection(42L, any(), any(), any()) }

        locations.emit(drivingSouth(elapsedMs = 6_000L, metresNorth = -90.0))
        advanceUntilIdle()

        // The whole remainder is re-planned for the new direction, not rotated:
        // see DirectionalSequencer.
        coVerify(exactly = 1) { repository.resequenceFromDirection(42L, any(), any(), any()) }
        assertTrue(viewModel.uiState.value.lastActionLabel!!.startsWith("Turned around"))
    }

    @Test
    fun `the same turn changes nothing when re-targeting is switched off`() = runTest(dispatcher) {
        every { preferences.autoRetarget } returns flowOf(false)
        snapshot = runWithStopsAt(1L to 500.0, 2L to -600.0)
        createViewModel()
        advanceUntilIdle()

        locations.emit(drivingSouth(elapsedMs = 0L, metresNorth = 0.0))
        locations.emit(drivingSouth(elapsedMs = 6_000L, metresNorth = -90.0))
        locations.emit(drivingSouth(elapsedMs = 12_000L, metresNorth = -180.0))
        advanceUntilIdle()

        coVerify(exactly = 0) { repository.resequenceFromDirection(42L, any(), any(), any()) }
    }

    @Test
    fun `leaving the routed leg fetches a new one`() = runTest(dispatcher) {
        snapshot = runWithStopsAt(1L to 500.0)
        // A leg running due north from the rider's start to the stop.
        coEvery { directionsService.route(any(), any(), any(), any(), any()) } returns
            RouteDirectionsService.DirectionsLeg(
                points = (0..10).map { metresNorthOf(it * 50.0) },
                distanceMetres = 500.0,
                steps = emptyList()
            )
        createViewModel()
        advanceUntilIdle()

        locations.emit(drivingSouth(elapsedMs = 0L, metresNorth = 0.0))
        advanceUntilIdle()
        coVerify(exactly = 1) { directionsService.route(any(), any(), any(), any(), any()) }

        // Well off the leg, for long enough to rule out one wild fix, and past
        // the throttle that follows adopting a leg.
        val offRouteStart = RerouteDecisionPolicy.MIN_REROUTE_INTERVAL_MS
        repeat(RerouteDecisionPolicy.OFF_ROUTE_FIXES) { i ->
            locations.emit(
                drivingSouth(
                    elapsedMs = offRouteStart + (i + 1) * 1_000L,
                    metresNorth = 200.0,
                    metresEast = 150.0
                )
            )
            advanceUntilIdle()
        }

        coVerify(exactly = 2) { directionsService.route(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `follow-main-route guidance rides the recorded course and asks no router`() =
        runTest(dispatcher) {
            // The stop is 500 m north; the recording gets there via a 200 m
            // eastward dogleg, which is the ground people will actually run.
            snapshot = runWithStopsAt(1L to 500.0).copy(ordering = RunOrdering.SPINE)
            coEvery { repository.getCourseDisplayLines(any(), any()) } returns
                displayOf(doglegCourse())

            val viewModel = createViewModel()
            advanceUntilIdle()
            locations.emit(drivingSouth(elapsedMs = 0L, metresNorth = 0.0))
            advanceUntilIdle()

            // "Follow main route" is answered from the recording, so nothing is
            // asked of the road graph at all.
            coVerify(exactly = 0) { directionsService.route(any(), any(), any(), any(), any()) }

            val state = viewModel.uiState.value
            assertTrue("guidance should count as routed", state.isRouted)
            // The dogleg is in the line, so this is the course and not the
            // straight two-point fallback.
            assertTrue("expected course geometry, got ${state.approachLine.size} points",
                state.approachLine.size > 5)
            assertTrue(
                "line should follow the eastward dogleg",
                state.approachLine.any { it.second > metresNorthOf(0.0).second + 1e-4 }
            )
            // And the distance quoted is the course's, not the crow-flies 500 m.
            assertTrue(
                "expected the along-course distance, got ${state.routedDistanceMetres}",
                (state.routedDistanceMetres ?: 0.0) > 600.0
            )
            // A course line has no maneuvers to call.
            assertEquals(null, state.nextInstruction)
        }

    @Test
    fun `shortest-path runs still route over the road graph`() = runTest(dispatcher) {
        // Same geometry, same stops - only the ordering mode differs.
        snapshot = runWithStopsAt(1L to 500.0).copy(ordering = RunOrdering.OPTIMIZED)
        coEvery { repository.getCourseDisplayLines(any(), any()) } returns
            displayOf(doglegCourse())
        coEvery { directionsService.route(any(), any(), any(), any(), any()) } returns
            RouteDirectionsService.DirectionsLeg(
                points = (0..10).map { metresNorthOf(it * 50.0) },
                distanceMetres = 500.0,
                steps = emptyList()
            )

        createViewModel()
        advanceUntilIdle()
        locations.emit(drivingSouth(elapsedMs = 0L, metresNorth = 0.0))
        advanceUntilIdle()

        coVerify(exactly = 1) { directionsService.route(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `follow-main-route falls back to routing when the rider is off the course`() =
        runTest(dispatcher) {
            snapshot = runWithStopsAt(1L to 500.0).copy(ordering = RunOrdering.SPINE)
            coEvery { repository.getCourseDisplayLines(any(), any()) } returns
                displayOf(doglegCourse())
            coEvery { directionsService.route(any(), any(), any(), any(), any()) } returns
                RouteDirectionsService.DirectionsLeg(
                    points = (0..10).map { metresNorthOf(it * 50.0) },
                    distanceMetres = 500.0,
                    steps = emptyList()
                )

            createViewModel()
            advanceUntilIdle()
            // 300 m east of the recording: the rider is not on it, and drawing a
            // line along it would be a lie.  Road routing takes over.
            locations.emit(
                drivingSouth(elapsedMs = 0L, metresNorth = 0.0, metresEast = 300.0)
            )
            advanceUntilIdle()

            coVerify(exactly = 1) { directionsService.route(any(), any(), any(), any(), any()) }
        }

    @Test
    fun `cancellation is retained in saved state`() = runTest(dispatcher) {
        val viewModel = createViewModel()
        advanceUntilIdle()
        viewModel.cancelAutoComplete()

        assertTrue(savedStateHandle.get<LongArray>("autoSuppressedStopIds")!!.contains(1L))
    }

    private fun createViewModel(dwellEngine: DwellEngine = DwellEngine()) = RunNavigationViewModel(
        repository = repository,
        dwellEngine = dwellEngine,
        distanceCalc = distanceCalculator,
        locationProvider = locationProvider,
        locationFilter = locationFilter,
        gpsEvaluator = gpsEvaluator,
        motionTracker = NavigationMotionTracker(distanceCalculator),
        placementGuidancePolicy = PlacementGuidancePolicy(distanceCalculator),
        zoomPolicy = NavigationZoomPolicy(),
        positionSnapPolicy = PositionSnapPolicy(),
        retargetPolicy = DirectionalRetargetPolicy(distanceCalculator),
        reroutePolicy = RerouteDecisionPolicy(),
        courseLegPlanner = CourseLegPlanner(distanceCalculator),
        directionsService = directionsService,
        userPreferences = preferences,
        distanceFormatter = DistanceFormatter(preferences),
        savedStateHandle = savedStateHandle
    )

    /** A fix [metresNorth] of the origin, driving south at 8 m/s. */
    private fun drivingSouth(
        elapsedMs: Long,
        metresNorth: Double,
        metresEast: Double = 0.0
    ): Location {
        val (lat, lon) = metresNorthOf(metresNorth, metresEast)
        return location(elapsedMs, lat = lat, lon = lon, speed = 8f, bearing = 180f)
    }

    private fun location(
        elapsedMs: Long,
        lat: Double = 0.0,
        lon: Double = 0.0,
        accuracy: Float = 5f,
        speed: Float? = 0f,
        bearing: Float? = null
    ): Location = mockk {
        every { latitude } returns lat
        every { longitude } returns lon
        every { this@mockk.accuracy } returns accuracy
        every { time } returns System.currentTimeMillis()
        every { elapsedRealtimeNanos } returns (elapsedMs + 1_000L) * 1_000_000L
        every { hasSpeed() } returns (speed != null)
        every { this@mockk.speed } returns (speed ?: 0f)
        every { hasBearing() } returns (bearing != null)
        every { this@mockk.bearing } returns (bearing ?: 0f)
    }

    companion object {
        /** Trondheim, not the equator, where projection skew is 1.0 and mistakes hide. */
        private const val BASE_LAT = 63.43
        private const val BASE_LON = 10.39

        private val METRES_PER_DEGREE_LAT =
            CumulativeDistanceCalculator.EARTH_RADIUS_M * Math.PI / 180.0

        /** A point [metresNorth] / [metresEast] of the fixture origin. */
        private fun metresNorthOf(
            metresNorth: Double,
            metresEast: Double = 0.0
        ): Pair<Double, Double> {
            val metresPerDegreeLon = METRES_PER_DEGREE_LAT * Math.cos(Math.toRadians(BASE_LAT))
            return BASE_LAT + metresNorth / METRES_PER_DEGREE_LAT to
                BASE_LON + metresEast / metresPerDegreeLon
        }

        /**
         * Course from the rider to the stop 500 m north, bulging 200 m east, so
         * you can tell which line guidance drew.
         */
        private fun doglegCourse(): List<Pair<Double, Double>> = buildList {
            for (i in 0..10) add(metresNorthOf(i * 10.0, i * 20.0))       // out east
            for (i in 1..30) add(metresNorthOf(100.0 + i * 10.0, 200.0))  // north
            for (i in 1..10) add(metresNorthOf(400.0 + i * 10.0, 200.0 - i * 20.0))
        }

        /** A [RouteRepository.CourseDisplay] carrying just [points] as course 7. */
        private fun displayOf(points: List<Pair<Double, Double>>): RouteRepository.CourseDisplay {
            val routePoints = points.map { (lat, lon) ->
                RoutePoint(lat, lon, null, null, 0L, isSmoothed = true)
            }
            val index = PolylineIndex.build(routePoints, CumulativeDistanceCalculator())!!
            return RouteRepository.CourseDisplay(
                lines = listOf(
                    RouteRepository.CourseDisplayLine(
                        courseId = 7L,
                        courseName = "Course",
                        colorHex = "#FF0000",
                        soloPaths = listOf(points),
                        ribbonPaths = emptyList()
                    )
                ),
                sharedCorridors = emptyList(),
                indices = listOf(CorridorAnalyzer.IndexedCourse(7L, index))
            )
        }

        /** A run whose stops sit at the given metre offsets north of the origin. */
        private fun runWithStopsAt(vararg stops: Pair<Long, Double>): PlacementRun =
            runWithStops(stops.size).copy(
                stops = stops.mapIndexed { index, (id, metresNorth) ->
                    val (lat, lon) = metresNorthOf(metresNorth)
                    RunStop(
                        id = id,
                        runId = 42L,
                        stopIndex = index,
                        lat = lat,
                        lon = lon,
                        signs = listOf(RunSign("Course", "$id km")),
                        state = RunStopState.PENDING
                    )
                }
            )

        private fun runWithStops(count: Int): PlacementRun {
            val stops = (1..count).map { index ->
                RunStop(
                    id = index.toLong(),
                    runId = 42L,
                    stopIndex = index - 1,
                    lat = 0.0,
                    lon = 0.0,
                    signs = listOf(RunSign("Course", "$index km")),
                    state = RunStopState.PENDING
                )
            }
            return PlacementRun(
                id = 42L,
                name = "Run",
                createdAt = 0L,
                updatedAt = 0L,
                ordering = RunOrdering.OPTIMIZED,
                spineCourseId = null,
                startedAt = null,
                completedAt = null,
                totalPlannedMetres = 0.0,
                travelProfile = TravelProfile.CAR,
                stops = stops
            )
        }
    }
}
