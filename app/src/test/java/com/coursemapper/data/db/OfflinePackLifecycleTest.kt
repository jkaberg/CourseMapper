package com.coursemapper.data.db

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.coursemapper.domain.model.OfflinePackChoice
import com.coursemapper.domain.model.OfflinePackStatus
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * When a pack may be deleted. Real Room since the rule is in SQL: courses are
 * soft-deleted, so every "is this pack needed" query has to join on
 * `isDeleted = 0` or deleted courses keep their packs forever.
 */
@RunWith(RobolectricTestRunner::class)
// A plain Application: the real one boots MapLibre, whose native library does
// not exist on the JVM.  Nothing here needs the app graph - only SQLite.
@Config(application = android.app.Application::class)
class OfflinePackLifecycleTest {

    private lateinit var db: CourseMapperDatabase
    private lateinit var courses: CourseDao
    private lateinit var routes: RouteDao
    private lateinit var packs: OfflinePackDao

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            CourseMapperDatabase::class.java
        ).allowMainThreadQueries().build()
        courses = db.courseDao()
        routes = db.routeDao()
        packs = db.offlinePackDao()
    }

    @After
    fun tearDown() = db.close()

    private suspend fun insertRoute(): Long =
        routes.insert(BaseRouteEntity(name = "Recording", source = "gpx_import"))

    private suspend fun insertCourse(name: String, routeId: Long): Long =
        courses.insert(
            ComposedCourseEntity(
                baseRouteId = routeId,
                name = name,
                totalDistanceMetres = 5_000.0
            )
        )

    private suspend fun insertPack(
        label: String,
        courseIds: List<Long>,
        status: OfflinePackStatus = OfflinePackStatus.READY,
        sizeBytes: Long = 100_000_000
    ): Long {
        val packId = packs.insertPack(
            OfflinePackEntity(
                label = label,
                boundsJson = """{"south":63.0,"north":63.5,"west":10.0,"east":10.5}""",
                status = status,
                sizeBytes = sizeBytes
            )
        )
        packs.insertCourseLinks(courseIds.map { OfflinePackCourseEntity(packId, it) })
        return packId
    }

    @Test
    fun `a pack shared by four courses survives deleting one of them`() = runTest {
        val routeId = insertRoute()
        val marathon = insertCourse("Marathon", routeId)
        val half = insertCourse("Half", routeId)
        val tenK = insertCourse("10 K", routeId)
        val fiveK = insertCourse("5 K", routeId)
        val packId = insertPack("Trondheim", listOf(marathon, half, tenK, fiveK))

        courses.softDelete(fiveK)
        packs.deleteCourseLinks(fiveK)

        assertEquals(
            "the other three still need this map",
            3,
            packs.liveCourseIds(packId).size
        )
        assertTrue(
            "a pack three live courses need must not be an orphan",
            packs.findOrphanPackIds().isEmpty()
        )
    }

    @Test
    fun `a pack becomes an orphan only when its last live course goes`() = runTest {
        val routeId = insertRoute()
        val marathon = insertCourse("Marathon", routeId)
        val fiveK = insertCourse("5 K", routeId)
        val packId = insertPack("Trondheim", listOf(marathon, fiveK))

        courses.softDelete(fiveK)
        packs.deleteCourseLinks(fiveK)
        assertTrue(packs.findOrphanPackIds().isEmpty())

        courses.softDelete(marathon)
        packs.deleteCourseLinks(marathon)

        assertEquals(
            "with no live course left, the pack is releasable",
            listOf(packId),
            packs.findOrphanPackIds()
        )
    }

    /**
     * Membership row left in place (a crash between the two writes does this).
     * The pack must still be released, it goes by the course's deleted flag.
     */
    @Test
    fun `a soft-deleted course does not pin its pack through a surviving link row`() = runTest {
        val routeId = insertRoute()
        val fiveK = insertCourse("5 K", routeId)
        val packId = insertPack("Trondheim", listOf(fiveK))

        courses.softDelete(fiveK)
        // Deliberately no deleteCourseLinks: the link row outlives the course.

        assertTrue(
            "the link row must not keep a dead course's pack alive",
            packs.liveCourseIds(packId).isEmpty()
        )
        assertEquals(
            "the orphan sweep must find it anyway",
            listOf(packId),
            packs.findOrphanPackIds()
        )
    }

    @Test
    fun `deleting a pack cascades to its regions and its course links`() = runTest {
        val routeId = insertRoute()
        val courseId = insertCourse("Marathon", routeId)
        val packId = insertPack("Trondheim", listOf(courseId))
        packs.insertRegion(
            OfflinePackRegionEntity(packId = packId, styleVariant = "light", maplibreRegionId = 7)
        )
        packs.insertRegion(
            OfflinePackRegionEntity(packId = packId, styleVariant = "dark", maplibreRegionId = 8)
        )
        assertEquals(2, packs.getRegions(packId).size)

        packs.deletePack(packId)

        assertNull(packs.getById(packId))
        assertTrue("regions cascade", packs.getRegions(packId).isEmpty())
        assertTrue("links cascade", packs.allLiveLinks().none { it.packId == packId })
    }

    @Test
    fun `a usable pack is found for its course but a failed one is not`() = runTest {
        val routeId = insertRoute()
        val covered = insertCourse("Marathon", routeId)
        val broken = insertCourse("Half", routeId)
        insertPack("Good", listOf(covered), status = OfflinePackStatus.READY)
        insertPack("Broken", listOf(broken), status = OfflinePackStatus.FAILED)

        assertNotNull(packs.findUsablePackForCourse(covered))
        assertNull(
            "a failed pack is not coverage",
            packs.findUsablePackForCourse(broken)
        )
    }

    @Test
    fun `a stale pack still counts as usable`() = runTest {
        val routeId = insertRoute()
        val courseId = insertCourse("Marathon", routeId)
        insertPack("Old", listOf(courseId), status = OfflinePackStatus.STALE)

        assertNotNull(
            "stale means old, not broken — it still draws a map",
            packs.findUsablePackForCourse(courseId)
        )
    }

    /** Home uses this query, the ranking must match `OfflineMapRepository.rank`. */
    @Test
    fun `course offline ranks report the best pack per course`() = runTest {
        val routeId = insertRoute()
        val courseId = insertCourse("Marathon", routeId)
        insertPack("Failed attempt", listOf(courseId), status = OfflinePackStatus.FAILED)
        insertPack("Good one", listOf(courseId), status = OfflinePackStatus.READY)

        val ranks = packs.observeCourseOfflineRanks().first()
        val entry = ranks.single { it.courseId == courseId }

        assertEquals("READY is rank 0", 0, entry.offlineRank)
        assertTrue(
            "a course with one good pack and one failed one is covered",
            entry.isUsable
        )
    }

    @Test
    fun `status writes always move updatedAt forward`() = runTest {
        val routeId = insertRoute()
        val courseId = insertCourse("Marathon", routeId)
        val packId = insertPack("Trondheim", listOf(courseId), status = OfflinePackStatus.QUEUED)
        val before = packs.getById(packId)!!.updatedAt

        packs.setStatus(
            packId,
            OfflinePackStatus.DOWNLOADING,
            null,
            null,
            now = before + 5_000
        )

        val after = packs.getById(packId)!!
        assertEquals(OfflinePackStatus.DOWNLOADING, after.status)
        assertTrue(
            // ordering depends on it
            "updatedAt must move on every status write",
            after.updatedAt > before
        )
    }

    @Test
    fun `marking a pack ready clears any earlier failure`() = runTest {
        val routeId = insertRoute()
        val courseId = insertCourse("Marathon", routeId)
        val packId = insertPack("Trondheim", listOf(courseId), status = OfflinePackStatus.QUEUED)
        packs.setStatus(
            packId,
            OfflinePackStatus.FAILED,
            com.coursemapper.domain.model.OfflineFailureReason.NETWORK,
            "connection dropped"
        )

        packs.markReady(packId, sizeBytes = 12_345, completed = 100, required = 100)

        val pack = packs.getById(packId)!!
        assertEquals(OfflinePackStatus.READY, pack.status)
        assertNull("a retry that succeeded must not keep the old reason", pack.failureReason)
        assertNull(pack.failureMessage)
        assertEquals(12_345L, pack.sizeBytes)
        assertNotNull(pack.downloadedAt)
    }

    @Test
    fun `a declined pack keeps its choice across status changes`() = runTest {
        val routeId = insertRoute()
        val courseId = insertCourse("Marathon", routeId)
        val packId = insertPack("Trondheim", listOf(courseId), status = OfflinePackStatus.PAUSED)

        packs.setUserChoice(packId, OfflinePackChoice.DECLINED)
        packs.setStatus(packId, OfflinePackStatus.QUEUED, null, null)

        assertEquals(
            "the answer is about the area, not about this attempt",
            OfflinePackChoice.DECLINED,
            packs.getById(packId)!!.userChoice
        )
    }

    @Test
    fun `total bytes sums every pack`() = runTest {
        val routeId = insertRoute()
        val a = insertCourse("Marathon", routeId)
        val b = insertCourse("Fjord loop", routeId)
        insertPack("Trondheim", listOf(a), sizeBytes = 100)
        insertPack("Bergen", listOf(b), sizeBytes = 250)

        assertEquals(350L, packs.observeTotalBytes().first())
    }
}
