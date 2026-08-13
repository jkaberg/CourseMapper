package com.coursemapper.data.db

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A deleted member course must disappear from its group everywhere.
 *
 * Real Room rather than mocks, the join lives in SQL. Courses are soft-deleted
 * so the membership row stays and the FK never fires - without the join Home
 * counted it, detail didn't show it, and `createRun` got a dead course id.
 */
@RunWith(RobolectricTestRunner::class)
// A plain Application: the real one boots MapLibre, whose native library does
// not exist on the JVM.  Nothing here needs the app graph - only SQLite.
@Config(application = android.app.Application::class)
class CourseGroupMembershipTest {

    private lateinit var db: CourseMapperDatabase
    private lateinit var courses: CourseDao
    private lateinit var groups: CourseGroupDao
    private lateinit var routes: RouteDao

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            CourseMapperDatabase::class.java
        ).allowMainThreadQueries().build()
        courses = db.courseDao()
        groups  = db.courseGroupDao()
        routes  = db.routeDao()
    }

    @After
    fun tearDown() = db.close()

    private suspend fun insertCourse(name: String, routeId: Long): Long =
        courses.insert(
            ComposedCourseEntity(
                baseRouteId         = routeId,
                name                = name,
                totalDistanceMetres = 5_000.0
            )
        )

    private suspend fun insertRoute(): Long =
        routes.insert(BaseRouteEntity(name = "Recording", source = "gpx_import"))

    @Test
    fun `a soft-deleted member disappears from the group's membership`() = runTest {
        val routeId = insertRoute()
        val marathon = insertCourse("Marathon", routeId)
        val half     = insertCourse("Half", routeId)
        val tenK     = insertCourse("10 K", routeId)
        val fiveK    = insertCourse("5 K", routeId)

        val groupId = groups.insertGroup(CourseGroupEntity(name = "Trondheim Marathon"))
        groups.insertMembers(
            listOf(marathon, half, tenK, fiveK).map {
                CourseGroupMemberEntity(groupId = groupId, courseId = it)
            }
        )

        assertEquals(4, groups.getMemberCourseIds(groupId).size)

        courses.softDelete(fiveK)

        val live = groups.getMemberCourseIds(groupId)

        // The count Home renders.
        assertEquals(3, live.size)
        // the list the detail screen resolves must match the count
        assertEquals(setOf(marathon, half, tenK), live.toSet())
        // And the ids handed to createRun: a stop pointing at a course that no
        // longer resolves is a sign nobody can plant.
        assertFalse("deleted member reached the run's course ids", fiveK in live)
    }

    @Test
    fun `the membership row survives, which is why the join is needed`() = runTest {
        val routeId = insertRoute()
        val course  = insertCourse("5 K", routeId)
        val groupId = groups.insertGroup(CourseGroupEntity(name = "Event"))
        groups.insertMembers(listOf(CourseGroupMemberEntity(groupId = groupId, courseId = course)))

        courses.softDelete(course)

        // The row is still there - the RESTRICT foreign key was never going to
        // fire, because nothing is ever hard-deleted.  Only the join hides it.
        val rawRows = db.query("SELECT courseId FROM course_group_members WHERE groupId = ?", arrayOf(groupId))
        rawRows.use { assertEquals(1, it.count) }

        assertTrue(groups.getMemberCourseIds(groupId).isEmpty())
    }

    @Test
    fun `a course knows which groups it would be removed from`() = runTest {
        val routeId = insertRoute()
        val shared  = insertCourse("5 K", routeId)
        val other   = insertCourse("10 K", routeId)

        val spring = groups.insertGroup(CourseGroupEntity(name = "Spring Race"))
        val autumn = groups.insertGroup(CourseGroupEntity(name = "Autumn Race"))
        groups.insertMembers(
            listOf(
                CourseGroupMemberEntity(groupId = spring, courseId = shared),
                CourseGroupMemberEntity(groupId = spring, courseId = other),
                CourseGroupMemberEntity(groupId = autumn, courseId = shared)
            )
        )

        val containing = groups.getGroupsContainingCourse(shared).map { it.name }.toSet()
        assertEquals(setOf("Spring Race", "Autumn Race"), containing)

        // A deleted group stops claiming its members.
        groups.softDelete(autumn)
        assertEquals(
            setOf("Spring Race"),
            groups.getGroupsContainingCourse(shared).map { it.name }.toSet()
        )
    }

    @Test
    fun `replacing members adds and removes in one step`() = runTest {
        val routeId = insertRoute()
        val a = insertCourse("A", routeId)
        val b = insertCourse("B", routeId)
        val c = insertCourse("C", routeId)

        val groupId = groups.insertGroup(CourseGroupEntity(name = "Event"))
        groups.replaceMembers(groupId, listOf(a, b))
        assertEquals(listOf(a, b), groups.getMemberCourseIds(groupId))

        groups.replaceMembers(groupId, listOf(b, c))
        assertEquals(listOf(b, c), groups.getMemberCourseIds(groupId))
    }

    @Test
    fun `a run records the group it was launched from`() = runTest {
        val routeId = insertRoute()
        val course  = insertCourse("5 K", routeId)
        val groupId = groups.insertGroup(CourseGroupEntity(name = "Event"))
        groups.insertMembers(listOf(CourseGroupMemberEntity(groupId = groupId, courseId = course)))

        val runs = db.placementRunDao()
        val adHoc = runs.insertRun(PlacementRunEntity(name = "Ad-hoc"))
        val fromGroup = runs.insertRun(
            PlacementRunEntity(name = "Event", orderingMode = "optimized",
                travelProfile = "bike", groupId = groupId)
        )

        // Provenance is what lets the group offer last time's settings without
        // storing per-outing state on the group itself.
        val latest = runs.findLatestRunForGroup(groupId)
        assertEquals(fromGroup, latest?.id)
        assertEquals("optimized", latest?.orderingMode)
        assertEquals("bike", latest?.travelProfile)

        // Unfinished drives Resume; finishing it takes Resume away but leaves
        // the settings behind for next time.
        assertEquals(fromGroup, runs.findUnfinishedRunForGroup(groupId)?.id)
        runs.getById(fromGroup)?.let { runs.updateRun(it.copy(completedAt = 1L)) }
        assertEquals(null, runs.findUnfinishedRunForGroup(groupId))
        assertEquals(fromGroup, runs.findLatestRunForGroup(groupId)?.id)

        // An ad-hoc run belongs to no group and must not be offered as one.
        assertEquals(null, runs.getById(adHoc)?.groupId)
    }
}
