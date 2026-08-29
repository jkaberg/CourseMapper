package com.coursemapper.ui.designsystem

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Spacing grid. Values below 4 dp stay as literals, they are optical tweaks
 * inside a component and not layout spacing.
 */
object Spacing {
    /** Between a label and the thing it labels. */
    val xs: Dp = 4.dp

    /** Default gap inside a component. */
    val sm: Dp = 8.dp

    /** Between rows of a list that isn't a `ListItem`. */
    val md: Dp = 12.dp

    /** Between components, and the screen gutter. */
    val lg: Dp = 16.dp

    /** Between sections of a screen. */
    val xl: Dp = 24.dp

    /** Around a screen's single hero element. */
    val xxl: Dp = 32.dp
}

object IconSize {
    /** Inline with `bodySmall` / `labelMedium` text. */
    val sm: Dp = 16.dp

    /** Inline with `bodyLarge`, and the default leading icon. */
    val md: Dp = 20.dp

    /** Material default, app bars and `IconButton`. */
    val lg: Dp = 24.dp

    /** Empty states and the GPS gate. */
    val hero: Dp = 64.dp
}

object ScreenGutter {
    val horizontal: Dp = Spacing.lg

    val listContent: PaddingValues =
        PaddingValues(horizontal = Spacing.lg, vertical = Spacing.md)

    /** Bottom padding for lists that end under a FAB or bottom bar. */
    val listBottom: Dp = Spacing.xl
}

/**
 * Height of an embedded map strip.
 *
 * Not cosmetic: the height decides the fit zoom, and the fit zoom picks the
 * [com.coursemapper.map.RibbonLod] band - which decides whether shared corridors
 * draw as one ribbon or as parallel lanes. `RibbonPreviewMapZoomTest` reads this.
 */
object MapStrip {
    val height: Dp = 240.dp
}
