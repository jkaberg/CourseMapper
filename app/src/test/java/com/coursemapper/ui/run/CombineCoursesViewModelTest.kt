package com.coursemapper.ui.run

import com.coursemapper.data.repository.RouteRepository
import com.coursemapper.domain.model.ComposedCourse
import com.coursemapper.domain.model.CourseGroup
import com.coursemapper.domain.model.MarkerPresetSnapshot
import com.coursemapper.domain.model.DistanceMarker
import com.coursemapper.domain.model.MarkerType
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Combining courses you already have, without going through GPX import. */
@OptIn(ExperimentalCoroutinesApi::class)
class CombineCoursesViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private val repository = mockk<RouteRepository>(relaxed = true)

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        every { repository.observeCourses() } returns flowOf(
            listOf(course(1L, "Marathon"), course(2L, "Half"), course(3L, "10 K"))
        )
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun viewModel() = CombineCoursesViewModel(repository)

    @Test
    fun `two selected courses become a new combined course, in list order`() =
        runTest(dispatcher) {
            coEvery { repository.createCourseGroup(any(), any()) } returns 42L
            val vm = viewModel()
            vm.start(null)
            advanceUntilIdle()

            // Ticked out of order; the group keeps the order the list shows, so
            // the member colours on the row match the lines on the map.
            vm.toggle(3L)
            vm.toggle(1L)
            vm.onNameChange("Trondheim Marathon")
            vm.save()
            advanceUntilIdle()

            coVerify { repository.createCourseGroup("Trondheim Marathon", listOf(1L, 3L)) }
            assertEquals(42L, vm.uiState.value.savedGroupId)
        }

    @Test
    fun `fewer than two members cannot be saved`() = runTest(dispatcher) {
        val vm = viewModel()
        vm.start(null)
        advanceUntilIdle()

        assertFalse("nothing selected", vm.uiState.value.canSave)

        vm.toggle(1L)
        assertFalse("one course is a course, not an event", vm.uiState.value.canSave)

        vm.save()
        advanceUntilIdle()
        coVerify(exactly = 0) { repository.createCourseGroup(any(), any()) }

        vm.toggle(2L)
        assertTrue(vm.uiState.value.canSave)
    }

    @Test
    fun `an unnamed combined course still gets a name`() = runTest(dispatcher) {
        coEvery { repository.createCourseGroup(any(), any()) } returns 1L
        val vm = viewModel()
        vm.start(null)
        advanceUntilIdle()
        vm.toggle(1L)
        vm.toggle(2L)
        vm.save()
        advanceUntilIdle()

        coVerify { repository.createCourseGroup("Combined course (2 routes)", listOf(1L, 2L)) }
    }

    @Test
    fun `editing seeds the current membership and writes the change back`() =
        runTest(dispatcher) {
            coEvery { repository.getCourseGroup(5L) } returns CourseGroup(
                id = 5L, name = "Spring Race", createdAt = 0L, updatedAt = 0L,
                courseIds = listOf(1L, 2L)
            )
            val vm = viewModel()
            vm.start(5L)
            advanceUntilIdle()

            assertTrue(vm.uiState.value.isEditing)
            assertEquals(setOf(1L, 2L), vm.uiState.value.selectedIds)
            assertEquals("Spring Race", vm.uiState.value.name)

            vm.toggle(2L)   // remove the Half
            vm.toggle(3L)   // add the 10 K
            vm.save()
            advanceUntilIdle()

            coVerify { repository.setCourseGroupMembers(5L, listOf(1L, 3L)) }
            // Membership edits are not a new group - the same link keeps working.
            coVerify(exactly = 0) { repository.createCourseGroup(any(), any()) }
            assertEquals(5L, vm.uiState.value.savedGroupId)
        }

    @Test
    fun `renaming while editing goes through the rename API`() = runTest(dispatcher) {
        coEvery { repository.getCourseGroup(5L) } returns CourseGroup(
            id = 5L, name = "Spring Race", createdAt = 0L, updatedAt = 0L,
            courseIds = listOf(1L, 2L)
        )
        val vm = viewModel()
        vm.start(5L)
        advanceUntilIdle()

        vm.onNameChange("Autumn Race")
        vm.save()
        advanceUntilIdle()

        coVerify { repository.renameCourseGroup(5L, "Autumn Race") }
    }

    @Test
    fun `start is idempotent, because its host calls it from a LaunchedEffect`() =
        runTest(dispatcher) {
            coEvery { repository.getCourseGroup(5L) } returns CourseGroup(
                id = 5L, name = "Spring Race", createdAt = 0L, updatedAt = 0L,
                courseIds = listOf(1L, 2L)
            )
            val vm = viewModel()
            vm.start(5L)
            advanceUntilIdle()
            vm.toggle(2L)

            vm.start(5L)
            advanceUntilIdle()

            // The second call must not throw the organiser's edit away.
            assertEquals(setOf(1L), vm.uiState.value.selectedIds)
        }

    private fun course(id: Long, name: String) = ComposedCourse(
        id                     = id,
        baseRouteId            = 1L,
        name                   = name,
        createdAt              = 0L,
        updatedAt              = 0L,
        lapCount               = 1,
        finalLapDistanceMetres = 0.0,
        totalDistanceMetres    = 5_000.0,
        presetSnapshot         = MarkerPresetSnapshot(sourcePresetId = null, rules = emptyList()),
        markers                = listOf(
            DistanceMarker(
                id = id * 10, courseId = id, sequenceIndex = 1, lat = 0.0, lon = 0.0,
                cumulativeDistanceMetres = 1_000.0, type = MarkerType.DISTANCE,
                label = "1 km", isManuallyMoved = false
            )
        ),
        isDraft                = false
    )
}
