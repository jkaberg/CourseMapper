package com.coursemapper.ui.run

import android.location.Location
import androidx.lifecycle.SavedStateHandle
import com.coursemapper.data.prefs.UserPreferencesRepository
import com.coursemapper.data.repository.RouteRepository
import com.coursemapper.domain.CourseLegPlanner
import com.coursemapper.domain.CumulativeDistanceCalculator
import com.coursemapper.domain.DirectionalRetargetPolicy
import com.coursemapper.domain.DwellEngine
import com.coursemapper.domain.FollowCameraSmoother
import com.coursemapper.domain.GpsReadinessEvaluator
import com.coursemapper.domain.LocationFilter
import com.coursemapper.domain.NavigationMotionTracker
import com.coursemapper.domain.NavigationZoomPolicy
import com.coursemapper.domain.PlacementGuidancePolicy
import com.coursemapper.domain.PositionSnapPolicy
import com.coursemapper.domain.RerouteDecisionPolicy
import com.coursemapper.domain.model.PlacementRun
import com.coursemapper.domain.model.RunOrdering
import com.coursemapper.domain.model.RunSign
import com.coursemapper.domain.model.RunStop
import com.coursemapper.domain.model.RunStopState
import com.coursemapper.domain.model.TravelProfile
import com.coursemapper.location.LocationProvider
import com.coursemapper.map.camera.MapCameraController
import com.coursemapper.routing.RouteDirectionsService
import com.coursemapper.testing.GpxFixtures
import com.coursemapper.ui.format.DistanceFormatter
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

/**
 * Simulated ride on the real 5 km course through the real view model and
 * smoother, at ATV speeds with a stop every km. Does the map open up with speed,
 * does the next sign stay on screen, does the arrow keep up.
 *
 * Can't check MapLibre's own projection under tilt, see `FollowAnchorOnDeviceTest`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RunNavigationCameraSimulationTest {

    // ── The phone ─────────────────────────────────────────────────────────────
    // A mounted mid-size phone: 400 dp wide, 800 dp of map, with the measured
    // banner and controls taking their real bites out of it.
    private val viewportWidthDp = 400.0
    private val mapHeightDp = 800.0
    private val topChromeDp = 110.0
    private val bottomChromeDp = 155.0
    private val bandHeightDp = mapHeightDp - topChromeDp - bottomChromeDp
    private val anchorInBand = MapCameraController.FOLLOW_ANCHOR_FRACTION

    private val dispatcher = StandardTestDispatcher()
    private val locations = MutableSharedFlow<Location>(extraBufferCapacity = 64)
    private val repository = mockk<RouteRepository>(relaxed = true)
    private val locationProvider = mockk<LocationProvider>()
    private val locationFilter = mockk<LocationFilter>(relaxed = true)
    private val gpsEvaluator = mockk<GpsReadinessEvaluator>()
    private val directionsService = mockk<RouteDirectionsService>()
    private val preferences = mockk<UserPreferencesRepository>()
    private val distanceCalculator = CumulativeDistanceCalculator()
    private lateinit var snapshot: PlacementRun

    /** The real 5 km course, as a polyline. */
    private val track = GpxFixtures.load(GpxFixtures.FIVE_KM)
    private val cumulative = distanceCalculator.cumulativeDistances(track)

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        every { preferences.dwellRadiusMetres } returns flowOf(25.0)
        every { preferences.dwellSeconds } returns flowOf(3.0)
        every { preferences.positionSnapRadiusMetres } returns flowOf(0.0)
        every { preferences.largeControls } returns flowOf(false)
        every { preferences.courseUpCamera } returns flowOf(true)
        every { preferences.autoRetarget } returns flowOf(false)
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
    fun tearDown() = Dispatchers.resetMain()

    /** One second of the simulated ride, as it left the view model. */
    private data class Tick(
        val elapsedSeconds: Double,
        val speed: Double,
        val lat: Double,
        val lon: Double,
        val zoom: Double,
        val tiltDeg: Double,
        val cameraBearingDeg: Double,
        val distanceToStopMetres: Double?,
        val bearingToStopDeg: Double?
    )

    /** Drive from [fromMetres] for [seconds] at [speedAt] m/s, returns what the camera was told each second. */
    private suspend fun ride(
        viewModel: RunNavigationViewModel,
        fromMetres: Double,
        seconds: Int,
        speedAt: (Int) -> Double,
        advance: suspend () -> Unit
    ): List<Tick> {
        val ticks = mutableListOf<Tick>()
        var along = fromMetres
        for (second in 0 until seconds) {
            val speed = speedAt(second)
            val (lat, lon) = pointAt(along)
            // Bearing from where we were a moment ago to where we are going - 
            // the same thing a real fix carries.
            val (aheadLat, aheadLon) = pointAt(along + 5.0)
            val bearing = distanceCalculator.bearingDegrees(lat, lon, aheadLat, aheadLon)

            locations.emit(fix(second * 1_000L, lat, lon, speed.toFloat(), bearing.toFloat()))
            advance()

            val state = viewModel.uiState.value
            val stop = state.activeStop
            ticks += Tick(
                elapsedSeconds = second.toDouble(),
                speed = speed,
                lat = lat,
                lon = lon,
                zoom = state.navigationZoom,
                tiltDeg = state.cameraTiltDeg,
                cameraBearingDeg = state.cameraBearing ?: 0.0,
                distanceToStopMetres = state.distanceToActiveMetres,
                bearingToStopDeg = stop?.let {
                    distanceCalculator.bearingDegrees(lat, lon, it.lat, it.lon)
                }
            )
            along += speed
        }
        return ticks
    }

    /** Metres of ground per logical point at [zoom]. */
    private fun metresPerPoint(zoom: Double, latitudeDeg: Double): Double =
        cos(latitudeDeg * PI / 180.0) * 2.0 * PI * 6_378_137.0 / (2.0.pow(zoom) * 512.0)

    /** Metres of course visible ahead of the rider in the unobstructed band. */
    private fun metresAhead(tick: Tick): Double =
        anchorInBand * bandHeightDp * metresPerPoint(tick.zoom, tick.lat)

    /** Active stop on screen, from band top and left edge. Course-up, flat projection. */
    private fun stopOnScreen(tick: Tick): Pair<Double, Double>? {
        val distance = tick.distanceToStopMetres ?: return null
        val bearing = tick.bearingToStopDeg ?: return null
        val offAxis = (bearing - tick.cameraBearingDeg) * PI / 180.0
        val mpp = metresPerPoint(tick.zoom, tick.lat)
        val forwardPoints = distance * cos(offAxis) / mpp
        val lateralPoints = distance * sin(offAxis) / mpp
        return (anchorInBand * bandHeightDp - forwardPoints) to
            (viewportWidthDp / 2.0 + lateralPoints)
    }

    @Test
    fun `speeding up opens the map, slowing down closes it`() = runTest(dispatcher) {
        // A stop 4 km down the course, so nothing is being framed and the
        // look-ahead is on its own.
        snapshot = runWithDistantStop()
        val viewModel = createViewModel()
        advanceUntilIdle()
        viewModel.setMapViewportDp(viewportWidthDp, bandHeightDp)

        // Accelerate 0 → 14 m/s (50 km/h) over 25 s, hold, then brake.
        val ticks = ride(viewModel, fromMetres = 0.0, seconds = 60, speedAt = { s ->
            when {
                s < 25 -> s * 14.0 / 25.0
                s < 45 -> 14.0
                else -> 14.0 * (60 - s) / 15.0
            }
        }, advance = { advanceUntilIdle() })

        val cruising = ticks.filter { it.speed >= 13.0 }
        val crawling = ticks.filter { it.speed in 0.5..2.0 }
        assertTrue("no cruising ticks", cruising.isNotEmpty())
        assertTrue("no crawling ticks", crawling.isNotEmpty())

        val fastAhead = cruising.map { metresAhead(it) }.average()
        val slowAhead = crawling.map { metresAhead(it) }.average()
        assertTrue(
            "at 50 km/h the map shows ${fastAhead.toInt()} m, crawling ${slowAhead.toInt()} m",
            fastAhead > slowAhead * 2.5
        )
        // And it is a useful amount of road, not a token difference.
        assertTrue("only ${fastAhead.toInt()} m ahead at 50 km/h", fastAhead > 240.0)
    }

    @Test
    fun `at a steady speed the rider gets the look-ahead they were promised`() =
        runTest(dispatcher) {
            snapshot = runWithDistantStop()
            val viewModel = createViewModel()
            advanceUntilIdle()
            viewModel.setMapViewportDp(viewportWidthDp, bandHeightDp)

            // Long enough for the rate limiter to have settled.
            val ticks = ride(viewModel, 0.0, 45, { 11.0 }, { advanceUntilIdle() })
            val settled = ticks.drop(15)

            settled.forEach { tick ->
                val seconds = metresAhead(tick) / tick.speed
                assertEquals(
                    "at t=${tick.elapsedSeconds}s the map showed ${seconds.toInt()}s of road",
                    NavigationZoomPolicy.LOOKAHEAD_SECONDS,
                    seconds,
                    1.5
                )
            }
        }

    @Test
    fun `closing on a stop keeps the rider and the stop on screen together`() =
        runTest(dispatcher) {
            // Start 350 m short of the 1 km sign and drive at it.
            snapshot = runWithStopsAlong(1_000.0)
            val viewModel = createViewModel()
            advanceUntilIdle()
            viewModel.setMapViewportDp(viewportWidthDp, bandHeightDp)

            val ticks = ride(viewModel, fromMetres = 650.0, seconds = 40, speedAt = { s ->
                // Approach at 10 m/s, easing off over the last stretch as a
                // rider actually would.
                if (s < 28) 10.0 else 3.0
            }, advance = { advanceUntilIdle() })

            // a stop 2 km out isn't framed on purpose, but once a sign is on
            // screen it has to stay there all the way in
            fun visible(tick: Tick): Boolean {
                val (y, x) = stopOnScreen(tick) ?: return false
                return y >= 0.0 && y <= bandHeightDp && x >= 0.0 && x <= viewportWidthDp
            }

            val firstVisible = ticks.indexOfFirst { visible(it) }
            assertTrue("the stop never came on screen at all", firstVisible >= 0)

            // It has to appear with real warning, not pop up on the bumper.
            val distanceWhenSeen = ticks[firstVisible].distanceToStopMetres!!
            assertTrue(
                "the stop only appeared ${distanceWhenSeen.toInt()} m out",
                distanceWhenSeen > 120.0
            )

            // …and from then on it never leaves, through the real bends where
            // it is off to one side rather than dead ahead.
            ticks.drop(firstVisible).forEach { tick ->
                val (y, x) = stopOnScreen(tick) ?: return@forEach
                val distance = tick.distanceToStopMetres!!
                assertTrue(
                    "at ${distance.toInt()} m the stop sat ${y.toInt()}pt down a " +
                        "${bandHeightDp.toInt()}pt band — it left the screen",
                    y >= 0.0 && y <= bandHeightDp
                )
                assertTrue(
                    "at ${distance.toInt()} m the stop sat ${x.toInt()}pt across a " +
                        "${viewportWidthDp.toInt()}pt screen — off the side",
                    x >= 0.0 && x <= viewportWidthDp
                )
            }
        }

    @Test
    fun `the approach stops tightening at fifteen metres`() = runTest(dispatcher) {
        snapshot = runWithStopsAlong(1_000.0)
        val viewModel = createViewModel()
        advanceUntilIdle()
        viewModel.setMapViewportDp(viewportWidthDp, bandHeightDp)

        val ticks = ride(viewModel, fromMetres = 880.0, seconds = 45, speedAt = { 3.0 },
            advance = { advanceUntilIdle() })

        val inside = ticks.filter { (it.distanceToStopMetres ?: 999.0) < 15.0 }
        assertTrue("never got inside 15 m", inside.isNotEmpty())
        val tightest = ticks.maxOf { it.zoom }
        assertTrue(
            "zoomed to $tightest, past the ceiling",
            tightest <= NavigationZoomPolicy.MAX_ZOOM
        )
        // Inside the floor the camera has arrived: it must not keep closing in
        // as the last few metres tick away.
        inside.zipWithNext { a, b ->
            assertTrue(
                "still zooming in at ${b.distanceToStopMetres?.toInt()} m",
                b.zoom <= a.zoom + 0.001
            )
        }
    }

    @Test
    fun `the zoom never jumps between fixes`() = runTest(dispatcher) {
        snapshot = runWithStopsAlong(1_000.0, 2_000.0, 3_000.0)
        val viewModel = createViewModel()
        advanceUntilIdle()
        viewModel.setMapViewportDp(viewportWidthDp, bandHeightDp)

        val ticks = ride(viewModel, 0.0, 90, { s -> if (s % 20 < 10) 13.0 else 4.0 },
            advance = { advanceUntilIdle() })

        ticks.zipWithNext { a, b ->
            val step = abs(b.zoom - a.zoom)
            assertTrue(
                "zoom stepped $step in one second, past the rate limit",
                step <= NavigationZoomPolicy.MAX_ZOOM_RATE_PER_SECOND + 0.001
            )
        }
    }

    @Test
    fun `the puck keeps up with the rider at speed`() = runTest(dispatcher) {
        // the camera centres on the smoothed position, so check the smoother
        // doesn't lag behind the rider
        snapshot = runWithDistantStop()
        val viewModel = createViewModel()
        advanceUntilIdle()
        viewModel.setMapViewportDp(viewportWidthDp, bandHeightDp)

        val smoother = FollowCameraSmoother()
        var worstLagMetres = 0.0
        var worstFrameJumpMetres = 0.0
        var previousFrame: FollowCameraSmoother.Frame? = null

        var along = 0.0
        val speed = 14.0
        for (second in 0 until 40) {
            val (lat, lon) = pointAt(along)
            val (aheadLat, aheadLon) = pointAt(along + 5.0)
            val bearing = distanceCalculator.bearingDegrees(lat, lon, aheadLat, aheadLon)
            locations.emit(fix(second * 1_000L, lat, lon, speed.toFloat(), bearing.toFloat()))
            advanceUntilIdle()

            val target = viewModel.uiState.value.toCameraTarget() ?: continue
            // One second of display frames at 60 Hz, exactly as the screen's
            // withFrameNanos loop drives it.
            repeat(60) {
                val frame = smoother.advance(target, 1.0 / 60.0)
                previousFrame?.let { previous ->
                    worstFrameJumpMetres = maxOf(
                        worstFrameJumpMetres,
                        distanceCalculator.haversineMetres(
                            previous.lat, previous.lon, frame.lat, frame.lon
                        )
                    )
                }
                previousFrame = frame
            }
            if (second > 5) {
                worstLagMetres = maxOf(
                    worstLagMetres,
                    distanceCalculator.haversineMetres(
                        previousFrame!!.lat, previousFrame!!.lon, lat, lon
                    )
                )
            }
            along += speed
        }

        // A constant lag is what smooth looks like; the question is how big.
        // At 14 m/s the 0.55 s time constant is worth about eight metres.
        assertTrue(
            "the arrow trailed the rider by ${worstLagMetres.toInt()} m at 50 km/h",
            worstLagMetres < 12.0
        )
        // And no single display frame may lurch - that is the whole reason the
        // smoother exists.
        assertTrue(
            "a single frame moved ${worstFrameJumpMetres} m",
            worstFrameJumpMetres < 1.0
        )
    }

    /** The point [metresAlong] the real course. */
    private fun pointAt(metresAlong: Double): Pair<Double, Double> {
        val clamped = metresAlong.coerceIn(0.0, cumulative.last())
        val index = cumulative.indexOfFirst { it >= clamped }.coerceAtLeast(1)
        val before = track[index - 1]
        val after = track[index]
        val span = cumulative[index] - cumulative[index - 1]
        val t = if (span <= 0.0) 0.0 else (clamped - cumulative[index - 1]) / span
        return before.lat + (after.lat - before.lat) * t to
            before.lon + (after.lon - before.lon) * t
    }

    /**
     * A run with one stop far away so framing never kicks in. Can't be on the
     * course, the 5 km loop is never more than 1 503 m across.
     */
    private fun runWithDistantStop(): PlacementRun {
        val (lat, lon) = pointAt(0.0)
        return runWithStopsAlong(0.0).let { run ->
            run.copy(stops = run.stops.map { it.copy(lat = lat + 0.05, lon = lon) })
        }
    }

    /** A run with a stop at each of [metresAlong] the real course. */
    private fun runWithStopsAlong(vararg metresAlong: Double): PlacementRun = PlacementRun(
        id = 42L,
        name = "5 km",
        createdAt = 0L,
        updatedAt = 0L,
        ordering = RunOrdering.SPINE,
        spineCourseId = null,
        startedAt = null,
        completedAt = null,
        totalPlannedMetres = 0.0,
        travelProfile = TravelProfile.CAR,
        stops = metresAlong.mapIndexed { index, metres ->
            val (lat, lon) = pointAt(metres)
            RunStop(
                id = index + 1L,
                runId = 42L,
                stopIndex = index,
                lat = lat,
                lon = lon,
                signs = listOf(RunSign("5 km", "${(metres / 1000).toInt()} km")),
                state = RunStopState.PENDING
            )
        }
    )

    private fun fix(
        elapsedMs: Long,
        lat: Double,
        lon: Double,
        speed: Float,
        bearing: Float
    ): Location = mockk {
        every { latitude } returns lat
        every { longitude } returns lon
        every { accuracy } returns 5f
        every { time } returns System.currentTimeMillis()
        every { elapsedRealtimeNanos } returns (elapsedMs + 1_000L) * 1_000_000L
        every { hasSpeed() } returns true
        every { this@mockk.speed } returns speed
        every { hasBearing() } returns true
        every { this@mockk.bearing } returns bearing
    }

    private fun createViewModel() = RunNavigationViewModel(
        repository = repository,
        dwellEngine = DwellEngine(),
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
        savedStateHandle = SavedStateHandle(mapOf("runId" to 42L))
    )
}
