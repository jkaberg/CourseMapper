package com.coursemapper.ui.run

import com.coursemapper.domain.CorridorAnalyzer
import com.coursemapper.domain.CourseDisplayPlanner
import com.coursemapper.domain.CumulativeDistanceCalculator
import com.coursemapper.map.MapPalette
import com.coursemapper.map.MapPolylineOverlay
import com.coursemapper.map.RibbonLod
import com.coursemapper.testing.GpxFixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlin.math.roundToInt

/**
 * No zoom may leave shared ground grey. Sweeps the whole zoom range over the
 * real courses and requires every course on a shared road to be drawn in its
 * own colour there, at every zoom.
 */
class RibbonZoomCoverageTest {

    private lateinit var calc: CumulativeDistanceCalculator
    private lateinit var planner: CourseDisplayPlanner

    private val strokeDp = 4f

    /** Wider than any interpolation step, far tighter than the corridor tolerance. */
    private val onCorridorMetres = 12.0

    @Before
    fun setUp() {
        calc = CumulativeDistanceCalculator()
        planner = CourseDisplayPlanner(calc, CorridorAnalyzer(calc))
    }

    private data class Drawn(
        val lines: List<RunCourseLine>,
        val corridors: List<CourseDisplayPlanner.SharedCorridor>,
        val overlays: List<MapPolylineOverlay>
    )

    private fun realEvent(): Drawn {
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
        val lines = plan.coursePaths.mapIndexed { i, paths ->
            RunCourseLine(
                courseId = paths.courseId,
                soloPaths = paths.soloPaths,
                ribbonPaths = paths.ribbonPaths,
                colorHex = MapPalette.COURSE_CYCLE[i % MapPalette.COURSE_CYCLE.size],
                courseName = sources[i],
                coarseRibbonBands = paths.coarseRibbonBands
            )
        }
        return Drawn(lines, plan.sharedCorridorPaths, lines.toMapOverlays(plan.sharedCorridorPaths, strokeDp))
    }

    private fun MapPolylineOverlay.visibleAt(zoom: Float): Boolean =
        (minZoom == null || zoom >= minZoom!!) && (maxZoom == null || zoom < maxZoom!!)

    /** Grid of shared ground points, so "is this on a corridor" is a lookup. */
    private class CorridorGrid(corridors: List<CourseDisplayPlanner.SharedCorridor>) {
        // ~11 m of latitude, ~5 m of longitude at this latitude - a cell small
        // enough that a hit plus its neighbours bounds the search tightly.
        private val cells = HashSet<Long>()

        private fun key(lat: Double, lon: Double): Long =
            (lat * 10_000).roundToInt().toLong() * 10_000_000L + (lon * 10_000).roundToInt()

        init {
            corridors.forEach { corridor ->
                corridor.path.forEach { (lat, lon) ->
                    for (dLat in -1..1) for (dLon in -1..1) {
                        cells += key(lat + dLat * 1e-4, lon + dLon * 1e-4)
                    }
                }
            }
        }

        fun contains(lat: Double, lon: Double) = key(lat, lon) in cells
    }

    /** Courses that actually take part in at least one corridor. */
    private fun participants(drawn: Drawn): Set<Long> =
        drawn.corridors.flatMap { it.courseIds }.toSet()

    @Test
    fun `every shared course keeps its colour on shared ground at every zoom`() {
        val drawn = realEvent()
        val grid = CorridorGrid(drawn.corridors)
        val sharing = participants(drawn)
        assertTrue("the real event must produce shared corridors", sharing.size >= 2)

        // Which overlays touch shared ground at all - computed once, so the
        // zoom sweep below is a filter rather than a geometry pass.
        val onShared = drawn.overlays.filter { overlay ->
            overlay.colorHex != MapPalette.SHARED_CORRIDOR &&
                overlay.paths.any { path -> path.any { (lat, lon) -> grid.contains(lat, lon) } }
        }
        val expected = drawn.lines.filter { it.courseId in sharing }.map { it.colorHex }.toSet()

        // 9 is further out than the whole event ever needs; 17 is closer than
        // the follow camera ever goes.
        var zoom = 9.0f
        while (zoom <= 17.0f) {
            val visible = onShared.filter { it.visibleAt(zoom) }.map { it.colorHex }.toSet()
            val missing = expected - visible
            assertTrue(
                "at zoom $zoom these courses have no colour on shared ground: $missing",
                missing.isEmpty()
            )
            zoom += 0.25f
        }
    }

    @Test
    fun `the zoom the event fits at is not the broken one`() {
        // The specific camera position that was reported grey.  Four courses
        // over 5.4 km fit a phone at about 12.5, and a pinch either side of
        // that must still be in colour - 12.0 used to be the first grey frame.
        val drawn = realEvent()
        val grid = CorridorGrid(drawn.corridors)
        val expected = drawn.lines.filter { it.courseId in participants(drawn) }.map { it.colorHex }.toSet()

        listOf(11.5f, 12.0f, 12.5f, 13.0f).forEach { zoom ->
            val visible = drawn.overlays
                .filter { it.visibleAt(zoom) && it.colorHex != MapPalette.SHARED_CORRIDOR }
                .filter { o -> o.paths.any { p -> p.any { (lat, lon) -> grid.contains(lat, lon) } } }
                .map { it.colorHex }
                .toSet()
            assertEquals("shared ground lost colours at zoom $zoom", expected, visible)
        }
    }

    @Test
    fun `far out the corridors are drawn as offset lanes`() {
        val drawn = realEvent()
        val laneCut = RibbonLod.laneMaxZoom(
            drawn.lines.first().allPoints.first().first,
            strokeDp
        )
        val far = laneCut - 1f

        val lanes = drawn.overlays.filter { it.visibleAt(far) && it.lineOffsetDp != 0f }
        assertTrue("no lanes are drawn below the chunk bands", lanes.isNotEmpty())

        // A lane is drawn on the corridor's own geometry, so every participant
        // of a corridor is visible along its whole length rather than in turn.
        val grid = CorridorGrid(drawn.corridors)
        lanes.forEach { lane ->
            assertTrue(
                "a lane must sit on shared geometry",
                lane.paths.any { p -> p.any { (lat, lon) -> grid.contains(lat, lon) } }
            )
        }
        // And no chunk band is on screen down there, or the two would fight.
        assertTrue(
            "chunk bands must be off below the lane hand-off",
            drawn.overlays.none { it.visibleAt(far) && it.minZoom != null }
        )
    }

    @Test
    fun `zoomed in the corridors are drawn as chunks and never as lanes`() {
        val drawn = realEvent()
        val near = 16f
        assertTrue(
            "lanes must not survive into navigation zoom, where they would read as separate roads",
            drawn.overlays.none { it.visibleAt(near) && it.lineOffsetDp != 0f }
        )
        assertTrue(
            "the grey casing must not survive into navigation zoom",
            drawn.overlays.none { it.visibleAt(near) && it.colorHex == MapPalette.SHARED_CORRIDOR }
        )
        assertTrue(
            "the finest chunk band must be on screen",
            drawn.overlays.any { it.visibleAt(near) && it.minZoom != null }
        )
    }

    @Test
    fun `exactly one chunk band is on screen at a time`() {
        val drawn = realEvent()
        // Two bands of the same course drawn together would double-paint the
        // corridor at two different chunk lengths.
        val byCourse = drawn.lines.associate { line ->
            line.colorHex to drawn.overlays.filter { it.colorHex == line.colorHex && it.minZoom != null }
        }
        var zoom = 9.0f
        while (zoom <= 17.0f) {
            byCourse.forEach { (colour, bands) ->
                val on = bands.count { it.visibleAt(zoom) }
                assertTrue("$colour has $on chunk bands on screen at zoom $zoom", on <= 1)
            }
            zoom += 0.25f
        }
    }

    @Test
    fun `the grey casing is a backing, never the whole story`() {
        val drawn = realEvent()
        val casing = drawn.overlays.single { it.colorHex == MapPalette.SHARED_CORRIDOR }
        // It is drawn first, so it is underneath everything.
        assertEquals(0, drawn.overlays.indexOf(casing))
        // It only exists where lanes do, and it is wide enough to sit behind them.
        assertEquals(
            "the casing must retire exactly where the lanes do",
            drawn.overlays.filter { it.lineOffsetDp != 0f }.mapNotNull { it.maxZoom }.distinct().single(),
            casing.maxZoom!!,
            1e-4f
        )
        val widestBand = drawn.corridors.maxOf { it.courseIds.size }
        assertTrue(casing.widthDp >= RibbonLod.casingWidthDp(strokeDp, widestBand) - 1e-4f)
    }

    @Test
    fun `levels of detail do not multiply into a wall of layers`() {
        val drawn = realEvent()
        // lanes keyed by (course, position) keeps the layer count bounded
        assertTrue(
            "overlay count ballooned to ${drawn.overlays.size}",
            drawn.overlays.size <= 40
        )
        // The coarse band must genuinely be fewer, longer pieces than the fine
        // one - otherwise it is copies, not a level of detail.
        drawn.lines.filter { it.ribbonPaths.isNotEmpty() }.forEach { line ->
            val fine = line.ribbonBands.first().size
            val coarse = line.ribbonBands.last().size
            assertTrue(
                "${line.courseName}: coarse band has $coarse pieces against $fine fine ones",
                coarse < fine
            )
        }
    }

    @Test
    fun `every level of detail covers the whole of every corridor`() {
        val drawn = realEvent()
        fun metres(paths: List<List<Pair<Double, Double>>>) =
            paths.sumOf { path ->
                path.zipWithNext { a, b -> calc.haversineMetres(a.first, a.second, b.first, b.second) }.sum()
            }

        val corridorMetres = metres(drawn.corridors.map { it.path })
        assertTrue("the real event must share real distance", corridorMetres > 1_000.0)

        // coarser bands change piece length, not coverage. Summed across courses,
        // 8 chunks between 3 courses can't be even
        val bandCount = drawn.lines.first().ribbonBands.size
        (0 until bandCount).forEach { band ->
            val painted = metres(drawn.lines.flatMap { it.ribbonBands[band] })
            assertEquals(
                "band $band paints $painted m of ${corridorMetres} m of shared road",
                corridorMetres,
                painted,
                // Chunks overlap their neighbour by a metre so a colour change
                // shows no seam, so a finely cut band is slightly the longer.
                corridorMetres * 0.05
            )
        }
    }

    @Test
    fun `no course is dealt out of a band by coarsening`() {
        // but no course may get zero chunks in a band, it would vanish at that zoom
        val drawn = realEvent()
        val sharing = participants(drawn)
        drawn.lines.filter { it.courseId in sharing }.forEach { line ->
            line.ribbonBands.forEachIndexed { band, paths ->
                assertTrue(
                    "${line.courseName} has no chunks in band $band",
                    paths.isNotEmpty()
                )
            }
        }
    }
}
