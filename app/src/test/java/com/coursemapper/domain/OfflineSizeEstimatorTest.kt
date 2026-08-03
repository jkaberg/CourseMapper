package com.coursemapper.domain

import com.coursemapper.domain.model.GeoBounds
import com.coursemapper.domain.model.OfflineZoom
import com.coursemapper.testing.GpxFixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The tile count drives both the prompt size and refusing impossible downloads. */
class OfflineSizeEstimatorTest {

    private val estimator = OfflineSizeEstimator()
    private val planner = OfflinePackPlanner()

    // Trondheim, roughly - the latitudes this app is actually used at, where
    // a degree of longitude is about half its equatorial width.
    private fun trondheim(spanDeg: Double = 0.1) = GeoBounds(
        south = 63.40,
        north = 63.40 + spanDeg,
        west = 10.30,
        east = 10.30 + spanDeg
    )

    @Test
    fun `zoom 0 is a single tile whatever the bounds`() {
        assertEquals(1L, estimator.tilesAtZoom(trondheim(spanDeg = 40.0), 0))
    }

    @Test
    fun `each zoom level roughly quadruples the tile count`() {
        val bounds = trondheim(spanDeg = 1.0)
        // compared deep in the pyramid, near the top grid alignment moves the ratio 1x-9x
        val z12 = estimator.tilesAtZoom(bounds, 12)
        val z13 = estimator.tilesAtZoom(bounds, 13)

        assertTrue("z12 ($z12) should be large enough to compare", z12 > 50)
        assertTrue("z13 ($z13) should be near 4x z12 ($z12)", z13 >= z12 * 3.5)
        assertTrue("z13 ($z13) should not exceed 5x z12 ($z12)", z13 <= z12 * 5)
    }

    @Test
    fun `tile count is dominated by the deepest zoom level`() {
        val bounds = trondheim()
        val total = estimator.tileCount(bounds, OfflineZoom.MIN_ZOOM, OfflineZoom.MAX_ZOOM)
        val deepest = estimator.tilesAtZoom(bounds, OfflineSizeEstimator.SOURCE_MAX_ZOOM.toInt())

        assertTrue(
            "the bottom level should be most of the pyramid",
            deepest.toDouble() / total > 0.6
        )
    }

    /** Min zoom went down to z5 so zooming out to orient isn't blank. Check it's close to free. */
    @Test
    fun `dropping the zoom floor to 5 costs almost nothing`() {
        val bounds = trondheim()
        val fromFive = estimator.tileCount(bounds, 5.0, OfflineZoom.MAX_ZOOM)
        val fromTen = estimator.tileCount(bounds, 10.0, OfflineZoom.MAX_ZOOM)

        val extra = fromFive - fromTen
        assertTrue(
            "z5-z9 added $extra tiles; the whole point is that it is negligible",
            extra < 40
        )
    }

    @Test
    fun `bounds beyond the mercator cutoff do not blow up`() {
        val polar = GeoBounds(south = 84.0, north = 89.0, west = -10.0, east = 10.0)
        val count = estimator.tileCount(polar, 5.0, 10.0)

        assertTrue("a polar box must still produce a finite count", count in 1..100_000)
    }

    @Test
    fun `estimate scales with the calibration constant`() {
        val bounds = trondheim()
        val cheap = estimator.estimate(bounds, 5.0, 14.0, 1_000.0, includeBothStyles = false)
        val dear = estimator.estimate(bounds, 5.0, 14.0, 2_000.0, includeBothStyles = false)

        assertEquals(cheap.tileCount, dear.tileCount)
        assertEquals(
            "twice the bytes per tile is twice the estimate",
            cheap.bytes * 2,
            dear.bytes
        )
    }

    /** The second style shares tiles, it must be overhead and not a second pyramid. */
    @Test
    fun `the second basemap style costs a constant, not another set of tiles`() {
        val small = trondheim(spanDeg = 0.1)
        val large = trondheim(spanDeg = 2.0)

        fun delta(bounds: GeoBounds): Long =
            estimator.estimate(bounds, 5.0, 14.0, 12_000.0, includeBothStyles = true).bytes -
                estimator.estimate(bounds, 5.0, 14.0, 12_000.0, includeBothStyles = false).bytes

        // second style overhead doesn't scale with area
        assertEquals(OfflineSizeEstimator.SECOND_STYLE_OVERHEAD, delta(small))
        assertEquals(
            "a twenty-times-larger area must pay the same style overhead",
            delta(small),
            delta(large)
        )

        // On a course-sized area, where the tiles dominate, that constant is a
        // small fraction of the download rather than a doubling of it.
        val oneStyle = estimator.estimate(large, 5.0, 14.0, 12_000.0, includeBothStyles = false)
        val bothStyles = estimator.estimate(large, 5.0, 14.0, 12_000.0, includeBothStyles = true)
        assertTrue(
            "both styles (${bothStyles.bytes}) must not approach double one " +
                "(${oneStyle.bytes}) at realistic sizes",
            bothStyles.bytes < oneStyle.bytes * 1.5
        )
    }

    @Test
    fun `a very large area is flagged before anything is downloaded`() {
        val huge = GeoBounds(south = 58.0, north = 70.0, west = 5.0, east = 30.0)
        val estimate = estimator.estimate(huge, 5.0, 14.0, 12_000.0, includeBothStyles = true)

        assertTrue(
            "the whole of Norway must trip the tile-limit warning, not fail mid-download",
            estimate.exceedsTileLimit
        )
    }

    /** The real marathon's buffered bbox must be a plausible download, not refused. */
    @Test
    fun `the real marathon course estimates to a plausible download`() {
        val points = GpxFixtures.load("Helmaraton.gpx")
        val plan = planner.planForPoints(points)
        val bounds = (plan as OfflinePackPlanner.PlanResult.Planned).bounds

        val estimate = estimator.estimate(
            bounds,
            OfflineZoom.MIN_ZOOM,
            OfflineZoom.MAX_ZOOM,
            OfflineSizeEstimator.DEFAULT_BYTES_PER_TILE,
            includeBothStyles = true
        )

        assertTrue(
            "a marathon must not trip the tile-limit refusal (${estimate.tileCount} tiles)",
            !estimate.exceedsTileLimit
        )
        val megabytes = estimate.bytes / (1024 * 1024)
        assertTrue(
            "a marathon's map estimated at $megabytes MB is not a credible figure",
            megabytes in 1..2_000
        )
    }

    /** Four overlapping races planned together cost barely more than the largest. */
    @Test
    fun `one pack over all four races costs little more than the marathon alone`() {
        val marathon = GpxFixtures.load("Helmaraton.gpx")
        val half = GpxFixtures.load("Halvmaraton.gpx")
        val tenK = GpxFixtures.load("10km.gpx")
        val fiveK = GpxFixtures.load("5km.gpx")

        fun tilesFor(vararg courses: List<com.coursemapper.domain.model.RoutePoint>): Long {
            val plan = planner.planForCourses(courses.toList())
            val bounds = (plan as OfflinePackPlanner.PlanResult.Planned).bounds
            return estimator.tileCount(bounds, OfflineZoom.MIN_ZOOM, OfflineZoom.MAX_ZOOM)
        }

        val marathonOnly = tilesFor(marathon)
        val allFourTogether = tilesFor(marathon, half, tenK, fiveK)
        val separately =
            tilesFor(marathon) + tilesFor(half) + tilesFor(tenK) + tilesFor(fiveK)

        assertTrue(
            "the union must not be smaller than its largest member",
            allFourTogether >= marathonOnly
        )
        assertTrue(
            "one shared pack ($allFourTogether tiles) must beat four packs " +
                "($separately tiles) — this is why a pack is an area, not a course",
            allFourTogether < separately
        )
    }
}
