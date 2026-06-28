package com.coursemapper.ui.run

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.coursemapper.ui.designsystem.toComposeColor
import com.coursemapper.map.MapPalette
import com.coursemapper.map.camera.MapCameraController
import kotlin.math.roundToInt

/**
 * The rider puck in screen space, for follow mode.
 *
 * In follow mode the camera keeps the rider at [anchorFraction] and course-up
 * means zero rotation, so drawing through the map (GeoJSON + JNI every frame)
 * was a lot of work for something that doesn't move on screen. Browse mode still
 * uses `MapOverlayManager.setCurrentPosition` since the rider can be anywhere there.
 *
 * Overlay and camera must use the same anchor
 * ([MapCameraController.anchorFractionInBand]).
 */
@androidx.compose.runtime.Composable
fun RiderPuckOverlay(
    /** Screen space rotation. A lambda so per-frame updates only redraw, not recompose. */
    rotationDeg: () -> Float,
    /** Where the camera holds the rider, as a fraction of viewport height. */
    anchorFraction: Double,
    modifier: Modifier = Modifier,
    diameter: Dp = PUCK_DIAMETER
) {
    Box(modifier.fillMaxSize()) {
        Canvas(
            Modifier
                // must be outermost so it gets the map's constraints - behind
                // `.size()` it measures against 44 dp and the puck ends up top
                // left. `RiderPuckAnchorTest` covers this.
                .layout { measurable, constraints ->
                    val placeable = measurable.measure(constraints)
                    // Bounded in every real use (the parent fills the map), but
                    // an unbounded axis would make `layout()` throw, so fall
                    // back to the puck's own size rather than crash a run.
                    val width =
                        if (constraints.hasBoundedWidth) constraints.maxWidth else placeable.width
                    val height =
                        if (constraints.hasBoundedHeight) constraints.maxHeight else placeable.height
                    layout(width, height) {
                        // Centre the puck on (0.5 × width, anchorFraction ×
                        // height) of the map - the same point the camera
                        // targets.
                        placeable.place(
                            x = ((width - placeable.width) / 2f).roundToInt(),
                            y = (height * anchorFraction - placeable.height / 2f).roundToInt()
                        )
                    }
                }
                .size(diameter)
                // for `RiderPuckAnchorTest`
                .testTag(RIDER_PUCK_TEST_TAG)
        ) {
            val r = size.minDimension / 2f
            val centre = Offset(size.width / 2f, size.height / 2f)

            // Same shape as the map-anchored puck: a white-ringed disc with a
            // travel-direction triangle, so switching modes does not switch look.
            drawCircle(Color.White, radius = r, center = centre)
            drawCircle(
                MapPalette.ROUTE.toComposeColor(),
                radius = r * 0.86f,
                center = centre
            )
            rotate(degrees = rotationDeg(), pivot = centre) {
                val arrow = Path().apply {
                    moveTo(centre.x, centre.y - r * 0.58f)
                    lineTo(centre.x + r * 0.42f, centre.y + r * 0.46f)
                    lineTo(centre.x, centre.y + r * 0.20f)
                    lineTo(centre.x - r * 0.42f, centre.y + r * 0.46f)
                    close()
                }
                drawPath(arrow, Color.White)
            }
        }
    }
}

/** Identifies the drawn puck to `RiderPuckAnchorTest`. */
const val RIDER_PUCK_TEST_TAG = "riderPuck"

/** Matches `MapOverlayManager.PUCK_DIAMETER_DP` so the two renderers agree. */
val PUCK_DIAMETER: Dp = 44.dp
