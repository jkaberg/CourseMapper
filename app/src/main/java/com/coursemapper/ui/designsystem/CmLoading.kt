package com.coursemapper.ui.designsystem

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier

/**
 * Centered spinner filling the content area.
 *
 * [modifier] goes after `fillMaxSize()` so `Modifier.padding(padding)` from the
 * scaffold insets the content, not the box.
 */
@Composable
fun CmLoading(modifier: Modifier = Modifier) {
    Box(
        modifier = Modifier.fillMaxSize().then(modifier),
        contentAlignment = Alignment.Center
    ) {
        CircularProgressIndicator()
    }
}
