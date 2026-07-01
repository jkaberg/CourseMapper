package com.coursemapper.domain

import javax.inject.Inject
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.sin

/**
 * Follow camera zoom and tilt. Rider position on screen is fixed elsewhere
 * ([com.coursemapper.map.camera.MapCameraController.FOLLOW_ANCHOR_FRACTION]).
 *
 * Both parts answer how much ground must be on screen, through [zoomToFit]:
 * - travelling: [LOOKAHEAD_SECONDS] of travel, so speed buys warning
 * - approaching: frame both rider and stop ([frameZoom]), with the look-ahead
 *   as floor so a stop 2 km away doesn't zoom out to an aerial photo
 *
 * Tilt flattens over the last [FLATTEN_SECONDS] of travel. Mapbox uses a fixed
 * 180 m, our stops can be 200 m apart.
 */
class NavigationZoomPolicy @Inject constructor() {
    companion object {
        /**
         * Zoom-out backstop, below anything the look-ahead asks for so it never
         * clips on some phones and not others. Packs go down to z5.
         */
        const val MIN_ZOOM = 13.5

        /** Zoom-in backstop, [TARGET_FRAME_FLOOR_M] is what actually stops the approach. */
        const val MAX_ZOOM = 20.0

        /** Zoom before the first fix has been processed. */
        const val INITIAL_ZOOM = 16.0

        /**
         * Max zoom target change per second (not per fix, that depended on GPS
         * rate). Bounds step changes like a retarget, [FollowCameraSmoother] does
         * the easing.
         */
        const val MAX_ZOOM_RATE_PER_SECOND = 1.2

        /** Assumed gap between fixes when the caller does not supply one. */
        const val NOMINAL_FIX_SECONDS = 1.0

        /** Longest gap the rate limiter honours, after a GPS outage it shouldn't allow any jump. */
        const val MAX_LIMITER_SECONDS = 2.0

        /** Seconds of travel kept visible ahead of the rider. */
        const val LOOKAHEAD_SECONDS = 20.0

        /** Shortest look-ahead, a stationary rider keeps a sensible zoom. */
        const val MIN_LOOKAHEAD_M = 40.0

        /** Longest look-ahead, metres; pairs with [MIN_ZOOM]. */
        const val MAX_LOOKAHEAD_M = 500.0

        /**
         * Approach zoom stops here, the sign is going in the ground by now. The
         * dwell ring runs off screen, the banner has the distance.
         */
        const val TARGET_FRAME_FLOOR_M = 15.0

        /**
         * Inside this the stop decides the zoom regardless of speed. Mostly for
         * slow riders, where the look-ahead bottoms out and would crop the sign.
         */
        const val FRAME_TAKEOVER_RANGE_M = 250.0

        const val NEAR_MARKER_DISTANCE_M = 30.0
        const val MOVING_TILT_DEGREES = 50.0
        const val NEAR_MARKER_TILT_DEGREES = 22.0

        /** Seconds of travel the flatten-out is spread over. */
        const val FLATTEN_SECONDS = 8.0

        /** The flatten distance never exceeds this, however fast the rider is. */
        const val MAX_FLATTEN_DISTANCE_M = 120.0

        /** Framed targets fill this much, so the ring isn't on the very edge. */
        const val FRAME_FILL = 0.8

        /** MapLibre's tile size in logical points; its zoom scale is built on it. */
        private const val TILE_SIZE_POINTS = 512.0

        private const val EARTH_CIRCUMFERENCE_M = 2.0 * PI * 6_378_137.0
    }

    /**
     * What the camera fits the target into.
     *
     * [visibleBandHeightDp] is the map height not covered by banner and controls,
     * in logical points. [anchorFraction] is where the rider sits in that band.
     * [cameraBearingDeg] is 0 in north-up and the travel bearing in course-up.
     * Flat projection on purpose, tilt only shows more ahead.
     */
    data class Framing(
        val visibleBandHeightDp: Double,
        val viewportWidthDp: Double,
        val latitudeDeg: Double,
        val anchorFraction: Double,
        val cameraBearingDeg: Double = 0.0
    ) {
        val isUsable: Boolean
            get() = visibleBandHeightDp > 0.0 && viewportWidthDp > 0.0 && anchorFraction > 0.0
    }

    /**
     * Zoom for this fix. Without [bearingToTargetDeg] the stop is framed as dead
     * ahead. [elapsedSeconds] is for the rate limiter.
     */
    fun zoom(
        speedMetresPerSecond: Float?,
        distanceToTargetMetres: Double?,
        previousZoom: Double?,
        framing: Framing? = null,
        bearingToTargetDeg: Double? = null,
        elapsedSeconds: Double? = null
    ): Double {
        val speed = speedMetresPerSecond?.toDouble()?.coerceAtLeast(0.0) ?: 0.0
        val usable = framing?.takeIf { it.isUsable }
        val travelZoom = lookAheadZoom(speed, usable)

        val target = when {
            distanceToTargetMetres == null -> travelZoom
            usable != null -> {
                val framed = frameZoom(distanceToTargetMetres, usable, bearingToTargetDeg)
                // look-ahead is the floor while the stop is far, but at 70 m and
                // 3 m/s it wants less road than the framing and the stop goes off
                // screen. Inside FRAME_TAKEOVER_RANGE_M the stop wins.
                if (framed >= travelZoom ||
                    distanceToTargetMetres <= FRAME_TAKEOVER_RANGE_M
                ) framed else travelZoom
            }
            else -> distanceZoom(distanceToTargetMetres, travelZoom)
        }

        val boundedTarget = target.coerceIn(MIN_ZOOM, MAX_ZOOM)
        if (previousZoom == null) return boundedTarget
        val dt = (elapsedSeconds ?: NOMINAL_FIX_SECONDS).coerceIn(0.0, MAX_LIMITER_SECONDS)
        val maxChange = MAX_ZOOM_RATE_PER_SECOND * dt
        return boundedTarget.coerceIn(previousZoom - maxChange, previousZoom + maxChange)
    }

    /** Zoom showing [LOOKAHEAD_SECONDS] ahead, or the fallback curve before layout. */
    fun lookAheadZoom(speedMetresPerSecond: Double, framing: Framing?): Double {
        if (framing == null || !framing.isUsable) return fallbackSpeedZoom(speedMetresPerSecond)
        val metres = (speedMetresPerSecond * LOOKAHEAD_SECONDS)
            .coerceIn(MIN_LOOKAHEAD_M, MAX_LOOKAHEAD_M)
        val pointsAhead = framing.anchorFraction * framing.visibleBandHeightDp
        return zoomToFit(metres, pointsAhead, framing.latitudeDeg)
    }

    /**
     * Zoom with both rider and stop on screen. The stop is resolved into ahead and
     * sideways in camera space and whichever binds wins, so stops off to the side
     * or behind (after a retarget) are framed properly. Floored at
     * [TARGET_FRAME_FLOOR_M].
     */
    fun frameZoom(
        distanceMetres: Double,
        framing: Framing,
        bearingToTargetDeg: Double? = null
    ): Double {
        // no early MAX_ZOOM at 0, the floor handles it and the camera would keep
        // closing in over the last metres
        val framed = distanceMetres.coerceAtLeast(TARGET_FRAME_FLOOR_M)

        val offAxisRadians = bearingToTargetDeg?.let {
            (it - framing.cameraBearingDeg) * PI / 180.0
        }
        val forward = offAxisRadians?.let { framed * cos(it) } ?: framed
        val lateral = offAxisRadians?.let { abs(framed * sin(it)) } ?: 0.0

        val forwardShare =
            if (forward >= 0.0) framing.anchorFraction else 1.0 - framing.anchorFraction
        val forwardBudget = forwardShare * framing.visibleBandHeightDp * FRAME_FILL
        val lateralBudget = 0.5 * framing.viewportWidthDp * FRAME_FILL

        // A zero extent on an axis is not a constraint, and zoomToFit answers
        // MAX_ZOOM for it - which is exactly "does not bind" under the min.
        return minOf(
            zoomToFit(abs(forward), forwardBudget, framing.latitudeDeg),
            zoomToFit(lateral, lateralBudget, framing.latitudeDeg)
        )
    }

    /**
     * Zoom where [metres] spans [points]. MapLibre's metres per point at zoom z
     * is `cos(lat) * circumference / (2^z * 512)`.
     */
    fun zoomToFit(metres: Double, points: Double, latitudeDeg: Double): Double {
        if (metres <= 0.0 || points <= 0.0) return MAX_ZOOM
        val latitudeRadians = latitudeDeg * PI / 180.0
        val scale = cos(latitudeRadians).coerceAtLeast(0.01) *
            EARTH_CIRCUMFERENCE_M * points /
            (TILE_SIZE_POINTS * metres)
        if (scale <= 0.0) return MIN_ZOOM
        return ln(scale) / ln(2.0)
    }

    /**
     * Tilt, flattened over the last [FLATTEN_SECONDS]. Floored at
     * [NEAR_MARKER_DISTANCE_M], capped at [MAX_FLATTEN_DISTANCE_M].
     */
    fun tiltDegrees(speedMetresPerSecond: Float?, distanceToTargetMetres: Double?): Double {
        val distance = distanceToTargetMetres ?: return MOVING_TILT_DEGREES
        if (distance <= NEAR_MARKER_DISTANCE_M) return NEAR_MARKER_TILT_DEGREES

        val speed = speedMetresPerSecond?.toDouble()?.coerceAtLeast(0.0) ?: 0.0
        val flattenFrom = (speed * FLATTEN_SECONDS)
            .coerceIn(NEAR_MARKER_DISTANCE_M, MAX_FLATTEN_DISTANCE_M)
        if (distance >= flattenFrom) return MOVING_TILT_DEGREES

        return interpolate(
            distance,
            NEAR_MARKER_DISTANCE_M, flattenFrom,
            NEAR_MARKER_TILT_DEGREES, MOVING_TILT_DEGREES
        )
    }

    /** Speed table for frames before the map has been measured. */
    private fun fallbackSpeedZoom(speed: Double): Double = when {
        speed <= 4.0 -> interpolate(speed, 0.0, 4.0, 18.2, 17.8)
        speed <= 15.0 -> interpolate(speed, 4.0, 15.0, 17.8, 16.7)
        speed <= 25.0 -> interpolate(speed, 15.0, 25.0, 16.7, 16.2)
        else -> 16.0
    }

    /** Distance curve for frames before the map has been measured. */
    private fun distanceZoom(distance: Double, speedZoom: Double): Double = when (distance) {
        in 0.0..NEAR_MARKER_DISTANCE_M -> MAX_ZOOM
        in NEAR_MARKER_DISTANCE_M..100.0 -> interpolate(
            distance, NEAR_MARKER_DISTANCE_M, 100.0, MAX_ZOOM, maxOf(speedZoom, 18.0)
        )
        in 100.0..500.0 -> maxOf(
            speedZoom, interpolate(distance, 100.0, 500.0, 18.0, speedZoom)
        )
        else -> speedZoom
    }

    private fun interpolate(
        value: Double,
        from: Double,
        to: Double,
        start: Double,
        end: Double
    ): Double {
        if (to == from) return end
        val fraction = ((value - from) / (to - from)).coerceIn(0.0, 1.0)
        return start + (end - start) * fraction
    }
}
