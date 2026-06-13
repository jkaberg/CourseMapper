package com.coursemapper.ui.navigation

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.coursemapper.domain.model.RunOrdering
import com.coursemapper.ui.help.HelpScreen
import com.coursemapper.ui.home.HomeScreen
import com.coursemapper.ui.offline.MapStorageScreen
import com.coursemapper.ui.onboarding.OnboardingScreen
import com.coursemapper.ui.presets.MarkerPresetScreen
import com.coursemapper.ui.record.RecordSetupScreen
import com.coursemapper.ui.record.RecordingGateScreen
import com.coursemapper.ui.record.RecordingScreen
import com.coursemapper.ui.routes.BranchAuthoringScreen
import com.coursemapper.ui.routes.CourseGeometryEditorScreen
import com.coursemapper.ui.routes.NetworkDetailScreen
import com.coursemapper.ui.routes.RoutesScreen
import com.coursemapper.ui.routes.MarkerTableScreen
import com.coursemapper.ui.routes.VariantBuilderScreen
import com.coursemapper.ui.navigate.NavigationGateScreen
import com.coursemapper.ui.run.EventWizardScreen
import com.coursemapper.ui.run.GroupDetailScreen
import com.coursemapper.ui.run.PackingListScreen
import com.coursemapper.ui.run.RunNavigationScreen
import com.coursemapper.ui.run.RunSetupScreen
import com.coursemapper.ui.settings.SettingsScreen
import com.coursemapper.ui.workspace.CourseWorkspaceScreen

private object Routes {
    const val ONBOARDING            = "onboarding"
    const val HOME                  = "home"
    const val SETTINGS              = "settings"
    const val HELP                  = "help"
    const val PRESETS               = "presets"
    const val MAP_STORAGE           = "map_storage"
    const val RECORD_SETUP          = "record_setup"
    const val RECORDING_GATE        = "recording_gate"
    const val RECORDING             = "recording"
    const val COURSE_WORKSPACE      = "course_workspace"
    // routes
    const val ROUTES                = "routes"
    const val COURSE_GEOMETRY       = "course_geometry"
    // route networks
    const val NETWORK_DETAIL        = "network_detail"
    const val BRANCH_AUTHORING      = "branch_authoring"
    const val VARIANT_BUILDER       = "variant_builder"
    const val MARKER_TABLE          = "marker_table"
    // Placement runs
    const val EVENT_WIZARD          = "event_wizard"
    const val RUN_SETUP             = "run_setup"
    const val RUN_GATE              = "run_gate"
    const val RUN_NAVIGATION        = "run_navigation"
    const val PACKING_LIST          = "packing_list"
    const val GROUP_DETAIL          = "group_detail"
}

/**
 * Packing list route. Pass [runId] when a run exists, it has its own order.
 * Otherwise [courseIds] plus the [ordering] the launch screen offers - the two
 * orders are completely different.
 */
private fun packingListRoute(
    runId: Long? = null,
    courseIds: List<Long> = emptyList(),
    ordering: RunOrdering = RunOrdering.SPINE
): String = if (runId != null) {
    "${Routes.PACKING_LIST}?runId=$runId"
} else {
    "${Routes.PACKING_LIST}?courseIds=${courseIds.joinToString(",")}&ordering=${ordering.name}"
}

/**
 * Root navigation host for the CourseMapper app.
 */
@Composable
fun CourseMapperNavHost(
    startOnboarding: Boolean = false
) {
    val navController = rememberNavController()
    val startDestination = if (startOnboarding) Routes.ONBOARDING else Routes.HOME

    NavHost(
        navController    = navController,
        startDestination = startDestination
    ) {
        composable(Routes.ONBOARDING) {
            OnboardingScreen(
                onFinish = {
                    navController.navigate(Routes.HOME) {
                        popUpTo(Routes.ONBOARDING) { inclusive = true }
                    }
                }
            )
        }

        composable(Routes.HOME) {
            HomeScreen(
                onRecordClicked   = { navController.navigate(Routes.RECORD_SETUP) },
                onImportGpxClicked = { navController.navigate(Routes.EVENT_WIZARD) },
                onCourseClicked   = { courseId ->
                    navController.navigate("${Routes.COURSE_WORKSPACE}?courseId=$courseId")
                },
                onNewRunClicked   = { navController.navigate(Routes.RUN_SETUP) },
                onResumeRun       = { runId ->
                    navController.navigate("${Routes.RUN_NAVIGATION}?runId=$runId")
                },
                onGroupClicked    = { groupId ->
                    navController.navigate("${Routes.GROUP_DETAIL}?groupId=$groupId")
                },
                onOpenRouteWorkspace = { routeId ->
                    navController.navigate("${Routes.COURSE_WORKSPACE}?routeId=$routeId")
                },
                onSettingsClicked = { navController.navigate(Routes.SETTINGS) },
                onHelpClicked     = { navController.navigate(Routes.HELP) },
                onNetworksClicked = { navController.navigate(Routes.ROUTES) }
            )
        }

        composable(Routes.SETTINGS) {
            SettingsScreen(
                onBack              = { navController.popBackStack() },
                onMapStorageClicked = { navController.navigate(Routes.MAP_STORAGE) },
                onHelpClicked       = { navController.navigate(Routes.HELP) }
            )
        }

        composable(Routes.HELP) {
            HelpScreen(onBack = { navController.popBackStack() })
        }

        composable(Routes.PRESETS) {
            MarkerPresetScreen(
                onBack = { navController.popBackStack() }
            )
        }

        composable(Routes.MAP_STORAGE) {
            MapStorageScreen(
                onBack = { navController.popBackStack() }
            )
        }

        // ── Event wizard: batch GPX import → published courses ─────────────────
        // Lands on the durable thing it just created - the combined course, or
        // the single course's own screen - not on a disposable run-setup form.
        composable(Routes.EVENT_WIZARD) {
            EventWizardScreen(
                onBack           = { navController.popBackStack() },
                onPresetsClicked = { navController.navigate(Routes.PRESETS) },
                onCoursesCreated = { courseIds, groupId ->
                    val destination = when {
                        groupId != null      -> "${Routes.GROUP_DETAIL}?groupId=$groupId"
                        courseIds.size == 1  -> "${Routes.COURSE_WORKSPACE}?courseId=${courseIds.first()}"
                        else                 ->
                            "${Routes.RUN_SETUP}?preselect=${courseIds.joinToString(",")}"
                    }
                    navController.navigate(destination) { popUpTo(Routes.HOME) }
                }
            )
        }

        composable(
            route     = "${Routes.RUN_SETUP}?preselect={preselect}",
            arguments = listOf(
                navArgument("preselect") { type = NavType.StringType; defaultValue = "" }
            )
        ) {
            RunSetupScreen(
                onBack       = { navController.popBackStack() },
                onRunCreated = { runId ->
                    navController.navigate("${Routes.RUN_GATE}?runId=$runId")
                }
            )
        }

        composable(
            route     = "${Routes.RUN_GATE}?runId={runId}",
            arguments = listOf(navArgument("runId") { type = NavType.LongType; defaultValue = -1L })
        ) { backStack ->
            val runId = backStack.arguments?.getLong("runId") ?: -1L
            NavigationGateScreen(
                onBack        = { navController.popBackStack() },
                onPackingList = { navController.navigate(packingListRoute(runId = runId)) },
                onProceed     = {
                    navController.navigate("${Routes.RUN_NAVIGATION}?runId=$runId") {
                        popUpTo("${Routes.RUN_GATE}?runId={runId}") { inclusive = true }
                    }
                }
            )
        }

        composable(
            route     = "${Routes.RUN_NAVIGATION}?runId={runId}",
            arguments = listOf(navArgument("runId") { type = NavType.LongType; defaultValue = -1L })
        ) {
            RunNavigationScreen(
                onExit = {
                    navController.navigate(Routes.HOME) {
                        popUpTo(Routes.HOME) { inclusive = true }
                    }
                }
            )
        }

        // ── Packing list ──────────────────────────────────────────────────────
        // Reachable from a created run and, before one exists, from a course or
        // combined course - the rack is loaded in the workshop.
        composable(
            route     = "${Routes.PACKING_LIST}?runId={runId}&courseIds={courseIds}&ordering={ordering}",
            arguments = listOf(
                navArgument("runId")     { type = NavType.LongType;   defaultValue = -1L },
                navArgument("courseIds") { type = NavType.StringType; defaultValue = "" },
                navArgument("ordering")  {
                    type = NavType.StringType
                    defaultValue = RunOrdering.SPINE.name
                }
            )
        ) {
            PackingListScreen(onBack = { navController.popBackStack() })
        }

        composable(
            route     = "${Routes.GROUP_DETAIL}?groupId={groupId}",
            arguments = listOf(navArgument("groupId") { type = NavType.LongType; defaultValue = -1L })
        ) {
            GroupDetailScreen(
                onBack          = { navController.popBackStack() },
                onRunReady      = { runId ->
                    navController.navigate("${Routes.RUN_GATE}?runId=$runId")
                },
                onPackingList   = { runId, courseIds, ordering ->
                    navController.navigate(packingListRoute(runId, courseIds, ordering))
                },
                onCourseClicked = { courseId ->
                    navController.navigate("${Routes.COURSE_WORKSPACE}?courseId=$courseId")
                }
            )
        }

        composable(Routes.RECORD_SETUP) {
            RecordSetupScreen(
                onBack           = { navController.popBackStack() },
                onPresetsClicked = { navController.navigate(Routes.PRESETS) },
                onProceed        = { name, notes, presetId, lapCount, targetDistanceCm,
                                     includeStart, includeFinish ->
                    val encodedName  = Uri.encode(name)
                    val encodedNotes = Uri.encode(notes)
                    navController.navigate(
                        "${Routes.RECORDING_GATE}?routeName=$encodedName" +
                        "&notes=$encodedNotes" +
                        "&presetId=$presetId" +
                        "&lapCount=$lapCount" +
                        "&targetDistanceCm=$targetDistanceCm" +
                        "&includeStart=$includeStart" +
                        "&includeFinish=$includeFinish"
                    )
                }
            )
        }

        composable(
            route     = "${Routes.RECORDING_GATE}?routeName={routeName}&notes={notes}&presetId={presetId}&lapCount={lapCount}&targetDistanceCm={targetDistanceCm}&includeStart={includeStart}&includeFinish={includeFinish}",
            arguments = listOf(
                navArgument("routeName")        { type = NavType.StringType; defaultValue = "" },
                navArgument("notes")            { type = NavType.StringType; defaultValue = "" },
                navArgument("presetId")         { type = NavType.LongType;   defaultValue = -1L },
                navArgument("lapCount")         { type = NavType.IntType;    defaultValue = 1 },
                navArgument("targetDistanceCm") { type = NavType.LongType;   defaultValue = 0L },
                navArgument("includeStart")     { type = NavType.BoolType;   defaultValue = false },
                navArgument("includeFinish")    { type = NavType.BoolType;   defaultValue = false }
            )
        ) { backStack ->
            val routeName        = backStack.arguments?.getString("routeName")       ?: ""
            val notes            = backStack.arguments?.getString("notes")           ?: ""
            val presetId         = backStack.arguments?.getLong("presetId")          ?: -1L
            val lapCount         = backStack.arguments?.getInt("lapCount")           ?: 1
            val targetDistanceCm = backStack.arguments?.getLong("targetDistanceCm") ?: 0L
            val includeStart     = backStack.arguments?.getBoolean("includeStart")  ?: false
            val includeFinish    = backStack.arguments?.getBoolean("includeFinish") ?: false

            RecordingGateScreen(
                routeName = routeName,
                onBack    = { navController.popBackStack() },
                onProceed = {
                    val encodedName  = Uri.encode(routeName)
                    val encodedNotes = Uri.encode(notes)
                    navController.navigate(
                        "${Routes.RECORDING}?routeName=$encodedName" +
                        "&notes=$encodedNotes" +
                        "&presetId=$presetId" +
                        "&lapCount=$lapCount" +
                        "&targetDistanceCm=$targetDistanceCm" +
                        "&includeStart=$includeStart" +
                        "&includeFinish=$includeFinish"
                    ) {
                        popUpTo(Routes.RECORDING_GATE) { inclusive = true }
                    }
                }
            )
        }

        composable(
            route     = "${Routes.RECORDING}?routeName={routeName}&notes={notes}&presetId={presetId}&lapCount={lapCount}&targetDistanceCm={targetDistanceCm}&includeStart={includeStart}&includeFinish={includeFinish}",
            arguments = listOf(
                navArgument("routeName")        { type = NavType.StringType; defaultValue = "" },
                navArgument("notes")            { type = NavType.StringType; defaultValue = "" },
                navArgument("presetId")         { type = NavType.LongType;   defaultValue = -1L },
                navArgument("lapCount")         { type = NavType.IntType;    defaultValue = 1 },
                navArgument("targetDistanceCm") { type = NavType.LongType;   defaultValue = 0L },
                navArgument("includeStart")     { type = NavType.BoolType;   defaultValue = false },
                navArgument("includeFinish")    { type = NavType.BoolType;   defaultValue = false }
            )
        ) {
            RecordingScreen(
                onNavigateToWorkspace = { routeId, presetId, lapCount, targetDistanceCm,
                                          includeStart, includeFinish ->
                    navController.navigate(
                        "${Routes.COURSE_WORKSPACE}?routeId=$routeId" +
                        "&presetId=$presetId" +
                        "&lapCount=$lapCount" +
                        "&targetDistanceCm=$targetDistanceCm" +
                        "&includeStart=$includeStart" +
                        "&includeFinish=$includeFinish"
                    ) {
                        popUpTo(Routes.HOME)
                    }
                },
                onDiscarded = {
                    navController.navigate(Routes.HOME) {
                        popUpTo(Routes.HOME) { inclusive = true }
                    }
                }
            )
        }

        composable(
            route     = "${Routes.COURSE_WORKSPACE}?courseId={courseId}&routeId={routeId}&presetId={presetId}&lapCount={lapCount}&targetDistanceCm={targetDistanceCm}&includeStart={includeStart}&includeFinish={includeFinish}",
            arguments = listOf(
                navArgument("courseId")         { type = NavType.LongType; defaultValue = -1L },
                navArgument("routeId")          { type = NavType.LongType; defaultValue = -1L },
                navArgument("presetId")         { type = NavType.LongType; defaultValue = -1L },
                navArgument("lapCount")         { type = NavType.IntType;  defaultValue = 1 },
                navArgument("targetDistanceCm") { type = NavType.LongType; defaultValue = 0L },
                navArgument("includeStart")     { type = NavType.BoolType; defaultValue = false },
                navArgument("includeFinish")    { type = NavType.BoolType; defaultValue = false }
            )
        ) {
            CourseWorkspaceScreen(
                onBack           = { navController.popBackStack() },
                onPublished      = { _ ->
                    navController.navigate(Routes.HOME) {
                        popUpTo(Routes.HOME) { inclusive = true }
                    }
                },
                onNetworkCreated = { networkId ->
                    navController.navigate("${Routes.NETWORK_DETAIL}/$networkId") {
                        popUpTo(Routes.HOME)
                    }
                },
                onRerecord       = {
                    navController.navigate(Routes.RECORD_SETUP) {
                        popUpTo(Routes.HOME)
                    }
                },
                onStartRun       = { runId ->
                    navController.navigate("${Routes.RUN_GATE}?runId=$runId")
                },
                onAdjustGeometry = { courseId ->
                    navController.navigate("${Routes.COURSE_GEOMETRY}?courseId=$courseId")
                },
                onViewMarkers    = { courseId ->
                    navController.navigate("${Routes.MARKER_TABLE}?courseId=$courseId")
                },
                onPackingList    = { courseId, runId ->
                    navController.navigate(
                        packingListRoute(runId = runId, courseIds = listOf(courseId))
                    )
                },
                onBackToNetwork  = { networkId ->
                    navController.navigate("${Routes.NETWORK_DETAIL}/$networkId") {
                        popUpTo(Routes.ROUTES)
                    }
                }
            )
        }

        composable(Routes.ROUTES) {
            RoutesScreen(
                onBack           = { navController.popBackStack() },
                onCourseClicked  = { courseId ->
                    navController.navigate("${Routes.COURSE_WORKSPACE}?courseId=$courseId")
                },
                onNetworkClicked = { networkId ->
                    navController.navigate("${Routes.NETWORK_DETAIL}/$networkId")
                }
            )
        }

        composable(
            route     = "${Routes.NETWORK_DETAIL}/{networkId}",
            arguments = listOf(navArgument("networkId") { type = NavType.LongType })
        ) {
            NetworkDetailScreen(
                onBack                 = { navController.popBackStack() },
                onAddBranch            = { networkId ->
                    navController.navigate("${Routes.BRANCH_AUTHORING}?networkId=$networkId")
                },
                onCreateVariant        = { networkId ->
                    navController.navigate("${Routes.VARIANT_BUILDER}/$networkId")
                },
                onVariantCourseClicked = { courseId ->
                    navController.navigate("${Routes.COURSE_WORKSPACE}?courseId=$courseId")
                }
            )
        }

        composable(
            route     = "${Routes.BRANCH_AUTHORING}?networkId={networkId}",
            arguments = listOf(navArgument("networkId") { type = NavType.LongType; defaultValue = -1L })
        ) {
            BranchAuthoringScreen(
                onBack  = { navController.popBackStack() },
                onSaved = { navController.popBackStack() }
            )
        }

        composable(
            route     = "${Routes.VARIANT_BUILDER}/{networkId}",
            arguments = listOf(navArgument("networkId") { type = NavType.LongType })
        ) {
            VariantBuilderScreen(
                onBack  = { navController.popBackStack() },
                onSaved = { navController.popBackStack() }
            )
        }

        composable(
            route     = "${Routes.COURSE_GEOMETRY}?courseId={courseId}",
            arguments = listOf(
                navArgument("courseId") { type = NavType.LongType; defaultValue = -1L }
            )
        ) {
            CourseGeometryEditorScreen(
                onBack  = { navController.popBackStack() },
                onSaved = { navController.popBackStack() }
            )
        }

        composable(
            route     = "${Routes.MARKER_TABLE}?courseId={courseId}",
            arguments = listOf(
                navArgument("courseId") { type = NavType.LongType; defaultValue = -1L }
            )
        ) {
            MarkerTableScreen(
                onBack = { navController.popBackStack() }
            )
        }

    }
}
