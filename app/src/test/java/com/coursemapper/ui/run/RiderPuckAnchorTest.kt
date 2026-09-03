package com.coursemapper.ui.run

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import com.coursemapper.map.camera.MapCameraController
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Where the puck actually lands on screen. Measures the composed node - the
 * modifier order decides which constraints the offset is computed against, and
 * behind `.size()` the puck ended up top left under the banner.
 */
@RunWith(RobolectricTestRunner::class)
@Config(
    application = android.app.Application::class,
    qualifiers = RobolectricDeviceQualifiers.Pixel5
)
class RiderPuckAnchorTest {

    @get:Rule
    val compose = createComposeRule()

    private var anchor by mutableDoubleStateOf(0.65)

    private fun setUpContent() {
        compose.setContent {
            Box(Modifier.fillMaxSize()) {
                RiderPuckOverlay(
                    rotationDeg = { 0f },
                    anchorFraction = anchor
                )
            }
        }
        compose.waitForIdle()
    }

    /** The drawn puck's centre in root pixels, and the map it sits in. */
    private fun puckCentre(): Triple<Float, Float, androidx.compose.ui.unit.IntSize> {
        val puck = compose.onNodeWithTag(RIDER_PUCK_TEST_TAG).fetchSemanticsNode()
        val root = compose.onRoot().fetchSemanticsNode().size
        return Triple(
            puck.positionInRoot.x + puck.size.width / 2f,
            puck.positionInRoot.y + puck.size.height / 2f,
            root
        )
    }

    @Test
    fun `the puck lands on the anchor, not in the corner`() {
        setUpContent()
        val (centreX, centreY, root) = puckCentre()

        assertEquals("horizontally centred", root.width / 2f, centreX, 1f)
        assertEquals("at 65% of the map height", root.height * 0.65f, centreY, 1f)
        // The regression put it here.  Naming it keeps the test honest about
        // what it is actually defending.
        assertTrue("puck is not in the top-left corner", centreY > root.height * 0.5f)
    }

    @Test
    fun `the puck follows the anchor it is given`() {
        setUpContent()
        listOf(0.5, 0.65, 0.8).forEach { fraction ->
            compose.runOnUiThread { anchor = fraction }
            compose.waitForIdle()
            val (_, centreY, root) = puckCentre()
            assertEquals("anchor $fraction", root.height * fraction.toFloat(), centreY, 1f)
        }
    }

    @Test
    fun `the puck sits where the camera is aimed`() {
        // The overlay and the camera must read one number, or the rider is
        // drawn somewhere the map is not centred.  This is the screen half of
        // that contract; MapCameraControllerTest is the camera half.
        val fraction = MapCameraController.anchorFractionInBand(
            viewportHeightPx = 800,
            topChromePx = 132,
            bottomChromePx = 170
        )
        anchor = fraction
        setUpContent()
        val (_, centreY, root) = puckCentre()
        assertEquals(root.height * fraction, centreY.toDouble(), 1.0)
    }
}
