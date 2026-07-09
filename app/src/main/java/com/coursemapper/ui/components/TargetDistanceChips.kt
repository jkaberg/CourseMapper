package com.coursemapper.ui.components

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.coursemapper.ui.designsystem.Spacing
import com.coursemapper.domain.model.RaceDistance
import com.coursemapper.domain.model.targetMetresToCentimetres

/**
 * Target distance chips for record setup and the import wizard. Tapping the
 * selected chip clears it. Scrolls horizontally so it's always one line.
 *
 * @param targetDistanceCm current target, 0 for none
 * @param onTargetSelected new target in centimetres, 0 when cleared
 */
@Composable
fun TargetDistanceChips(
    targetDistanceCm: Long,
    onTargetSelected: (Long) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    val selected = RaceDistance.matchingCentimetres(targetDistanceCm)
    Row(
        modifier            = modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
    ) {
        RaceDistance.entries.forEach { distance ->
            val isSelected = distance == selected
            FilterChip(
                selected = isSelected,
                enabled  = enabled,
                onClick  = {
                    onTargetSelected(
                        if (isSelected) 0L else distance.metres.targetMetresToCentimetres()
                    )
                },
                label = { Text(distance.label) }
            )
        }
    }
}
