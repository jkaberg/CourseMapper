package com.coursemapper.ui.run

import com.coursemapper.domain.CorridorAnalyzer
import com.coursemapper.domain.CourseDisplayPlanner
import com.coursemapper.domain.CumulativeDistanceCalculator
import com.coursemapper.map.RibbonLod
import com.coursemapper.testing.GpxFixtures
import com.coursemapper.ui.designsystem.MapStrip
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max

/**
 * The zoom an embedded map opens at vs. the zoom the ribbon needs. The event
 * fits a full screen at ~12.5, but a 240 dp strip costs ~1.5 zoom levels and
 * would land in the lane band, where shared roads look like separate roads.
 */
class RibbonPreviewMapZoomTest {

    private val calc = CumulativeDistanceCalculator()
    private val planner = CourseDisplayPlanner(calc, CorridorAnalyzer(calc))
    private val strokeDp = 4f

    /** Read from [MapStrip.height] so the tested height is the shipped one. */
    private val embeddedMapHeightDp = MapStrip.height.value.toDouble()

    /** A phone's full-screen map, for comparison. */
    private val fullScreenMapHeightDp = 700.0

    /** Roughly a phone's width in dp. */
    private val mapWidthDp = 400.0

    private data class Bounds(
        val minLat: Double, val maxLat: Double,
        val minLon: Double, val maxLon: Double
    ) {
        val centreLat get() = (minLat + maxLat) / 2.0
    }

    private fun eventBounds(): Bounds {
        val points = listOf(
            GpxFixtures.MARATHON,
            GpxFixtures.HALF_MARATHON,
            GpxFixtures.TEN_KM,
            GpxFixtures.FIVE_KM
        ).flatMap { GpxFixtures.load(it) }
        return Bounds(
            minLat = points.minOf { it.lat }, maxLat = points.maxOf { it.lat },
            minLon = points.minOf { it.lon }, maxLon = points.maxOf { it.lon }
        )
    }

    /** Zoom where [bounds] fills [widthDp] × [heightDp], same Web Mercator math as [RibbonLod.zoomWhere]. */
    private fun fitZoom(bounds: Bounds, widthDp: Double, heightDp: Double): Double {
        val metresPerDegLat = 111_320.0
        val metresPerDegLon = 111_320.0 * cos(Math.toRadians(bounds.centreLat))
        val spanNorthM = (bounds.maxLat - bounds.minLat) * metresPerDegLat
        val spanEastM  = (bounds.maxLon - bounds.minLon) * metresPerDegLon
        // Whichever axis is tighter decides the fit.
        val zoomForHeight = RibbonLod.zoomWhere(spanNorthM, heightDp, bounds.centreLat)
        val zoomForWidth  = RibbonLod.zoomWhere(spanEastM, widthDp, bounds.centreLat)
        return minOf(zoomForHeight, zoomForWidth)
    }

    @Test
    fun `the real event's overview zoom is reported against the lane boundary`() {
        val bounds = eventBounds()
        val lat = bounds.centreLat
        val laneCeiling = RibbonLod.laneMaxZoom(lat, strokeDp)
        val bands = RibbonLod.bands(lat, strokeDp)

        val embedded = fitZoom(bounds, mapWidthDp, embeddedMapHeightDp)
        val fullScreen = fitZoom(bounds, mapWidthDp, fullScreenMapHeightDp)

        println(
            buildString {
                appendLine("── Ribbon level-of-detail, real four-race event ──")
                appendLine("centre latitude      : %.4f".format(lat))
                bands.forEach { band ->
                    appendLine(
                        "chunk %6.0f m       : zoom %.2f … %s".format(
                            band.chunkMetres, band.minZoom, band.maxZoom?.let { "%.2f".format(it) } ?: "∞"
                        )
                    )
                }
                appendLine("lanes below          : zoom %.2f".format(laneCeiling))
                appendLine("fit zoom, %.0f dp map : %.2f".format(embeddedMapHeightDp, embedded))
                appendLine("fit zoom, full screen: %.2f".format(fullScreen))
            }
        )

        // Not an assertion about taste - an assertion that the boundary is where
        // the code says it is, so a future change to CHUNK_METRES or
        // MIN_CHUNK_STROKES cannot move it without this failing.
        assertTrue("bands must be ordered finest first", bands.first().chunkMetres < bands.last().chunkMetres)
        assertTrue(
            "the lane ceiling must be the coarsest band's floor",
            laneCeiling == bands.last().minZoom
        )
    }

    @Test
    fun `an event opened on an embedded map still gets its alternating colours`() {
        val bounds = eventBounds()
        val lat = bounds.centreLat
        val embedded = fitZoom(bounds, mapWidthDp, embeddedMapHeightDp)
        val laneCeiling = RibbonLod.laneMaxZoom(lat, strokeDp)

        // the default view of a group must be above the lane ceiling, on a 240 dp
        // map parallel lanes read as separate roads
        assertTrue(
            "the %.0f dp overview (zoom %.2f) falls below the lane ceiling (%.2f), so a ".format(embeddedMapHeightDp, embedded, laneCeiling) +
                "combined course opens showing parallel lanes rather than one ribbon",
            embedded >= laneCeiling
        )
    }

    /** Guard against a chunk length that cannot alternate on a real corridor. */
    @Test
    fun `the coarsest chunk still alternates on the corridors this event has`() {
        val sources = listOf(
            GpxFixtures.MARATHON,
            GpxFixtures.HALF_MARATHON,
            GpxFixtures.TEN_KM,
            GpxFixtures.FIVE_KM
        )
        val indexed = sources.mapIndexedNotNull { i, name ->
            planner.index(i.toLong(), GpxFixtures.load(name))
        }
        val plan = planner.plan(
            indexed,
            toleranceMetres = CorridorAnalyzer.ENTER_TOLERANCE_METRES,
            chunkMetres = RibbonLod.CHUNK_METRES
        )
        val coarsest = RibbonLod.CHUNK_METRES.max()
        val corridorLengths = plan.sharedCorridorPaths.map { corridor ->
            corridor.path.zipWithNext().sumOf { (a, b) ->
                calc.haversineMetres(a.first, a.second, b.first, b.second)
            }
        }
        val total = corridorLengths.sum()
        val alternating = corridorLengths.filter { it >= coarsest * 2 }.sum()

        println(
            "corridors: %d, total %.0f m, longest %.0f m".format(
                corridorLengths.size, total, corridorLengths.maxOrNull() ?: 0.0
            )
        )
        listOf(40.0, 160.0, 640.0, 2560.0).forEach { candidate ->
            val ok = corridorLengths.filter { it >= candidate * 2 }.sum()
            println(
                "  chunk %6.0f m → %5.0f%% of corridor length can alternate (band floor z%.2f)"
                    .format(candidate, 100.0 * ok / max(1.0, total),
                        RibbonLod.zoomWhere(candidate, RibbonLod.MIN_CHUNK_STROKES * strokeDp, 63.42))
            )
        }
        println(
            "coarsest chunk %.0f m: %.0f m of %.0f m of corridor (%.0f%%) is long enough to alternate"
                .format(coarsest, alternating, total, 100.0 * alternating / max(1.0, total))
        )

        // A chunk length longer than most corridors paints whole shared streets
        // in one course's colour, which misreports them as solo - the reason
        // the bands cannot simply be extended downwards for ever.
        assertTrue(
            "the coarsest chunk must still alternate over most shared ground",
            alternating >= 0.5 * total
        )
    }

    private fun ln2(x: Double) = ln(x) / ln(2.0)
}
