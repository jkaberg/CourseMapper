package com.coursemapper.ui.run

import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.platform.app.InstrumentationRegistry
import com.coursemapper.map.CourseMapView
import com.coursemapper.map.MapOverlayManager
import com.coursemapper.map.MapState
import com.coursemapper.map.camera.MapCameraController
import com.coursemapper.map.rememberMapState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.maplibre.android.geometry.LatLng
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Real [CourseMapView] with follow padding and tilt, real [RiderPuckOverlay] on
 * top, and checks both agree on where the rider is on screen. The other tests
 * each check one side against a number, this checks them against each other.
 * An orange marker sits on the same coordinate so it's visible while running.
 */
class RiderPuckOnMapTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val rider = LatLng(63.4305, 10.3951)

    /** Chrome proportions the navigation screen measures on a phone this size. */
    private val topChromeFraction = 0.14
    private val bottomChromeFraction = 0.20

    @Test
    fun theArrowIsDrawnOnTheGroundPointTheMapPutsTheRiderAt() {
        val cameraPlaced = AtomicBoolean(false)
        lateinit var mapStateRef: MapState
        var anchorUsed = 0.0

        compose.activity.runOnUiThread {
            compose.activity.window.addFlags(
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
            )
        }

        compose.setContent {
            val mapState = rememberMapState()
            mapStateRef = mapState
            var heightPx by remember { mutableIntStateOf(0) }
            val anchor = MapCameraController.anchorFractionInBand(
                viewportHeightPx = heightPx,
                topChromePx = (heightPx * topChromeFraction).toInt(),
                bottomChromePx = (heightPx * bottomChromeFraction).toInt()
            )
            anchorUsed = anchor
            val padding = MapCameraController.anchorPadding(heightPx, anchor)

            Box(
                Modifier
                    .fillMaxSize()
                    .onSizeChanged { heightPx = it.height }
            ) {
                CourseMapView(
                    modifier = Modifier.fillMaxSize(),
                    state = mapState,
                    viewportPadding = padding
                )
                RiderPuckOverlay(rotationDeg = { 0f }, anchorFraction = anchor)
            }

            LaunchedEffect(mapState.isReady, heightPx) {
                if (!mapState.isReady || heightPx <= 0) return@LaunchedEffect
                MapOverlayManager.setMarkers(
                    mapState.map,
                    listOf(Triple(rider.latitude, rider.longitude, "rider"))
                )
                MapOverlayManager.setCamera(
                    mapState.map,
                    lat = rider.latitude,
                    lon = rider.longitude,
                    zoom = 17.5,
                    viewportPadding = padding,
                    bearingDeg = 0.0,
                    tiltDeg = 50.0
                )
                cameraPlaced.set(true)
            }
        }

        // The style comes over the network; give it room.
        val deadline = System.currentTimeMillis() + 40_000
        while (!cameraPlaced.get() && System.currentTimeMillis() < deadline) {
            compose.waitForIdle()
            Thread.sleep(250)
        }
        assertTrue("the map never finished loading", cameraPlaced.get())
        compose.waitForIdle()
        Thread.sleep(1_000)

        // Where the OVERLAY drew the arrow.
        val puck = compose.onNodeWithTag(RIDER_PUCK_TEST_TAG).fetchSemanticsNode()
        val puckCentreX = puck.positionInRoot.x + puck.size.width / 2f
        val puckCentreY = puck.positionInRoot.y + puck.size.height / 2f

        // runOnMainSync, runOnUiThread posts and returns before the assignments
        var mapX = Float.NaN
        var mapY = Float.NaN
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val point = mapStateRef.map!!.projection.toScreenLocation(rider)
            mapX = point.x
            mapY = point.y
        }
        assertTrue("the map was never asked", !mapX.isNaN() && !mapY.isNaN())

        // Half the puck's radius: closer than this and the arrow is sitting on
        // the rider by any standard a person on an ATV could care about.
        val tolerance = puck.size.height / 4f
        assertEquals("arrow is left/right of the rider", mapX, puckCentreX, tolerance)
        assertEquals("arrow is above/below the rider", mapY, puckCentreY, tolerance)
        assertTrue("anchor was never measured", anchorUsed > 0.0)
    }
}
