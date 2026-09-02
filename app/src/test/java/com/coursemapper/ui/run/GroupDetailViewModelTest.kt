package com.coursemapper.ui.run

import androidx.lifecycle.SavedStateHandle
import com.coursemapper.data.repository.RouteRepository
import com.coursemapper.domain.model.ComposedCourse
import com.coursemapper.domain.model.CourseGroup
import com.coursemapper.domain.model.DistanceMarker
import com.coursemapper.domain.model.MarkerPresetSnapshot
import com.coursemapper.domain.model.MarkerType
import com.coursemapper.domain.model.PlacementRun
import com.coursemapper.domain.model.RunOrdering
import com.coursemapper.domain.model.TravelProfile
import com.coursemapper.ui.format.DistanceFormatter
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * The group launches its own run. The group holds no per-outing state, so the
 * run records its group and defaults come from that history.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GroupDetailViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private val repository = mockk<RouteRepository>(relaxed = true)
    private val formatter  = mockk<DistanceFormatter>(relaxed = true)

    private val groupId = 5L

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        every { formatter.unitEnumFlow } returns flowOf(DistanceFormatter.DistanceUnit.KM)
        every { formatter.formatCompact(any(), any()) } returns "19.6 km"
        coEvery { repository.getCourseGroup(groupId) } returns CourseGroup(
            id = groupId, name = "Trondheim Marathon", createdAt = 0L, updatedAt = 0L,
            courseIds = listOf(1L, 2L)
        )
        coEvery { repository.getCourse(1L) } returns course(1L, "Marathon")
        coEvery { repository.getCourse(2L) } returns course(2L, "5 K")
        coEvery { repository.getCourseDisplayLines(any(), any()) } returns
            RouteRepository.CourseDisplay(emptyList(), emptyList(), emptyList())
        coEvery { repository.previewRun(any(), any()) } returns null
        coEvery { repository.getUnfinishedRunForGroup(groupId) } returns null
        coEvery { repository.getLatestRunForGroup(groupId) } returns null
        coEvery { repository.createRun(any(), any(), any(), any(), any(), any()) } returns 77L
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun viewModel() = GroupDetailViewModel(
        repository, formatter, SavedStateHandle(mapOf("groupId" to groupId))
    )

    @Test
    fun `a first run uses the defaults`() = runTest(dispatcher) {
        val vm = viewModel()
        advanceUntilIdle()

        assertEquals(GroupRunAction.START_FIRST, vm.uiState.value.runAction)
        assertEquals(RunOrdering.SPINE, vm.uiState.value.ordering)
        assertEquals(TravelProfile.CAR, vm.uiState.value.travelProfile)

        vm.startRun()
        advanceUntilIdle()

        coVerify {
            repository.createRun(
                name          = "Trondheim Marathon",
                courseIds     = listOf(1L, 2L),
                ordering      = RunOrdering.SPINE,
                spineCourseId = null,
                travelProfile = TravelProfile.CAR,
                groupId       = groupId
            )
        }
        assertEquals(77L, vm.uiState.value.startedRunId)
    }

    @Test
    fun `a second run reuses last time's ordering and travel profile`() = runTest(dispatcher) {
        coEvery { repository.getLatestRunForGroup(groupId) } returns run(
            id = 9L, ordering = RunOrdering.OPTIMIZED, profile = TravelProfile.BIKE,
            completedAt = 1_000L
        )
        val vm = viewModel()
        advanceUntilIdle()

        assertEquals(GroupRunAction.START_AGAIN, vm.uiState.value.runAction)
        assertEquals(RunOrdering.OPTIMIZED, vm.uiState.value.ordering)
        assertEquals(TravelProfile.BIKE, vm.uiState.value.travelProfile)

        vm.startRun()
        advanceUntilIdle()

        coVerify {
            repository.createRun(
                name = any(), courseIds = any(),
                ordering = RunOrdering.OPTIMIZED, spineCourseId = any(),
                travelProfile = TravelProfile.BIKE, groupId = groupId
            )
        }
    }

    @Test
    fun `an unfinished run is resumed, not duplicated`() = runTest(dispatcher) {
        coEvery { repository.getUnfinishedRunForGroup(groupId) } returns run(
            id = 12L, ordering = RunOrdering.SPINE, profile = TravelProfile.CAR
        )
        val vm = viewModel()
        advanceUntilIdle()

        assertEquals(GroupRunAction.RESUME, vm.uiState.value.runAction)
        assertEquals(12L, vm.uiState.value.resumableRunId)

        vm.startRun()
        advanceUntilIdle()

        assertEquals(12L, vm.uiState.value.startedRunId)
        coVerify(exactly = 0) {
            repository.createRun(any(), any(), any(), any(), any(), any())
        }
    }

    @Test
    fun `changing the plan before starting is what gets created`() = runTest(dispatcher) {
        val vm = viewModel()
        advanceUntilIdle()

        vm.selectOrdering(RunOrdering.OPTIMIZED)
        vm.selectTravelProfile(TravelProfile.FOOT)
        vm.startRun()
        advanceUntilIdle()

        coVerify {
            repository.createRun(
                name = any(), courseIds = any(),
                ordering = RunOrdering.OPTIMIZED, spineCourseId = any(),
                travelProfile = TravelProfile.FOOT, groupId = groupId
            )
        }
    }

    @Test
    fun `a soft-deleted member never reaches createRun`() = runTest(dispatcher) {
        // live members only (DAO join), a stale id would give a run stop for a
        // deleted course
        coEvery { repository.getCourseGroup(groupId) } returns CourseGroup(
            id = groupId, name = "Trondheim Marathon", createdAt = 0L, updatedAt = 0L,
            courseIds = listOf(1L)   // the 5 K was deleted
        )
        val vm = viewModel()
        advanceUntilIdle()

        assertEquals(listOf(course(1L, "Marathon")), vm.uiState.value.courses)

        vm.startRun()
        advanceUntilIdle()

        coVerify {
            repository.createRun(
                name = any(), courseIds = listOf(1L), ordering = any(),
                spineCourseId = any(), travelProfile = any(), groupId = groupId
            )
        }
    }

    @Test
    fun `a group whose members all have no markers cannot start a run`() = runTest(dispatcher) {
        coEvery { repository.getCourse(1L) } returns course(1L, "Marathon", markers = emptyList())
        coEvery { repository.getCourse(2L) } returns course(2L, "5 K", markers = emptyList())
        val vm = viewModel()
        advanceUntilIdle()

        vm.startRun()
        advanceUntilIdle()

        assertNull(vm.uiState.value.startedRunId)
        coVerify(exactly = 0) { repository.createRun(any(), any(), any(), any(), any(), any()) }
    }

    private fun run(
        id: Long,
        ordering: RunOrdering,
        profile: TravelProfile,
        completedAt: Long? = null
    ) = PlacementRun(
        id                 = id,
        name               = "Trondheim Marathon",
        createdAt          = 0L,
        updatedAt          = 0L,
        ordering           = ordering,
        spineCourseId      = null,
        startedAt          = 1L,
        completedAt        = completedAt,
        totalPlannedMetres = 19_606.0,
        travelProfile      = profile,
        groupId            = groupId,
        courseIds          = listOf(1L, 2L)
    )

    private fun course(
        id: Long,
        name: String,
        markers: List<DistanceMarker> = listOf(
            DistanceMarker(
                id = id * 10, courseId = id, sequenceIndex = 1, lat = 0.0, lon = 0.0,
                cumulativeDistanceMetres = 1_000.0, type = MarkerType.DISTANCE,
                label = "1 km", isManuallyMoved = false
            )
        )
    ) = ComposedCourse(
        id                     = id,
        baseRouteId            = 1L,
        name                   = name,
        createdAt              = 0L,
        updatedAt              = 0L,
        lapCount               = 1,
        finalLapDistanceMetres = 0.0,
        totalDistanceMetres    = 5_000.0,
        presetSnapshot         = MarkerPresetSnapshot(sourcePresetId = null, rules = emptyList()),
        markers                = markers,
        isDraft                = false
    )
}
