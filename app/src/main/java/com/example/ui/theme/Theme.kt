package com.example.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density

private val PhantomCyberColorScheme = darkColorScheme(
    primary = NeonCyan,
    onPrimary = CyberBg,
    primaryContainer = CyberCardElevated,
    onPrimaryContainer = NeonCyan,
    secondary = EmeraldGreen,
    onSecondary = CyberBg,
    secondaryContainer = CyberCardElevated,
    onSecondaryContainer = EmeraldGreen,
    tertiary = CyberPurple,
    onTertiary = TextPrimary,
    background = CyberBg,
    onBackground = TextPrimary,
    surface = CyberCard,
    onSurface = TextPrimary,
    surfaceVariant = CyberCardElevated,
    onSurfaceVariant = TextSecondary,
    outline = CyberBorder,
    outlineVariant = CyberBorderGlow,
    error = RedDanger,
    onError = TextPrimary
)

@Composable
fun MyApplicationTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false, // Keep high-contrast custom cyber theme
    content: @Composable () -> Unit
) {
    val colorScheme = PhantomCyberColorScheme
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = CyberBg.toArgb()
            window.navigationBarColor = CyberBgDarker.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = false
            WindowCompat.getInsetsController(window, view).isAppearanceLightNavigationBars = false
        }
    }

    val baseDensity = LocalDensity.current
    val configuration = LocalConfiguration.current
    val targetMinWidthDp = 440f
    val currentWidthDp = configuration.screenWidthDp.toFloat().coerceAtLeast(1f)
    val adaptiveScale = if (currentWidthDp < targetMinWidthDp) {
        currentWidthDp / targetMinWidthDp
    } else {
        1.0f
    }
    val adaptiveDensity = Density(
        density = baseDensity.density * adaptiveScale,
        fontScale = 1.0f // Always normalize font scale to 1.0f so oversized system font scale doesn't wrap lines
    )

    CompositionLocalProvider(LocalDensity provides adaptiveDensity) {
        ProvideCyberShimmer {
            MaterialTheme(
                colorScheme = colorScheme,
                typography = Typography,
                content = content
            )
        }
    }
}
