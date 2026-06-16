package com.coursemapper.domain

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import javax.inject.Inject

class PlacementGuidancePolicy @Inject constructor(
    private val distanceCalculator: CumulativeDistanceCalculator
) {
    enum class Direction { HERE, AHEAD, BEHIND, LATERAL, UNSIGNED, SETTLING }

    data class Guidance(
        val direction: Direction,
        val distanceMetres: Int?,
        val text: String
    )

    fun guidance(
        riderLat: Double,
        riderLon: Double,
        targetLat: Double,
        targetLon: Double,
        accuracyMetres: Float,
        travelBearingDeg: Float?
    ): Guidance {
        val distance = distanceCalculator.haversineMetres(riderLat, riderLon, targetLat, targetLon)
        val targetBearing = bearingDegrees(riderLat, riderLon, targetLat, targetLon)
        val headingDelta = travelBearingDeg?.let { smallestAngleDegrees(it.toDouble(), targetBearing) }
        val alongFraction = headingDelta?.let { cos(Math.toRadians(it)) }
        val signedDirection = when {
            alongFraction == null -> null
            alongFraction >= 0.65 -> Direction.AHEAD
            alongFraction <= -0.65 -> Direction.BEHIND
            else -> Direction.LATERAL
        }

        if (accuracyMetres > 20f) {
            if (distance <= maxOf(25.0, accuracyMetres.toDouble())) {
                return Guidance(Direction.SETTLING, null, "Near marker · GPS settling")
            }
            return when (signedDirection) {
                Direction.AHEAD -> Guidance(Direction.AHEAD, null, "Marker ahead")
                Direction.BEHIND -> Guidance(Direction.BEHIND, null, "Marker behind")
                else -> {
                    val rounded = quantize(distance, 10)
                    Guidance(Direction.UNSIGNED, rounded, "Marker about $rounded m away")
                }
            }
        }

        val moderate = accuracyMetres > 8f
        val step = when {
            moderate -> 5
            accuracyMetres <= 3f -> 1
            accuracyMetres <= 6f -> 2
            else -> 5
        }
        val metres = quantize(distance, step)
        val hereThreshold = maxOf(3.0, accuracyMetres.toDouble() * 0.5)
        if (moderate && distance <= maxOf(10.0, accuracyMetres.toDouble())) {
            return Guidance(Direction.UNSIGNED, metres, "Near marker")
        }
        if (distance <= hereThreshold) {
            return Guidance(Direction.HERE, 0, "Place here")
        }

        return when (signedDirection) {
            Direction.AHEAD -> Guidance(
                Direction.AHEAD,
                metres,
                if (moderate) "Place in about $metres m" else "Place in $metres m"
            )
            Direction.BEHIND -> Guidance(
                Direction.BEHIND,
                metres,
                if (moderate) "Place about $metres m back" else "Place $metres m back"
            )
            Direction.LATERAL -> if (distance <= 30.0) {
                Guidance(Direction.LATERAL, metres, "Near marker")
            } else {
                Guidance(Direction.LATERAL, metres, "Marker about $metres m away")
            }
            else -> Guidance(Direction.UNSIGNED, metres, "Marker about $metres m away")
        }
    }

    private fun quantize(value: Double, step: Int): Int =
        ((value / step).roundToInt() * step).coerceAtLeast(step)

    private fun bearingDegrees(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val phi1 = Math.toRadians(lat1)
        val phi2 = Math.toRadians(lat2)
        val deltaLon = Math.toRadians(lon2 - lon1)
        val y = sin(deltaLon) * cos(phi2)
        val x = cos(phi1) * sin(phi2) - sin(phi1) * cos(phi2) * cos(deltaLon)
        return (Math.toDegrees(atan2(y, x)) + 360.0) % 360.0
    }

    private fun smallestAngleDegrees(first: Double, second: Double): Double =
        ((second - first + 540.0) % 360.0) - 180.0
}
