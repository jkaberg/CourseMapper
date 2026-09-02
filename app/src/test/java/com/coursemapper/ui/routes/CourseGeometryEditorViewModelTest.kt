package com.coursemapper.ui.routes

import androidx.lifecycle.SavedStateHandle
import com.coursemapper.data.repository.RouteRepository
import com.coursemapper.domain.CourseCompositionEngine
import com.coursemapper.domain.CourseGeometryEditor
import com.coursemapper.domain.CumulativeDistanceCalculator
import com.coursemapper.domain.model.BaseRoute
import com.coursemapper.domain.model.ComposedCourse
import com.coursemapper.domain.model.CourseBuildSpec
import com.coursemapper.domain.model.CourseGeometryEdit
import com.coursemapper.domain.model.MarkerPresetSnapshot
import com.coursemapper.domain.model.RoutePoint
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlin.math.abs
import kotlin.math.cos

@OptIn(ExperimentalCoroutinesApi::class)
class CourseGeometryEditorViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private val calc = CumulativeDistanceCalculator()
    private val editor = CourseGeometryEditor(calc)
    private val composition = CourseCompositionEngine(calc)
    private val repository = mockk<RouteRepository>(relaxed = true)

    private val lat0 = 63.43
    private val metresPerDegLat = CumulativeDistanceCalculator.EARTH_RADIUS_M * Math.PI / 180.0
    private val metresPerDegLon = metresPerDegLat * cos(Math.toRadians(lat0))

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun pt(northM: Double, eastM: Double) = RoutePoint(
        lat = lat0 + northM / metresPerDegLat,
        lon = 10.4 + eastM / metresPerDegLon,
        altMetres = null, accuracyMetres = null, timestampMs = 0, isSmoothed = true
    )

    /** A recording that runs 10.4 km when the race is 10 km - the real case. */
    private fun longRecording() = (0..1040).map { pt(0.0, it * 10.0) }

    /** A closed 1040 m square, ending exactly where it started. */
    private fun closedLoop(): List<RoutePoint> = buildList {
        for (i in 0..26) add(pt(0.0, i * 10.0))
        for (i in 1..26) add(pt(i * 10.0, 260.0))
        for (i in 1..26) add(pt(260.0, 260.0 - i * 10.0))
        for (i in 1..26) add(pt(260.0 - i * 10.0, 0.0))
    }

    private fun course(
        points: List<RoutePoint>,
        spec: CourseBuildSpec = CourseBuildSpec.FixedLaps(1),
        edit: CourseGeometryEdit = CourseGeometryEdit.NONE
    ): ComposedCourse {
        val route = BaseRoute(
            id = 7L, name = "Recording", notes = "", createdAt = 0L, updatedAt = 0L,
            isApproved = true,
            distanceMetres = calc.pathDistanceMetres(points, smoothedOnly = false),
            source = "recorded", rawPoints = points, smoothedPoints = points
        )
        coEvery { repository.getRoute(7L) } returns route
        coEvery { repository.hasRunInProgress(1L) } returns false

        val composed = ComposedCourse(
            id = 1L, baseRouteId = 7L, name = "10K", createdAt = 0L, updatedAt = 0L,
            lapCount = 1, finalLapDistanceMetres = 0.0,
            totalDistanceMetres = route.distanceMetres,
            presetSnapshot = MarkerPresetSnapshot(sourcePresetId = null, rules = emptyList()),
            buildSpec = spec, geometryEdit = edit
        )
        coEvery { repository.getCourse(1L) } returns composed
        return composed
    }

    private fun viewModel() = CourseGeometryEditorViewModel(
        repository = repository,
        editor = editor,
        compositionEngine = composition,
        calc = calc,
        savedStateHandle = SavedStateHandle(mapOf("courseId" to 1L))
    )

    @Test
    fun `loads the recording and measures it as it stands`() = runTest(dispatcher) {
        course(longRecording())
        val vm = viewModel()
        dispatcher.scheduler.advanceUntilIdle()

        val state = vm.uiState.value
        assertFalse(state.isLoading)
        assertEquals(10_400.0, state.totalMetres, 5.0)
        assertFalse("an untouched course has nothing to save", state.hasChanges)
    }

    @Test
    fun `trimming to ten kilometres lands on it exactly`() = runTest(dispatcher) {
        course(longRecording())
        val vm = viewModel()
        dispatcher.scheduler.advanceUntilIdle()

        vm.trimToDistance(10_000.0)
        dispatcher.scheduler.advanceUntilIdle()

        val state = vm.uiState.value
        assertEquals(10_000.0, state.totalMetres, 1.0)
        assertTrue("trimming must be something to save", state.hasChanges)
    }

    @Test
    fun `a lapped course is not offered a race-distance trim`() = runTest(dispatcher) {
        // closed loop x4, moving the finish can't shorten it since the seam closes
        // back to start - don't offer a trim that does nothing
        course(closedLoop(), spec = CourseBuildSpec.FixedLaps(4))
        val vm = viewModel()
        dispatcher.scheduler.advanceUntilIdle()

        val state = vm.uiState.value
        assertEquals(4, state.lapCount)
        assertFalse("a lapped course's length is its loop, not its finish", state.canTrimToDistance)
    }

    @Test
    fun `a single-lap course is offered the trim`() = runTest(dispatcher) {
        course(longRecording())
        val vm = viewModel()
        dispatcher.scheduler.advanceUntilIdle()

        assertTrue(vm.uiState.value.canTrimToDistance)
    }

    @Test
    fun `trimming converges even with the loop closed`() = runTest(dispatcher) {
        // Closing the loop adds a leg whose length depends on where the finish
        // is - so the correction feeds back into itself and a single division
        // would miss.
        course(longRecording())
        val vm = viewModel()
        dispatcher.scheduler.advanceUntilIdle()

        vm.toggleCloseLoop()
        dispatcher.scheduler.advanceUntilIdle()
        vm.trimToDistance(10_000.0)
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(10_000.0, vm.uiState.value.totalMetres, 1.0)
    }

    @Test
    fun `nudging the start leaves the finish where it was`() = runTest(dispatcher) {
        course(longRecording())
        val vm = viewModel()
        dispatcher.scheduler.advanceUntilIdle()
        val finishBefore = vm.uiState.value.finishPoint!!

        vm.nudgeStart(100.0)
        dispatcher.scheduler.advanceUntilIdle()

        val state = vm.uiState.value
        assertEquals(
            "moving the start must not drag the finish with it",
            0.0,
            calc.haversineMetres(
                finishBefore.first, finishBefore.second,
                state.finishPoint!!.first, state.finishPoint!!.second
            ),
            1.0
        )
        assertEquals(10_300.0, state.totalMetres, 5.0)
    }

    @Test
    fun `nudging the finish shortens the course`() = runTest(dispatcher) {
        course(longRecording())
        val vm = viewModel()
        dispatcher.scheduler.advanceUntilIdle()

        vm.nudgeFinish(-400.0)
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(10_000.0, vm.uiState.value.totalMetres, 5.0)
    }

    @Test
    fun `a tap only moves an endpoint when one is being placed`() = runTest(dispatcher) {
        course(longRecording())
        val vm = viewModel()
        dispatcher.scheduler.advanceUntilIdle()

        // No mode selected: a stray tap while panning must change nothing.
        vm.onMapTap(pt(0.0, 3000.0).lat, pt(0.0, 3000.0).lon)
        dispatcher.scheduler.advanceUntilIdle()
        assertFalse(vm.uiState.value.hasChanges)

        vm.setTapMode(GeometryTapMode.SET_START)
        vm.onMapTap(pt(0.0, 400.0).lat, pt(0.0, 400.0).lon)
        dispatcher.scheduler.advanceUntilIdle()

        val state = vm.uiState.value
        assertEquals(400.0, state.edit.startOffsetMetres, 5.0)
        assertEquals(10_000.0, state.totalMetres, 10.0)
    }

    @Test
    fun `reversing keeps the same stretch of ground`() = runTest(dispatcher) {
        course(longRecording())
        val vm = viewModel()
        dispatcher.scheduler.advanceUntilIdle()

        vm.nudgeStart(200.0)
        vm.nudgeFinish(-200.0)
        dispatcher.scheduler.advanceUntilIdle()
        val before = vm.uiState.value
        val startBefore = before.startPoint!!
        val finishBefore = before.finishPoint!!

        vm.toggleReversed()
        dispatcher.scheduler.advanceUntilIdle()
        val after = vm.uiState.value

        assertEquals("the kept length must survive a direction flip", before.totalMetres, after.totalMetres, 2.0)
        // The ends swap rather than jumping to the far end of the recording.
        assertEquals(
            0.0,
            calc.haversineMetres(
                finishBefore.first, finishBefore.second,
                after.startPoint!!.first, after.startPoint!!.second
            ),
            2.0
        )
        assertEquals(
            0.0,
            calc.haversineMetres(
                startBefore.first, startBefore.second,
                after.finishPoint!!.first, after.finishPoint!!.second
            ),
            2.0
        )
    }

    @Test
    fun `reset returns the course to the recording`() = runTest(dispatcher) {
        course(longRecording())
        val vm = viewModel()
        dispatcher.scheduler.advanceUntilIdle()

        vm.trimToDistance(10_000.0)
        dispatcher.scheduler.advanceUntilIdle()
        vm.reset()
        dispatcher.scheduler.advanceUntilIdle()

        assertTrue(vm.uiState.value.edit.isIdentity)
        assertEquals(10_400.0, vm.uiState.value.totalMetres, 5.0)
    }

    @Test
    fun `saving persists the edit`() = runTest(dispatcher) {
        course(longRecording())
        val vm = viewModel()
        dispatcher.scheduler.advanceUntilIdle()

        vm.trimToDistance(10_000.0)
        dispatcher.scheduler.advanceUntilIdle()
        vm.requestSave()
        dispatcher.scheduler.advanceUntilIdle()

        val saved = slot<CourseGeometryEdit>()
        coVerify { repository.updateCourseGeometryEdit(1L, capture(saved)) }
        assertTrue(abs(saved.captured.keepLengthMetres - 10_000.0) < 5.0)
        assertTrue(vm.uiState.value.saved)
    }

    @Test
    fun `a run in progress is confirmed before saving`() = runTest(dispatcher) {
        course(longRecording())
        coEvery { repository.hasRunInProgress(1L) } returns true
        val vm = viewModel()
        dispatcher.scheduler.advanceUntilIdle()

        vm.trimToDistance(10_000.0)
        dispatcher.scheduler.advanceUntilIdle()
        vm.requestSave()
        dispatcher.scheduler.advanceUntilIdle()

        assertTrue("a run under way must be flagged first", vm.uiState.value.confirmSave)
        coVerify(exactly = 0) { repository.updateCourseGeometryEdit(any(), any()) }

        vm.save()
        dispatcher.scheduler.advanceUntilIdle()
        coVerify(exactly = 1) { repository.updateCourseGeometryEdit(1L, any()) }
    }

    @Test
    fun `an unchanged course cannot be saved`() = runTest(dispatcher) {
        course(longRecording())
        val vm = viewModel()
        dispatcher.scheduler.advanceUntilIdle()

        vm.requestSave()
        dispatcher.scheduler.advanceUntilIdle()

        coVerify(exactly = 0) { repository.updateCourseGeometryEdit(any(), any()) }
    }

    @Test
    fun `a target-distance course already on its target is not offered the trim`() = runTest(dispatcher) {
        // 21.0975 km asked of a 10.4 km recording: composition repeats the lap
        // and adds a partial one to land exactly on the target, so there is
        // nothing left for a trim to fix.
        course(longRecording(), spec = CourseBuildSpec.TargetDistance(21_097.5))
        val vm = viewModel()
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(21_097.5, vm.uiState.value.totalMetres, 5.0)
        assertFalse(vm.uiState.value.canTrimToDistance)
    }

    @Test
    fun `a target shorter than one lap still wants trimming`() = runTest(dispatcher) {
        // The engine's minimum is one full lap, so a 10 km target on a 10.4 km
        // recording comes out at 10.4 km - exactly the case the editor exists
        // for, and one a spec-type check would have hidden the control from.
        course(longRecording(), spec = CourseBuildSpec.TargetDistance(10_000.0))
        val vm = viewModel()
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(10_400.0, vm.uiState.value.totalMetres, 5.0)
        assertTrue(vm.uiState.value.canTrimToDistance)

        vm.trimToDistance(10_000.0)
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals(10_000.0, vm.uiState.value.totalMetres, 1.0)
    }
}
