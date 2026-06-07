package com.coursemapper.ui.presets

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.coursemapper.data.repository.RouteRepository
import com.coursemapper.domain.model.MarkerPreset
import com.coursemapper.domain.model.MarkerRule
import com.coursemapper.domain.model.MarkerType
import com.coursemapper.domain.model.hasEndpoint
import com.coursemapper.domain.model.isEndpoint
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

data class PresetEditorState(
    val name: String = "",
    val rules: List<MarkerRule> = listOf(
        MarkerRule(MarkerType.START),
        MarkerRule(MarkerType.DISTANCE, intervalMetres = 1_000.0),
        MarkerRule(MarkerType.FINISH)
    ),
    val editingPresetId: Long? = null,
    val isSaving: Boolean = false,
    val savedAndClose: Boolean = false
)

data class PresetsUiState(
    val presets: List<MarkerPreset> = emptyList(),
    val editor: PresetEditorState? = null
)

@HiltViewModel
class MarkerPresetViewModel @Inject constructor(
    private val repository: RouteRepository
) : ViewModel() {

    private val _editor = MutableStateFlow<PresetEditorState?>(null)

    val uiState: StateFlow<PresetsUiState> = combine(
        repository.observePresets(),
        _editor
    ) { presets, editor ->
        PresetsUiState(presets = presets, editor = editor)
    }.stateIn(
        scope        = viewModelScope,
        started      = SharingStarted.WhileSubscribed(5_000),
        initialValue = PresetsUiState()
    )

    fun startNewPreset() {
        _editor.value = PresetEditorState()
    }

    fun startEditPreset(preset: MarkerPreset) {
        _editor.value = PresetEditorState(
            name            = preset.name,
            rules           = preset.rules.toList(),
            editingPresetId = preset.id
        )
    }

    fun closeEditor() {
        _editor.value = null
    }

    fun onNameChange(name: String) {
        _editor.update { it?.copy(name = name) }
    }

    /**
     * Add a rule of [type]. Only one START and one FINISH, a duplicate would put
     * two identical signs on one stop. The menu hides them too.
     */
    fun addRule(type: MarkerType) {
        val interval = if (type == MarkerType.DISTANCE) 1_000.0 else 5_000.0
        _editor.update { state ->
            if (state == null) return@update null
            if (type.isEndpoint() && state.rules.hasEndpoint(type)) return@update state
            state.copy(rules = state.rules + MarkerRule(type, interval))
        }
    }

    fun removeRule(index: Int) {
        _editor.update { state ->
            state?.copy(rules = state.rules.toMutableList().also { it.removeAt(index) })
        }
    }

    fun updateRuleInterval(index: Int, intervalMetres: Double) {
        _editor.update { state ->
            if (state == null) return@update null
            val updated = state.rules.toMutableList()
            updated[index] = updated[index].copy(intervalMetres = intervalMetres)
            state.copy(rules = updated)
        }
    }

    fun addCustomDistance(ruleIndex: Int, distanceMetres: Double) {
        _editor.update { state ->
            if (state == null) return@update null
            val updated = state.rules.toMutableList()
            val rule = updated[ruleIndex]
            val newList = (rule.customDistancesMetres + distanceMetres).sortedBy { it }
            updated[ruleIndex] = rule.copy(customDistancesMetres = newList)
            state.copy(rules = updated)
        }
    }

    fun removeCustomDistance(ruleIndex: Int, distanceMetres: Double) {
        _editor.update { state ->
            if (state == null) return@update null
            val updated = state.rules.toMutableList()
            val rule = updated[ruleIndex]
            updated[ruleIndex] = rule.copy(
                customDistancesMetres = rule.customDistancesMetres.filter { it != distanceMetres }
            )
            state.copy(rules = updated)
        }
    }

    fun savePreset() {
        val state = _editor.value ?: return
        if (state.name.isBlank()) return
        viewModelScope.launch {
            _editor.update { it?.copy(isSaving = true) }
            repository.savePreset(
                MarkerPreset(
                    id        = state.editingPresetId ?: 0L,
                    name      = state.name.trim(),
                    rules     = state.rules,
                    createdAt = System.currentTimeMillis(),
                    updatedAt = System.currentTimeMillis()
                )
            )
            _editor.value = null
        }
    }

    fun deletePreset(id: Long) {
        viewModelScope.launch { repository.deletePreset(id) }
    }
}
