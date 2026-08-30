package com.coursemapper.ui.screenshot

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.onRoot
import androidx.test.core.app.ApplicationProvider
import com.coursemapper.data.prefs.UserPreferencesRepository
import com.coursemapper.ui.format.DistanceFormatter
import com.coursemapper.ui.format.LocalDistanceFormatter
import com.coursemapper.ui.format.LocalDistanceUnit
import com.coursemapper.ui.theme.CourseMapperTheme
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import com.github.takahirom.roborazzi.captureRoboImage
import com.github.takahirom.roborazzi.roborazziSystemPropertyOutputDirectory
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Base class for the JVM screenshot baselines.
 *
 * ```
 * ./gradlew :app:recordRoborazziDebug   # rewrite the baselines
 * ./gradlew :app:verifyRoborazziDebug   # fail on any changed pixel
 * ```
 *
 * Baselines live in `app/src/test/screenshots/` and are committed. A failed
 * verify writes the comparison to `app/build/outputs/roborazzi/`. A plain
 * `testDebugUnitTest` neither records nor verifies.
 *
 * Robolectric uses its own fonts so baselines are stable across machines, a
 * Compose BOM bump will move pixels. Always the real [CourseMapperTheme], a
 * test theme can't catch theme bugs.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(
    application = android.app.Application::class,
    qualifiers = RobolectricDeviceQualifiers.Pixel5
)
abstract class ScreenshotTest {

    @get:Rule
    val compose = createComposeRule()

    private val distanceFormatter by lazy {
        DistanceFormatter(UserPreferencesRepository(ApplicationProvider.getApplicationContext()))
    }

    /** Drives the theme from outside the composition, so one test can shoot both. */
    private val darkTheme = mutableStateOf(false)

    /**
     * Render [content] in the app theme to `<name>_light.png` and
     * `<name>_dark.png`. Composition is set once and the theme toggled.
     */
    protected fun screenshot(name: String, content: @Composable () -> Unit) {
        setUpContent(content)
        capture("${name}_light")
        darkTheme.value = true
        capture("${name}_dark")
    }

    /** Like [screenshot] but captures the dialog window, `onRoot()` would be the empty content behind. */
    protected fun screenshotDialog(name: String, content: @Composable () -> Unit) {
        setUpContent(content)
        captureDialog("${name}_light")
        darkTheme.value = true
        captureDialog("${name}_dark")
    }

    /** Single-theme capture, for cases where only one is meaningful. */
    protected fun screenshot(
        name: String,
        dark: Boolean,
        content: @Composable () -> Unit
    ) {
        darkTheme.value = dark
        setUpContent(content)
        capture(name)
    }

    private fun setUpContent(content: @Composable () -> Unit) {
        compose.setContent {
            CourseMapperTheme(darkTheme = darkTheme.value) {
                // The formatter and the unit are normally provided by
                // MainActivity and by a Hilt ViewModel respectively.  Neither
                // exists in a JVM test, and neither is what is under test here.
                CompositionLocalProvider(
                    LocalDistanceFormatter provides distanceFormatter,
                    LocalDistanceUnit provides DistanceFormatter.DistanceUnit.KM
                ) {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.background
                    ) {
                        Box(Modifier.fillMaxWidth()) { content() }
                    }
                }
            }
        }
    }

    @OptIn(ExperimentalRoborazziApi::class)
    private fun capture(fileName: String) {
        compose.waitForIdle()
        compose.onRoot().captureRoboImage(
            "${roborazziSystemPropertyOutputDirectory()}/$fileName.png"
        )
    }

    @OptIn(ExperimentalRoborazziApi::class)
    private fun captureDialog(fileName: String) {
        compose.waitForIdle()
        compose.onNode(isDialog()).captureRoboImage(
            "${roborazziSystemPropertyOutputDirectory()}/$fileName.png"
        )
    }
}
