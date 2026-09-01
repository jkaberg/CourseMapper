package com.coursemapper.ui.about

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.LocalCafe
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.window.DialogProperties
import com.coursemapper.BuildConfig
import com.coursemapper.ui.designsystem.IconSize
import com.coursemapper.ui.designsystem.Spacing
import kotlin.math.ceil

/** Where the app came from, and how to say thanks. */
private const val REPO_URL   = "https://github.com/jkaberg/CourseMapper"
private const val COFFEE_URL = "https://buymeacoffee.com/jkaberg"

/** Buy Me a Coffee yellow, fixed in both themes since it's their brand. */
private val CoffeeYellow = Color(0xFFFFDD00)
private val CoffeeInk    = Color(0xFF000000)

/**
 * About dialog: version, source and a tip jar.
 *
 * [gateSeconds] is only set on first showing and keeps it open that long, with
 * back and outside tap disabled too. It never closes by itself.
 */
@Composable
fun AboutDialog(
    onDismiss: () -> Unit,
    gateSeconds: Int? = null
) {
    val uriHandler = LocalUriHandler.current

    // one animation drives the fill, the seconds and the gate, so they can't disagree
    val level = remember { Animatable(1f) }
    LaunchedEffect(gateSeconds) {
        if (gateSeconds != null) {
            level.animateTo(
                targetValue   = 0f,
                animationSpec = tween(gateSeconds * 1_000, easing = LinearEasing)
            )
        }
    }
    val gated = gateSeconds != null && level.value > 0f

    AlertDialog(
        onDismissRequest = { if (!gated) onDismiss() },
        properties = DialogProperties(
            dismissOnBackPress    = !gated,
            dismissOnClickOutside = !gated
        ),
        // "CourseMapper v1.2.3".  VERSION_NAME is whatever tag the release
        // workflow built from, so the card names its own build without a second
        // source of truth to keep in step.
        title = { Text("CourseMapper v${BuildConfig.VERSION_NAME}") },
        text  = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
                Text(
                    text  = "Plan a course, then plant every kilometre marker in one pass.",
                    style = MaterialTheme.typography.bodyMedium
                )

                OutlinedButton(
                    onClick  = { uriHandler.openUri(REPO_URL) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(
                        Icons.Default.Code,
                        contentDescription = null,
                        modifier = Modifier.size(IconSize.md)
                    )
                    Spacer(Modifier.width(Spacing.sm))
                    Text("Source code on GitHub")
                }

                Button(
                    onClick  = { uriHandler.openUri(COFFEE_URL) },
                    colors   = ButtonDefaults.buttonColors(
                        containerColor = CoffeeYellow,
                        contentColor   = CoffeeInk
                    ),
                    shape    = RoundedCornerShape(Spacing.md),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(
                        Icons.Default.LocalCafe,
                        contentDescription = null,
                        modifier = Modifier.size(IconSize.md)
                    )
                    Spacer(Modifier.width(Spacing.sm))
                    Text("Buy me a coffee", style = MaterialTheme.typography.titleSmall)
                }

                Text(
                    text      = "Made with ❤️ from Norway by jkaberg",
                    style     = MaterialTheme.typography.bodySmall,
                    color     = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier  = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center
                )
            }
        },
        // full width box so the single button is centred
        confirmButton = {
            Box(
                modifier         = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                if (gateSeconds == null) {
                    TextButton(onClick = onDismiss) { Text("Close") }
                } else {
                    DrainingDismissButton(
                        level        = level.value,
                        totalSeconds = gateSeconds,
                        onClick      = onDismiss
                    )
                }
            }
        }
    )
}

/**
 * Dismiss button that drains like a bottle and works once it's empty. Shows the
 * seconds too, a disabled button without a reason looks broken. Becomes a
 * normal filled button when ready.
 */
@Composable
private fun DrainingDismissButton(
    level: Float,
    totalSeconds: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val ready     = level <= 0f
    val remaining = ceil(level * totalSeconds).toInt().coerceAtLeast(1)

    val shape  = RoundedCornerShape(Spacing.md)
    val track  = MaterialTheme.colorScheme.surfaceVariant
    val liquid = MaterialTheme.colorScheme.primary.copy(alpha = 0.30f)

    Button(
        onClick   = onClick,
        enabled   = ready,
        shape     = shape,
        elevation = null,
        colors    = ButtonDefaults.buttonColors(
            containerColor         = MaterialTheme.colorScheme.primary,
            contentColor           = MaterialTheme.colorScheme.onPrimary,
            // Transparent while gated so the track and the liquid below show
            // through; the Button would otherwise paint its own container over
            // both.
            disabledContainerColor = Color.Transparent,
            disabledContentColor   = MaterialTheme.colorScheme.onSurfaceVariant
        ),
        modifier = modifier
            .clip(shape)
            .then(
                if (ready) {
                    Modifier
                } else {
                    Modifier
                        .background(track)
                        .drawBehind {
                            val fillHeight = size.height * level
                            drawRect(
                                color    = liquid,
                                topLeft  = Offset(0f, size.height - fillHeight),
                                size     = Size(size.width, fillHeight)
                            )
                        }
                }
            )
    ) {
        Text(if (ready) "Dismiss" else "Dismiss ($remaining)")
    }
}
