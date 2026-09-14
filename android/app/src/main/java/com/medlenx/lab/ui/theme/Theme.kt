package com.medlenx.lab.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

/**
 * Light theme only.
 *
 * The web app deliberately removed dark mode (`<html class="light">`, zero `dark:`
 * classes), so the Android app ships light-only to stay identical. A dark scheme is
 * declared for completeness but is never selected.
 */
private val LightColors = lightColorScheme(
    primary = Mlx.Brand600,
    onPrimary = Color.White,
    primaryContainer = Mlx.Brand900,
    onPrimaryContainer = Color.White,
    secondary = Mlx.Brand700,
    onSecondary = Color.White,
    secondaryContainer = Mlx.Accent100,
    onSecondaryContainer = Mlx.BlueText,
    tertiary = Mlx.Violet,
    onTertiary = Color.White,
    background = Mlx.Screen,
    onBackground = Mlx.Text900,
    surface = Mlx.Surface,
    onSurface = Mlx.Text900,
    surfaceVariant = Mlx.Brand100,
    onSurfaceVariant = Mlx.Text600,
    outline = Mlx.Brand200,
    outlineVariant = Mlx.Brand100,
    error = Mlx.Danger,
    onError = Color.White,
    errorContainer = Mlx.DangerBg,
    onErrorContainer = Mlx.DangerText,
)

private val DarkColors = darkColorScheme(
    primary = Mlx.Brand500,
    onPrimary = Color.White,
    background = Mlx.Brand900,
    onBackground = Color.White,
    surface = Mlx.Brand800,
    onSurface = Color.White,
)

@Composable
fun MedLenXTheme(
    // Intentionally ignored: the product is light-only. Kept in the signature so a
    // future dark theme is a one-line change rather than a refactor.
    @Suppress("UNUSED_PARAMETER") darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            // Status bar sits on the dark brand colour; nav bar is light.
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = false
                isAppearanceLightNavigationBars = true
            }
        }
    }

    MaterialTheme(
        colorScheme = LightColors,
        typography = MlxTypography,
        content = content,
    )
}

/** Unused until a dark theme is commissioned. */
@Suppress("unused")
private val unusedDarkScheme = DarkColors
