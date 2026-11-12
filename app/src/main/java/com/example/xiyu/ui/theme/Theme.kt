package com.example.xiyu.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val LightColorScheme = lightColorScheme(
    primary = BrandDark,
    onPrimary = BrandCream,
    primaryContainer = BrandPalePink,
    onPrimaryContainer = BrandDark,
    secondary = BrandRose,
    onSecondary = BrandCream,
    secondaryContainer = BrandSoftRose,
    onSecondaryContainer = BrandDark,
    tertiary = BrandAccent,
    background = BrandCream,
    onBackground = BrandTextDark,
    surface = BrandCardBg,
    onSurface = BrandTextDark,
    surfaceVariant = BrandCream,
    onSurfaceVariant = BrandTextMuted,
    outline = BrandBorder
)

@Composable
fun XIYUUploaderTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = LightColorScheme
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = colorScheme.background.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = true
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
