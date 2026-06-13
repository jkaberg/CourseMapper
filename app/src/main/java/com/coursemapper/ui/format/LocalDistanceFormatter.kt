package com.coursemapper.ui.format

import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * App-wide [DistanceFormatter], provided in [com.coursemapper.MainActivity].
 *
 * ```kotlin
 * val formatter = LocalDistanceFormatter.current
 * val unit by rememberDistanceUnit()
 * Text(formatter.formatCompact(course.totalDistanceMetres, unit))
 * ```
 */
val LocalDistanceFormatter = compositionLocalOf<DistanceFormatter> {
    error("LocalDistanceFormatter not provided. Wrap your content in the app root.")
}

/**
 * Fixed unit for [rememberDistanceUnit] where there's no Hilt graph, ie
 * previews and screenshot tests. Null in the app.
 */
val LocalDistanceUnit = staticCompositionLocalOf<DistanceFormatter.DistanceUnit?> { null }
