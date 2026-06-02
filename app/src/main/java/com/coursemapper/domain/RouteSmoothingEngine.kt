package com.coursemapper.domain

import com.coursemapper.domain.model.RoutePoint
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Route smoother, runs on approval.
 *
 * 1. Constant velocity Kalman filter per axis in a [LocalFrame], measurement
 *    noise from each fix's reported accuracy.
 * 2. Distance gate thinning to [minPointSpacingMetres].
 *
 * Raw 1 Hz fixes with σ ≈ 5 m measure a 5 km course +212 % long, so filtering
 * isn't optional. A position-only model drags the route towards its centroid
 * (1.5-3.7 % short), and a fixed gain can't handle both 3 m and 15 m noise.
 *
 * Returns copies, the caller keeps the raw observation. The smoothed polyline is
 * the source for all marker placement and distances.
 */
@Singleton
class RouteSmoothingEngine @Inject constructor(
    private val distanceCalculator: CumulativeDistanceCalculator
) {

    /**
     * Spacing of the kept polyline. Part of the measurement, not just rendering -
     * it averages out what the filter left. ~250 vertices for 5 km.
     */
    var minPointSpacingMetres: Double = 20.0

    /**
     * Process noise, m/s². Stiff on purpose since we're surveying a fixed
     * course. 0.2 halved cross-track error but doubled length error.
     */
    var accelerationNoise: Double = 0.1

    /** Accuracy for fixes that have none, eg GPX imports. */
    var assumedAccuracyMetres: Double = 8.0

    /** Accuracy values outside this range are clamped before use as √R. */
    private val accuracyRangeMetres = 2.0..50.0

    /** Sample intervals outside this range are clamped; see [smooth]. */
    private val stepSecondsRange = 0.1..10.0

    /**
     * Smooth a route. Points already rejected by [LocationFilter] are left as
     * is, points dropped by the gate get isSmoothed = false.
     *
     * Pass the whole route, chunking restarts the filter transient and biases it.
     */
    fun smooth(points: List<RoutePoint>): List<RoutePoint> {
        val (accepted, rejected) = points.partition { it.isSmoothed }
        val filtered = applyConstantVelocityKalman(accepted)
        val spaced   = applyDistanceGate(filtered)

        // Merge back rejected raw points, preserving original index order via
        // timestamp (timestamps are unique per fix within a session).
        val rejectedMap = rejected.associateBy { it.timestampMs }
        val acceptedMap = spaced.associateBy { it.timestampMs }

        return points.map { raw ->
            acceptedMap[raw.timestampMs]
                ?: rejectedMap[raw.timestampMs]
                ?: raw.copy(isSmoothed = false)
        }
    }

    /** One axis: position and velocity with a 2x2 covariance. */
    private class Axis(position: Double, measurementVariance: Double) {
        var pos = position
        var vel = 0.0
        // Start uncertain in velocity: the first fix says nothing about heading.
        var p00 = measurementVariance
        var p01 = 0.0
        var p11 = INITIAL_VELOCITY_VARIANCE

        fun predict(dt: Double, accelVariance: Double) {
            // x = F x  with F = [[1, dt], [0, 1]]
            pos += vel * dt

            // P = F P Fᵀ + Q, Q from a white-noise-acceleration model.
            val dt2 = dt * dt
            val dt3 = dt2 * dt
            val dt4 = dt2 * dt2
            val newP00 = p00 + 2 * dt * p01 + dt2 * p11 + accelVariance * dt4 / 4.0
            val newP01 = p01 + dt * p11 + accelVariance * dt3 / 2.0
            val newP11 = p11 + accelVariance * dt2
            p00 = newP00; p01 = newP01; p11 = newP11
        }

        fun update(measurement: Double, measurementVariance: Double) {
            // H = [1, 0], so the innovation covariance is simply p00 + R.
            val s = p00 + measurementVariance
            if (s <= 0.0) return
            val k0 = p00 / s
            val k1 = p01 / s
            val residual = measurement - pos
            pos += k0 * residual
            vel += k1 * residual
            // P = (I - K H) P
            val newP00 = (1 - k0) * p00
            val newP01 = (1 - k0) * p01
            val newP11 = p11 - k1 * p01
            p00 = newP00; p01 = newP01; p11 = newP11
        }

        companion object {
            /** (10 m/s)² - wide enough to cover anything from walking to a car. */
            const val INITIAL_VELOCITY_VARIANCE = 100.0
        }
    }

    private fun applyConstantVelocityKalman(points: List<RoutePoint>): List<RoutePoint> {
        if (points.size < 3) return points   // nothing meaningful to estimate

        val frame = LocalFrame.at(points.first().lat, points.first().lon)
        val accelVariance = accelerationNoise * accelerationNoise

        val east  = Axis(frame.eastMetres(points.first().lon), varianceOf(points.first()))
        val north = Axis(frame.northMetres(points.first().lat), varianceOf(points.first()))

        val result = ArrayList<RoutePoint>(points.size)
        result.add(points.first())
        var previousMs = points.first().timestampMs

        for (i in 1 until points.size) {
            val p = points[i]
            // Clamp the step: duplicate or out-of-order timestamps would make the
            // prediction meaningless, and a long pause would inflate the
            // covariance so far that the next fix is copied verbatim.
            val dt = ((p.timestampMs - previousMs) / 1000.0).coerceIn(stepSecondsRange)
            previousMs = p.timestampMs

            val r = varianceOf(p)
            east.predict(dt, accelVariance)
            north.predict(dt, accelVariance)
            east.update(frame.eastMetres(p.lon), r)
            north.update(frame.northMetres(p.lat), r)

            result.add(p.copy(lat = frame.latOf(north.pos), lon = frame.lonOf(east.pos)))
        }
        return result
    }

    /** Measurement variance for one fix, from its own reported accuracy. */
    private fun varianceOf(point: RoutePoint): Double {
        val sigma = (point.accuracyMetres?.toDouble() ?: assumedAccuracyMetres)
            .coerceIn(accuracyRangeMetres)
        return sigma * sigma
    }

    /**
     * Thin to [minPointSpacingMetres]. Residual noise ε at spacing d inflates
     * length by about ε²/d², going from 3 m to 20 m cut worst-case length error
     * about sevenfold.
     *
     * Tried keeping corner vertices, it kept too much noise (+3.7 % to +9.6 % at σ = 15 m).
     */
    private fun applyDistanceGate(points: List<RoutePoint>): List<RoutePoint> {
        if (points.isEmpty()) return emptyList()
        val result = mutableListOf(points.first())
        for (p in points.drop(1)) {
            val last = result.last()
            val d = distanceCalculator.haversineMetres(last.lat, last.lon, p.lat, p.lon)
            if (d >= minPointSpacingMetres) {
                result.add(p)
            }
        }
        // Always include the final point so the route terminates at the actual end.
        if (result.last().timestampMs != points.last().timestampMs) {
            result.add(points.last())
        }
        return result
    }
}
