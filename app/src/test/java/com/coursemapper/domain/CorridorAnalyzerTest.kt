package com.coursemapper.domain

import com.coursemapper.domain.model.RoutePoint
import com.coursemapper.testing.GpxFixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlin.math.cos

class CorridorAnalyzerTest {

    private lateinit var calc: CumulativeDistanceCalculator
    private lateinit var analyzer: CorridorAnalyzer

    private val lat0 = 63.43
    private val metresPerDegLat = CumulativeDistanceCalculator.EARTH_RADIUS_M * Math.PI / 180.0
    private val metresPerDegLon = metresPerDegLat * cos(Math.toRadians(lat0))

    @Before
    fun setUp() {
        calc = CumulativeDistanceCalculator()
        analyzer = CorridorAnalyzer(calc)
    }

    private fun pt(northM: Double, eastM: Double) = RoutePoint(
        lat = lat0 + northM / metresPerDegLat,
        lon = 10.4 + eastM / metresPerDegLon,
        altMetres = null, accuracyMetres = null, timestampMs = 0, isSmoothed = true
    )

    /** Straight line east at [northM], from [fromEastM] to [toEastM]. */
    private fun eastLine(northM: Double, fromEastM: Double, toEastM: Double, stepM: Double = 5.0) =
        generateSequence(fromEastM) { it + stepM }
            .takeWhile { it <= toEastM + 1e-9 }
            .map { pt(northM, it) }
            .toList()

    private fun course(id: Long, points: List<RoutePoint>) =
        CorridorAnalyzer.IndexedCourse(id, PolylineIndex.build(points, calc)!!)

    @Test
    fun `a single course shares nothing`() {
        val result = analyzer.analyze(listOf(course(1L, eastLine(0.0, 0.0, 1000.0))))
        assertTrue(result.corridors.isEmpty())
    }

    @Test
    fun `courses far apart share nothing`() {
        val result = analyzer.analyze(
            listOf(
                course(1L, eastLine(0.0, 0.0, 1000.0)),
                course(2L, eastLine(500.0, 0.0, 1000.0))
            )
        )
        assertTrue(result.corridors.isEmpty())
    }

    @Test
    fun `courses within tolerance and heading form one shared corridor`() {
        // Two lines 6 m apart, both heading east for 1 km.
        val result = analyzer.analyze(
            listOf(
                course(1L, eastLine(0.0, 0.0, 1000.0)),
                course(2L, eastLine(6.0, 0.0, 1000.0))
            )
        )

        // One entry per participant, expressed on its own geometry…
        assertEquals(1, result.corridorsFor(1L).size)
        assertEquals(1, result.corridorsFor(2L).size)
        // …and exactly one of them draws it.
        assertEquals(1, result.primaryCorridors.size)

        val corridor = result.primaryCorridors.single()
        assertEquals(2, corridor.courseCount)
        assertEquals(1000.0, corridor.lengthMetres, 20.0)
        assertEquals(listOf(1L, 2L), corridor.courseIds)
    }

    @Test
    fun `a crossing is not a corridor`() {
        // A north-south course cutting straight across an east-west one.  Their
        // closest approach is 0 m - distance alone would call this shared.
        val eastWest = eastLine(0.0, 0.0, 1000.0)
        val northSouth = (0..100).map { pt(-250.0 + it * 5.0, 500.0) }

        val result = analyzer.analyze(listOf(course(1L, eastWest), course(2L, northSouth)))
        assertTrue(
            "a perpendicular crossing must not read as a shared corridor",
            result.corridors.isEmpty()
        )
    }

    @Test
    fun `a brief graze is not a corridor`() {
        // Course 2 dips to within 5 m of course 1 for ~20 m, then leaves again - 
        // under the 40 m minimum.
        val main = eastLine(0.0, 0.0, 1000.0)
        val grazing = listOf(
            pt(60.0, 400.0), pt(20.0, 470.0), pt(5.0, 490.0),
            pt(5.0, 510.0), pt(20.0, 530.0), pt(60.0, 600.0)
        )

        val result = analyzer.analyze(listOf(course(1L, main), course(2L, grazing)))
        assertTrue("a 20 m graze is noise, not a corridor", result.corridors.isEmpty())
    }

    @Test
    fun `an out-and-back leg shares the corridor and is flagged reversed`() {
        val outbound = eastLine(0.0, 0.0, 1000.0)
        val inbound = eastLine(6.0, 0.0, 1000.0).reversed()

        val result = analyzer.analyze(listOf(course(1L, outbound), course(2L, inbound)))

        assertEquals(1, result.primaryCorridors.size)
        val partner = result.primaryCorridors.single().partners.single()
        assertTrue("opposite direction on shared road must be flagged", partner.reversed)
    }

    @Test
    fun `wobble across the tolerance does not shatter the corridor`() {
        // Course 2 weaves between 6 m and 12 m of course 1 - straddling the 10 m
        // enter tolerance, which is exactly what GPS noise on a shared road does.
        val main = eastLine(0.0, 0.0, 1000.0)
        val weaving = (0..200).map { i ->
            val offset = if ((i / 4) % 2 == 0) 6.0 else 12.0
            pt(offset, i * 5.0)
        }

        val result = analyzer.analyze(listOf(course(1L, main), course(2L, weaving)))

        assertEquals("weaving must yield one corridor, not many", 1, result.primaryCorridors.size)
        assertTrue(result.primaryCorridors.single().lengthMetres > 800.0)
    }

    @Test
    fun `a short detour is bridged rather than splitting the corridor`() {
        // Course 2 runs alongside, swings 18 m out briefly, and comes back - 
        // clear of the 15 m exit tolerance, but back inside well within the
        // 30 m the analyser is willing to bridge.
        val main = eastLine(0.0, 0.0, 1000.0)
        val detouring = buildList {
            addAll(eastLine(6.0, 0.0, 400.0))
            add(pt(18.0, 408.0))
            add(pt(18.0, 414.0))
            addAll(eastLine(6.0, 422.0, 1000.0))
        }

        val result = analyzer.analyze(listOf(course(1L, main), course(2L, detouring)))
        assertEquals(1, result.primaryCorridors.size)
    }

    @Test
    fun `a long detour does split the corridor`() {
        // Same shape, but 300 m away for 300 m - a genuinely separate stretch.
        val main = eastLine(0.0, 0.0, 1500.0)
        val detouring = buildList {
            addAll(eastLine(6.0, 0.0, 400.0))
            addAll((0..60).map { pt(6.0 + it * 5.0, 400.0 + it * 5.0) })
            addAll((0..60).map { pt(306.0 - it * 5.0, 700.0 + it * 5.0) })
            addAll(eastLine(6.0, 1000.0, 1500.0))
        }

        val result = analyzer.analyze(listOf(course(1L, main), course(2L, detouring)))
        assertEquals(2, result.primaryCorridors.size)
    }

    @Test
    fun `three courses on one road are one corridor drawn once`() {
        // ±4 m keeps all three within 10 m of each other, at ±5 the outer pair
        // sits exactly on the tolerance and it's down to floating point
        val result = analyzer.analyze(
            listOf(
                course(1L, eastLine(0.0, 0.0, 1000.0)),
                course(2L, eastLine(4.0, 0.0, 1000.0)),
                course(3L, eastLine(-4.0, 0.0, 1000.0))
            )
        )

        assertEquals(
            "one ribbon, drawn by one participant",
            1, result.primaryCorridors.size
        )
        assertEquals(3, result.primaryCorridors.single().courseCount)
        assertEquals(listOf(1L, 2L, 3L), result.primaryCorridors.single().courseIds)
    }

    @Test
    fun `partial overlaps are cut so each interval names its own courses`() {
        // Course 2 shares the first part of course 1, course 3 a later part,
        // with clear ground between them.  The failure this guards against is
        // reporting one corridor of three courses over the whole length.
        val result = analyzer.analyze(
            listOf(
                course(1L, eastLine(0.0, 0.0, 1000.0)),
                course(2L, eastLine(6.0, 0.0, 400.0)),
                course(3L, eastLine(-6.0, 600.0, 1000.0))
            )
        )

        val onCourseOne = result.corridorsFor(1L)
        assertEquals(2, onCourseOne.size)
        assertEquals(listOf(1L, 2L), onCourseOne[0].courseIds)
        assertEquals(listOf(1L, 3L), onCourseOne[1].courseIds)
        assertTrue(onCourseOne.none { it.courseCount == 3 })
    }

    @Test
    fun `a stretch shared by two and then three is cut at the boundary`() {
        // Course 2 runs the whole way; course 3 joins halfway.
        val result = analyzer.analyze(
            listOf(
                course(1L, eastLine(0.0, 0.0, 1000.0)),
                course(2L, eastLine(6.0, 0.0, 1000.0)),
                course(3L, eastLine(-6.0, 500.0, 1000.0))
            )
        )

        val onCourseOne = result.corridorsFor(1L)
        assertEquals(2, onCourseOne.size)
        assertEquals(2, onCourseOne[0].courseCount)
        assertEquals(3, onCourseOne[1].courseCount)
        assertEquals(500.0, onCourseOne[1].fromMetres, 20.0)
    }

    @Test
    fun `the longest course draws the corridor`() {
        val result = analyzer.analyze(
            listOf(
                course(1L, eastLine(5.0, 0.0, 400.0)),
                course(2L, eastLine(0.0, 0.0, 2000.0))
            )
        )
        assertEquals(2L, result.primaryCorridors.single().courseId)
    }

    @Test
    fun `a course overlapping only a shorter course still finds its corridor`() {
        // 3 shares ground with 2 only; 2 is nowhere near the longest course 1.
        val result = analyzer.analyze(
            listOf(
                course(1L, eastLine(0.0, 0.0, 3000.0)),
                course(2L, eastLine(800.0, 0.0, 900.0)),
                course(3L, eastLine(806.0, 0.0, 900.0))
            )
        )
        assertEquals(1, result.primaryCorridors.size)
        assertEquals(listOf(2L, 3L), result.primaryCorridors.single().courseIds)
    }

    @Test
    fun `analysis does not depend on input order`() {
        val a = course(1L, eastLine(0.0, 0.0, 1000.0))
        val b = course(2L, eastLine(6.0, 0.0, 1000.0))
        val c = course(3L, eastLine(-6.0, 0.0, 1000.0))

        fun summary(analysis: CorridorAnalyzer.Analysis) =
            analysis.corridors.map { Triple(it.courseId, it.courseIds, it.isPrimary) }

        assertEquals(summary(analyzer.analyze(listOf(a, b, c))), summary(analyzer.analyze(listOf(c, b, a))))
    }

    @Test
    fun `exactly one participant is primary for every shared stretch`() {
        val result = analyzer.analyze(
            listOf(
                course(1L, eastLine(0.0, 0.0, 1000.0)),
                course(2L, eastLine(6.0, 0.0, 1000.0)),
                course(3L, eastLine(-6.0, 0.0, 1000.0))
            )
        )
        // Three participants, one ribbon: three entries, one of them primary.
        assertEquals(3, result.corridors.size)
        assertEquals(1, result.corridors.count { it.isPrimary })
    }

    private fun realCourses() = listOf(
        course(1L, GpxFixtures.load(GpxFixtures.FIVE_KM)),
        course(2L, GpxFixtures.load(GpxFixtures.TEN_KM)),
        course(3L, GpxFixtures.load(GpxFixtures.HALF_MARATHON))
    )

    @Test
    fun `the three Trondheim races share real corridors`() {
        val result = analyzer.analyze(realCourses())

        assertTrue(
            "real races that finish together must share something",
            result.primaryCorridors.isNotEmpty()
        )

        result.corridors.forEach { corridor ->
            assertTrue(
                "corridor shorter than the minimum: ${corridor.lengthMetres}",
                corridor.lengthMetres >= CorridorAnalyzer.MIN_SLIVER_METRES - 1.0
            )
            assertTrue("a corridor needs at least two courses", corridor.courseCount >= 2)
            assertTrue(
                "member ranges must be ordered",
                corridor.partners.all { it.toMetres >= it.fromMetres }
            )
        }

        // The shared ground is a real fraction of the shortest course, not a
        // handful of metres - these races run the same streets.
        val fiveKmShared = result.corridorsFor(1L).sumOf { it.lengthMetres }
        assertTrue(
            "expected the 5 km to share a meaningful stretch, got $fiveKmShared m",
            fiveKmShared > 300.0
        )
    }

    @Test
    fun `real corridors stay inside their courses and do not overlap each other`() {
        val courses = realCourses()
        val lengths = courses.associate { it.courseId to it.index.lengthMetres }
        val result = analyzer.analyze(courses)

        result.corridors.forEach { corridor ->
            assertTrue(
                "corridor runs past the end of course ${corridor.courseId}",
                corridor.toMetres <= lengths.getValue(corridor.courseId) + 1.0
            )
            corridor.partners.forEach { partner ->
                assertTrue(
                    "partner ${partner.courseId} runs past the end of its own course",
                    partner.toMetres <= lengths.getValue(partner.courseId) + 1.0
                )
            }
        }

        // Intervals on one course must tile it, never overlap: a renderer walks
        // them in order and would otherwise paint the same metres twice.
        courses.forEach { c ->
            val onCourse = result.corridorsFor(c.courseId)
            onCourse.zipWithNext { a, b ->
                assertTrue(
                    "overlapping intervals on course ${c.courseId}: $a then $b",
                    b.fromMetres >= a.toMetres - 0.001
                )
            }
        }
    }
}
