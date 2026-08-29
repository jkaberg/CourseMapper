package com.coursemapper.ui.designsystem

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

/**
 * Explicit Material 3 colour schemes.
 *
 * No dynamic colour: `primary`, `tertiary` and `error` mean GPS ready, weak
 * signal and no signal on the readiness gate. Derived from a wallpaper they can
 * end up nearly the same colour, which is useless on an ATV in daylight.
 *
 * Seeds come from [com.coursemapper.map.MapPalette] so the UI matches the map:
 * - `primary` from `ROUTE` #2962FF
 * - `secondary` from `ROUTE_VARIANT` #00897B
 * - `tertiary` from `JUNCTION` #FFB300 (amber, orange lands too close to error in dark)
 * - `error` from `ACTIVE_TARGET` #D32F2F
 *
 * Tones are the Material 3 ones (40/100/90/10 light, 80/20/30/90 dark).
 * `ColorSchemeContrastTest` checks every `on*` pair against WCAG AA.
 */
object CourseMapperColors {

    val light = lightColorScheme(
        primary                 = Color(0xFF0051E0),
        onPrimary               = Color(0xFFFFFFFF),
        primaryContainer        = Color(0xFFE2DFFF),
        onPrimaryContainer      = Color(0xFF00164D),
        secondary               = Color(0xFF006B5F),
        onSecondary             = Color(0xFFFFFFFF),
        secondaryContainer      = Color(0xFF8BF5E4),
        onSecondaryContainer    = Color(0xFF00201C),
        tertiary                = Color(0xFF7E5700),
        onTertiary              = Color(0xFFFFFFFF),
        tertiaryContainer       = Color(0xFFFFDDB1),
        onTertiaryContainer     = Color(0xFF261900),
        error                   = Color(0xFFBC111F),
        onError                 = Color(0xFFFFFFFF),
        errorContainer          = Color(0xFFFFDAD4),
        onErrorContainer        = Color(0xFF3B0900),
        background              = Color(0xFFFCFCFF),
        onBackground            = Color(0xFF1B1B20),
        surface                 = Color(0xFFFCFCFF),
        onSurface               = Color(0xFF1B1B20),
        surfaceVariant          = Color(0xFFE3E0F4),
        onSurfaceVariant        = Color(0xFF474555),
        surfaceTint             = Color(0xFF0051E0),
        inverseSurface          = Color(0xFF303036),
        inverseOnSurface        = Color(0xFFF1F0F8),
        inversePrimary          = Color(0xFFC4C0FF),
        outline                 = Color(0xFF777586),
        outlineVariant          = Color(0xFFC7C4D7),
        scrim                   = Color(0xFF000000),
        surfaceBright           = Color(0xFFF9F9FF),
        surfaceDim              = Color(0xFFDAD9E1),
        surfaceContainer        = Color(0xFFEEEDF5),
        surfaceContainerHigh    = Color(0xFFE8E7EF),
        surfaceContainerHighest = Color(0xFFE3E2E9),
        surfaceContainerLow     = Color(0xFFF4F3FA),
        surfaceContainerLowest  = Color(0xFFFFFFFF),
    )

    val dark = darkColorScheme(
        primary                 = Color(0xFFC4C0FF),
        onPrimary               = Color(0xFF00297B),
        primaryContainer        = Color(0xFF003CAC),
        onPrimaryContainer      = Color(0xFFE2DFFF),
        secondary               = Color(0xFF6ED8C8),
        onSecondary             = Color(0xFF003731),
        secondaryContainer      = Color(0xFF005048),
        onSecondaryContainer    = Color(0xFF8BF5E4),
        tertiary                = Color(0xFFFFBA3C),
        onTertiary              = Color(0xFF422C00),
        tertiaryContainer       = Color(0xFF604100),
        onTertiaryContainer     = Color(0xFFFFDDB1),
        error                   = Color(0xFFFFB4A8),
        onError                 = Color(0xFF690008),
        errorContainer          = Color(0xFF930013),
        onErrorContainer        = Color(0xFFFFDAD4),
        background              = Color(0xFF1B1B20),
        onBackground            = Color(0xFFE3E2E9),
        surface                 = Color(0xFF1B1B20),
        onSurface               = Color(0xFFE3E2E9),
        surfaceVariant          = Color(0xFF474555),
        onSurfaceVariant        = Color(0xFFC7C4D7),
        surfaceTint             = Color(0xFFC4C0FF),
        inverseSurface          = Color(0xFFE3E2E9),
        inverseOnSurface        = Color(0xFF303036),
        inversePrimary          = Color(0xFF0051E0),
        outline                 = Color(0xFF918FA0),
        outlineVariant          = Color(0xFF474555),
        scrim                   = Color(0xFF000000),
        surfaceBright           = Color(0xFF39383E),
        surfaceDim              = Color(0xFF131318),
        surfaceContainer        = Color(0xFF201F24),
        surfaceContainerHigh    = Color(0xFF2A292F),
        surfaceContainerHighest = Color(0xFF35343A),
        surfaceContainerLow     = Color(0xFF1B1B20),
        surfaceContainerLowest  = Color(0xFF0E0D14),
    )
}
