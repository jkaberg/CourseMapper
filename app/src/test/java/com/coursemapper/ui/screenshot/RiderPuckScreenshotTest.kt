package com.coursemapper.ui.screenshot

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.coursemapper.map.camera.MapCameraController
import com.coursemapper.ui.run.RiderPuckOverlay
import org.junit.Test

/**
 * Baselines for the rider puck. The navigation screen itself can't be captured
 * (MapLibre's native lib under Robolectric kills the JVM). Anchored with the
 * real chrome heights, so a banner size change moves the baseline.
 */
class RiderPuckScreenshotTest : ScreenshotTest() {

    /** A stand-in for the map: the overlay has to be given a height to anchor in. */
    private val mapHeight = 640.dp

    @Test
    fun `course-up, anchored in the visible band`() =
        screenshot("rider_puck_course_up") {
            Box(Modifier.fillMaxWidth().height(mapHeight)) {
                RiderPuckOverlay(
                    rotationDeg = { 0f },
                    anchorFraction = MapCameraController.anchorFractionInBand(
                        viewportHeightPx = 640,
                        topChromePx = 132,
                        bottomChromePx = 170
                    )
                )
            }
        }

    @Test
    fun `north-up, turned to the direction of travel`() =
        screenshot("rider_puck_north_up") {
            Box(Modifier.fillMaxWidth().height(mapHeight)) {
                RiderPuckOverlay(
                    // North-up is the mode where the arrow has to carry the
                    // heading on its own, because the map is not turned.
                    rotationDeg = { 135f },
                    anchorFraction = MapCameraController.anchorFractionInBand(
                        viewportHeightPx = 640,
                        topChromePx = 132,
                        bottomChromePx = 170,
                        bandFraction = 0.5
                    )
                )
            }
        }
}
