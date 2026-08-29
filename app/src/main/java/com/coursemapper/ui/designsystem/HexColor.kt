package com.coursemapper.ui.designsystem

import androidx.compose.ui.graphics.Color
import java.util.concurrent.ConcurrentHashMap

/**
 * Hex string ([com.coursemapper.map.MapPalette] keeps colours as hex for MapLibre)
 * to Compose [Color], cached since the navigation screen asks for these every frame.
 *
 * A plain map rather than `remember` so it also works outside composition.
 */
private val parsed = ConcurrentHashMap<String, Color>()

/** Cap so unexpected input falls back to parsing instead of growing forever. */
private const val MAX_CACHED = 64

fun String.toComposeColor(): Color =
    parsed[this]
        ?: Color(android.graphics.Color.parseColor(this))
            .also { if (parsed.size < MAX_CACHED) parsed[this] = it }
