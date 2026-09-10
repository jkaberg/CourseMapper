package com.coursemapper.map.camera

import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.coursemapper.map.MapOverlayManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.maplibre.android.MapLibre
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/**
 * Checks that MapLibre actually puts the camera target where
 * [MapCameraController.anchorPadding] says, also at the 50° navigation tilt.
 * The puck overlay relies on it and the JVM tests can only assume it.
 * Uses the map's own projection, no pixels or network needed.
 */
@RunWith(AndroidJUnit4::class)
class FollowAnchorOnDeviceTest {

    private val trondheim = LatLng(63.4305, 10.3951)

    /**
     * Run [block] against a laid-out map, and report the map's pixel height.
     */
    private fun withMap(block: (map: MapLibreMap, view: MapView, heightPx: Int) -> Unit) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        // MapLibre refuses every interaction from a worker thread, this one
        // included - the test body runs on the instrumentation thread.
        instrumentation.runOnMainSync { MapLibre.getInstance(instrumentation.targetContext) }

        // ComponentActivity, an activity from the androidTest manifest ends up in
        // the test apk. `compose-ui-test-manifest` puts this one in the debug apk.
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            var mapView: MapView? = null
            val ready = CountDownLatch(1)
            var map: MapLibreMap? = null

            scenario.onActivity { activity ->
                // A sleeping or locked phone never creates the GL surface, so
                // getMapAsync never fires and this reads as "the map never
                // became available".  Cost one confusing red run.
                activity.window.addFlags(
                    WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                        WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                        WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
                )
                val container = FrameLayout(activity)
                activity.setContentView(
                    container,
                    ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                )
                val view = MapView(activity)
                mapView = view
                view.onCreate(null)
                view.onStart()
                view.onResume()
                container.addView(
                    view,
                    ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                )
                view.getMapAsync { ready.countDown(); map = it }
            }

            assertTrue("the map never became available", ready.await(30, TimeUnit.SECONDS))
            instrumentation.waitForIdleSync()

            scenario.onActivity {
                val view = requireNotNull(mapView)
                block(requireNotNull(map), view, view.height)
            }
        }
    }

    /** Where [target] lands as a fraction of map height with [anchorFraction] padding and [tiltDeg] tilt. */
    private fun anchorReachedBy(
        map: MapLibreMap,
        heightPx: Int,
        anchorFraction: Double,
        tiltDeg: Double
    ): Double {
        val padding = MapCameraController.anchorPadding(heightPx, anchorFraction)
        MapOverlayManager.setCamera(
            map,
            lat = trondheim.latitude,
            lon = trondheim.longitude,
            zoom = 17.0,
            viewportPadding = padding,
            bearingDeg = 0.0,
            tiltDeg = tiltDeg
        )
        val onScreen = map.projection.toScreenLocation(trondheim)
        return onScreen.y / heightPx.toDouble()
    }

    @Test
    fun theCameraPutsTheRiderOnTheAnchorThePuckIsDrawnAt() = withMap { map, _, heightPx ->
        assertTrue("the map view was never laid out", heightPx > 0)

        // Flat first: this is the case the padding arithmetic was derived for.
        assertEquals(
            "flat camera missed the anchor",
            MapCameraController.FOLLOW_ANCHOR_FRACTION,
            anchorReachedBy(map, heightPx, MapCameraController.FOLLOW_ANCHOR_FRACTION, 0.0),
            0.01
        )
    }

    @Test
    fun tiltDoesNotSlideTheRiderOffTheAnchor() = withMap { map, _, heightPx ->
        // tilt pivots around the camera target so it shouldn't move - check it
        // isn't applying the padding in projected space
        val flat = anchorReachedBy(map, heightPx, MapCameraController.FOLLOW_ANCHOR_FRACTION, 0.0)
        val tilted = anchorReachedBy(
            map, heightPx, MapCameraController.FOLLOW_ANCHOR_FRACTION, 50.0
        )
        assertEquals("50° of tilt moved the rider on screen", flat, tilted, 0.01)
        assertEquals(
            "tilted camera missed the anchor",
            MapCameraController.FOLLOW_ANCHOR_FRACTION,
            tilted,
            0.01
        )
    }

    @Test
    fun theBandAnchorLandsWhereTheChromeLeavesRoom() = withMap { map, _, heightPx ->
        // The shipped anchor is a fraction of the band between the banner and
        // the controls, not of the raw viewport.  Same chrome proportions the
        // navigation screen measures on this phone.
        val topChrome = (heightPx * 0.14).toInt()
        val bottomChrome = (heightPx * 0.20).toInt()
        val fraction = MapCameraController.anchorFractionInBand(
            heightPx, topChrome, bottomChrome
        )
        val reached = anchorReachedBy(map, heightPx, fraction, 50.0)
        assertEquals("band anchor missed", fraction, reached, 0.01)

        // And it really is inside the visible band, below its middle.
        val bandTop = topChrome / heightPx.toDouble()
        val bandBottom = 1.0 - bottomChrome / heightPx.toDouble()
        assertTrue("rider is under the banner", reached > bandTop)
        assertTrue("rider is behind the controls", reached < bandBottom)
        assertTrue(
            "rider is not below the middle of the band",
            reached > (bandTop + bandBottom) / 2.0
        )
    }

    @Test
    fun northUpCentresTheBandToo() = withMap { map, _, heightPx ->
        val topChrome = (heightPx * 0.14).toInt()
        val bottomChrome = (heightPx * 0.20).toInt()
        val fraction = MapCameraController.anchorFractionInBand(
            heightPx, topChrome, bottomChrome, bandFraction = 0.5
        )
        val reached = anchorReachedBy(map, heightPx, fraction, 0.0)
        assertEquals(fraction, reached, 0.01)
        // Unequal chrome, so the band's middle is NOT the viewport's middle.
        assertTrue(
            "band centring collapsed to viewport centring",
            abs(reached - 0.5) > 0.01
        )
    }
}
