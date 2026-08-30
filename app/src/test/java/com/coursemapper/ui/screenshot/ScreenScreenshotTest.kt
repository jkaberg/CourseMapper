package com.coursemapper.ui.screenshot

import com.coursemapper.domain.model.OfflineDownloadPolicy
import com.coursemapper.domain.model.OfflinePackStatus
import com.coursemapper.ui.about.AboutViewModel
import com.coursemapper.ui.help.HelpScreen
import com.coursemapper.ui.home.HomeScreen
import com.coursemapper.ui.home.HomeUiState
import com.coursemapper.ui.home.HomeViewModel
import com.coursemapper.ui.navigate.NavigationGateScreen
import com.coursemapper.ui.navigate.NavigationGateUiState
import com.coursemapper.ui.navigate.NavigationGateViewModel
import com.coursemapper.ui.offline.MapStorageScreen
import com.coursemapper.ui.offline.MapStorageUiState
import com.coursemapper.ui.offline.MapStorageViewModel
import com.coursemapper.ui.onboarding.OnboardingScreen
import com.coursemapper.ui.onboarding.OnboardingViewModel
import com.coursemapper.ui.presets.MarkerPresetScreen
import com.coursemapper.ui.presets.MarkerPresetViewModel
import com.coursemapper.ui.presets.PresetsUiState
import com.coursemapper.ui.record.GateState
import com.coursemapper.ui.record.RecordSetupScreen
import com.coursemapper.ui.record.RecordSetupUiState
import com.coursemapper.ui.record.RecordSetupViewModel
import com.coursemapper.ui.record.RecordingGateScreen
import com.coursemapper.ui.record.RecordingGateUiState
import com.coursemapper.ui.record.RecordingGateViewModel
import com.coursemapper.ui.routes.MarkerTableScreen
import com.coursemapper.ui.routes.MarkerTableUiState
import com.coursemapper.ui.routes.MarkerTableViewModel
import com.coursemapper.ui.routes.NetworkDisplayItem
import com.coursemapper.ui.routes.RoutesScreen
import com.coursemapper.ui.routes.RoutesUiState
import com.coursemapper.ui.routes.RoutesViewModel
import com.coursemapper.ui.routes.StandaloneCourseItem
import com.coursemapper.ui.routes.VariantCourseItem
import com.coursemapper.ui.run.PackingListScreen
import com.coursemapper.ui.run.PackingListUiState
import com.coursemapper.ui.run.PackingListViewModel
import com.coursemapper.ui.settings.SettingsScreen
import com.coursemapper.ui.settings.SettingsUiState
import com.coursemapper.ui.settings.SettingsViewModel
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Test

/**
 * Baselines for the screens that render on the JVM, empty and populated.
 *
 * The ViewModel is mocked here since these only assert pixels, a real one
 * needs Hilt and reads the clock. Screens with a MapLibre `MapView` can't be
 * captured under Robolectric.
 */
class ScreenScreenshotTest : ScreenshotTest() {

    @Test
    fun `home, nothing recorded yet`() = screenshot("home_empty") {
        HomeScreen(viewModel = homeVm(HomeUiState()), aboutViewModel = aboutVm())
    }

    /** All five course statuses through [com.coursemapper.ui.components.CourseStatusBadge]. */
    @Test
    fun `home, an event and its four courses`() = screenshot("home_populated") {
        HomeScreen(
            viewModel = homeVm(
                HomeUiState(
                    courses = ScreenFixtures.allCourses,
                    courseGroups = listOf(ScreenFixtures.trondheimEvent),
                    courseStatuses = ScreenFixtures.courseStatuses,
                    groupRowInfo = mapOf(ScreenFixtures.trondheimEvent.id to ScreenFixtures.eventRowInfo),
                    courseGroupNames = ScreenFixtures.allCourses.associate {
                        it.id to listOf(ScreenFixtures.trondheimEvent.name)
                    }
                )
            ),
            aboutViewModel = aboutVm()
        )
    }

    private fun homeVm(state: HomeUiState): HomeViewModel =
        mockk(relaxed = true) { every { uiState } returns MutableStateFlow(state) }

    /** About card VM held still, a first-run showing would cover the screen. */
    private fun aboutVm(): AboutViewModel =
        mockk(relaxed = true) { every { showOnFirstRun } returns MutableStateFlow(false) }

    /** GPS indicator in every state on both gates, same component and labels. */
    @Test
    fun `recording gate, no permission`() = screenshot("gate_recording_no_permission") {
        RecordingGateScreen(
            routeName = "Marathon",
            onBack = {},
            onProceed = {},
            viewModel = recordingGateVm(RecordingGateUiState(gateState = GateState.NoPermission))
        )
    }

    @Test
    fun `recording gate, settings required`() = screenshot("gate_recording_settings_required") {
        RecordingGateScreen(
            routeName = "Marathon",
            onBack = {},
            onProceed = {},
            viewModel = recordingGateVm(RecordingGateUiState(gateState = GateState.SettingsRequired))
        )
    }

    @Test
    fun `recording gate, GPS ready`() = screenshot("gate_recording_green") {
        RecordingGateScreen(
            routeName = "Marathon",
            onBack = {},
            onProceed = {},
            viewModel = recordingGateVm(
                RecordingGateUiState(gateState = GateState.Green(accuracyMetres = 4f, ageSeconds = 1L))
            )
        )
    }

    @Test
    fun `navigation gate, no permission`() = screenshot("gate_navigation_no_permission") {
        NavigationGateScreen(
            onBack = {},
            onProceed = {},
            viewModel = navGateVm(
                NavigationGateUiState(
                    gateState = GateState.NoPermission,
                    offlineStatus = OfflinePackStatus.READY,
                    offlineChecking = false,
                    runName = "Trondheim Marathon",
                    stopCount = 34
                )
            )
        )
    }

    @Test
    fun `navigation gate, settings required`() = screenshot("gate_navigation_settings_required") {
        NavigationGateScreen(
            onBack = {},
            onProceed = {},
            viewModel = navGateVm(
                NavigationGateUiState(
                    gateState = GateState.SettingsRequired,
                    offlineStatus = OfflinePackStatus.READY,
                    offlineChecking = false,
                    runName = "Trondheim Marathon",
                    stopCount = 34
                )
            )
        )
    }

    /** Weak signal plus a download in flight - the two amber-ish roles together. */
    @Test
    fun `navigation gate, weak signal while tiles download`() =
        screenshot("gate_navigation_yellow_downloading") {
            NavigationGateScreen(
                onBack = {},
                onProceed = {},
                viewModel = navGateVm(
                    NavigationGateUiState(
                        gateState = GateState.Yellow(accuracyMetres = 22f, ageSeconds = 3L),
                        offlineStatus = OfflinePackStatus.DOWNLOADING,
                        offlineChecking = false,
                        offlineProgress = 0.44f,
                        offlineEstimatedBytes = 52L * 1024 * 1024,
                        runName = "Trondheim Marathon",
                        stopCount = 34,
                        autoAdvanceCancelled = true
                    )
                )
            )
        }

    /** A pack stopped part way shows as paused with Resume. */
    @Test
    fun `navigation gate, download paused part-way`() =
        screenshot("gate_navigation_offline_paused") {
            NavigationGateScreen(
                onBack = {},
                onProceed = {},
                viewModel = navGateVm(
                    NavigationGateUiState(
                        gateState = GateState.Green(accuracyMetres = 5f, ageSeconds = 1L),
                        offlineStatus = OfflinePackStatus.PAUSED,
                        offlineChecking = false,
                        runName = "Trondheim Marathon",
                        stopCount = 34,
                        autoAdvanceEnabled = false
                    )
                )
            )
        }

    @Test
    fun `navigation gate, offline map missing`() = screenshot("gate_navigation_offline_missing") {
        NavigationGateScreen(
            onBack = {},
            onProceed = {},
            viewModel = navGateVm(
                NavigationGateUiState(
                    gateState = GateState.Red,
                    offlineStatus = null,
                    offlineChecking = false,
                    offlineEstimatedBytes = 52L * 1024 * 1024,
                    runName = "Trondheim Marathon",
                    stopCount = 34
                )
            )
        )
    }

    private fun recordingGateVm(state: RecordingGateUiState): RecordingGateViewModel =
        mockk(relaxed = true) { every { uiState } returns MutableStateFlow(state) }

    private fun navGateVm(state: NavigationGateUiState): NavigationGateViewModel =
        mockk(relaxed = true) { every { uiState } returns MutableStateFlow(state) }

    @Test
    fun `map storage, empty`() = screenshot("map_storage_empty") {
        MapStorageScreen(onBack = {}, viewModel = mapStorageVm(MapStorageUiState()))
    }

    /** Every pack status the screen draws, PAUSED and FAILED included. */
    @Test
    fun `map storage, one pack per status`() = screenshot("map_storage_populated") {
        MapStorageScreen(
            onBack = {},
            viewModel = mapStorageVm(
                MapStorageUiState(
                    packs = ScreenFixtures.packs,
                    totalBytes = 60L * 1024 * 1024,
                    wifiOnly = true
                )
            )
        )
    }

    private fun mapStorageVm(state: MapStorageUiState): MapStorageViewModel =
        mockk(relaxed = true) { every { uiState } returns MutableStateFlow(state) }

    @Test
    fun `courses list, empty`() = screenshot("routes_empty") {
        RoutesScreen(
            onBack = {},
            onCourseClicked = {},
            onNetworkClicked = {},
            viewModel = routesVm(RoutesUiState(isLoading = false))
        )
    }

    /** The shared `CmLoading` in situ, under a scaffold's insets. */
    @Test
    fun `courses list, still loading`() = screenshot("routes_loading") {
        RoutesScreen(
            onBack = {},
            onCourseClicked = {},
            onNetworkClicked = {},
            viewModel = routesVm(RoutesUiState(isLoading = true))
        )
    }

    @Test
    fun `courses list, networks and variants`() = screenshot("routes_populated") {
        RoutesScreen(
            onBack = {},
            onCourseClicked = {},
            onNetworkClicked = {},
            viewModel = routesVm(
                RoutesUiState(
                    isLoading = false,
                    items = listOf(
                        NetworkDisplayItem(
                            networkId = 1L,
                            networkName = "Trondheim network",
                            updatedAt = ScreenFixtures.FIXED_TIME,
                            trunkDistanceKm = 44.2,
                            standaloneCourses = emptyList(),
                            variantCourses = listOf(
                                VariantCourseItem(1L, "Marathon", 1L, 42.2, isApproved = true),
                                VariantCourseItem(2L, "Half Marathon", 2L, 21.1, isApproved = true),
                                VariantCourseItem(3L, "10 km", null, null, isApproved = false)
                            )
                        ),
                        NetworkDisplayItem(
                            networkId = 2L,
                            networkName = "Bymarka loop",
                            updatedAt = ScreenFixtures.FIXED_TIME,
                            trunkDistanceKm = null,
                            standaloneCourses = listOf(
                                StandaloneCourseItem(4L, "Bymarka loop", 8.4, markerCount = 9)
                            ),
                            variantCourses = emptyList()
                        )
                    )
                )
            )
        )
    }

    private fun routesVm(state: RoutesUiState): RoutesViewModel =
        mockk(relaxed = true) { every { uiState } returns MutableStateFlow(state) }

    @Test
    fun `marker table`() = screenshot("marker_table") {
        MarkerTableScreen(
            onBack = {},
            viewModel = mockk<MarkerTableViewModel>(relaxed = true) {
                every { uiState } returns MutableStateFlow(
                    MarkerTableUiState(
                        courseName = "Marathon",
                        variantName = null,
                        markers = ScreenFixtures.markers,
                        isLoading = false
                    )
                )
            }
        )
    }

    @Test
    fun `packing list`() = screenshot("packing_list") {
        PackingListScreen(
            onBack = {},
            viewModel = mockk<PackingListViewModel>(relaxed = true) {
                every { uiState } returns MutableStateFlow(
                    PackingListUiState(
                        title = "Trondheim Marathon",
                        stops = ScreenFixtures.packingStops,
                        isLoading = false,
                        signsPerCourse = mapOf("Marathon" to 3, "Half Marathon" to 2, "10 km" to 1),
                        canExport = true
                    )
                )
            }
        )
    }

    @Test
    fun `record setup`() = screenshot("record_setup") {
        RecordSetupScreen(
            onBack = {},
            onProceed = { _, _, _, _, _, _, _ -> },
            viewModel = mockk<RecordSetupViewModel>(relaxed = true) {
                every { uiState } returns MutableStateFlow(
                    RecordSetupUiState(
                        name = "Marathon",
                        notes = "Start at Torvet, finish on the bridge.",
                        selectedPresetId = 1L,
                        includeStart = true,
                        includeFinish = true,
                        lapCount = 1,
                        presets = ScreenFixtures.presets,
                        compositionPreview = "1 lap · 42.2 km",
                        canProceed = true
                    )
                )
            }
        )
    }

    @Test
    fun `marker presets`() = screenshot("marker_presets") {
        MarkerPresetScreen(
            onBack = {},
            viewModel = mockk<MarkerPresetViewModel>(relaxed = true) {
                every { uiState } returns MutableStateFlow(
                    PresetsUiState(presets = ScreenFixtures.presets)
                )
            }
        )
    }

    @Test
    fun `settings`() = screenshot("settings") {
        SettingsScreen(
            onBack = {},
            viewModel = mockk<SettingsViewModel>(relaxed = true) {
                every { uiState } returns MutableStateFlow(
                    SettingsUiState(
                        offlineDownloadPolicy = OfflineDownloadPolicy.ASK,
                        offlineTotalBytes = 60L * 1024 * 1024,
                        isBatteryUnrestricted = false,
                        isLocationEnabled = true
                    )
                )
            }
        )
    }

    @Test
    fun `onboarding`() = screenshot("onboarding") {
        OnboardingScreen(onFinish = {}, viewModel = mockk<OnboardingViewModel>(relaxed = true))
    }

    @Test
    fun `help`() = screenshot("help") {
        HelpScreen(onBack = {})
    }
}
