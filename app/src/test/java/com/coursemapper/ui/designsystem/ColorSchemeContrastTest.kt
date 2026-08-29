package com.coursemapper.ui.designsystem

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.cbrt
import kotlin.math.hypot
import kotlin.math.pow

/**
 * Checks the explicit colour schemes: every `on*` role readable on its pair, and
 * the three GPS roles (ready, weak, none) clearly different colours.
 * Plain JVM, no Robolectric.
 */
class ColorSchemeContrastTest {

    /** WCAG AA for normal text. Every pair here clears it with margin. */
    private val minContrast = 4.5

    /** Min CIELAB distance between roles that mean different things. */
    private val minSeparation = 40.0

    @Test
    fun `light scheme keeps every on-role readable`() = assertReadable("light", CourseMapperColors.light)

    @Test
    fun `dark scheme keeps every on-role readable`() = assertReadable("dark", CourseMapperColors.dark)

    private fun assertReadable(name: String, s: ColorScheme) {
        val pairs = listOf(
            "onPrimary/primary" to (s.onPrimary to s.primary),
            "onSecondary/secondary" to (s.onSecondary to s.secondary),
            "onTertiary/tertiary" to (s.onTertiary to s.tertiary),
            "onError/error" to (s.onError to s.error),
            "onPrimaryContainer/primaryContainer" to (s.onPrimaryContainer to s.primaryContainer),
            "onSecondaryContainer/secondaryContainer" to (s.onSecondaryContainer to s.secondaryContainer),
            "onTertiaryContainer/tertiaryContainer" to (s.onTertiaryContainer to s.tertiaryContainer),
            "onErrorContainer/errorContainer" to (s.onErrorContainer to s.errorContainer),
            "onSurface/surface" to (s.onSurface to s.surface),
            "onSurfaceVariant/surfaceVariant" to (s.onSurfaceVariant to s.surfaceVariant),
            "onBackground/background" to (s.onBackground to s.background),
            "inverseOnSurface/inverseSurface" to (s.inverseOnSurface to s.inverseSurface),
            // The three roles the gates paint text in, on the surface behind them.
            "primary/surface" to (s.primary to s.surface),
            "tertiary/surface" to (s.tertiary to s.surface),
            "error/surface" to (s.error to s.surface),
        )
        pairs.forEach { (label, pair) ->
            val ratio = contrast(pair.first, pair.second)
            assertTrue(
                "$name: $label is %.2f:1, below the %.1f:1 floor".format(ratio, minContrast),
                ratio >= minContrast
            )
        }
    }

    @Test
    fun `the GPS roles cannot be mistaken for one another`() {
        listOf("light" to CourseMapperColors.light, "dark" to CourseMapperColors.dark)
            .forEach { (name, s) ->
                listOf(
                    "ready/marginal" to (s.primary to s.tertiary),
                    "marginal/no fix" to (s.tertiary to s.error),
                    "ready/no fix" to (s.primary to s.error),
                ).forEach { (label, pair) ->
                    val separation = deltaE(pair.first, pair.second)
                    assertTrue(
                        "$name: $label are %.1f CIELAB apart, under the %.0f floor — the two states ".format(
                            separation, minSeparation
                        ) + "would read as the same colour on a phone bolted to an ATV",
                        separation >= minSeparation
                    )
                }
            }
    }

    /** Outline is a hairline, so WCAG's 3:1 non-text floor. */
    @Test
    fun `outlines stay visible`() {
        listOf("light" to CourseMapperColors.light, "dark" to CourseMapperColors.dark)
            .forEach { (name, s) ->
                val ratio = contrast(s.outline, s.surface)
                assertTrue(
                    "$name: outline on surface is %.2f:1, below 3:1".format(ratio), ratio >= 3.0
                )
            }
    }

    private fun linear(c: Float): Double {
        val v = c.toDouble()
        return if (v <= 0.04045) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)
    }

    private fun relativeLuminance(c: Color): Double =
        0.2126 * linear(c.red) + 0.7152 * linear(c.green) + 0.0722 * linear(c.blue)

    private fun contrast(a: Color, b: Color): Double {
        val (hi, lo) = listOf(relativeLuminance(a), relativeLuminance(b)).sortedDescending()
        return (hi + 0.05) / (lo + 0.05)
    }

    private fun lab(c: Color): Triple<Double, Double, Double> {
        val r = linear(c.red)
        val g = linear(c.green)
        val b = linear(c.blue)
        val x = (0.4124564 * r + 0.3575761 * g + 0.1804375 * b) / 0.95047
        val y = (0.2126729 * r + 0.7151522 * g + 0.0721750 * b) / 1.0
        val z = (0.0193339 * r + 0.1191920 * g + 0.9503041 * b) / 1.08883
        fun f(t: Double) = if (t > 216.0 / 24389.0) cbrt(t) else (841.0 / 108.0) * t + 4.0 / 29.0
        val (fx, fy, fz) = Triple(f(x), f(y), f(z))
        return Triple(116 * fy - 16, 500 * (fx - fy), 200 * (fy - fz))
    }

    private fun deltaE(a: Color, b: Color): Double {
        val (l1, a1, b1) = lab(a)
        val (l2, a2, b2) = lab(b)
        return hypot(abs(l1 - l2), hypot(abs(a1 - a2), abs(b1 - b2)))
    }
}
