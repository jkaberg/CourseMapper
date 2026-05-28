package com.coursemapper.domain

import kotlin.math.cos

/**
 * Local east-north plane in metres around an origin.
 *
 * Don't filter in degrees: a degree of longitude is only 0.45 of a degree of
 * latitude in Trondheim. Error stays well under a centimetre within tens of km.
 */
class LocalFrame(
    private val originLat: Double,
    private val originLon: Double
) {
    private val metresPerDegreeLat = CumulativeDistanceCalculator.EARTH_RADIUS_M * Math.PI / 180.0
    private val metresPerDegreeLon = metresPerDegreeLat * cos(Math.toRadians(originLat))

    /** Metres east of the origin. */
    fun eastMetres(lon: Double): Double = (lon - originLon) * metresPerDegreeLon

    /** Metres north of the origin. */
    fun northMetres(lat: Double): Double = (lat - originLat) * metresPerDegreeLat

    /** Inverse of [eastMetres]. */
    fun lonOf(east: Double): Double =
        if (metresPerDegreeLon == 0.0) originLon else originLon + east / metresPerDegreeLon

    /** Inverse of [northMetres]. */
    fun latOf(north: Double): Double = originLat + north / metresPerDegreeLat

    companion object {
        /** Anchor a frame at the first point of a route. */
        fun at(lat: Double, lon: Double) = LocalFrame(lat, lon)
    }
}
