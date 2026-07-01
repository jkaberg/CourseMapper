package com.coursemapper.domain

import kotlin.math.abs
import kotlin.math.exp

/**
 * Turns 1 Hz fixes into a follow camera that moves every frame.
 *
 * Each fix is a target to converge on rather than a position to jump to:
 *
 * ```
 * alpha = 1 - exp(-dt / tau)
 * current += (target - current) * alpha
 * ```
 *
 * Frame rate independent, never overshoots, and at steady speed settles into a
 * constant lag of [POSITION_TIME_CONSTANT_S], which is what smooth looks like.
 *
 * - position leads bearing, zoom and tilt, otherwise the map wobbles
 * - [MAX_FRAME_SECONDS] clamps dt so a frame after a stall doesn't jump
 * - jumps over [SNAP_DISTANCE_M] (first fix, back from background, tunnel) snap
 * - bearing takes the short way round
 */
class FollowCameraSmoother(
    private val distanceCalculator: CumulativeDistanceCalculator = CumulativeDistanceCalculator()
) {

    companion object {
        /** Position convergence time constant, seconds. */
        const val POSITION_TIME_CONSTANT_S = 0.55

        /** Heading convergence time constant, seconds. */
        const val BEARING_TIME_CONSTANT_S = 0.70

        /** Zoom convergence time constant, seconds. */
        const val ZOOM_TIME_CONSTANT_S = 0.80

        /** Tilt convergence time constant, seconds. */
        const val TILT_TIME_CONSTANT_S = 0.80

        /** Longest step a single frame may advance, seconds. */
        const val MAX_FRAME_SECONDS = 0.05

        /** A position jump beyond this is adopted outright, not eased. */
        const val SNAP_DISTANCE_M = 80.0

        /**
         * Max lag behind the direction of travel. The time constant alone lets the
         * camera fall far behind on a hairpin. Applied before smoothing.
         */
        const val MAX_BEARING_LAG_DEG = 45.0

        /** Below these gaps the camera has arrived and can stop animating. */
        const val SETTLED_DISTANCE_M = 0.05
        const val SETTLED_BEARING_DEG = 0.05
        const val SETTLED_ZOOM = 0.001
        const val SETTLED_TILT_DEG = 0.05
    }

    /**
     * Camera target. Null [headingDeg] draws a plain dot, but the last heading is
     * kept so a short gap doesn't spin the puck to north and back.
     */
    data class Target(
        val lat: Double,
        val lon: Double,
        val headingDeg: Double?,
        val cameraBearingDeg: Double,
        val zoom: Double,
        val tiltDeg: Double
    )

    /** One frame's worth of camera and puck state. */
    data class Frame(
        val lat: Double,
        val lon: Double,
        val headingDeg: Double?,
        val cameraBearingDeg: Double,
        val zoom: Double,
        val tiltDeg: Double
    )

    private var lat = 0.0
    private var lon = 0.0
    private var heading = 0.0
    private var cameraBearing = 0.0
    private var zoom = 0.0
    private var tilt = 0.0
    private var started = false

    /** Forget the current frame; the next [advance] adopts its target whole. */
    fun reset() {
        started = false
    }

    /** Advance towards [target] by [elapsedSeconds]. Once per display frame. */
    fun advance(target: Target, elapsedSeconds: Double): Frame {
        if (!started) {
            started = true
            lat = target.lat
            lon = target.lon
            heading = target.headingDeg ?: target.cameraBearingDeg
            cameraBearing = target.cameraBearingDeg
            zoom = target.zoom
            tilt = target.tiltDeg
            return frame(target)
        }

        val dt = elapsedSeconds.coerceIn(0.0, MAX_FRAME_SECONDS)
        if (dt <= 0.0) return frame(target)

        val jump = distanceCalculator.haversineMetres(lat, lon, target.lat, target.lon)
        if (jump >= SNAP_DISTANCE_M) {
            lat = target.lat
            lon = target.lon
        } else {
            val positionAlpha = alpha(dt, POSITION_TIME_CONSTANT_S)
            lat += (target.lat - lat) * positionAlpha
            lon += (target.lon - lon) * positionAlpha
        }

        val bearingAlpha = alpha(dt, BEARING_TIME_CONSTANT_S)
        target.headingDeg?.let { heading = approachAngle(heading, it, bearingAlpha) }
        // Cap the lag first, then ease the rest of the way: the smoothing keeps
        // doing the visible work, and only the part that has fallen further
        // behind than [MAX_BEARING_LAG_DEG] is taken away outright.
        cameraBearing = clampLag(cameraBearing, target.cameraBearingDeg, MAX_BEARING_LAG_DEG)
        cameraBearing = approachAngle(cameraBearing, target.cameraBearingDeg, bearingAlpha)

        zoom += (target.zoom - zoom) * alpha(dt, ZOOM_TIME_CONSTANT_S)
        tilt += (target.tiltDeg - tilt) * alpha(dt, TILT_TIME_CONSTANT_S)

        return frame(target)
    }

    /** Converged, the caller can stop requesting frames until the next fix. */
    fun isSettled(target: Target): Boolean {
        if (!started) return false
        return distanceCalculator.haversineMetres(lat, lon, target.lat, target.lon) <= SETTLED_DISTANCE_M &&
            (target.headingDeg == null || angleGap(heading, target.headingDeg) <= SETTLED_BEARING_DEG) &&
            angleGap(cameraBearing, target.cameraBearingDeg) <= SETTLED_BEARING_DEG &&
            abs(target.zoom - zoom) <= SETTLED_ZOOM &&
            abs(target.tiltDeg - tilt) <= SETTLED_TILT_DEG
    }

    private fun frame(target: Target) = Frame(
        lat = lat,
        lon = lon,
        // The heading is remembered but only reported while the target has one,
        // so losing the bearing renders a dot instead of an arrow pointing at a
        // stale direction.
        headingDeg = target.headingDeg?.let { heading },
        cameraBearingDeg = cameraBearing,
        zoom = zoom,
        tiltDeg = tilt
    )

    private fun alpha(dt: Double, timeConstantSeconds: Double): Double =
        1.0 - exp(-dt / timeConstantSeconds)

    /** Move [from] a fraction of the way to [to] the short way round. */
    private fun approachAngle(from: Double, to: Double, alpha: Double): Double =
        normalize(from + signedDelta(from, to) * alpha)

    /** Pull [from] to within [maxLagDeg] of [to], the short way round. */
    private fun clampLag(from: Double, to: Double, maxLagDeg: Double): Double {
        val delta = signedDelta(from, to)
        if (abs(delta) <= maxLagDeg) return from
        val excess = delta - maxLagDeg * (if (delta > 0) 1.0 else -1.0)
        return normalize(from + excess)
    }

    private fun angleGap(from: Double, to: Double): Double = abs(signedDelta(from, to))

    /** Signed shortest turn from [from] to [to], in [-180, 180). */
    private fun signedDelta(from: Double, to: Double): Double =
        ((to - from + 540.0) % 360.0) - 180.0

    private fun normalize(degrees: Double): Double = ((degrees % 360.0) + 360.0) % 360.0
}
