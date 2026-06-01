package com.coursemapper.ui.record

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.coursemapper.data.repository.RouteRepository
import com.coursemapper.domain.model.MarkerPreset
import com.coursemapper.domain.model.MarkerType
import com.coursemapper.domain.model.hasEndpoint
import com.coursemapper.domain.model.targetCentimetresToMetres
import com.coursemapper.domain.model.targetMetresToCentimetres
import com.coursemapper.ui.format.DistanceFormatter
import com.coursemapper.ui.format.toDistanceInputOrNull
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import java.util.Locale
import javax.inject.Inject
import kotlin.math.abs
import kotlin.math.roundToLong

data class RecordSetupUiState(
    val name: String = "",
    val notes: String = "",
    val selectedPresetId: Long = -1L,
    /** Start/finish marker, seeded from the profile and overridable. */
    val includeStart: Boolean = false,
    val includeFinish: Boolean = false,
    val lapCount: Int = 1,
    /** Raw text of the target field, in [distanceUnit]. */
    val targetDistanceKm: String = "",
    val presets: List<MarkerPreset> = emptyList(),
    val compositionPreview: String = "",
    val canProceed: Boolean = false,
    val distanceUnit: DistanceFormatter.DistanceUnit = DistanceFormatter.DistanceUnit.KM,
    /** [targetDistanceKm] in centimetres, 0 for none. Derived here so chips and recorder agree. */
    val targetDistanceCm: Long = 0L
)

@HiltViewModel
class RecordSetupViewModel @Inject constructor(
    private val repository: RouteRepository,
    private val distanceFormatter: DistanceFormatter
) : ViewModel() {

    private val _form = MutableStateFlow(RecordSetupUiState())

    /** Latest unit preference, for converting a tapped race distance to text. */
    private val distanceUnit: StateFlow<DistanceFormatter.DistanceUnit> =
        distanceFormatter.unitEnumFlow
            .stateIn(viewModelScope, SharingStarted.Eagerly, DistanceFormatter.DistanceUnit.KM)

    /** Held as StateFlow so selecting a profile can read its rules right away. */
    private val presets: StateFlow<List<MarkerPreset>> =
        repository.observePresets()
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val uiState: StateFlow<RecordSetupUiState> = combine(
        _form,
        presets,
        distanceFormatter.unitEnumFlow
    ) { form, presets, unit ->
        val updated = form.copy(
            presets          = presets,
            distanceUnit     = unit,
            targetDistanceCm = form.targetDistanceKm.toTargetCentimetres(unit)
        )
        updated.copy(
            compositionPreview = buildPreview(updated, unit),
            canProceed         = form.name.isNotBlank()
        )
    }.stateIn(
        scope        = viewModelScope,
        started      = SharingStarted.WhileSubscribed(5_000),
        initialValue = RecordSetupUiState()
    )

    fun onNameChange(value: String) = _form.update { it.copy(name = value) }
    fun onNotesChange(value: String) = _form.update { it.copy(notes = value) }
    /** Select a profile and reseed the endpoint toggles from it. */
    fun onPresetSelected(id: Long) = _form.update { form ->
        val rules = presets.value.firstOrNull { it.id == id }?.rules.orEmpty()
        form.copy(
            selectedPresetId = id,
            includeStart     = rules.hasEndpoint(MarkerType.START),
            includeFinish    = rules.hasEndpoint(MarkerType.FINISH)
        )
    }

    fun onIncludeStartChange(value: Boolean) = _form.update { it.copy(includeStart = value) }
    fun onIncludeFinishChange(value: Boolean) = _form.update { it.copy(includeFinish = value) }
    fun onLapCountChange(count: Int) = _form.update { it.copy(lapCount = count.coerceAtLeast(1)) }
    fun onTargetDistanceChange(value: String) = _form.update { it.copy(targetDistanceKm = value) }

    /** Race distance chip in centimetres, 0 clears. Goes through the text field. */
    fun onTargetDistancePreset(targetDistanceCm: Long) {
        val text = if (targetDistanceCm <= 0L) "" else {
            val units = targetDistanceCm.targetCentimetresToMetres() / distanceUnit.value.metresPerUnit
            // Whole distances read better without decimals ("10", not "10.0000");
            // four places keep a half marathon exact enough in km and in miles
            // for the chip to still register as selected on the way back.
            if (abs(units - units.roundToLong()) < 1e-6) units.roundToLong().toString()
            // Locale.US, this goes back into the field and toDoubleOrNull wants '.'
            else String.format(Locale.US, "%.4f", units).trimEnd('0').trimEnd('.')
        }
        _form.update { it.copy(targetDistanceKm = text) }
    }

    private fun String.toTargetCentimetres(unit: DistanceFormatter.DistanceUnit): Long =
        ((toDistanceInputOrNull() ?: 0.0) * unit.metresPerUnit).targetMetresToCentimetres()

    private fun buildPreview(state: RecordSetupUiState, unit: DistanceFormatter.DistanceUnit): String {
        val laps = state.lapCount
        val targetValue = state.targetDistanceKm.toDoubleOrNull() ?: 0.0
        return when {
            targetValue > 0.0 -> {
                val targetMetres = targetValue * unit.metresPerUnit
                "Targeting ${distanceFormatter.formatCompact(targetMetres, unit)} — " +
                    "full laps (at least $laps) + final partial computed after recording"
            }
            laps == 1      -> "Single full lap of the base route"
            else           -> "$laps full laps of the base route"
        }
    }
}
