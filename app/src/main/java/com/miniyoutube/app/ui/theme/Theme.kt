package com.miniyoutube.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * One dark palette. Video thumbnails read better on dark, and a single scheme keeps every
 * screen consistent without theme plumbing. Dynamic colour is not used: the accent is the
 * one thing that says "act on this", and the wallpaper should not get to repaint it.
 */
val Bg = Color(0xFF0F0F0F)
val Surface = Color(0xFF1A1A1A)
val SurfaceAlt = Color(0xFF242424)
val Border = Color(0xFF303030)
val TextPrimary = Color(0xFFF1F1F1)
val TextMuted = Color(0xFFAAAAAA)
val Accent = Color(0xFFFF4E45)
val Danger = Color(0xFFF26D6D)

private val Colors =
    darkColorScheme(
        primary = Accent,
        onPrimary = Color.White,
        secondary = Accent,
        onSecondary = Color.White,
        background = Bg,
        onBackground = TextPrimary,
        surface = Bg,
        onSurface = TextPrimary,
        surfaceVariant = SurfaceAlt,
        onSurfaceVariant = TextMuted,
        surfaceContainer = Surface,
        surfaceContainerHigh = SurfaceAlt,
        outline = Border,
        error = Danger,
        onError = Color.White,
    )

@Composable
fun MiniYouTubeTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Colors, content = content)
}
