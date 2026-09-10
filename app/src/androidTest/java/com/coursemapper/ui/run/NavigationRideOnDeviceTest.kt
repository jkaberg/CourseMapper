package com.coursemapper.ui.run

import android.location.Location
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.lifecycle.SavedStateHandle
import androidx.test.platform.app.InstrumentationRegistry
import com.coursemapper.data.prefs.UserPreferencesRepository
import com.coursemapper.data.repository.RouteRepository
import com.coursemapper.domain.CourseLegPlanner
import com.coursemapper.domain.CumulativeDistanceCalculator
import com.coursemapper.domain.DirectionalRetargetPolicy
import com.coursemapper.domain.DwellEngine
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
import com.coursemapper.routing.RouteDirectionsService
import com.coursemapper.ui.format.DistanceFormatter
import com.coursemapper.ui.theme.CourseMapperTheme
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import kotlin.math.abs

/**
 * Scripted ride through the real navigation screen on a phone: real banner and
 * controls, fake GPS, zoom read back from MapLibre's camera. Checks the arrow is
 * on the rider, the map opens up with speed and closes in near a sign.
 */
class NavigationRideOnDeviceTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val locations = MutableSharedFlow<Location>(extraBufferCapacity = 64)
    private val repository = mockk<RouteRepository>(relaxed = true)
    private val locationProvider = mockk<LocationProvider>()
    private val locationFilter = mockk<LocationFilter>(relaxed = true)
    private val gpsEvaluator = mockk<GpsReadinessEvaluator>()
    private val directionsService = mockk<RouteDirectionsService>()
    private val preferences = mockk<UserPreferencesRepository>()
    private val distanceCalculator = CumulativeDistanceCalculator()

    /** Due north of the start, so "ahead" is a straight line up the screen. */
    private val startLat = 63.4200
    private val startLon = 10.3951
    private val metresPerDegreeLat = CumulativeDistanceCalculator.EARTH_RADIUS_M * Math.PI / 180.0

    /** The sign, 900 m north: the scripted ride covers 869 m, stopping 31 m short. */
    private val stopMetresNorth = 900.0

    private lateinit var snapshot: PlacementRun
    private var mapRef: MapLibreMap? = null
    private var mapViewRef: org.maplibre.android.maps.MapView? = null

    @Before
    fun setUp() {
        snapshot = runWithStopAt(stopMetresNorth)
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
        coEvery { repository.getRun(any()) } answers { snapshot }
    }

    @Test
    fun theMapOpensUpAtSpeedAndClosesInOnTheSign() {
        val viewModel = createViewModel()

        compose.activity.runOnUiThread {
            compose.activity.window.addFlags(
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
            )
        }
        compose.setContent {
            CourseMapperTheme(darkTheme = false) {
                RunNavigationScreen(onExit = {}, viewModel = viewModel)
            }
        }
        settle(3_000)

        // The map object the screen actually created.
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            mapRef = findMap()
        }
        assertTrue("no MapLibre map was created by the screen", mapRef != null)

        driveFor(speed = 1.5f, seconds = 6)
        val crawlZoom = renderedZoom()

        driveFor(speed = 14f, seconds = 30)
        val cruiseZoom = renderedZoom()

        driveFor(speed = 8f, seconds = 25)
        val midZoom = renderedZoom()

        driveFor(speed = 8f, seconds = 30)
        val arrivedZoom = renderedZoom()
        val signOnScreen = stopIsOnScreen()

        // arrow on the rider after the whole ride. Both converted to window
        // coordinates, the map is inset by the scaffold padding (status bar)
        val puck = compose.onNodeWithTag(RIDER_PUCK_TEST_TAG).fetchSemanticsNode()
        val puckWindowY = puck.positionInWindow.y + puck.size.height / 2f
        var riderWindowY = Float.NaN
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val onMap = mapRef!!.projection.toScreenLocation(
                LatLng(latAt(alongMetres), startLon)
            )
            val mapOrigin = IntArray(2)
            mapViewRef!!.getLocationInWindow(mapOrigin)
            riderWindowY = mapOrigin[1] + onMap.y
        }
        assertTrue(
            "the arrow ($puckWindowY) is not on the rider ($riderWindowY)",
            abs(puckWindowY - riderWindowY) <= puck.size.height / 2f
        )

        // The rendered zooms, for the record - this is what the map was
        // actually showing at each point of the ride.
        println(
            "RIDE crawl=$crawlZoom cruise=$cruiseZoom mid=$midZoom " +
                "arrived=$arrivedZoom puckY=$puckWindowY riderY=$riderWindowY"
        )

        assertTrue(
            "speeding up did not open the map: crawl $crawlZoom, cruise $cruiseZoom",
            cruiseZoom < crawlZoom - 0.5
        )
        assertTrue(
            "closing on the sign did not zoom in: mid $midZoom, arrived $arrivedZoom",
            arrivedZoom > midZoom + 0.5
        )
        assertTrue(
            "the approach never got tighter than cruising: " +
                "cruise $cruiseZoom, arrived $arrivedZoom",
            arrivedZoom > cruiseZoom + 0.5
        )
        assertTrue("the sign was off screen on arrival", signOnScreen)
    }

    private var clockMs = 0L
    private var alongMetres = 0.0

    /** Drive [seconds] further at [speed], one fix a second, moving at the speed reported. */
    private fun driveFor(speed: Float, seconds: Int) {
        repeat(seconds) {
            clockMs += 1_000
            alongMetres += speed
            runBlocking {
                locations.emit(fix(clockMs, latAt(alongMetres), startLon, speed))
            }
            settle(250)
        }
        // Let the follow camera converge: the zoom time constant is 0.8 s, so
        // three seconds is within a percent of the target.
        settle(3_000)
    }

    /** The zoom MapLibre is currently rendering - not the policy's opinion. */
    private fun renderedZoom(): Double {
        var zoom = Double.NaN
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            zoom = mapRef!!.cameraPosition.zoom
        }
        assertTrue("no camera zoom", !zoom.isNaN())
        return zoom
    }

    /** Is the sign inside the map viewport right now? */
    private fun stopIsOnScreen(): Boolean {
        var onScreen = false
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val map = mapRef!!
            val point = map.projection.toScreenLocation(
                LatLng(latAt(stopMetresNorth), startLon)
            )
            val view = compose.activity.window.decorView
            onScreen = point.x >= 0 && point.x <= view.width &&
                point.y >= 0 && point.y <= view.height
        }
        return onScreen
    }

    private fun settle(millis: Long) {
        val deadline = System.currentTimeMillis() + millis
        while (System.currentTimeMillis() < deadline) {
            compose.waitForIdle()
            Thread.sleep(50)
        }
    }

    /** The screen's MapView is somewhere under the activity's content. */
    private fun findMap(): MapLibreMap? {
        val root = compose.activity.window.decorView
        val found = ArrayList<org.maplibre.android.maps.MapView>()
        fun walk(v: android.view.View) {
            if (v is org.maplibre.android.maps.MapView) found += v
            if (v is android.view.ViewGroup) {
                for (i in 0 until v.childCount) walk(v.getChildAt(i))
            }
        }
        walk(root)
        mapViewRef = found.firstOrNull()
        var map: MapLibreMap? = null
        mapViewRef?.getMapAsync { map = it }
        return map
    }

    private fun latAt(metresNorth: Double) = startLat + metresNorth / metresPerDegreeLat

    private fun fix(elapsedMs: Long, lat: Double, lon: Double, speed: Float): Location =
        mockk {
            every { latitude } returns lat
            every { longitude } returns lon
            every { accuracy } returns 5f
            every { time } returns System.currentTimeMillis()
            every { elapsedRealtimeNanos } returns (elapsedMs + 1_000L) * 1_000_000L
            every { hasSpeed() } returns true
            every { this@mockk.speed } returns speed
            every { hasBearing() } returns true
            every { bearing } returns 0f // due north, straight at the sign
        }

    private fun runWithStopAt(metresNorth: Double) = PlacementRun(
        id = 42L,
        name = "device ride",
        createdAt = 0L,
        updatedAt = 0L,
        ordering = RunOrdering.SPINE,
        spineCourseId = null,
        startedAt = null,
        completedAt = null,
        totalPlannedMetres = 0.0,
        travelProfile = TravelProfile.CAR,
        stops = listOf(
            RunStop(
                id = 1L,
                runId = 42L,
                stopIndex = 0,
                lat = latAt(metresNorth),
                lon = startLon,
                signs = listOf(RunSign("5 km", "1 km")),
                state = RunStopState.PENDING
            )
        )
    )

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
