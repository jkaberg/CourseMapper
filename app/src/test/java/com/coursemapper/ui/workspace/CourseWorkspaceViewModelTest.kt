package com.coursemapper.ui.workspace

import androidx.lifecycle.SavedStateHandle
import com.coursemapper.data.prefs.UserPreferencesRepository
import com.coursemapper.data.repository.RouteRepository
import com.coursemapper.domain.CourseCompositionEngine
import com.coursemapper.domain.CumulativeDistanceCalculator
import com.coursemapper.domain.PlacementPlanner
import com.coursemapper.domain.model.BaseRoute
import com.coursemapper.domain.model.ComposedCourse
import com.coursemapper.domain.model.CourseBuildSpec
import com.coursemapper.domain.model.MarkerPresetSnapshot
import com.coursemapper.domain.model.MarkerRule
import com.coursemapper.domain.model.MarkerType
import com.coursemapper.domain.model.RaceDistance
import com.coursemapper.domain.model.RoutePoint
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Runs keep a snapshot of their stops, so recomposing a course strands a run
 * in progress. These cover the warning.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CourseWorkspaceViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private val repository = mockk<RouteRepository>(relaxed = true)
    private val placementPlanner = mockk<PlacementPlanner>(relaxed = true)
    private val preferences = mockk<UserPreferencesRepository>(relaxed = true)
    private val calc = CumulativeDistanceCalculator()
    private val engine = CourseCompositionEngine(calc)
    private lateinit var formatter: DistanceFormatter

    private val points = (0 until 20).map { i ->
        RoutePoint(
            lat = 0.0, lon = i * 0.001, altMetres = null,
            accuracyMetres = null, timestampMs = i.toLong(), isSmoothed = true
        )
    }

    private val course = ComposedCourse(
        id = 1L, baseRouteId = 2L, name = "Loop", notes = "",
        createdAt = 0L, updatedAt = 0L,
        lapCount = 1, finalLapDistanceMetres = 0.0, totalDistanceMetres = 2000.0,
        presetSnapshot = MarkerPresetSnapshot(sourcePresetId = 5L, rules = emptyList()),
        isDraft = false,
        buildSpec = CourseBuildSpec.FixedLaps(1)
    )

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        every { preferences.distanceUnit } returns flowOf("km")
        formatter = DistanceFormatter(preferences)

        coEvery { repository.getCourse(1L) } returns course
        coEvery { repository.getRoute(2L) } returns BaseRoute(
            id = 2L, name = "Loop", notes = "", createdAt = 0L, updatedAt = 0L,
            isApproved = true, distanceMetres = 2000.0, source = "recorded",
            rawPoints = points, smoothedPoints = points
        )
        coEvery { repository.getStops(1L) } returns emptyList()
        coEvery { repository.getCourseGeometry(1L) } returns points
        every { repository.observePresets() } returns flowOf(emptyList())
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun viewModel() = CourseWorkspaceViewModel(
        repository, placementPlanner, preferences, calc, engine, formatter,
        SavedStateHandle(mapOf("courseId" to 1L))
    )

    @Test
    fun `changing laps with a run in progress asks first`() = runTest(dispatcher) {
        coEvery { repository.hasRunInProgress(1L) } returns true
        val vm = viewModel()
        advanceUntilIdle()

        vm.onLapCountChange(3)
        vm.saveChanges()
        advanceUntilIdle()

        assertTrue(vm.uiState.value.pendingRecomposeConfirm)
        coVerify(exactly = 0) {
            repository.updateCourse(any(), any(), any(), any(), any(), any(), any(), any(), any())
        }
    }

    @Test
    fun `confirming applies the recompose`() = runTest(dispatcher) {
        coEvery { repository.hasRunInProgress(1L) } returns true
        val vm = viewModel()
        advanceUntilIdle()

        vm.onLapCountChange(3)
        vm.saveChanges()
        advanceUntilIdle()
        vm.confirmRecomposeAndSave()
        advanceUntilIdle()

        assertFalse(vm.uiState.value.pendingRecomposeConfirm)
        coVerify(exactly = 1) {
            repository.updateCourse(
                courseId = 1L, newName = "Loop", newNotes = "",
                newLapCount = 3, newTargetDistanceCm = any(), newPresetId = any(),
                includeStart = any(), includeFinish = any(),
                newMeasuredLengthMetres = any()
            )
        }
    }

    @Test
    fun `changing laps with no run in progress saves straight away`() = runTest(dispatcher) {
        coEvery { repository.hasRunInProgress(1L) } returns false
        val vm = viewModel()
        advanceUntilIdle()

        vm.onLapCountChange(3)
        vm.saveChanges()
        advanceUntilIdle()

        assertFalse(vm.uiState.value.pendingRecomposeConfirm)
        coVerify(exactly = 1) {
            repository.updateCourse(
                courseId = 1L, newName = any(), newNotes = any(),
                newLapCount = 3, newTargetDistanceCm = any(), newPresetId = any(),
                includeStart = any(), includeFinish = any(),
                newMeasuredLengthMetres = any()
            )
        }
    }

    @Test
    fun `renaming during a run in progress is not a recompose`() = runTest(dispatcher) {
        // The run's snapshot is unaffected by a name change, so there is
        // nothing to warn about - the warning must not fire on every save.
        coEvery { repository.hasRunInProgress(1L) } returns true
        val vm = viewModel()
        advanceUntilIdle()

        vm.onNameChange("Loop renamed")
        vm.saveChanges()
        advanceUntilIdle()

        assertFalse(vm.uiState.value.pendingRecomposeConfirm)
        coVerify(exactly = 1) {
            repository.updateCourse(
                courseId = 1L, newName = "Loop renamed", newNotes = any(),
                newLapCount = any(), newTargetDistanceCm = any(), newPresetId = any(),
                includeStart = any(), includeFinish = any(),
                newMeasuredLengthMetres = any()
            )
        }
    }

    @Test
    fun `a half marathon target survives a load-edit-save round trip`() = runTest(dispatcher) {
        // 21.0975 km rendered as "21.10" would re-save the course 2.5 m long
        // and leave the Half chip looking unselected.
        coEvery { repository.getCourse(1L) } returns course.copy(
            buildSpec = CourseBuildSpec.TargetDistance(totalMetres = 21_097.5, lapCount = 1)
        )
        coEvery { repository.hasRunInProgress(1L) } returns false

        val vm = viewModel()
        advanceUntilIdle()

        assertEquals(2_109_750L, vm.uiState.value.editTargetDistanceCm)

        // Renaming must not disturb the stored target.
        vm.onNameChange("Renamed")
        vm.saveChanges()
        advanceUntilIdle()

        coVerify(exactly = 1) {
            repository.updateCourse(
                courseId = 1L, newName = "Renamed", newNotes = any(),
                newLapCount = any(), newTargetDistanceCm = 2_109_750L, newPresetId = any(),
                includeStart = any(), includeFinish = any(),
                newMeasuredLengthMetres = any()
            )
        }
    }

    @Test
    fun `a race distance chip sets the exact stored target`() = runTest(dispatcher) {
        val vm = viewModel()
        advanceUntilIdle()

        vm.onTargetDistancePreset(RaceDistance.HALF_MARATHON.let { (it.metres * 100).toLong() })

        assertEquals(2_109_750L, vm.uiState.value.editTargetDistanceCm)
        assertEquals(
            RaceDistance.HALF_MARATHON,
            RaceDistance.matchingCentimetres(vm.uiState.value.editTargetDistanceCm)
        )
    }

    @Test
    fun `endpoint toggles are read back from the course snapshot`() = runTest(dispatcher) {
        coEvery { repository.getCourse(1L) } returns course.copy(
            presetSnapshot = MarkerPresetSnapshot(
                sourcePresetId = 5L,
                rules = listOf(
                    MarkerRule(MarkerType.DISTANCE, intervalMetres = 1_000.0),
                    MarkerRule(MarkerType.FINISH)
                )
            )
        )
        val vm = viewModel()
        advanceUntilIdle()

        assertFalse(vm.uiState.value.editIncludeStart)
        assertTrue(vm.uiState.value.editIncludeFinish)
    }

    @Test
    fun `turning an endpoint off is saved as a composition change`() = runTest(dispatcher) {
        coEvery { repository.getCourse(1L) } returns course.copy(
            presetSnapshot = MarkerPresetSnapshot(
                sourcePresetId = 5L,
                rules = listOf(MarkerRule(MarkerType.START), MarkerRule(MarkerType.FINISH))
            )
        )
        coEvery { repository.hasRunInProgress(1L) } returns false

        val vm = viewModel()
        advanceUntilIdle()

        vm.onIncludeStartChange(false)
        vm.saveChanges()
        advanceUntilIdle()

        coVerify(exactly = 1) {
            repository.updateCourse(
                courseId = 1L, newName = any(), newNotes = any(),
                newLapCount = any(), newTargetDistanceCm = any(), newPresetId = any(),
                includeStart = false, includeFinish = true,
                newMeasuredLengthMetres = any()
            )
        }
    }

    @Test
    fun `changing an endpoint during a run in progress asks first`() = runTest(dispatcher) {
        // Removing a marker removes a placement stop, so a run already under way
        // would keep navigating to a sign that no longer exists.
        coEvery { repository.getCourse(1L) } returns course.copy(
            presetSnapshot = MarkerPresetSnapshot(
                sourcePresetId = 5L,
                rules = listOf(MarkerRule(MarkerType.START), MarkerRule(MarkerType.FINISH))
            )
        )
        coEvery { repository.hasRunInProgress(1L) } returns true

        val vm = viewModel()
        advanceUntilIdle()

        vm.onIncludeFinishChange(false)
        vm.saveChanges()
        advanceUntilIdle()

        assertTrue(vm.uiState.value.pendingRecomposeConfirm)
        coVerify(exactly = 0) {
            repository.updateCourse(any(), any(), any(), any(), any(), any(), any(), any(), any())
        }
    }

    @Test
    fun `publishing a draft applies a pending endpoint toggle`() = runTest(dispatcher) {
        // Publish persists pending edits before flipping the draft flag; the
        // endpoint choice has to travel with them.
        coEvery { repository.getCourse(1L) } returns course.copy(
            isDraft = true,
            presetSnapshot = MarkerPresetSnapshot(
                sourcePresetId = 5L,
                rules = listOf(MarkerRule(MarkerType.START), MarkerRule(MarkerType.FINISH))
            )
        )

        val vm = viewModel()
        advanceUntilIdle()

        vm.onIncludeStartChange(false)
        vm.publish()
        advanceUntilIdle()

        coVerify(exactly = 1) {
            repository.updateCourse(
                courseId = 1L, newName = any(), newNotes = any(),
                newLapCount = any(), newTargetDistanceCm = any(), newPresetId = any(),
                includeStart = false, includeFinish = true,
                newMeasuredLengthMetres = any()
            )
        }
        coVerify(exactly = 1) { repository.publishCourse(1L) }
    }

    @Test
    fun `a comma decimal typed on a European keyboard is accepted`() = runTest(dispatcher) {
        // KeyboardType.Decimal offers a comma on a nb-NO or de-DE device.
        val vm = viewModel()
        advanceUntilIdle()

        vm.onTargetDistanceChange("21,0975")

        assertEquals(2_109_750L, vm.uiState.value.editTargetDistanceCm)
    }

    @Test
    fun `a course opened from home does not show the post-recording lap prompt`() =
        runTest(dispatcher) {
            val vm = viewModel()
            advanceUntilIdle()
            assertFalse(vm.uiState.value.showRecordedLapPrompt)
        }

    @Test
    fun `arriving from a recording shows the lap prompt`() = runTest(dispatcher) {
        coEvery { repository.createDraftCourse(2L, any(), any(), any()) } returns 1L
        val vm = CourseWorkspaceViewModel(
            repository, placementPlanner, preferences, calc, engine, formatter,
            // Explicitly typed: in a mapOf whose other values are Long, the
            // integer literal 1 would be inferred as 1L and the ViewModel reads
            // lapCount as an Int (NavType.IntType in the nav graph).
            SavedStateHandle(
                mapOf<String, Any>("routeId" to 2L, "presetId" to 5L, "lapCount" to 1)
            )
        )
        advanceUntilIdle()

        assertTrue(vm.uiState.value.showRecordedLapPrompt)

        vm.dismissRecordedLapPrompt()
        assertFalse(vm.uiState.value.showRecordedLapPrompt)
    }

    @Test
    fun `the measured length is typed in metres and saved in metres`() = runTest(dispatcher) {
        coEvery { repository.hasRunInProgress(1L) } returns false
        val vm = viewModel()
        advanceUntilIdle()

        vm.onMeasuredLengthChange("42195")
        assertEquals(42_195.0, vm.uiState.value.editMeasuredLengthMetres, 0.0)

        vm.saveChanges()
        advanceUntilIdle()

        coVerify(exactly = 1) {
            repository.updateCourse(
                courseId = 1L, newName = any(), newNotes = any(),
                newLapCount = any(), newTargetDistanceCm = any(), newPresetId = any(),
                includeStart = any(), includeFinish = any(),
                newMeasuredLengthMetres = 42_195.0
            )
        }
    }

    /** Measuring re-places every marker, so it gets the same warning as a lap change. */
    @Test
    fun `measuring a course during a run in progress asks first`() = runTest(dispatcher) {
        coEvery { repository.hasRunInProgress(1L) } returns true
        val vm = viewModel()
        advanceUntilIdle()

        vm.onMeasuredLengthChange("1950")
        vm.saveChanges()
        advanceUntilIdle()

        assertTrue(vm.uiState.value.pendingRecomposeConfirm)
        coVerify(exactly = 0) {
            repository.updateCourse(any(), any(), any(), any(), any(), any(), any(), any(), any())
        }
    }

    @Test
    fun `clearing the field returns the course to its drawn line`() = runTest(dispatcher) {
        coEvery { repository.getCourse(1L) } returns course.copy(measuredLengthMetres = 1_950.0)
        coEvery { repository.hasRunInProgress(1L) } returns false
        val vm = viewModel()
        advanceUntilIdle()

        assertEquals("1950", vm.uiState.value.editMeasuredLengthText)

        vm.onMeasuredLengthChange("")
        vm.saveChanges()
        advanceUntilIdle()

        coVerify(exactly = 1) {
            repository.updateCourse(
                courseId = 1L, newName = any(), newNotes = any(),
                newLapCount = any(), newTargetDistanceCm = any(), newPresetId = any(),
                includeStart = any(), includeFinish = any(),
                newMeasuredLengthMetres = 0.0
            )
        }
    }

    /** Over ~2 % off isn't drawing slop, the 5 km and 10 km have wrong endpoints. */
    @Test
    fun `an implausibly large correction is flagged`() = runTest(dispatcher) {
        val vm = viewModel()
        advanceUntilIdle()

        // 2000 m drawn, measured 1990 -> 0.5 %: ordinary.
        vm.onMeasuredLengthChange("1990")
        assertTrue(vm.uiState.value.measuredLengthEffect.isNotBlank())
        assertTrue(vm.uiState.value.measuredLengthWarning.isBlank())

        // 2000 m drawn, measured 1900 -> 5.3 %: something else is wrong.
        vm.onMeasuredLengthChange("1900")
        assertTrue(vm.uiState.value.measuredLengthWarning.isNotBlank())
    }

    @Test
    fun `the effect readout names both lengths and promises the ends stay put`() =
        runTest(dispatcher) {
            val vm = viewModel()
            advanceUntilIdle()

            vm.onMeasuredLengthChange("1950")
            val effect = vm.uiState.value.measuredLengthEffect
            assertTrue("names the drawn line", effect.contains("2000"))
            assertTrue("names the measurement", effect.contains("1950"))
            assertTrue("says the ends do not move", effect.contains("Start and finish do not move"))
        }

    @Test
    fun `an unmeasured course leaves the field blank and says nothing`() = runTest(dispatcher) {
        val vm = viewModel()
        advanceUntilIdle()

        assertEquals("", vm.uiState.value.editMeasuredLengthText)
        assertEquals(0.0, vm.uiState.value.editMeasuredLengthMetres, 0.0)
        assertTrue(vm.uiState.value.measuredLengthEffect.isBlank())
        assertTrue(vm.uiState.value.measuredLengthWarning.isBlank())
    }
}
