package com.coursemapper.ui.routes

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.coursemapper.data.repository.RouteRepository
import com.coursemapper.domain.CourseCompositionEngine
import com.coursemapper.domain.CourseGeometryEditor
import com.coursemapper.domain.CumulativeDistanceCalculator
import com.coursemapper.domain.PolylineIndex
import com.coursemapper.domain.model.CourseBuildSpec
import com.coursemapper.domain.model.CourseGeometryEdit
import com.coursemapper.domain.model.RoutePoint
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.math.abs
import javax.inject.Inject

/** What a tap on the map does next. */
enum class GeometryTapMode {
    /** Taps do nothing - the default, so a stray tap while panning cannot move a line. */
    NONE,
    SET_START,
    SET_FINISH
}

data class CourseGeometryUiState(
    val isLoading: Boolean = true,
    val courseName: String = "",
    /** The recording, as recorded - drawn faint underneath. */
    val basePath: List<Pair<Double, Double>> = emptyList(),
    /** What the course would be after the pending edit. */
    val editedPath: List<Pair<Double, Double>> = emptyList(),
    val startPoint: Pair<Double, Double>? = null,
    val finishPoint: Pair<Double, Double>? = null,

    val edit: CourseGeometryEdit = CourseGeometryEdit.NONE,
    val savedEdit: CourseGeometryEdit = CourseGeometryEdit.NONE,
    val tapMode: GeometryTapMode = GeometryTapMode.NONE,

    /** Length of one lap after the edit. */
    val lapMetres: Double = 0.0,
    /** Length of the whole composed course after the edit. */
    val totalMetres: Double = 0.0,
    /** The course's declared target, when it has one, for the delta readout. */
    val targetMetres: Double? = null,
    /** Full laps the composed course is made of. */
    val lapCount: Int = 1,
    /** Distance from the recording's finish back to its start. */
    val endpointGapMetres: Double = 0.0,
    val isLoop: Boolean = false,

    val hasRunInProgress: Boolean = false,
    val confirmSave: Boolean = false,
    val isSaving: Boolean = false,
    val saved: Boolean = false
) {
    val hasChanges: Boolean get() = edit != savedEdit

    /** How far the composed course misses its target by; null when there is none. */
    val targetDeltaMetres: Double? get() = targetMetres?.let { totalMetres - it }

    /**
     * Whether moving the finish can change the course length. Not for lapped
     * courses (the seam closes back to start, change the lap count instead) and
     * not for target courses that already reach their target.
     */
    val canTrimToDistance: Boolean
        get() = lapCount <= 1 &&
            (targetMetres == null || abs(totalMetres - targetMetres) > 1.0)
}

/**
 * Adjust start, finish and direction. Typical case is a recording started in
 * the car park and stopped past the finish. Nothing is saved until Save, the
 * preview uses [CourseGeometryEditor] so it matches what gets built.
 */
@HiltViewModel
class CourseGeometryEditorViewModel @Inject constructor(
    private val repository: RouteRepository,
    private val editor: CourseGeometryEditor,
    private val compositionEngine: CourseCompositionEngine,
    private val calc: CumulativeDistanceCalculator,
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    companion object {
        /** How far a tap may land from the recording and still count. */
        private const val TAP_RADIUS_M = 250.0

        /** Step for the nudge buttons, in metres. */
        const val NUDGE_METRES = 10.0

        /** Close enough for [trimToDistance] to stop. */
        private const val TRIM_TOLERANCE_METRES = 0.5

        /** Iteration cap for [trimToDistance]; it converges in two or three. */
        private const val MAX_TRIM_PASSES = 4
    }

    private val courseId: Long = savedStateHandle["courseId"] ?: -1L

    private val _state = MutableStateFlow(CourseGeometryUiState())
    val uiState: StateFlow<CourseGeometryUiState> = _state.asStateFlow()

    /** The recording this course is cut from, unedited. */
    private var basePoints: List<RoutePoint> = emptyList()
    private var buildSpec: CourseBuildSpec = CourseBuildSpec.FixedLaps(1)

    /** The recording in the current direction, rebuilt on flip since offsets follow direction. */
    private var orientedIndex: PolylineIndex? = null
    private var orientedReversed = false

    init {
        viewModelScope.launch { load() }
    }

    private suspend fun load() {
        val course = repository.getCourse(courseId)
        if (course == null) {
            _state.update { it.copy(isLoading = false) }
            return
        }
        val route = repository.getRoute(course.baseRouteId)
        basePoints = route?.smoothedPoints?.ifEmpty { route.rawPoints }.orEmpty()
        buildSpec = course.buildSpec

        _state.update {
            it.copy(
                isLoading = false,
                courseName = course.name,
                basePath = basePoints.map { p -> p.lat to p.lon },
                edit = course.geometryEdit,
                savedEdit = course.geometryEdit,
                targetMetres = (course.buildSpec as? CourseBuildSpec.TargetDistance)?.totalMetres,
                hasRunInProgress = repository.hasRunInProgress(courseId)
            )
        }
        refreshPreview()
    }

    fun setTapMode(mode: GeometryTapMode) = _state.update {
        it.copy(tapMode = if (it.tapMode == mode) GeometryTapMode.NONE else mode)
    }

    /** Put start or finish at the tap, projected onto the recording. */
    fun onMapTap(lat: Double, lon: Double) {
        val mode = _state.value.tapMode
        if (mode == GeometryTapMode.NONE) return
        val index = orientedIndex() ?: return
        val hit = index.nearest(lat, lon, TAP_RADIUS_M) ?: return

        when (mode) {
            GeometryTapMode.SET_START -> setStart(hit.cumulativeMetres, index)
            GeometryTapMode.SET_FINISH -> setFinish(hit.cumulativeMetres, index)
            GeometryTapMode.NONE -> Unit
        }
    }

    /** Move the start, leaving the finish where it is. */
    fun nudgeStart(deltaMetres: Double) {
        val index = orientedIndex() ?: return
        setStart((_state.value.edit.startOffsetMetres + deltaMetres).coerceIn(0.0, index.lengthMetres), index)
    }

    /** Move the finish, leaving the start where it is. */
    fun nudgeFinish(deltaMetres: Double) {
        val index = orientedIndex() ?: return
        val edit = _state.value.edit
        val currentKeep = resolvedKeep(edit, index)
        applyEdit(edit.copy(keepLengthMetres = (currentKeep + deltaMetres).coerceAtLeast(1.0)))
    }

    fun toggleReversed() {
        val edit = _state.value.edit
        // offsets follow the direction, so flip them too - the kept stretch stays
        // the same ground
        val index = orientedIndex() ?: return
        val total = index.lengthMetres
        val keep = resolvedKeep(edit, index)
        val newStart = (total - (edit.startOffsetMetres + keep)).coerceIn(0.0, total)

        applyEdit(
            edit.copy(
                reversed = !edit.reversed,
                startOffsetMetres = newStart,
                keepLengthMetres = keep
            )
        )
    }

    fun toggleCloseLoop() {
        val edit = _state.value.edit
        applyEdit(edit.copy(closeLoop = !edit.closeLoop))
    }

    /**
     * Move the finish so the composed course measures [targetMetres]. Scaled and
     * iterated, the closing leg changes with the finish so it isn't one to one.
     */
    fun trimToDistance(targetMetres: Double) {
        val index = orientedIndex() ?: return
        if (targetMetres <= 0.0 || basePoints.size < 2) return

        var edit = _state.value.edit
        var passes = 0
        while (passes < MAX_TRIM_PASSES) {
            val summary = compositionEngine.summarize(editor.apply(basePoints, edit), buildSpec)
            val total = summary.totalDistanceMetres
            if (total <= 0.0) return
            if (abs(total - targetMetres) < TRIM_TOLERANCE_METRES) break

            val scaled = resolvedKeep(edit, index) * (targetMetres / total)
            edit = edit.copy(keepLengthMetres = scaled.coerceIn(1.0, index.lengthMetres))
            passes++
        }
        applyEdit(edit)
    }

    fun reset() = applyEdit(CourseGeometryEdit.NONE)

    fun requestSave() {
        val state = _state.value
        if (!state.hasChanges || state.isSaving) return
        // Run stops are snapshots: rebuilding them strands a run already under
        // way, so the organiser is asked first - the same guard the workspace
        // uses before recomposing a course.
        if (state.hasRunInProgress) {
            _state.update { it.copy(confirmSave = true) }
        } else {
            save()
        }
    }

    fun cancelSave() = _state.update { it.copy(confirmSave = false) }

    fun save() {
        val edit = _state.value.edit
        _state.update { it.copy(confirmSave = false, isSaving = true) }
        viewModelScope.launch {
            repository.updateCourseGeometryEdit(courseId, edit)
            _state.update { it.copy(isSaving = false, savedEdit = edit, saved = true) }
        }
    }

    private fun setStart(cumulativeMetres: Double, index: PolylineIndex) {
        val edit = _state.value.edit
        val finish = edit.startOffsetMetres + resolvedKeep(edit, index)
        val newStart = cumulativeMetres.coerceIn(0.0, index.lengthMetres)
        val newKeep = (finish - newStart).takeIf { it > 1.0 }
            ?: (index.lengthMetres - newStart).coerceAtLeast(1.0)
        applyEdit(edit.copy(startOffsetMetres = newStart, keepLengthMetres = newKeep))
    }

    private fun setFinish(cumulativeMetres: Double, index: PolylineIndex) {
        val edit = _state.value.edit
        val total = index.lengthMetres
        val start = edit.startOffsetMetres
        val keep = when {
            cumulativeMetres > start -> cumulativeMetres - start
            // Behind the start on a loop: the course wraps the seam.
            _state.value.isLoop -> total - start + cumulativeMetres
            else -> return
        }
        applyEdit(edit.copy(keepLengthMetres = keep.coerceAtLeast(1.0)))
    }

    /** The edit's kept length, resolved against "0 means everything". */
    private fun resolvedKeep(edit: CourseGeometryEdit, index: PolylineIndex): Double {
        if (edit.keepLengthMetres > 0.0) return edit.keepLengthMetres
        val total = index.lengthMetres
        val gap = editor.endpointGap(basePoints)
        return if (gap <= CourseGeometryEditor.LOOP_TOLERANCE_METRES) total
        else total - edit.startOffsetMetres
    }

    private fun applyEdit(edit: CourseGeometryEdit) {
        _state.update { it.copy(edit = edit) }
        refreshPreview()
    }

    private fun orientedIndex(): PolylineIndex? {
        val reversed = _state.value.edit.reversed
        val cached = orientedIndex
        if (cached != null && orientedReversed == reversed) return cached
        val points = if (reversed) basePoints.reversed() else basePoints
        val built = PolylineIndex.build(points, calc)
        orientedIndex = built
        orientedReversed = reversed
        return built
    }

    private fun refreshPreview() {
        if (basePoints.size < 2) return
        val edit = _state.value.edit
        val edited = editor.apply(basePoints, edit)
        val description = editor.describe(basePoints, edit)
        val summary = compositionEngine.summarize(edited, buildSpec)

        _state.update {
            it.copy(
                editedPath = edited.map { p -> p.lat to p.lon },
                startPoint = edited.firstOrNull()?.let { p -> p.lat to p.lon },
                finishPoint = edited.lastOrNull()?.let { p -> p.lat to p.lon },
                lapMetres = description.resultLengthMetres,
                totalMetres = summary.totalDistanceMetres,
                endpointGapMetres = description.endpointGapMetres,
                lapCount = summary.fullLapCount,
                isLoop = description.isLoop
            )
        }
    }
}
