package com.coursemapper.ui.help

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.coursemapper.ui.designsystem.CmDisclosureIcon
import com.coursemapper.ui.designsystem.CmTopBar

/** Advanced help (GPS, offline maps, field readiness), reached from Settings only. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HelpScreen(onBack: () -> Unit) {
    Scaffold(
        topBar = {
            CmTopBar(
                title   = "Help & Troubleshooting",
                onBack  = onBack
            )
        }
    ) { padding ->
        LazyColumn(
            modifier        = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding  = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            item { HelpSection("GPS Troubleshooting", gpsItems) }
            item { Spacer(Modifier.height(8.dp)) }
            item { HelpSection("Offline Map Issues", offlineItems) }
            item { Spacer(Modifier.height(8.dp)) }
            item { HelpSection("Field Readiness Decisions", fieldReadinessItems) }
            item { Spacer(Modifier.height(24.dp)) }
            item {
                Text(
                    text  = "For additional support, consult the project documentation " +
                        "or open a GitHub issue.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.padding(bottom = 16.dp)
                )
            }
        }
    }
}

@Composable
private fun HelpSection(title: String, items: List<Pair<String, String>>) {
    var expanded by remember { mutableStateOf(false) }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column {
            ListItem(
                headlineContent = { Text(title, style = MaterialTheme.typography.titleMedium) },
                leadingContent  = { CmDisclosureIcon(expanded) },
                modifier = Modifier.clickable { expanded = !expanded }
            )
            if (expanded) {
                HorizontalDivider()
                items.forEach { (question, answer) ->
                    HelpItem(question = question, answer = answer)
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                }
            }
        }
    }
}

@Composable
private fun HelpItem(question: String, answer: String) {
    var expanded by remember { mutableStateOf(false) }
    ListItem(
        headlineContent  = { Text(question, style = MaterialTheme.typography.bodyMedium) },
        supportingContent = if (expanded) {
            { Text(answer, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant) }
        } else null,
        trailingContent  = {
            CmDisclosureIcon(expanded, tint = MaterialTheme.colorScheme.outline)
        },
        modifier = Modifier.clickable { expanded = !expanded }
    )
}

private val gpsItems = listOf(
    "Signal stays Yellow or Red at the gate" to
        "Ensure high-accuracy mode is enabled in Android Location settings. " +
        "Move to open sky away from buildings or tree canopy. Keep Wi-Fi on — " +
        "Android uses Wi-Fi scanning to assist GPS fix acquisition.",
    "Accuracy value jumps frequently" to
        "The device is cycling between cell-tower and satellite fixes. Disable " +
        "Battery Saver and confirm Location mode is set to 'High accuracy'. " +
        "Allow 60–90 seconds after powering the screen on before judging accuracy.",
    "GPS shows wrong position on map" to
        "Check that the device date/time is correct and that location permissions " +
        "are set to 'Allow all the time'. A stale GPS almanac can cause a bad first " +
        "fix; wait for a fresh fix (60+ seconds) in open sky.",
    "Recording stops unexpectedly" to
        "Android may kill the foreground service under extreme battery pressure. " +
        "Disable battery optimisation for CourseMapper in Settings → Battery → " +
        "Unrestricted, and keep the screen on or the notification visible.",
)

private val offlineItems = listOf(
    "Map tiles missing in the field" to
        "Confirm the offline region downloaded successfully on the Map Storage screen " +
        "before leaving connectivity. If the status shows anything other than 'Ready', " +
        "tap 'Re-download' to retry the full region.",
    "Offline download keeps failing" to
        "The download requires an active internet connection. Check that the device " +
        "is connected before starting. If a VPN is active, disable it — some VPN " +
        "configurations block the tile server.",
    "Map looks blurry at close zoom levels" to
        "Offline regions are stored for zoom levels 10–16. If you zoom in beyond " +
        "level 16, tiles may not be available offline. The Map Storage screen shows " +
        "whether the region is complete.",
    "Region shows Stale or Corrupt after download" to
        "A stale or corrupt status means the download did not complete cleanly. " +
        "Tap 'Re-download' on the Map Storage screen to fetch the region again. " +
        "If the error persists, check your internet connection.",
)

private val fieldReadinessItems = listOf(
    "Ready status turns AwaitingOffline unexpectedly" to
        "CourseMapper detected that some route tiles are not present locally. " +
        "This check runs on every navigation gate open. Connect to Wi-Fi and wait " +
        "for the background sync to complete.",
    "How does a stop get marked done without me touching the phone" to
        "By dwelling. Stand inside the arrival radius (Settings → Placement) for " +
        "the dwell time and the stop confirms itself; the ring on screen shows it " +
        "counting down. A poor fix pauses the count rather than resetting it, so " +
        "a moment of bad accuracy does not cost you the wait. Undo puts a stop " +
        "back, and it will not immediately re-confirm — it waits until you have " +
        "left the radius.",
    "The plan sends me back the way I came" to
        "If you turn round and keep driving away from the next stop, navigation " +
        "hands the target to the nearest unplaced stop ahead of you and " +
        "re-sequences the rest of the run from there — it does not simply give " +
        "you the one behind you and then resume the old order. It needs a real " +
        "turn, held for several seconds and tens of metres, so a hairpin or a " +
        "fast curve cannot trigger it. Turn it off with Settings → " +
        "'Re-target when you turn around'.",
    "Why can't I edit the placement stops" to
        "Stops are worked out, not authored. Markers say where each sign goes; a " +
        "stop is simply the group of markers close enough to plant from one " +
        "parking spot, using the clustering distance in Settings → Placement. " +
        "Anything that changes a course — laps, target distance, marker preset, " +
        "start and finish — recomputes them, so a hand-made grouping could not " +
        "survive an edit. Widen or narrow the clustering distance to change how " +
        "signs group.",
)
