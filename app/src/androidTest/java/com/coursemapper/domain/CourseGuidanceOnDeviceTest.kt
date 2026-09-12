package com.coursemapper.domain

import android.database.sqlite.SQLiteDatabase
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.coursemapper.domain.model.RoutePoint
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Runs [CourseLegPlanner] against the real placement runs on the device, since
 * fixtures can't prove the stops actually sit on their courses.
 *
 * Reads the db as plain SQLite, read only - it's real field data and shouldn't
 * go near a migration. Skips when there's nothing to read.
 */
@RunWith(AndroidJUnit4::class)
class CourseGuidanceOnDeviceTest {

    private var db: SQLiteDatabase? = null
    private val calc = CumulativeDistanceCalculator()
    private val planner = CourseLegPlanner(calc)

    @Before
    fun open() {
        // targetContext, not the test app's own: the database being checked is
        // the one the installed app writes in the field.
        val path = InstrumentationRegistry.getInstrumentation().targetContext
            .getDatabasePath("coursemapper.db")
        if (!path.exists()) return
        db = SQLiteDatabase.openDatabase(path.path, null, SQLiteDatabase.OPEN_READWRITE)
    }

    @After
    fun close() {
        db?.close()
        db = null
    }

    private data class Fixture(
        val runId: Long,
        val stops: List<Pair<Double, Double>>,
        val courses: List<CourseLegPlanner.Course>
    )

    private fun SQLiteDatabase.longs(sql: String, vararg args: String): List<Long> =
        rawQuery(sql, args).use { c -> buildList { while (c.moveToNext()) add(c.getLong(0)) } }

    private fun SQLiteDatabase.positions(sql: String, vararg args: String):
        List<Pair<Double, Double>> =
        rawQuery(sql, args).use { c ->
            buildList { while (c.moveToNext()) add(c.getDouble(0) to c.getDouble(1)) }
        }

    /** The most recent run on the device that has both stops and geometry. */
    private fun loadFixture(): Fixture? {
        val database = db ?: return null
        val runIds = database.longs(
            "SELECT id FROM placement_runs WHERE isDeleted = 0 ORDER BY updatedAt DESC"
        )
        for (runId in runIds) {
            val stops = database.positions(
                "SELECT latDeg, lonDeg FROM placement_run_stops " +
                    "WHERE runId = ? ORDER BY stopIndex ASC",
                runId.toString()
            )
            if (stops.size < 2) continue

            val courses = database.longs(
                "SELECT c.id FROM placement_run_courses pc " +
                    "JOIN composed_courses c ON c.id = pc.courseId AND c.isDeleted = 0 " +
                    "WHERE pc.runId = ?",
                runId.toString()
            ).mapNotNull { courseId ->
                val baseRouteId = database.longs(
                    "SELECT baseRouteId FROM composed_courses WHERE id = ?",
                    courseId.toString()
                ).firstOrNull() ?: return@mapNotNull null
                val points = database.positions(
                    "SELECT latDeg, lonDeg FROM route_points WHERE routeId = ? ORDER BY `index` ASC",
                    baseRouteId.toString()
                ).map { (lat, lon) -> RoutePoint(lat, lon, null, null, 0L, isSmoothed = true) }
                PolylineIndex.build(points, calc)?.let {
                    CourseLegPlanner.Course(courseId, it)
                }
            }
            if (courses.isEmpty()) continue
            return Fixture(runId, stops, courses)
        }
        return null
    }

    @Test
    fun theCourseJoinsMostConsecutiveStopsOnRealRunData() {
        val fixture = loadFixture()
        assumeTrue("no placement run with stops and geometry on this device", fixture != null)
        val (runId, stops, courses) = fixture!!

        var planned = 0
        var worstRatio = 0.0
        for (i in 0 until stops.size - 1) {
            val (fromLat, fromLon) = stops[i]
            val (toLat, toLon) = stops[i + 1]
            val leg = planner.plan(courses, fromLat, fromLon, toLat, toLon) ?: continue
            planned++

            assertTrue("leg $i is degenerate", leg.points.size >= 2)
            assertTrue(
                "leg $i does not start at the rider",
                calc.haversineMetres(
                    fromLat, fromLon, leg.points.first().first, leg.points.first().second
                ) < 1.0
            )
            assertTrue(
                "leg $i does not finish at the stop",
                calc.haversineMetres(
                    toLat, toLon, leg.points.last().first, leg.points.last().second
                ) < CourseLegPlanner.STOP_TOLERANCE_METRES
            )

            val straight = calc.haversineMetres(fromLat, fromLon, toLat, toLon)
            if (straight > CourseLegPlanner.DETOUR_FLOOR_METRES) {
                worstRatio = maxOf(worstRatio, leg.remainingMetres / straight)
            }
        }

        val coverage = planned.toDouble() / (stops.size - 1)
        // Logged, not just asserted: a test that skips itself when the device
        // holds no runs passes exactly like one that checked 81 legs, and the
        // difference matters to whoever is reading the result.
        android.util.Log.i(
            "CourseGuidance",
            "run $runId: $planned/${stops.size - 1} pairs joined along a course " +
                "(${(coverage * 100).toInt()}%), worst course/straight ratio " +
                String.format("%.2f", worstRatio)
        )
        // Measured on the run this was written against: 80 of 81 pairs.  Well
        // under that means the stops and the recording have come apart, and
        // guidance would be silently falling back to road routing everywhere.
        assertTrue(
            "run $runId: only $planned of ${stops.size - 1} consecutive pairs " +
                "could be joined along a course",
            coverage >= 0.8
        )
        // And when it does answer, the answer is the way there - not a lap.
        assertTrue(
            "run $runId: worst course/straight ratio was $worstRatio",
            worstRatio < CourseLegPlanner.MAX_DETOUR_FACTOR
        )
    }
}
