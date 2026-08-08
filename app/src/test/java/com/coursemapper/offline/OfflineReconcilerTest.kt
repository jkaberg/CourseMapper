package com.coursemapper.offline

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.coursemapper.data.db.BaseRouteEntity
import com.coursemapper.data.db.ComposedCourseEntity
import com.coursemapper.data.db.CourseMapperDatabase
import com.coursemapper.data.db.OfflinePackCourseEntity
import com.coursemapper.data.db.OfflinePackDao
import com.coursemapper.data.db.OfflinePackEntity
import com.coursemapper.data.db.OfflinePackRegionEntity
import com.coursemapper.domain.model.GeoBounds
import com.coursemapper.domain.model.OfflineFailureReason
import com.coursemapper.domain.model.OfflinePackStatus
import com.coursemapper.map.STYLE_URL_LIGHT
import com.coursemapper.testing.fakes.FakeMapLibreOfflineGateway
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Room and MapLibre drift silently, this is the sweep. */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class)
class OfflineReconcilerTest {

    private lateinit var db: CourseMapperDatabase
    private lateinit var packs: OfflinePackDao
    private lateinit var gateway: FakeMapLibreOfflineGateway
    private lateinit var registry: OfflineDownloadRegistry
    private lateinit var reconciler: OfflineReconciler

    private val bounds = GeoBounds(south = 63.4, north = 63.5, west = 10.3, east = 10.4)

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            CourseMapperDatabase::class.java
        ).allowMainThreadQueries().build()
        packs = db.offlinePackDao()
        gateway = FakeMapLibreOfflineGateway()
        registry = OfflineDownloadRegistry()
        reconciler = OfflineReconciler(packs, gateway, registry)
    }

    @After
    fun tearDown() = db.close()

    private suspend fun insertCourse(deleted: Boolean = false): Long {
        val routeId = db.routeDao().insert(BaseRouteEntity(name = "R", source = "gpx_import"))
        val id = db.courseDao().insert(
            ComposedCourseEntity(
                baseRouteId = routeId,
                name = "Marathon",
                totalDistanceMetres = 42_195.0
            )
        )
        if (deleted) db.courseDao().softDelete(id)
        return id
    }

    private suspend fun insertPack(
        status: OfflinePackStatus,
        courseIds: List<Long>,
        maplibreIds: List<Long> = emptyList(),
        downloadedAt: Long? = System.currentTimeMillis()
    ): Long {
        val packId = packs.insertPack(
            OfflinePackEntity(
                label = "Trondheim",
                boundsJson = bounds.toJson(),
                status = status,
                downloadedAt = downloadedAt
            )
        )
        packs.insertCourseLinks(courseIds.map { OfflinePackCourseEntity(packId, it) })
        maplibreIds.forEachIndexed { i, mlId ->
            packs.insertRegion(
                OfflinePackRegionEntity(
                    packId = packId,
                    styleVariant = if (i == 0) "light" else "dark",
                    maplibreRegionId = mlId,
                    status = status
                )
            )
            gateway.seedRegion(mlId, spec())
        }
        return packId
    }

    /** "Clear storage" in Android settings: MapLibre is empty but Room still says READY. */
    @Test
    fun `a ready pack whose tiles are gone is marked failed`() = runTest {
        val courseId = insertCourse()
        val packId = insertPack(OfflinePackStatus.READY, listOf(courseId), maplibreIds = listOf(1L))
        // MapLibre forgets everything.
        gateway.regions.clear()

        reconciler.reconcile()

        val pack = packs.getById(packId)!!
        assertEquals(OfflinePackStatus.FAILED, pack.status)
        assertEquals(OfflineFailureReason.MISSING_TILES, pack.failureReason)
    }

    /** Half a pack still draws in one theme, but must not stay READY or the other half never comes. */
    @Test
    fun `a ready pack missing one style variant is paused, not failed`() = runTest {
        val courseId = insertCourse()
        val packId = insertPack(
            OfflinePackStatus.READY,
            listOf(courseId),
            maplibreIds = listOf(1L, 2L)
        )
        gateway.regions.remove(2L)

        reconciler.reconcile()

        assertEquals(OfflinePackStatus.PAUSED, packs.getById(packId)!!.status)
    }

    @Test
    fun `a download left running by a dead process is parked as paused`() = runTest {
        val courseId = insertCourse()
        val packId = insertPack(
            OfflinePackStatus.DOWNLOADING,
            listOf(courseId),
            maplibreIds = listOf(1L)
        )

        // No registry entry: nothing in this process is downloading it.
        reconciler.reconcile()

        assertEquals(OfflinePackStatus.PAUSED, packs.getById(packId)!!.status)
    }

    @Test
    fun `a download this process really is running is left alone`() = runTest {
        val courseId = insertCourse()
        val packId = insertPack(
            OfflinePackStatus.DOWNLOADING,
            listOf(courseId),
            maplibreIds = listOf(1L)
        )
        registry.report(packId, FakeMapLibreOfflineGateway.progress(10, 100))

        reconciler.reconcile()

        assertEquals(OfflinePackStatus.DOWNLOADING, packs.getById(packId)!!.status)
    }

    /**
     * Deletions that happened while the app was not running to hear about them.
     */
    @Test
    fun `a pack no live course needs is deleted, tiles and all`() = runTest {
        val deletedCourse = insertCourse(deleted = true)
        val packId = insertPack(
            OfflinePackStatus.READY,
            listOf(deletedCourse),
            maplibreIds = listOf(1L)
        )

        reconciler.reconcile()

        assertNull("the pack row must go", packs.getById(packId))
        assertTrue("its tiles must go too", 1L in gateway.deletedRegionIds)
        assertTrue(
            "and the space must actually be returned",
            gateway.packDatabaseCalls > 0
        )
    }

    @Test
    fun `a pack a live course still needs is kept`() = runTest {
        val live = insertCourse()
        val packId = insertPack(OfflinePackStatus.READY, listOf(live), maplibreIds = listOf(1L))

        reconciler.reconcile()

        assertEquals(OfflinePackStatus.READY, packs.getById(packId)!!.status)
        assertTrue(gateway.deletedRegionIds.isEmpty())
    }

    /** MapLibre regions no pack claims (crash mid-delete, v15 migration). Only findable from here. */
    @Test
    fun `stray MapLibre regions with no pack row are deleted`() = runTest {
        gateway.seedRegion(77L, spec())
        gateway.seedRegion(78L, spec())

        reconciler.reconcile()

        assertTrue(gateway.deletedRegionIds.containsAll(listOf(77L, 78L)))
        assertTrue("the space must be returned", gateway.packDatabaseCalls > 0)
    }

    @Test
    fun `a region a pack does claim is not treated as stray`() = runTest {
        val courseId = insertCourse()
        insertPack(OfflinePackStatus.READY, listOf(courseId), maplibreIds = listOf(5L))

        reconciler.reconcile()

        assertTrue(
            "a claimed region must survive the stray sweep",
            gateway.deletedRegionIds.isEmpty()
        )
    }

    @Test
    fun `a pack older than the staleness window is offered a refresh`() = runTest {
        val courseId = insertCourse()
        val old = System.currentTimeMillis() - OfflineReconciler.STALE_AFTER_MS - 1
        val packId = insertPack(
            OfflinePackStatus.READY,
            listOf(courseId),
            maplibreIds = listOf(1L),
            downloadedAt = old
        )

        reconciler.reconcile()

        val pack = packs.getById(packId)!!
        assertEquals(OfflinePackStatus.STALE, pack.status)
        assertTrue(
            "stale is an offer to refresh, never a withdrawal — it still draws",
            pack.status.isUsable
        )
    }

    @Test
    fun `a recently downloaded pack stays ready`() = runTest {
        val courseId = insertCourse()
        val packId = insertPack(
            OfflinePackStatus.READY,
            listOf(courseId),
            maplibreIds = listOf(1L),
            downloadedAt = System.currentTimeMillis()
        )

        reconciler.reconcile()

        assertEquals(OfflinePackStatus.READY, packs.getById(packId)!!.status)
    }

    @Test
    fun `reconciling an empty database does nothing and does not throw`() = runTest {
        reconciler.reconcile()

        assertTrue(gateway.deletedRegionIds.isEmpty())
        assertEquals(0, gateway.packDatabaseCalls)
    }

    private fun spec() = OfflineRegionSpec(
        styleUrl = STYLE_URL_LIGHT,
        bounds = bounds,
        minZoom = 5.0,
        maxZoom = 16.0,
        pixelRatio = 1.0f
    )
}
