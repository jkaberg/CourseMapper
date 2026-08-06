package com.coursemapper.offline

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.coursemapper.data.db.CourseMapperDatabase
import com.coursemapper.data.db.OfflinePackDao
import com.coursemapper.data.db.OfflinePackEntity
import com.coursemapper.data.db.OfflinePackRegionEntity
import com.coursemapper.data.prefs.UserPreferencesRepository
import com.coursemapper.domain.OfflineSizeEstimator
import com.coursemapper.domain.model.GeoBounds
import com.coursemapper.domain.model.OfflineFailureReason
import com.coursemapper.domain.model.OfflinePackStatus
import com.coursemapper.map.STYLE_URL_DARK
import com.coursemapper.map.STYLE_URL_LIGHT
import com.coursemapper.testing.fakes.FakeMapLibreOfflineGateway
import com.coursemapper.testing.fakes.FakeMapLibreOfflineGateway.Companion.complete
import com.coursemapper.testing.fakes.FakeMapLibreOfflineGateway.Companion.progress
import io.mockk.coJustRun
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
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

/** Download orchestration through a fake MapLibre, with real Room since the status changes are writes. */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class)
class OfflineDownloadOrchestrationTest {

    private lateinit var db: CourseMapperDatabase
    private lateinit var packs: OfflinePackDao
    private lateinit var gateway: FakeMapLibreOfflineGateway
    private lateinit var registry: OfflineDownloadRegistry
    private lateinit var repository: OfflineMapRepository
    private lateinit var storage: DeviceStorage
    private lateinit var scheduler: OfflineWorkScheduler
    private lateinit var prefs: UserPreferencesRepository

    private val bounds = GeoBounds(south = 63.40, north = 63.50, west = 10.30, east = 10.40)

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            CourseMapperDatabase::class.java
        ).allowMainThreadQueries().build()
        packs = db.offlinePackDao()

        gateway = FakeMapLibreOfflineGateway()
        registry = OfflineDownloadRegistry()

        storage = mockk(relaxed = true)
        every { storage.hasRoomFor(any()) } returns true
        every { storage.freeBytes() } returns Long.MAX_VALUE

        scheduler = mockk(relaxed = true)

        prefs = mockk(relaxed = true)
        every { prefs.offlineBothThemes } returns flowOf(true)
        every { prefs.offlineWifiOnly } returns flowOf(false)
        every { prefs.offlineBytesPerTile } returns
            flowOf(OfflineSizeEstimator.DEFAULT_BYTES_PER_TILE)
        coJustRun { prefs.setOfflineBytesPerTile(any()) }

        repository = OfflineMapRepository(
            packDao = packs,
            gateway = gateway,
            registry = registry,
            estimator = OfflineSizeEstimator(),
            storage = storage,
            scheduler = scheduler,
            prefs = prefs
        )
    }

    @After
    fun tearDown() = db.close()

    private suspend fun seedPack(
        status: OfflinePackStatus = OfflinePackStatus.QUEUED,
        variants: List<String> = listOf("light", "dark"),
        estimatedBytes: Long = 1_000
    ): Long {
        val packId = packs.insertPack(
            OfflinePackEntity(
                label = "Trondheim",
                boundsJson = bounds.toJson(),
                status = status,
                estimatedBytes = estimatedBytes
            )
        )
        variants.forEach { variant ->
            packs.insertRegion(OfflinePackRegionEntity(packId = packId, styleVariant = variant))
        }
        return packId
    }

    /**
     * A pack whose light region points at [maplibreId], so the fake's script for
     * that id is what the download hits.
     */
    private suspend fun seedPackBoundTo(maplibreId: Long): Long {
        val packId = seedPack(variants = listOf("light"))
        gateway.seedRegion(maplibreId, spec())
        packs.setMaplibreRegionId(packs.getRegions(packId).single().id, maplibreId)
        return packId
    }

    @Test
    fun `a completed download marks the pack ready and records its size`() = runTest {
        val packId = seedPack()

        repository.runDownload(packId)

        val pack = packs.getById(packId)!!
        assertEquals(OfflinePackStatus.READY, pack.status)
        assertNotNull("a ready pack must know when it arrived", pack.downloadedAt)
        assertTrue("a ready pack must know what it cost", pack.sizeBytes > 0)
        assertNull(pack.failureReason)
    }

    /** Both styles are downloaded, a light-only pack is a blank map when the phone switches to dark. */
    @Test
    fun `a pack downloads both basemap styles`() = runTest {
        val packId = seedPack()

        repository.runDownload(packId)

        val styles = gateway.createdSpecs.map { it.styleUrl }.toSet()
        assertEquals(
            "both the light and dark basemap must be downloaded",
            setOf(STYLE_URL_LIGHT, STYLE_URL_DARK),
            styles
        )
        assertTrue(
            "both regions must cover the same ground",
            gateway.createdSpecs.all { it.bounds == bounds }
        )
    }

    @Test
    fun `only the light style is downloaded when both themes are turned off`() = runTest {
        every { prefs.offlineBothThemes } returns flowOf(false)
        val packId = seedPack(variants = listOf("light"))

        repository.runDownload(packId)

        assertEquals(listOf(STYLE_URL_LIGHT), gateway.createdSpecs.map { it.styleUrl })
    }

    @Test
    fun `the tile ceiling is raised before any download starts`() = runTest {
        repository.runDownload(seedPack())

        assertEquals(
            "MapLibre's 6 000-tile default is reachable by a buffered marathon",
            MapLibreOfflineGateway.DEFAULT_TILE_LIMIT,
            gateway.tileCountLimit
        )
    }

    /** A region that's already complete emits no callbacks, the download must not wait for one. */
    @Test
    fun `a region that is already complete finishes instead of hanging`() = runTest {
        val packId = seedPack()
        // No scripted progress at all: the fake emits a single complete status,
        // exactly as MapLibre does for a region with nothing left to fetch.
        gateway.scripted.clear()

        // No explicit timeout: runTest imposes its own, and a `withTimeout` here
        // would race the virtual clock rather than the code under test.  A
        // regression makes this hang and the test framework says so.
        repository.runDownload(packId)

        assertEquals(OfflinePackStatus.READY, packs.getById(packId)!!.status)
    }

    /** Progress stays in memory, not a Room write per resource. */
    @Test
    fun `progress is reported to the registry and cleared when the download ends`() = runTest {
        val packId = seedPackBoundTo(1L)
        gateway.scripted[1L] = listOf(
            progress(10, 100),
            progress(50, 100),
            complete(resources = 100)
        )

        assertNull(registry.snapshotOf(packId))
        repository.runDownload(packId)

        assertNull(
            "a finished download must not leave progress behind for a dead job",
            registry.snapshotOf(packId)
        )
    }

    @Test
    fun `progress counters never move backwards across the two style regions`() {
        val registry = OfflineDownloadRegistry()

        registry.report(1L, progress(80, 100, precise = true, bytes = 80_000))
        // The second region starts its own counters from zero.  Reported raw,
        // the bar would jump back to the start half way through the download.
        registry.report(1L, progress(5, 100, precise = true, bytes = 5_000))

        val snapshot = registry.snapshotOf(1L)!!
        assertEquals(80L, snapshot.completedResources)
        assertEquals(80_000L, snapshot.completedBytes)
    }

    /** Null fraction until MapLibre's count is precise, so the bar never goes backwards. */
    @Test
    fun `progress has no fraction until the resource count is precise`() {
        val registry = OfflineDownloadRegistry()

        registry.report(1L, progress(10, 100, precise = false))
        assertNull(
            "an imprecise count must not be rendered as a percentage",
            registry.snapshotOf(1L)!!.fraction
        )

        registry.report(1L, progress(50, 100, precise = true))
        assertEquals(0.5f, registry.snapshotOf(1L)!!.fraction!!, 0.001f)
    }

    @Test
    fun `a network failure is recorded as retryable, not as corruption`() = runTest {
        val packId = seedPackBoundTo(1L)
        gateway.failures[1L] = OfflineDownloadException(
            OfflineFailureReason.NETWORK,
            "connection dropped"
        )

        repository.runDownload(packId)

        val pack = packs.getById(packId)!!
        assertEquals(OfflinePackStatus.FAILED, pack.status)
        assertEquals(OfflineFailureReason.NETWORK, pack.failureReason)
        assertTrue(
            "walking out of Wi-Fi has not corrupted anything",
            pack.failureReason!!.isRetryable
        )
    }

    @Test
    fun `exceeding the tile limit is recorded as not worth retrying unchanged`() = runTest {
        val packId = seedPackBoundTo(1L)
        gateway.failures[1L] = OfflineDownloadException(
            OfflineFailureReason.TILE_LIMIT,
            "too many tiles"
        )

        repository.runDownload(packId)

        val reason = packs.getById(packId)!!.failureReason
        assertEquals(OfflineFailureReason.TILE_LIMIT, reason)
        assertTrue("retrying the same area would fail the same way", !reason!!.isRetryable)
    }

    /** Free space is checked before starting, not discovered at 95 %. */
    @Test
    fun `a download is refused up front when the disk is too full`() = runTest {
        every { storage.hasRoomFor(any()) } returns false
        val packId = seedPack(estimatedBytes = 500L * 1024 * 1024)

        repository.runDownload(packId)

        val pack = packs.getById(packId)!!
        assertEquals(OfflinePackStatus.FAILED, pack.status)
        assertEquals(OfflineFailureReason.DISK, pack.failureReason)
        assertTrue(
            "nothing should have been created when there is no room",
            gateway.createdSpecs.isEmpty()
        )
    }

    @Test
    fun `a pack with unusable bounds fails loudly instead of doing nothing`() = runTest {
        val packId = packs.insertPack(
            OfflinePackEntity(label = "Broken", boundsJson = "not json")
        )

        repository.runDownload(packId)

        assertEquals(OfflineFailureReason.NO_GEOMETRY, packs.getById(packId)!!.failureReason)
    }

    @Test
    fun `deleting a pack removes both regions and repacks the database`() = runTest {
        val packId = seedPack()
        repository.runDownload(packId)
        val regionIds = packs.getRegions(packId).mapNotNull { it.maplibreRegionId }
        assertEquals(2, regionIds.size)

        val packsBefore = gateway.packDatabaseCalls
        repository.deletePack(packId)

        assertTrue(
            "every style variant's tiles must go",
            gateway.deletedRegionIds.containsAll(regionIds)
        )
        assertNull(packs.getById(packId))
        assertTrue(
            // MapLibre's store is SQLite: deleting a region frees pages inside
            // the file without shrinking it, so without this the user deletes a
            // course, checks their storage, and sees nothing was freed.
            "the database must be packed or the disk is not actually returned",
            gateway.packDatabaseCalls > packsBefore
        )
    }

    @Test
    fun `an existing MapLibre region is reused rather than downloaded twice`() = runTest {
        val packId = seedPack(variants = listOf("light"))
        val region = packs.getRegions(packId).single()
        gateway.seedRegion(42L, spec())
        packs.setMaplibreRegionId(region.id, 42L)

        repository.runDownload(packId)

        assertTrue(
            "a region MapLibre already has must not be created again",
            gateway.createdSpecs.isEmpty()
        )
    }

    /** A stored id MapLibre doesn't know (cleared storage) must not be trusted. */
    @Test
    fun `a stored region id MapLibre has never heard of is replaced`() = runTest {
        val packId = seedPack(variants = listOf("light"))
        val region = packs.getRegions(packId).single()
        packs.setMaplibreRegionId(region.id, 999L)

        repository.runDownload(packId)

        assertEquals(
            "a dangling id must be replaced with a real download",
            1,
            gateway.createdSpecs.size
        )
        assertEquals(OfflinePackStatus.READY, packs.getById(packId)!!.status)
    }

    /** From the second pack on the estimate uses this device's own measurement. */
    @Test
    fun `a completed download calibrates the size estimate for next time`() = runTest {
        val packId = seedPackBoundTo(1L)
        gateway.finalStatus[1L] = complete(tiles = 500, tileBytes = 5_000_000)

        repository.runDownload(packId)

        io.mockk.coVerify { prefs.setOfflineBytesPerTile(any()) }
    }

    /**
     * The gate's Download after "not now" at import. The declined pack is PAUSED,
     * matching on status would create a second pack over the same ground.
     */
    @Test
    fun `starting a download for a declined area resumes that pack, not a new one`() = runTest {
        val courseId = insertCourse()
        val first = repository.createPack(
            label = "Trondheim",
            bounds = bounds,
            courseIds = listOf(courseId),
            choice = com.coursemapper.domain.model.OfflinePackChoice.DECLINED,
            startNow = false
        )
        assertEquals(OfflinePackStatus.PAUSED, packs.getById(first)!!.status)

        val second = repository.createPack(
            label = "Trondheim",
            bounds = bounds,
            courseIds = listOf(courseId),
            choice = com.coursemapper.domain.model.OfflinePackChoice.ACCEPTED,
            startNow = true
        )

        assertEquals("the declined pack must be picked back up", first, second)
        assertEquals(1, packs.observeAllSnapshot().size)
        assertEquals(
            "and its recorded answer must now be yes",
            com.coursemapper.domain.model.OfflinePackChoice.ACCEPTED,
            packs.getById(first)!!.userChoice
        )
    }

    @Test
    fun `a failed pack is retried in place rather than duplicated`() = runTest {
        val courseId = insertCourse()
        val packId = repository.createPack(
            label = "Trondheim",
            bounds = bounds,
            courseIds = listOf(courseId),
            choice = com.coursemapper.domain.model.OfflinePackChoice.ACCEPTED,
            startNow = false
        )
        packs.setStatus(
            packId,
            OfflinePackStatus.FAILED,
            OfflineFailureReason.NETWORK,
            "connection dropped"
        )

        val again = repository.createPack(
            label = "Trondheim",
            bounds = bounds,
            courseIds = listOf(courseId),
            choice = com.coursemapper.domain.model.OfflinePackChoice.ACCEPTED,
            startNow = true
        )

        assertEquals(packId, again)
        assertEquals(1, packs.observeAllSnapshot().size)
    }

    @Test
    fun `a second course at the same venue joins the existing pack`() = runTest {
        val marathon = insertCourse()
        val half = insertCourse()

        val packId = repository.createPack(
            label = "Trondheim",
            bounds = bounds,
            courseIds = listOf(marathon),
            choice = com.coursemapper.domain.model.OfflinePackChoice.ACCEPTED,
            startNow = false
        )
        val joined = repository.createPack(
            label = "Trondheim",
            bounds = bounds,
            courseIds = listOf(marathon, half),
            choice = com.coursemapper.domain.model.OfflinePackChoice.ACCEPTED,
            startNow = false
        )

        assertEquals(packId, joined)
        assertEquals(
            "both races must now hold the same map alive",
            setOf(marathon, half),
            packs.liveCourseIds(packId).toSet()
        )
    }

    /** Next week's 5 K at a venue already mapped joins the existing pack. */
    @Test
    fun `a new course inside an existing pack's area joins it instead of downloading again`() =
        runTest {
            val marathon = insertCourse()
            val packId = repository.createPack(
                label = "Trondheim",
                bounds = bounds,
                courseIds = listOf(marathon),
                choice = com.coursemapper.domain.model.OfflinePackChoice.ACCEPTED,
                startNow = false
            )

            // A 5 K entirely inside the marathon's area, created later.
            val fiveK = insertCourse()
            val smaller = GeoBounds(south = 63.42, north = 63.46, west = 10.32, east = 10.36)
            val reused = repository.createPack(
                label = "Trondheim 5 K",
                bounds = smaller,
                courseIds = listOf(fiveK),
                choice = com.coursemapper.domain.model.OfflinePackChoice.ACCEPTED,
                startNow = false
            )

            assertEquals("the venue is already mapped", packId, reused)
            assertEquals(1, packs.observeAllSnapshot().size)
            assertTrue(
                "and the new course now holds that map alive too",
                fiveK in packs.liveCourseIds(packId)
            )
        }

    @Test
    fun `a course somewhere else gets its own pack`() = runTest {
        val trondheim = insertCourse()
        val first = repository.createPack(
            label = "Trondheim",
            bounds = bounds,
            courseIds = listOf(trondheim),
            choice = com.coursemapper.domain.model.OfflinePackChoice.ACCEPTED,
            startNow = false
        )

        val bergen = insertCourse()
        val second = repository.createPack(
            label = "Bergen",
            bounds = GeoBounds(south = 60.3, north = 60.4, west = 5.3, east = 5.4),
            courseIds = listOf(bergen),
            choice = com.coursemapper.domain.model.OfflinePackChoice.ACCEPTED,
            startNow = false
        )

        assertTrue("a different venue is a different map", first != second)
        assertEquals(2, packs.observeAllSnapshot().size)
    }

    /** Deleting a course deletes its map, unless a live course still needs it. */
    @Test
    fun `a pack is released only when its last live course is deleted`() = runTest {
        val marathon = insertCourse()
        val half = insertCourse()
        val packId = repository.createPack(
            label = "Trondheim",
            bounds = bounds,
            courseIds = listOf(marathon, half),
            choice = com.coursemapper.domain.model.OfflinePackChoice.ACCEPTED,
            startNow = false
        )

        db.courseDao().softDelete(half)
        repository.onCourseDeleted(half)
        assertNotNull(
            "the marathon still needs this map",
            packs.getById(packId)
        )

        db.courseDao().softDelete(marathon)
        repository.onCourseDeleted(marathon)
        assertNull("with nothing left needing it, the map goes", packs.getById(packId))
        assertTrue(
            "and the space is actually returned",
            gateway.packDatabaseCalls > 0
        )
    }

    @Test
    fun `bytes freed are only reported when the map is really released`() = runTest {
        val marathon = insertCourse()
        val half = insertCourse()
        val packId = repository.createPack(
            label = "Trondheim",
            bounds = bounds,
            courseIds = listOf(marathon, half),
            choice = com.coursemapper.domain.model.OfflinePackChoice.ACCEPTED,
            startNow = false
        )
        packs.markReady(packId, sizeBytes = 180L * 1024 * 1024, completed = 10, required = 10)

        assertNull(
            "a shared map frees nothing, and the dialog must not promise it",
            repository.bytesFreedByDeletingCourse(marathon)
        )

        db.courseDao().softDelete(half)
        repository.onCourseDeleted(half)

        assertEquals(
            "now the marathon is the last one holding it",
            180L * 1024 * 1024,
            repository.bytesFreedByDeletingCourse(marathon)
        )
    }

    private suspend fun insertCourse(): Long {
        val routeId = db.routeDao().insert(
            com.coursemapper.data.db.BaseRouteEntity(name = "R", source = "gpx_import")
        )
        return db.courseDao().insert(
            com.coursemapper.data.db.ComposedCourseEntity(
                baseRouteId = routeId,
                name = "Course",
                totalDistanceMetres = 42_195.0
            )
        )
    }

    private fun spec() = OfflineRegionSpec(
        styleUrl = STYLE_URL_LIGHT,
        bounds = bounds,
        minZoom = 5.0,
        maxZoom = 16.0,
        pixelRatio = 1.0f
    )
}
