package com.coursemapper.map

import com.coursemapper.domain.CourseDisplayPlanner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Zoom arithmetic for the corridor level of detail: a band is visible exactly
 * while its chunks are legible, bands leave no zoom uncovered, and latitude matters.
 */
class RibbonLodTest {

    private val strokeDp = 4f
    private val trondheim = 63.42
    private val minPixels = RibbonLod.MIN_CHUNK_STROKES * strokeDp

    /** Length in pixels of a [metres] chunk at [zoom]. */
    private fun chunkPixels(metres: Double, zoom: Double, lat: Double = trondheim) =
        metres / RibbonLod.metresPerPixel(zoom, lat)

    @Test
    fun `metres per pixel matches the Web Mercator constant`() {
        // The scale that MapLibre itself works in: at zoom 0 on the equator one
        // style pixel is the earth's circumference over 256.
        assertEquals(156543.03392, RibbonLod.metresPerPixel(0.0, 0.0), 1e-6)
        // Halving per zoom level.
        assertEquals(
            RibbonLod.metresPerPixel(10.0, trondheim) / 2.0,
            RibbonLod.metresPerPixel(11.0, trondheim),
            1e-9
        )
    }

    @Test
    fun `zoomWhere inverts metresPerPixel`() {
        listOf(40.0, 160.0, 640.0).forEach { metres ->
            listOf(6.0, 10.0, 24.0).forEach { pixels ->
                val z = RibbonLod.zoomWhere(metres, pixels, trondheim)
                assertEquals(
                    "$metres m should measure $pixels px at z$z",
                    pixels,
                    chunkPixels(metres, z),
                    1e-6
                )
            }
        }
    }

    @Test
    fun `each band appears exactly when its chunks become legible`() {
        RibbonLod.bands(trondheim, strokeDp).forEach { band ->
            assertEquals(
                "band ${band.chunkMetres} m must switch on at its legibility floor",
                minPixels,
                chunkPixels(band.chunkMetres, band.minZoom.toDouble()),
                0.01
            )
        }
    }

    @Test
    fun `bands tile the zoom axis with no gap and no overlap`() {
        val bands = RibbonLod.bands(trondheim, strokeDp)
        assertNull("the finest band must stay on all the way in", bands.first().maxZoom)
        bands.zipWithNext { finer, coarser ->
            assertEquals(
                "coarser band must hand over exactly where the finer one starts",
                finer.minZoom,
                coarser.maxZoom!!,
                1e-4f
            )
        }
    }

    @Test
    fun `bands run finest first and coarsen downward`() {
        val bands = RibbonLod.bands(trondheim, strokeDp)
        bands.zipWithNext { finer, coarser ->
            assertTrue(
                "a coarser band must carry the longer chunk",
                coarser.chunkMetres > finer.chunkMetres
            )
            assertTrue(
                "a coarser band must live at a lower zoom",
                coarser.minZoom < finer.minZoom
            )
        }
    }

    @Test
    fun `no chunk is ever drawn below the legibility floor`() {
        val bands = RibbonLod.bands(trondheim, strokeDp)
        // Walk the zoom axis in tenths and check whichever band is on screen.
        var z = RibbonLod.laneMaxZoom(trondheim, strokeDp).toDouble()
        while (z <= 18.0) {
            val visible = bands.filter { z >= it.minZoom && (it.maxZoom == null || z < it.maxZoom) }
            assertEquals("exactly one chunk band must be on screen at z$z", 1, visible.size)
            val pixels = chunkPixels(visible.single().chunkMetres, z)
            assertTrue(
                "at z$z the visible chunk is only $pixels px against a $strokeDp dp stroke",
                pixels >= minPixels - 0.01
            )
            z += 0.1
        }
    }

    @Test
    fun `a 40 m chunk is illegible at zoom 12`() {
        // at z12 a 40 m chunk is shorter than the line is wide, the round caps
        // alone are longer than the chunk
        val oldThreshold = 12.0
        val pixels = chunkPixels(RibbonLod.CHUNK_METRES.first(), oldThreshold)
        assertTrue("40 m was $pixels px at z12", pixels < strokeDp)
        // And the derived floor for that same chunk sits two zoom levels higher.
        assertTrue(RibbonLod.bands(trondheim, strokeDp).first().minZoom > 13.5f)
    }

    @Test
    fun `an event that fits the screen is never left without colour`() {
        // the four Trondheim courses span ~5.4 km and fit a phone at z ~12.5,
        // both sides of that must show course colours
        val laneCut = RibbonLod.laneMaxZoom(trondheim, strokeDp)
        assertTrue(
            "the lane hand-off ($laneCut) must sit below a fitted overview",
            laneCut < 12.5f
        )
    }

    @Test
    fun `bands shift with latitude`() {
        val north = RibbonLod.bands(trondheim, strokeDp).first().minZoom
        val equator = RibbonLod.bands(0.0, strokeDp).first().minZoom
        // cos(63.42 degrees) is about 0.447, which is 1.16 zoom levels of scale:
        // the same chunk is legible sooner the further from the equator you are.
        assertEquals(1.16f, equator - north, 0.02f)
    }

    @Test
    fun `a wider stroke needs a longer chunk to read`() {
        val thin = RibbonLod.bands(trondheim, 2f).first().minZoom
        val thick = RibbonLod.bands(trondheim, 8f).first().minZoom
        assertTrue("a fatter line must postpone the chunks", thick > thin)
    }

    @Test
    fun `chunk lengths step by the band span`() {
        // If these drift apart the bands stop tiling: the ratio between chunk
        // lengths is what makes each band exactly as wide as the legible window.
        RibbonLod.CHUNK_METRES.zipWithNext { finer, coarser ->
            assertEquals(RibbonLod.BAND_SPAN, coarser / finer, 1e-9)
        }
    }

    @Test
    fun `the finest chunk is still the planner's own default`() {
        // The planner keeps a standalone default for callers that do not want
        // levels of detail; if the two disagreed, a zoomed-in map drawn through
        // one path would not match the other.
        assertEquals(
            CourseDisplayPlanner.DEFAULT_CHUNK_METRES,
            RibbonLod.CHUNK_METRES.first(),
            1e-9
        )
    }

    @Test
    fun `lanes are centred on the corridor`() {
        // The band's midpoint must sit on the road, whatever the participant
        // count - a band shoved to one side would draw the courses off the very
        // street the unification exists to put them on.
        (1..6).forEach { count ->
            val offsets = (0 until count).map { RibbonLod.laneOffsetDp(it, count) }
            assertEquals("$count lanes are off centre", 0f, offsets.sum(), 1e-4f)
        }
    }

    @Test
    fun `lanes are evenly spaced and distinct`() {
        (2..6).forEach { count ->
            val offsets = (0 until count).map { RibbonLod.laneOffsetDp(it, count) }
            assertEquals("lanes must not collide", count, offsets.toSet().size)
            offsets.zipWithNext { a, b ->
                assertEquals(RibbonLod.LANE_SPACING_DP, b - a, 1e-4f)
            }
        }
    }

    @Test
    fun `a lone course is not nudged off its own line`() {
        assertEquals(0f, RibbonLod.laneOffsetDp(0, 1), 1e-6f)
    }

    @Test
    fun `lane separation does not shrink with zoom`() {
        // The whole reason lanes can go where chunks cannot: the offset is a
        // screen-space paint property, so it has no zoom term at all.
        val offset = RibbonLod.laneOffsetDp(0, 4)
        assertTrue(abs(offset) > 0f)
        // Expressed as ground metres it grows as you zoom out, which is exactly
        // the behaviour a fixed ground length could never have.
        val near = abs(offset) * RibbonLod.metresPerPixel(16.0, trondheim)
        val far = abs(offset) * RibbonLod.metresPerPixel(10.0, trondheim)
        assertTrue("a lane must cover more ground the further out you go", far > near)
    }

    @Test
    fun `the casing backs the widest lane band edge to edge`() {
        (1..4).forEach { count ->
            val casing = RibbonLod.casingWidthDp(strokeDp, count)
            val outer = abs(RibbonLod.laneOffsetDp(0, count)) * 2f +
                RibbonLod.laneWidthDp(strokeDp)
            assertTrue(
                "casing $casing dp cannot cover $count lanes spanning $outer dp",
                casing >= outer - 1e-4f
            )
            assertTrue("casing must never be thinner than a plain line", casing >= strokeDp)
        }
    }

    @Test
    fun `a lane is thinner than a whole course line but never a hairline`() {
        assertTrue(RibbonLod.laneWidthDp(strokeDp) < strokeDp)
        assertTrue(RibbonLod.laneWidthDp(0.5f) >= RibbonLod.MIN_LANE_WIDTH_DP)
    }

    @Test
    fun `laneMaxZoom meets the coarsest band exactly`() {
        val bands = RibbonLod.bands(trondheim, strokeDp)
        assertNotNull(bands.lastOrNull())
        assertEquals(
            "lanes must hand over to the coarsest chunk band with no gap",
            bands.last().minZoom,
            RibbonLod.laneMaxZoom(trondheim, strokeDp),
            1e-6f
        )
    }
}
