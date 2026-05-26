package com.max.agent.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * Supervillain palette pushed into Material 3 tokens so dialogs, menus,
 * snackbars, and text fields inherit the same dark/gloomy mood as the
 * Compose surfaces. We force dark theme — there is no light mode.
 */
private val SupervillainScheme = darkColorScheme(
    primary             = Color(0xFF39FF14),   // toxic neon green
    onPrimary           = Color(0xFF000000),
    primaryContainer    = Color(0xFF0A2E00),
    onPrimaryContainer  = Color(0xFF39FF14),

    secondary           = Color(0xFF9D00FF),   // venom purple
    onSecondary         = Color(0xFF000000),
    secondaryContainer  = Color(0xFF2A0040),
    onSecondaryContainer= Color(0xFFC78AFF),

    tertiary            = Color(0xFFFF6B00),   // hazard orange
    onTertiary          = Color(0xFF000000),
    tertiaryContainer   = Color(0xFF3A1700),
    onTertiaryContainer = Color(0xFFFFB78A),

    error               = Color(0xFF8B0000),   // blood red
    onError             = Color(0xFFFFD4D4),
    errorContainer      = Color(0xFF4A0000),
    onErrorContainer    = Color(0xFFFF8A8A),

    background          = Color(0xFF000000),
    onBackground        = Color(0xFFC8C8C8),
    surface             = Color(0xFF0A080C),   // slight purple-tinted black
    onSurface           = Color(0xFFC8C8C8),
    surfaceVariant      = Color(0xFF1A141F),
    onSurfaceVariant    = Color(0xFF8A8A8A),
    outline             = Color(0xFF2A2A2A),
    outlineVariant      = Color(0xFF1A141F),
)

@Composable
fun MaxTheme(
    @Suppress("UNUSED_PARAMETER") darkTheme: Boolean = true,
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = SupervillainScheme,
        content = content
    )
}
