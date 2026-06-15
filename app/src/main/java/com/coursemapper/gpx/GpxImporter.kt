package com.coursemapper.gpx

import android.location.Location
import com.coursemapper.domain.model.BaseRoute
import com.coursemapper.domain.model.RoutePoint
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.InputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Lenient GPX 1.1 parser, unknown elements are ignored so Strava, Garmin, OsmAnd
 * etc. files just work. One [ImportResult] per `<trk>`, name and desc taken from
 * the track.
 *
 * Imported points are stored as-is and never smoothed. They're already processed
 * by whatever produced them, and filtering again shortens the route 2-4 %.
 */
@Singleton
class GpxImporter @Inject constructor() {

    data class ImportResult(
        val route: BaseRoute,
        val points: List<RoutePoint>
    )

    /** @throws GpxParseException if the stream isn't valid GPX. */
    fun parse(stream: InputStream, defaultName: String = "Imported route"): List<ImportResult> {
        val factory = XmlPullParserFactory.newInstance().apply { isNamespaceAware = true }
        val parser  = factory.newPullParser()
        parser.setInput(stream, null)

        val results = mutableListOf<ImportResult>()
        var currentTrackName   = defaultName
        var currentTrackDesc   = ""
        val currentPoints      = mutableListOf<RawPoint>()
        var inTrack = false
        var inTrackSeg = false

        var eventType = parser.eventType
        while (eventType != XmlPullParser.END_DOCUMENT) {
            when (eventType) {
                XmlPullParser.START_TAG -> when (parser.name) {
                    "trk" -> {
                        inTrack = true
                        currentTrackName = defaultName
                        currentTrackDesc = ""
                        currentPoints.clear()
                    }
                    "trkseg"  -> inTrackSeg = true
                    "trkpt"   -> if (inTrackSeg) {
                        val lat = parser.getAttributeValue(null, "lat")?.toDoubleOrNull()
                        val lon = parser.getAttributeValue(null, "lon")?.toDoubleOrNull()
                        if (lat != null && lon != null) {
                            currentPoints.add(RawPoint(lat = lat, lon = lon))
                        }
                    }
                    "name"    -> if (inTrack && !inTrackSeg) {
                        currentTrackName = parser.nextText().trim().ifBlank { defaultName }
                    }
                    "desc"    -> if (inTrack && !inTrackSeg) {
                        currentTrackDesc = parser.nextText().trim()
                    }
                    "ele"     -> if (inTrackSeg && currentPoints.isNotEmpty()) {
                        currentPoints.last().elevM = parser.nextText().trim().toDoubleOrNull()
                    }
                    "time"    -> if (inTrackSeg && currentPoints.isNotEmpty()) {
                        currentPoints.last().timeMs = parseIso8601(parser.nextText().trim())
                    }
                }
                XmlPullParser.END_TAG -> when (parser.name) {
                    "trkseg" -> inTrackSeg = false
                    "trk"    -> {
                        inTrack = false
                        if (currentPoints.isNotEmpty()) {
                            results.add(buildResult(currentTrackName, currentTrackDesc, currentPoints))
                        }
                    }
                }
            }
            eventType = parser.next()
        }

        return results
    }

    private fun buildResult(name: String, desc: String, rawPoints: List<RawPoint>): ImportResult {
        // Build android Location objects so LocationFilter can process them.
        var syntheticTime = System.currentTimeMillis() - rawPoints.size * 2_000L
        val locations = rawPoints.map { rp ->
            val t = rp.timeMs ?: syntheticTime.also { syntheticTime += 2_000L }
            Location("gpx").apply {
                latitude  = rp.lat
                longitude = rp.lon
                rp.elevM?.let { altitude = it }
                // GPX files have no accuracy field; assume good quality for imports.
                accuracy  = 5f
                time      = t
            }
        }

        // no speed filter here, sparse tracks (10 s Garmin intervals) look like
        // jumps and lose a lot of distance
        val routePoints = locations.map { loc ->
            RoutePoint(
                lat            = loc.latitude,
                lon            = loc.longitude,
                altMetres      = if (loc.hasAltitude()) loc.altitude else null,
                accuracyMetres = if (loc.hasAccuracy()) loc.accuracy else null,
                timestampMs    = loc.time,
                isSmoothed     = true
            )
        }

        val now = System.currentTimeMillis()
        val route = BaseRoute(
            id             = 0L,
            name           = name,
            notes          = desc,
            createdAt      = now,
            updatedAt      = now,
            isApproved     = false,
            distanceMetres = 0.0,
            source         = "gpx_import",
            rawPoints      = routePoints,
            smoothedPoints = routePoints.filter { it.isSmoothed }
        )
        return ImportResult(route = route, points = routePoints)
    }

    /** Parse ISO-8601 string (e.g. "2024-06-15T09:30:00Z" or "…T09:30:00.000Z") to epoch ms. */
    private fun parseIso8601(s: String): Long? {
        // Try most-specific patterns first (fractional seconds are common in Strava/Garmin exports).
        val formats = listOf(
            "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
            "yyyy-MM-dd'T'HH:mm:ss.SSSXXX",
            "yyyy-MM-dd'T'HH:mm:ss'Z'",
            "yyyy-MM-dd'T'HH:mm:ssXXX"
        )
        for (fmt in formats) {
            try {
                val sdf = java.text.SimpleDateFormat(fmt, java.util.Locale.US)
                    .apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
                val t = sdf.parse(s)?.time
                if (t != null) return t
            } catch (_: Exception) { /* try next */ }
        }
        return null
    }

    private data class RawPoint(
        val lat: Double,
        val lon: Double,
        var elevM: Double? = null,
        var timeMs: Long?  = null
    )
}

class GpxParseException(message: String, cause: Throwable? = null) : Exception(message, cause)
