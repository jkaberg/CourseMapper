package com.coursemapper.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import com.coursemapper.ui.designsystem.CourseMapperColors
import com.coursemapper.ui.designsystem.CourseMapperShapes
import com.coursemapper.ui.designsystem.CourseMapperTypography

/**
 * App theme, no dynamic colour. `primary`, `tertiary` and `error` mean GPS
 * ready, weak and none on the gates, and some wallpapers make them nearly the
 * same colour. See [CourseMapperColors].
 */
@Composable
fun CourseMapperTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) CourseMapperColors.dark else CourseMapperColors.light,
        typography  = CourseMapperTypography,
        shapes      = CourseMapperShapes,
        content     = content
    )
}
