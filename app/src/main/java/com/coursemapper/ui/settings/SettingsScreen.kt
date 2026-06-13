package com.coursemapper.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.coursemapper.ui.designsystem.ScreenGutter
import com.coursemapper.ui.designsystem.CmSectionHeader
import com.coursemapper.ui.designsystem.CmTopBar
import com.coursemapper.BuildConfig
import com.coursemapper.ui.about.AboutDialog
import com.coursemapper.domain.model.OfflineDownloadPolicy
import com.coursemapper.ui.offline.formatBytes
import kotlin.math.roundToInt

/** Settings: units, battery and GPS guidance, Map Storage and Help. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onMapStorageClicked: () -> Unit = {},
    onHelpClicked: () -> Unit = {},
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    // Refresh battery/GPS status whenever the screen becomes visible again
    // (user may have toggled settings in the system UI and returned).
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) viewModel.refreshSystemState()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // No countdown here: this card was asked for, so it stays until closed.
    var showAbout by remember { mutableStateOf(false) }
    if (showAbout) {
        AboutDialog(onDismiss = { showAbout = false })
    }

    Scaffold(
        topBar = {
            CmTopBar(
                title   = "Settings",
                onBack  = onBack
            )
        }
    ) { padding ->
        LazyColumn(
            modifier        = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding  = PaddingValues(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(0.dp)
        ) {
            item {
                CmSectionHeader("Units", Modifier.padding(horizontal = ScreenGutter.horizontal))
            }
            item {
                ListItem(
                    headlineContent   = { Text("Distance unit") },
                    supportingContent = { Text("Displayed in review and navigation screens") },
                    trailingContent   = {
                        DistanceUnitToggle(
                            selectedUnit = state.distanceUnit,
                            onUnitChange = viewModel::setDistanceUnit
                        )
                    }
                )
            }
            item { HorizontalDivider() }

            item { CmSectionHeader("Placement", Modifier.padding(horizontal = ScreenGutter.horizontal)) }
            item {
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    val thresholdM = state.clusteringThresholdMetres.roundToInt()
                    Text(
                        text  = "Marker clustering distance: ${thresholdM} m",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        text  = "Markers within this distance are grouped into a single " +
                                "placement stop, so you only drive the course once.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 2.dp, bottom = 8.dp)
                    )
                    Slider(
                        value         = state.clusteringThresholdMetres.toFloat(),
                        onValueChange = { viewModel.setClusteringThreshold(it.toDouble()) },
                        valueRange    = 1f..100f,
                        steps         = 98,   // 1 m increments
                        modifier      = Modifier.fillMaxWidth()
                    )
                    Row(
                        modifier              = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("1 m",   style = MaterialTheme.typography.labelSmall)
                        Text("100 m", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
            item { HorizontalDivider() }

            item { CmSectionHeader("Placement Run", Modifier.padding(horizontal = ScreenGutter.horizontal)) }
            item {
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    val radiusM = state.dwellRadiusMetres.roundToInt()
                    Text(
                        text  = "Arrival radius: $radiusM m",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        text  = "A stop counts as reached once you are inside this radius.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 2.dp, bottom = 8.dp)
                    )
                    Slider(
                        value         = state.dwellRadiusMetres.toFloat(),
                        onValueChange = { viewModel.setDwellRadius(it.toDouble()) },
                        valueRange    = 10f..50f,
                        steps         = 39,   // 1 m increments
                        modifier      = Modifier.fillMaxWidth()
                    )
                }
            }
            item {
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    val secs = state.dwellSeconds.roundToInt()
                    Text(
                        text  = "Auto-confirm after: $secs s",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        text  = "Time inside the radius (with good GPS) before a stop is " +
                                "marked placed automatically. Undo is always available.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 2.dp, bottom = 8.dp)
                    )
                    Slider(
                        value         = state.dwellSeconds.toFloat(),
                        onValueChange = { viewModel.setDwellSeconds(it.toDouble()) },
                        valueRange    = 2f..15f,
                        steps         = 12,   // 1 s increments
                        modifier      = Modifier.fillMaxWidth()
                    )
                }
            }
            item {
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    val snapM = state.positionSnapRadiusMetres.roundToInt()
                    Text(
                        text  = if (snapM == 0) "Snap my position to the course: off"
                                else "Snap my position to the course: within $snapM m",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        text  = "Draws your marker on the course line while you are driving " +
                                "along it. Arrival and distances always use the raw GPS fix, " +
                                "and nothing snaps while you are stopped at a marker.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 2.dp, bottom = 8.dp)
                    )
                    Slider(
                        // 0 is off, then 5-30 m. Below typical GPS error it would
                        // almost never snap and look broken.
                        value         = state.positionSnapRadiusMetres.toFloat(),
                        onValueChange = {
                            val v = it.toDouble()
                            viewModel.setPositionSnapRadius(if (v < 5.0) 0.0 else v)
                        },
                        valueRange    = 0f..30f,
                        steps         = 29,   // 1 m increments
                        modifier      = Modifier.fillMaxWidth()
                    )
                }
            }
            item {
                ListItem(
                    headlineContent   = { Text("Course-up map") },
                    supportingContent = { Text("Rotate the run map to your direction of travel") },
                    trailingContent   = {
                        Switch(
                            checked         = state.courseUpCamera,
                            onCheckedChange = viewModel::setCourseUpCamera
                        )
                    }
                )
            }
            item {
                ListItem(
                    headlineContent   = { Text("Re-target when you turn around") },
                    supportingContent = {
                        Text(
                            "If you drive away from the next marker and keep going, " +
                            "navigation hands over to the nearest unplaced marker ahead " +
                            "of you and continues the plan from there."
                        )
                    },
                    trailingContent   = {
                        Switch(
                            checked         = state.autoRetarget,
                            onCheckedChange = viewModel::setAutoRetarget
                        )
                    }
                )
            }
            item {
                ListItem(
                    headlineContent   = { Text("Skip the GPS check when the signal is good") },
                    supportingContent = {
                        Text(
                            "With a green fix and offline maps already downloaded, the " +
                            "readiness screen starts the run itself after a few seconds. " +
                            "Tap Wait to stop it. A weak or stale fix always waits for you."
                        )
                    },
                    trailingContent   = {
                        Switch(
                            checked         = state.skipGreenGate,
                            onCheckedChange = viewModel::setSkipGreenGate
                        )
                    }
                )
            }
            item {
                ListItem(
                    headlineContent   = { Text("Large controls") },
                    supportingContent = { Text("Oversized buttons and text for gloves and mounted phones") },
                    trailingContent   = {
                        Switch(
                            checked         = state.largeControls,
                            onCheckedChange = viewModel::setLargeControls
                        )
                    }
                )
            }
            item { HorizontalDivider() }

            item { CmSectionHeader("Battery", Modifier.padding(horizontal = ScreenGutter.horizontal)) }
            item {
                val batteryContext = LocalContext.current
                ListItem(
                    leadingContent = {
                        Icon(
                            if (state.isBatteryUnrestricted) Icons.Default.BatteryFull else Icons.Default.BatteryAlert,
                            contentDescription = null,
                            tint = if (state.isBatteryUnrestricted)
                                MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                        )
                    },
                    headlineContent = { Text("Optimise for field use") },
                    supportingContent = {
                        Column {
                            Text(
                                "Disable Battery Saver before heading out. Set CourseMapper " +
                                "to 'Unrestricted' in Android battery settings so the " +
                                "recording service is never killed by the OS. A screen-off " +
                                "recording session can run for 6\u20138 hours on a typical device.",
                                style = MaterialTheme.typography.bodySmall
                            )
                            if (!state.isBatteryUnrestricted) {
                                TextButton(
                                    onClick = { viewModel.requestBatteryUnrestricted(batteryContext) },
                                    contentPadding = PaddingValues(horizontal = 0.dp, vertical = 4.dp)
                                ) { Text("Fix this \u2192") }
                            }
                        }
                    },
                    trailingContent = if (state.isBatteryUnrestricted) ({
                        Icon(Icons.Default.CheckCircle, contentDescription = "Unrestricted",
                            tint = MaterialTheme.colorScheme.primary)
                    }) else null
                )
            }
            item { HorizontalDivider() }

            item { CmSectionHeader("GPS Accuracy", Modifier.padding(horizontal = ScreenGutter.horizontal)) }
            item {
                val gpsContext = LocalContext.current
                ListItem(
                    leadingContent = {
                        Icon(
                            if (state.isLocationEnabled) Icons.Default.GpsFixed else Icons.Default.GpsOff,
                            contentDescription = null,
                            tint = if (state.isLocationEnabled)
                                MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                        )
                    },
                    headlineContent = { Text("Achieve best accuracy") },
                    supportingContent = {
                        Column {
                            Text(
                                "Enable 'High accuracy' location mode in Android settings. " +
                                "Leave Wi-Fi on even without a network \u2014 Android uses nearby " +
                                "access points to accelerate GPS fix. Allow 60\u201390 seconds " +
                                "in open sky before starting a recording or navigation session.",
                                style = MaterialTheme.typography.bodySmall
                            )
                            if (!state.isLocationEnabled) {
                                TextButton(
                                    onClick = { viewModel.openLocationSettings(gpsContext) },
                                    contentPadding = PaddingValues(horizontal = 0.dp, vertical = 4.dp)
                                ) { Text("Open Location Settings \u2192") }
                            }
                        }
                    },
                    trailingContent = if (state.isLocationEnabled) ({
                        Icon(Icons.Default.CheckCircle, contentDescription = "Enabled",
                            tint = MaterialTheme.colorScheme.primary)
                    }) else null
                )
            }
            item { HorizontalDivider() }

            item { CmSectionHeader("Offline maps", Modifier.padding(horizontal = ScreenGutter.horizontal)) }
            item {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Text(
                        "When you create a course",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OfflineDownloadPolicy.entries.forEach { policy ->
                            FilterChip(
                                selected = state.offlineDownloadPolicy == policy,
                                onClick = { viewModel.setOfflineDownloadPolicy(policy) },
                                label = { Text(policyLabel(policy)) }
                            )
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        policyExplanation(state.offlineDownloadPolicy),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            item {
                ListItem(
                    headlineContent = { Text("Download on Wi-Fi only") },
                    supportingContent = {
                        Text(
                            "Off lets a map finish over mobile data — useful at a venue " +
                                "with no Wi-Fi, which is most of them"
                        )
                    },
                    trailingContent = {
                        Switch(
                            checked = state.offlineWifiOnly,
                            onCheckedChange = viewModel::setOfflineWifiOnly
                        )
                    }
                )
            }
            item {
                ListItem(
                    headlineContent = { Text("Include both map themes") },
                    supportingContent = {
                        Text(
                            "The map follows your system theme. With this off, the theme " +
                                "you did not download shows a blank map with no signal."
                        )
                    },
                    trailingContent = {
                        Switch(
                            checked = state.offlineBothThemes,
                            onCheckedChange = viewModel::setOfflineBothThemes
                        )
                    }
                )
            }
            item {
                ListItem(
                    leadingContent  = {
                        Icon(Icons.Default.Map, contentDescription = null)
                    },
                    headlineContent = { Text("Map Storage") },
                    supportingContent = {
                        Text(
                            if (state.offlineTotalBytes > 0) {
                                "${formatBytes(state.offlineTotalBytes)} downloaded"
                            } else {
                                "No offline maps downloaded yet"
                            }
                        )
                    },
                    trailingContent = {
                        Icon(Icons.Default.ChevronRight, contentDescription = null)
                    },
                    modifier = Modifier.clickableSettingsRow(onClick = onMapStorageClicked)
                )
            }
            item { HorizontalDivider() }

            item { CmSectionHeader("More", Modifier.padding(horizontal = ScreenGutter.horizontal)) }
            item {
                ListItem(
                    leadingContent  = {
                        Icon(Icons.AutoMirrored.Filled.HelpOutline, contentDescription = null)
                    },
                    headlineContent = { Text("Advanced Help") },
                    supportingContent = { Text("GPS tips, offline troubleshooting") },
                    trailingContent = {
                        Icon(Icons.Default.ChevronRight, contentDescription = null)
                    },
                    modifier = Modifier.clickableSettingsRow(onClick = onHelpClicked)
                )
            }
            item { HorizontalDivider() }

            item { CmSectionHeader("About this app", Modifier.padding(horizontal = ScreenGutter.horizontal)) }
            item {
                ListItem(
                    leadingContent  = {
                        Icon(Icons.Default.Info, contentDescription = null)
                    },
                    headlineContent = { Text("About CourseMapper") },
                    supportingContent = {
                        Text("Version ${BuildConfig.VERSION_NAME} · source code · support the app")
                    },
                    trailingContent = {
                        Icon(Icons.Default.ChevronRight, contentDescription = null)
                    },
                    modifier = Modifier.clickableSettingsRow(onClick = { showAbout = true })
                )
            }
        }
    }
}

@Composable
private fun DistanceUnitToggle(
    selectedUnit: String,
    onUnitChange: (String) -> Unit
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        listOf("km", "mi").forEach { unit ->
            FilterChip(
                selected  = selectedUnit == unit,
                onClick   = { onUnitChange(unit) },
                label     = { Text(unit) }
            )
        }
    }
}

/** Reusable modifier for tappable settings rows. */
private fun Modifier.clickableSettingsRow(onClick: () -> Unit): Modifier =
    this.clickable(onClick = onClick)

/** Chip label for a download policy - a verb the user can act on, not an enum name. */
private fun policyLabel(policy: OfflineDownloadPolicy): String = when (policy) {
    OfflineDownloadPolicy.ASK -> "Ask me"
    OfflineDownloadPolicy.ALWAYS -> "Always download"
    OfflineDownloadPolicy.NEVER -> "Never"
}

/** What each download policy does. ALWAYS downloads when the course is created. */
private fun policyExplanation(policy: OfflineDownloadPolicy): String = when (policy) {
    OfflineDownloadPolicy.ASK ->
        "You are offered a map once per import, with its size. Declining is remembered."
    OfflineDownloadPolicy.ALWAYS ->
        "Maps download as soon as a course is created, without asking."
    OfflineDownloadPolicy.NEVER ->
        "Maps are never downloaded on their own. You can still download one from " +
            "the readiness check or Map Storage."
}
