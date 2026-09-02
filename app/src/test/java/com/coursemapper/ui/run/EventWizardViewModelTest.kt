package com.coursemapper.ui.run

import android.content.ContentResolver
import android.net.Uri
import com.coursemapper.data.prefs.UserPreferencesRepository
import com.coursemapper.data.repository.RouteRepository
import com.coursemapper.domain.CourseCompositionEngine
import com.coursemapper.domain.CumulativeDistanceCalculator
import com.coursemapper.domain.model.BaseRoute
import com.coursemapper.domain.model.MarkerPreset
import com.coursemapper.domain.model.MarkerRule
import com.coursemapper.domain.model.MarkerType
import com.coursemapper.domain.model.RoutePoint
import com.coursemapper.gpx.GpxImporter
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream

/** Per-track lap count must reach the repository call that builds the course. */
@OptIn(ExperimentalCoroutinesApi::class)
class EventWizardViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private val repository = mockk<RouteRepository>(relaxed = true)
    private val calc = CumulativeDistanceCalculator()
    private val engine = CourseCompositionEngine(calc)
    private val prefs = mockk<UserPreferencesRepository>(relaxed = true)
    private val contentResolver = mockk<ContentResolver>()
    private val uri = mockk<Uri>()

    private lateinit var viewModel: EventWizardViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        every { repository.observePresets() } returns flowOf(emptyList())
        every { repository.observeCourses() } returns flowOf(emptyList())
        every { contentResolver.openInputStream(uri) } answers {
            ByteArrayInputStream(ByteArray(0))
        }
        coEvery {
            repository.importGpxTrackAsDraft(
                any(), any(), any(), any(), any(), any(), any(), any()
            )
        } returns 7L
        // nothing offered by default, the prompt is tested separately below
        coEvery { repository.proposeOfflinePackOnCreate(any()) } returns null
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `lap count set on a track is passed through to the import`() = runTest(dispatcher) {
        givenOneLoopTrack()

        viewModel.setTrackLaps(0, 4)
        advanceUntilIdle()
        viewModel.createCourses()
        advanceUntilIdle()

        val laps = slot<Int>()
        coVerify {
            repository.importGpxTrackAsDraft(
                result = any(), name = any(), notes = any(),
                presetId = any(), lapCount = capture(laps), targetDistanceCm = any(),
                includeStart = any(), includeFinish = any()
            )
        }
        assertEquals(4, laps.captured)
    }

    @Test
    fun `target distance set on a track is passed through to the import`() = runTest(dispatcher) {
        givenOneLoopTrack()

        // A half marathon composed from whatever lap the file contained.
        viewModel.setTrackTarget(0, 2_109_750L)
        advanceUntilIdle()
        viewModel.createCourses()
        advanceUntilIdle()

        val target = slot<Long>()
        coVerify {
            repository.importGpxTrackAsDraft(
                result = any(), name = any(), notes = any(),
                presetId = any(), lapCount = any(), targetDistanceCm = capture(target),
                includeStart = any(), includeFinish = any()
            )
        }
        assertEquals(2_109_750L, target.captured)
    }

    @Test
    fun `an untouched track still imports as a single lap`() = runTest(dispatcher) {
        givenOneLoopTrack()

        viewModel.createCourses()
        advanceUntilIdle()

        coVerify {
            repository.importGpxTrackAsDraft(
                result = any(), name = any(), notes = any(),
                presetId = any(), lapCount = 1, targetDistanceCm = 0L,
                includeStart = any(), includeFinish = any()
            )
        }
    }

    @Test
    fun `existing courses alone can build an event, with no GPX at all`() = runTest(dispatcher) {
        // existing courses must be selectable without any GPX to import
        every { repository.observeCourses() } returns flowOf(listOf(publishedCourse(11L), publishedCourse(12L)))
        coEvery { repository.createCourseGroup(any(), any()) } returns 99L
        viewModel = EventWizardViewModel(repository, calc, engine, prefs)
        advanceUntilIdle()

        viewModel.toggleExistingCourse(11L)
        viewModel.toggleExistingCourse(12L)
        viewModel.onEventNameChange("Trondheim Marathon")
        assertTrue("a selection-only build must be creatable", viewModel.uiState.value.canCreate)

        viewModel.createCourses()
        advanceUntilIdle()

        coVerify { repository.createCourseGroup("Trondheim Marathon", listOf(11L, 12L)) }
        // Nothing was imported: there was nothing to import.
        coVerify(exactly = 0) {
            repository.importGpxTrackAsDraft(any(), any(), any(), any(), any(), any(), any(), any())
        }

        val created = viewModel.uiState.value.created
        assertNotNull(created)
        assertEquals(99L, created!!.groupId)
        assertEquals(listOf(11L, 12L), created.courseIds)
    }

    @Test
    fun `a single imported track produces no group, so the caller lands on the course`() =
        runTest(dispatcher) {
            givenOneLoopTrack()

            viewModel.createCourses()
            advanceUntilIdle()

            val created = viewModel.uiState.value.created
            assertNotNull(created)
            assertNull("one course is not an event", created!!.groupId)
            assertEquals(listOf(7L), created.courseIds)
            coVerify(exactly = 0) { repository.createCourseGroup(any(), any()) }
        }

    private fun publishedCourse(id: Long) = com.coursemapper.domain.model.ComposedCourse(
        id                     = id,
        baseRouteId            = 1L,
        name                   = "Course $id",
        createdAt              = 0L,
        updatedAt              = 0L,
        lapCount               = 1,
        finalLapDistanceMetres = 0.0,
        totalDistanceMetres    = 5_000.0,
        presetSnapshot         = com.coursemapper.domain.model.MarkerPresetSnapshot(
            sourcePresetId = null, rules = emptyList()
        ),
        markers                = listOf(
            com.coursemapper.domain.model.DistanceMarker(
                id = id * 100, courseId = id, sequenceIndex = 1, lat = 0.0, lon = 0.0,
                cumulativeDistanceMetres = 1_000.0, type = MarkerType.DISTANCE,
                label = "1 km", isManuallyMoved = false
            )
        ),
        isDraft                = false
    )

    @Test
    fun `endpoint toggles are seeded from the chosen profile`() = runTest(dispatcher) {
        every { repository.observePresets() } returns flowOf(listOf(everyKmPreset))
        givenOneLoopTrack()

        // The default profile is applied to new rows, endpoints included.
        val row = viewModel.uiState.value.tracks.first()
        assertTrue(row.includeStart)
        assertTrue(row.includeFinish)
    }

    @Test
    fun `a profile without endpoints seeds both toggles off`() = runTest(dispatcher) {
        every { repository.observePresets() } returns flowOf(listOf(alongOnlyPreset))
        givenOneLoopTrack()

        val row = viewModel.uiState.value.tracks.first()
        assertFalse(row.includeStart)
        assertFalse(row.includeFinish)
    }

    @Test
    fun `endpoint choices are passed through to the import`() = runTest(dispatcher) {
        every { repository.observePresets() } returns flowOf(listOf(everyKmPreset))
        givenOneLoopTrack()

        // Four distances sharing one gantry: this one skips the banners.
        viewModel.setTrackIncludeStart(0, false)
        viewModel.setTrackIncludeFinish(0, false)
        advanceUntilIdle()
        viewModel.createCourses()
        advanceUntilIdle()

        coVerify {
            repository.importGpxTrackAsDraft(
                result = any(), name = any(), notes = any(), presetId = any(),
                lapCount = any(), targetDistanceCm = any(),
                includeStart = false, includeFinish = false
            )
        }
    }

    @Test
    fun `switching profile re-seeds the endpoint toggles`() = runTest(dispatcher) {
        every { repository.observePresets() } returns
            flowOf(listOf(alongOnlyPreset, everyKmPreset))
        givenOneLoopTrack()

        // Seeded from the first profile, which has no endpoints.
        assertFalse(viewModel.uiState.value.tracks.first().includeStart)

        viewModel.setTrackPreset(0, everyKmPreset.id)
        advanceUntilIdle()

        val row = viewModel.uiState.value.tracks.first()
        assertTrue(row.includeStart)
        assertTrue(row.includeFinish)
    }

    @Test
    fun `row reports the composed total for the chosen laps`() = runTest(dispatcher) {
        givenOneLoopTrack()
        val single = viewModel.uiState.value.tracks.first()

        viewModel.setTrackLaps(0, 3)
        advanceUntilIdle()

        val row = viewModel.uiState.value.tracks.first()
        assertEquals(3, row.composedFullLaps)
        assertTrue(row.isMultiLap)
        // Three laps of the same loop, within the seam-closing leg's length.
        assertEquals(single.composedDistanceMetres * 3, row.composedDistanceMetres, 5.0)
    }

    @Test
    fun `a target distance can force more laps than the row asked for`() = runTest(dispatcher) {
        givenOneLoopTrack()
        val lapMetres = viewModel.uiState.value.tracks.first().composedLapMetres

        viewModel.setTrackTarget(0, (lapMetres * 2.5 * 100).toLong())
        advanceUntilIdle()

        val row = viewModel.uiState.value.tracks.first()
        assertEquals(2, row.composedFullLaps)
        assertTrue("expected a trailing partial", row.composedPartialMetres > 0.0)
    }

    @Test
    fun `repeating a track that does not close asks before creating`() = runTest(dispatcher) {
        givenOneTrack(points = openRoute())

        viewModel.setTrackLaps(0, 2)
        advanceUntilIdle()

        assertNotNull(
            "an open route repeated must be flagged",
            viewModel.uiState.value.tracks.first().openRouteGapMetres
        )

        viewModel.createCourses()
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.pendingOpenLoopConfirm)
        coVerify(exactly = 0) {
            repository.importGpxTrackAsDraft(
                any(), any(), any(), any(), any(), any(), any(), any()
            )
        }
    }

    @Test
    fun `confirming the open-loop warning proceeds with the import`() = runTest(dispatcher) {
        givenOneTrack(points = openRoute())

        viewModel.setTrackLaps(0, 2)
        advanceUntilIdle()
        viewModel.createCourses()
        advanceUntilIdle()
        viewModel.confirmOpenLoopAndCreate()
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.pendingOpenLoopConfirm)
        coVerify(exactly = 1) {
            repository.importGpxTrackAsDraft(
                result = any(), name = any(), notes = any(),
                presetId = any(), lapCount = 2, targetDistanceCm = any(),
                includeStart = any(), includeFinish = any()
            )
        }
    }

    @Test
    fun `an open route used once is not flagged`() = runTest(dispatcher) {
        givenOneTrack(points = openRoute())
        advanceUntilIdle()

        // Point-to-point is the normal shape for a single-lap course; the gap
        // is only crossed when the route repeats.
        assertNull(viewModel.uiState.value.tracks.first().openRouteGapMetres)

        viewModel.createCourses()
        advanceUntilIdle()
        assertFalse(viewModel.uiState.value.pendingOpenLoopConfirm)
    }

    private val everyKmPreset = MarkerPreset(
        id = 1L, name = "Every km", createdAt = 0L, updatedAt = 0L,
        rules = listOf(
            MarkerRule(MarkerType.START),
            MarkerRule(MarkerType.DISTANCE, intervalMetres = 1_000.0),
            MarkerRule(MarkerType.FINISH)
        )
    )

    private val alongOnlyPreset = MarkerPreset(
        id = 2L, name = "Kilometres only", createdAt = 0L, updatedAt = 0L,
        rules = listOf(MarkerRule(MarkerType.DISTANCE, intervalMetres = 1_000.0))
    )

    private fun TestScope.givenOneLoopTrack() = givenOneTrack(points = loopRoute())

    /** Four tracks in one import, like a real event file. */
    private fun TestScope.givenFourLoopTracks() {
        val results = List(4) { i ->
            val points = loopRoute()
            GpxImporter.ImportResult(
                route = BaseRoute(
                    id = 0L, name = "Track $i", notes = "",
                    createdAt = 0L, updatedAt = 0L, isApproved = false,
                    distanceMetres = 0.0, source = "gpx_import",
                    rawPoints = points, smoothedPoints = points
                ),
                points = points
            )
        }
        coEvery { repository.previewGpx(any(), any()) } returns results

        viewModel = EventWizardViewModel(repository, calc, engine, prefs)
        viewModel.loadGpxFiles(listOf(uri), contentResolver)
        advanceUntilIdle()
    }

    private fun TestScope.givenOneTrack(points: List<RoutePoint>) {
        val result = GpxImporter.ImportResult(
            route  = BaseRoute(
                id = 0L, name = "Track", notes = "",
                createdAt = 0L, updatedAt = 0L, isApproved = false,
                distanceMetres = 0.0, source = "gpx_import",
                rawPoints = points, smoothedPoints = points
            ),
            points = points
        )
        coEvery { repository.previewGpx(any(), any()) } returns listOf(result)

        viewModel = EventWizardViewModel(repository, calc, engine, prefs)
        viewModel.loadGpxFiles(listOf(uri), contentResolver)
        advanceUntilIdle()
    }

    /** Out-and-back: the endpoints coincide, so repeating it is legitimate. */
    private fun loopRoute(): List<RoutePoint> {
        val out = line(0.0, 0.0, 0.0, 0.02, 20)
        return out + line(0.0, 0.02, 0.0, 0.0, 20).drop(1)
    }

    /** A straight point-to-point line: its end is far from its start. */
    private fun openRoute(): List<RoutePoint> = line(0.0, 0.0, 0.0, 0.02, 20)

    private fun line(
        lat1: Double, lon1: Double,
        lat2: Double, lon2: Double,
        n: Int
    ): List<RoutePoint> = (0 until n).map { i ->
        val t = i.toDouble() / (n - 1)
        RoutePoint(
            lat            = lat1 + t * (lat2 - lat1),
            lon            = lon1 + t * (lon2 - lon1),
            altMetres      = null,
            accuracyMetres = null,
            timestampMs    = i.toLong() * 1_000,
            isSmoothed     = true
        )
    }

    /** One prompt for the whole import, four tracks are one area. */
    @Test
    fun `importing four tracks asks about the offline map exactly once`() = runTest(dispatcher) {
        givenFourLoopTracks()
        coEvery { repository.proposeOfflinePackOnCreate(any()) } returns proposal(
            courseIds = listOf(7L, 7L, 7L, 7L)
        )

        viewModel.createCourses()
        advanceUntilIdle()

        coVerify(exactly = 1) { repository.proposeOfflinePackOnCreate(any()) }
        assertNotNull("the one prompt should be showing", viewModel.uiState.value.offlinePrompt)
    }

    /** Navigation waits for the answer, or the dialog is left on a screen that's gone. */
    @Test
    fun `navigation is held until the offline prompt is answered`() = runTest(dispatcher) {
        givenOneLoopTrack()
        coEvery { repository.proposeOfflinePackOnCreate(any()) } returns proposal()

        viewModel.createCourses()
        advanceUntilIdle()

        assertNull(
            "the wizard must not navigate away with its dialog still up",
            viewModel.uiState.value.created
        )

        viewModel.acceptOfflineDownload(alwaysFromNowOn = false)
        advanceUntilIdle()

        assertNotNull("answering releases the navigation", viewModel.uiState.value.created)
        assertNull(viewModel.uiState.value.offlinePrompt)
    }

    @Test
    fun `accepting starts the download`() = runTest(dispatcher) {
        givenOneLoopTrack()
        val offer = proposal()
        coEvery { repository.proposeOfflinePackOnCreate(any()) } returns offer

        viewModel.createCourses()
        advanceUntilIdle()
        viewModel.acceptOfflineDownload(alwaysFromNowOn = false)
        advanceUntilIdle()

        coVerify { repository.startOfflineDownload(offer) }
    }

    /** Declining is remembered for the area. */
    @Test
    fun `declining is recorded so the same area is not offered again`() = runTest(dispatcher) {
        givenOneLoopTrack()
        val offer = proposal()
        coEvery { repository.proposeOfflinePackOnCreate(any()) } returns offer

        viewModel.createCourses()
        advanceUntilIdle()
        viewModel.declineOfflineDownload()
        advanceUntilIdle()

        coVerify { repository.declineOfflineDownload(offer) }
        coVerify(exactly = 0) { repository.startOfflineDownload(any()) }
        assertNotNull("declining still lets the wizard finish", viewModel.uiState.value.created)
    }

    @Test
    fun `ticking always from now on changes the policy`() = runTest(dispatcher) {
        givenOneLoopTrack()
        coEvery { repository.proposeOfflinePackOnCreate(any()) } returns proposal()

        viewModel.createCourses()
        advanceUntilIdle()
        viewModel.acceptOfflineDownload(alwaysFromNowOn = true)
        advanceUntilIdle()

        coVerify {
            prefs.setOfflineDownloadPolicy(
                com.coursemapper.domain.model.OfflineDownloadPolicy.ALWAYS
            )
        }
    }

    /** Dismissing isn't a no, the courses are already saved so move on. */
    @Test
    fun `dismissing the prompt records nothing but still lets the wizard finish`() =
        runTest(dispatcher) {
            givenOneLoopTrack()
            coEvery { repository.proposeOfflinePackOnCreate(any()) } returns proposal()

            viewModel.createCourses()
            advanceUntilIdle()
            viewModel.dismissOfflinePrompt()
            advanceUntilIdle()

            coVerify(exactly = 0) { repository.declineOfflineDownload(any()) }
            coVerify(exactly = 0) { repository.startOfflineDownload(any()) }
            assertNotNull(viewModel.uiState.value.created)
        }

    private fun proposal(courseIds: List<Long> = listOf(7L)) =
        RouteRepository.OfflinePackProposal(
            courseIds = courseIds,
            courseNames = courseIds.map { "Course $it" },
            label = "Trondheim",
            quote = com.coursemapper.offline.PackQuote(
                bounds = com.coursemapper.domain.model.GeoBounds(63.4, 63.5, 10.3, 10.4),
                tileCount = 5_000,
                estimatedBytes = 120L * 1024 * 1024,
                exceedsTileLimit = false,
                hasRoom = true,
                freeBytes = 8L * 1024 * 1024 * 1024
            )
        )
}
