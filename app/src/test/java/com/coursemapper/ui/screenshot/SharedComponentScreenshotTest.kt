package com.coursemapper.ui.screenshot

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Route
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.coursemapper.domain.model.CourseStatus
import com.coursemapper.domain.model.OfflinePackStatus
import com.coursemapper.domain.model.RaceDistance
import com.coursemapper.domain.model.targetMetresToCentimetres
import com.coursemapper.ui.components.CourseStatusBadge
import com.coursemapper.ui.components.EndpointMarkerToggles
import com.coursemapper.ui.components.GpsStatusIndicator
import com.coursemapper.ui.components.OfflineStatusRow
import com.coursemapper.ui.components.TargetDistanceChips
import com.coursemapper.ui.designsystem.CmEmptyState
import com.coursemapper.ui.record.GateState
import org.junit.Test

/** Baselines for `ui/components/` and [CmEmptyState], in every state they have. */
class SharedComponentScreenshotTest : ScreenshotTest() {

    @Test
    fun `target distance chips, nothing selected`() = screenshot("chips_target_distance_none") {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            TargetDistanceChips(targetDistanceCm = 0L, onTargetSelected = {})
        }
    }

    @Test
    fun `target distance chips, marathon selected`() = screenshot("chips_target_distance_selected") {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            TargetDistanceChips(
                targetDistanceCm = RaceDistance.MARATHON.metres.targetMetresToCentimetres(),
                onTargetSelected = {}
            )
        }
    }

    @Test
    fun `target distance chips, disabled`() = screenshot("chips_target_distance_disabled") {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            TargetDistanceChips(targetDistanceCm = 0L, onTargetSelected = {}, enabled = false)
        }
    }

    /** Every [OfflinePackStatus] plus "no pack" and "checking". */
    @Test
    fun `offline status, every state`() = screenshot("offline_status_rows") {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            OfflineStatusRow(status = null, onAction = {}, checking = true)
            OfflineStatusRow(status = null, onAction = {}, estimatedSize = "52 MB")
            OfflineStatusRow(status = OfflinePackStatus.READY, onAction = {})
            OfflineStatusRow(status = OfflinePackStatus.STALE, onAction = {})
            OfflineStatusRow(status = OfflinePackStatus.QUEUED, onAction = {})
            OfflineStatusRow(
                status = OfflinePackStatus.DOWNLOADING,
                onAction = {},
                progress = 0.44f,
                showProgressBar = true
            )
            OfflineStatusRow(status = OfflinePackStatus.PAUSED, onAction = {})
            OfflineStatusRow(
                status = OfflinePackStatus.FAILED,
                onAction = {},
                failure = "Connection lost after 12 MB"
            )
        }
    }

    /** Every [GateState], through the indicator both gates now share. */
    @Test
    fun `gps status, every state`() = screenshot("gps_status_indicator") {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            listOf(
                GateState.Green(accuracyMetres = 4f, ageSeconds = 1L),
                GateState.Yellow(accuracyMetres = 22f, ageSeconds = 3L),
                GateState.Initializing,
                GateState.Red,
                GateState.NoPermission,
                GateState.SettingsRequired
            ).forEach { GpsStatusIndicator(it, Modifier.fillMaxWidth()) }
        }
    }

    /** Every [CourseStatus], through the badge that replaced both forks. */
    @Test
    fun `course status badge, every state`() = screenshot("course_status_badge") {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            CourseStatus.entries.forEach { CourseStatusBadge(it) }
            CourseStatusBadge(CourseStatus.DRAFT, label = "5 km · draft")
        }
    }

    @Test
    fun `empty state, with and without a call to action`() = screenshot("cm_empty_state") {
        Column(Modifier.fillMaxWidth()) {
            CmEmptyState(
                modifier = Modifier.height(320.dp),
                icon = Icons.Default.Route,
                title = "No courses yet",
                body = "Record or import a route to create a course."
            )
            CmEmptyState(
                modifier = Modifier.height(360.dp),
                icon = Icons.Default.Route,
                title = "No courses yet",
                body = "Tap + to record or import your first course."
            ) {
                OutlinedButton(onClick = {}) { Text("Create your first course") }
            }
        }
    }

    @Test
    fun `endpoint marker toggles, every combination`() = screenshot("endpoint_marker_toggles") {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            listOf(true to true, true to false, false to false).forEach { (start, finish) ->
                EndpointMarkerToggles(
                    includeStart = start,
                    includeFinish = finish,
                    onStartChange = {},
                    onFinishChange = {}
                )
            }
            EndpointMarkerToggles(
                includeStart = true,
                includeFinish = true,
                onStartChange = {},
                onFinishChange = {},
                enabled = false
            )
        }
    }
}
