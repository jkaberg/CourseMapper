package com.coursemapper.testing

import com.coursemapper.domain.model.RoutePoint

/**
 * The real Trondheim race tracks in `src/test/resources` as plain polylines:
 * 5 km, 10 km, half and full marathon, sharing roads and one finish.
 * Parsed straight from the XML since `GpxImporter` needs `android.location`.
 */
object GpxFixtures {

    const val FIVE_KM = "5km.gpx"
    const val TEN_KM = "10km.gpx"
    const val HALF_MARATHON = "Halvmaraton.gpx"

    /** The half as currently stored, starting 32 m from the marathon's start. [HALF_MARATHON] shares it exactly. */
    const val HALF_MARATHON_CURRENT = "Halvmaraton_current.gpx"
    const val MARATHON = "Helmaraton.gpx"

    private val TRKPT = Regex("""<trkpt\s+lat="([-0-9.]+)"\s+lon="([-0-9.]+)"""")

    /** Track points of [resourceName], in file order. */
    fun load(resourceName: String): List<RoutePoint> {
        val xml = requireNotNull(
            GpxFixtures::class.java.classLoader?.getResourceAsStream(resourceName)
        ) { "missing test resource: $resourceName" }.use { it.readBytes().toString(Charsets.UTF_8) }

        val points = TRKPT.findAll(xml).map { m ->
            RoutePoint(
                lat = m.groupValues[1].toDouble(),
                lon = m.groupValues[2].toDouble(),
                altMetres = null,
                accuracyMetres = 5f,
                timestampMs = 0L,
                isSmoothed = true
            )
        }.toList()

        check(points.size >= 2) { "$resourceName parsed to ${points.size} points" }
        return points
    }
}
