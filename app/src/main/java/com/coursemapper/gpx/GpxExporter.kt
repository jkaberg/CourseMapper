package com.coursemapper.gpx

import com.coursemapper.domain.model.BaseRoute
import com.coursemapper.domain.model.ComposedCourse
import com.coursemapper.domain.model.DistanceMarker
import com.coursemapper.domain.model.MarkerType
import com.coursemapper.domain.model.RoutePoint
import javax.inject.Inject
import javax.inject.Singleton
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * GPX 1.1 export. [exportRoute] writes a track, [exportCourse] a track plus a
 * waypoint per marker. Only the smoothed polyline is exported, same data the
 * distances and markers use.
 */
@Singleton
class GpxExporter @Inject constructor() {

    private val isoFmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    /**
     * Export [route]'s smoothed polyline as a GPX track.
     */
    fun exportRoute(route: BaseRoute): String = buildString {
        appendHeader()
        appendTrack(
            name   = route.name,
            desc   = route.notes,
            points = route.smoothedPoints
        )
        appendFooter()
    }

    /** Track plus marker waypoints. [routePoints] should be the composed polyline, not the base route. */
    fun exportCourse(
        course: ComposedCourse,
        routePoints: List<RoutePoint>
    ): String = buildString {
        appendHeader()

        // Waypoints - one per non-deleted marker.
        for (marker in course.markers) {
            appendWaypoint(marker)
        }

        appendTrack(
            name   = course.name,
            desc   = buildCourseDesc(course),
            points = routePoints.filter { it.isSmoothed }
        )
        appendFooter()
    }

    private fun StringBuilder.appendHeader() {
        appendLine("""<?xml version="1.0" encoding="UTF-8"?>""")
        appendLine(
            """<gpx version="1.1" creator="CourseMapper" """ +
            """xmlns="http://www.topografix.com/GPX/1/1" """ +
            """xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" """ +
            """xmlns:cm="https://coursemapper.app/gpx/1/0" """ +
            """xsi:schemaLocation="http://www.topografix.com/GPX/1/1 """ +
            """http://www.topografix.com/GPX/1/1/gpx.xsd">"""
        )
    }

    private fun StringBuilder.appendFooter() {
        appendLine("</gpx>")
    }

    private fun StringBuilder.appendTrack(
        name: String,
        desc: String,
        points: List<RoutePoint>
    ) {
        appendLine("  <trk>")
        appendLine("    <name>${name.escapeXml()}</name>")
        if (desc.isNotBlank()) appendLine("    <desc>${desc.escapeXml()}</desc>")
        appendLine("    <trkseg>")
        for (p in points) {
            append("""      <trkpt lat="${p.lat}" lon="${p.lon}">""")
            p.altMetres?.let { append("<ele>$it</ele>") }
            // Interpolated points (lap boundaries, partial-lap endpoints) have
            // no real timestamp - omit <time> rather than fabricating one.
            if (p.timestampMs > 0) {
                append("<time>${isoFmt.format(Date(p.timestampMs))}</time>")
            }
            appendLine("</trkpt>")
        }
        appendLine("    </trkseg>")
        appendLine("  </trk>")
    }

    private fun StringBuilder.appendWaypoint(marker: DistanceMarker) {
        val sym  = markerSymbol(marker.type)
        val name = markerName(marker)
        appendLine("""  <wpt lat="${marker.lat}" lon="${marker.lon}">""")
        appendLine("""    <name>${name.escapeXml()}</name>""")
        appendLine("""    <sym>$sym</sym>""")
        appendLine("""    <extensions><cm:dist>${marker.cumulativeDistanceMetres}</cm:dist></extensions>""")
        appendLine("""  </wpt>""")
    }

    private fun buildCourseDesc(course: ComposedCourse): String {
        val km = "%.2f km".format(course.displayDistanceMetres / 1000.0)
        val laps = "${course.lapCount} lap(s), $km total"
        // A measured course names both numbers: the waypoints below are placed
        // against the measurement, but the track in this same file is the drawn
        // line, and whoever opens it will measure that one.
        return if (course.isMeasured) {
            "$laps (measured; drawn line %.2f km)".format(course.totalDistanceMetres / 1000.0)
        } else {
            laps
        }
    }

    private fun markerSymbol(type: MarkerType) = when (type) {
        MarkerType.START            -> "Flag, Green"
        MarkerType.FINISH           -> "Flag, Red"
        MarkerType.CHECKPOINT       -> "Diamond, Blue"
        MarkerType.WATER_STATION    -> "Drinking Water"
        MarkerType.DISTANCE         -> "Dot"
        MarkerType.CUSTOM_DISTANCES -> "Dot"
    }

    private fun markerName(marker: DistanceMarker): String {
        val km = "%.2f km".format(marker.cumulativeDistanceMetres / 1000.0)
        return if (marker.label.isNotBlank()) "${marker.label} ($km)"
               else "${marker.type.name.lowercase().replace('_', ' ')} $km"
    }

    private fun String.escapeXml() = this
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;")
}
