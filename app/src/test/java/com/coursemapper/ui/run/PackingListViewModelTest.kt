package com.coursemapper.ui.run

import androidx.lifecycle.SavedStateHandle
import com.coursemapper.data.repository.RouteRepository
import com.coursemapper.domain.model.PlacementRun
import com.coursemapper.domain.model.RunOrdering
import com.coursemapper.domain.model.RunSign
import com.coursemapper.domain.model.RunStop
import com.coursemapper.domain.model.RunStopState
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * The packing list shows the plan the rider will follow, never its own. The two
 * candidate orders agree on only 4 of 80 stops for the real event.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PackingListViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private val repository = mockk<RouteRepository>(relaxed = true)

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    /** Four stops where the two orders share no position. */
    private val preview = RouteRepository.RunPreview(
        stopPositions   = listOf(0.0 to 0.0, 1.0 to 0.0, 2.0 to 0.0, 3.0 to 0.0),
        signCounts      = listOf(1, 1, 1, 1),
        signs           = listOf(
            listOf(RunSign("Marathon", "Start")),
            listOf(RunSign("Marathon", "1 km")),
            listOf(RunSign("Marathon", "2 km")),
            listOf(RunSign("Marathon", "Finish"))
        ),
        spineOrder      = listOf(0, 1, 2, 3),
        optimizedOrder  = listOf(3, 2, 1, 0),
        spineMetres     = 3000.0,
        optimizedMetres = 2500.0,
        spineCourseName = "Marathon"
    )

    private fun viewModel(vararg args: Pair<String, Any>) =
        PackingListViewModel(repository, SavedStateHandle(mapOf(*args)))

    private fun labels(state: PackingListUiState) =
        state.stops.map { stop -> stop.signs.joinToString(" · ") { it.label } }

    @Test
    fun `follow-the-main-route is the order when nothing else is asked for`() = runTest {
        coEvery { repository.previewRun(listOf(1L)) } returns preview

        val vm = viewModel("courseIds" to "1")
        advanceUntilIdle()

        assertEquals(listOf("Start", "1 km", "2 km", "Finish"), labels(vm.uiState.value))
        assertEquals(listOf(1, 2, 3, 4), vm.uiState.value.stops.map { it.visitNumber })
    }

    /** A shortest path run is packed in shortest path order. */
    @Test
    fun `shortest path packs in the order that run will be driven`() = runTest {
        coEvery { repository.previewRun(listOf(1L)) } returns preview

        val vm = viewModel("courseIds" to "1", "ordering" to RunOrdering.OPTIMIZED.name)
        advanceUntilIdle()

        assertEquals(listOf("Finish", "2 km", "1 km", "Start"), labels(vm.uiState.value))
    }

    /** An unparseable or absent mode must not silently reorder the rack. */
    @Test
    fun `an unrecognised ordering falls back to follow-the-main-route`() = runTest {
        coEvery { repository.previewRun(listOf(1L)) } returns preview

        val vm = viewModel("courseIds" to "1", "ordering" to "sideways")
        advanceUntilIdle()

        assertEquals(listOf("Start", "1 km", "2 km", "Finish"), labels(vm.uiState.value))
    }

    @Test
    fun `several courses are assembled as one pass`() = runTest {
        coEvery { repository.previewRun(listOf(1L, 2L)) } returns preview

        val vm = viewModel("courseIds" to "1,2")
        advanceUntilIdle()

        assertEquals(4, vm.uiState.value.stops.size)
    }

    /** An existing run is read as is, it may have been resequenced. No `previewRun`. */
    @Test
    fun `a run is packed from its own stops, whatever order they now hold`() = runTest {
        coEvery { repository.getRun(7L) } returns run(
            ordering = RunOrdering.SPINE,
            stops = listOf(
                stop(id = 10L, stopIndex = 2, label = "2 km"),
                stop(id = 11L, stopIndex = 0, label = "Finish", state = RunStopState.DONE),
                stop(id = 12L, stopIndex = 1, label = "Start")
            )
        )

        val vm = viewModel("runId" to 7L)
        advanceUntilIdle()

        assertEquals(listOf("Finish", "Start", "2 km"), labels(vm.uiState.value))
        assertEquals(RunStopState.DONE, vm.uiState.value.stops.first().state)
        assertEquals(true, vm.uiState.value.canExport)
    }

    /** A run id wins over course ids when a caller happens to pass both. */
    @Test
    fun `a run id is preferred to the courses it covers`() = runTest {
        coEvery { repository.getRun(7L) } returns run(
            ordering = RunOrdering.OPTIMIZED,
            stops = listOf(stop(id = 10L, stopIndex = 0, label = "Start"))
        )
        coEvery { repository.previewRun(any(), any()) } returns preview

        val vm = viewModel("runId" to 7L, "courseIds" to "1,2")
        advanceUntilIdle()

        assertEquals(listOf("Start"), labels(vm.uiState.value))
    }

    private fun run(ordering: RunOrdering, stops: List<RunStop>) = PlacementRun(
        id = 7L, name = "Trondheim Marathon", createdAt = 0L, updatedAt = 0L,
        ordering = ordering, spineCourseId = 1L, startedAt = null, completedAt = null,
        totalPlannedMetres = 3000.0, courseIds = listOf(1L, 2L), stops = stops
    )

    private fun stop(
        id: Long,
        stopIndex: Int,
        label: String,
        state: RunStopState = RunStopState.PENDING
    ) = RunStop(
        id = id, runId = 7L, stopIndex = stopIndex, lat = 63.4, lon = 10.4,
        signs = listOf(RunSign("Marathon", label)), state = state
    )
}
