package com.example.xiyu.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Density
import androidx.core.view.WindowCompat
import com.example.xiyu.data.model.ThemeMode
import com.example.xiyu.data.model.ThemeSettingsEntity

data class KruzenPalette(
    val cream: Color = BrandCream,
    val cardBg: Color = BrandCardBg,
    val dark: Color = BrandDark,
    val darkVariant: Color = BrandDarkVariant,
    val rose: Color = BrandRose,
    val softRose: Color = BrandSoftRose,
    val palePink: Color = BrandPalePink,
    val textDark: Color = BrandTextDark,
    val textMuted: Color = BrandTextMuted,
    val accent: Color = BrandAccent,
    val border: Color = BrandBorder,
    val success: Color = BrandSuccess,
    val error: Color = BrandError,
    val isDark: Boolean = false
)

val DefaultKruzenPalette = KruzenPalette()

val LocalKruzenPalette = staticCompositionLocalOf { DefaultKruzenPalette }

fun parseHexColorOrNull(hex: String): Color? {
    val clean = hex.trim().removePrefix("#")
    if (clean.length != 6 && clean.length != 8) return null
    return runCatching {
        val colorLong = clean.toLong(16)
        val argb = if (clean.length == 6) (0xFF000000L or colorLong) else colorLong
        Color(argb.toInt())
    }.getOrNull()
}

fun buildKruzenPalette(
    settings: ThemeSettingsEntity,
    systemDark: Boolean
): KruzenPalette {
    val useDark = when (settings.themeMode) {
        ThemeMode.DEFAULT_BRAND -> false
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
        ThemeMode.SYSTEM -> systemDark
    }

    val base = when {
        settings.themeMode == ThemeMode.DEFAULT_BRAND -> DefaultKruzenPalette
        useDark -> KruzenPalette(
            cream = Color(0xFF151314),
            cardBg = Color(0xFF211D1F),
            dark = Color(0xFFE7D3D4),
            darkVariant = Color(0xFF312A2D),
            rose = Color(0xFFD5A1A4),
            softRose = Color(0xFF3E2F32),
            palePink = Color(0xFF2D2426),
            textDark = Color(0xFFF6EFEA),
            textMuted = Color(0xFFB5A4A4),
            accent = Color(0xFFD9BA78),
            border = Color(0xFF3E3436),
            success = Color(0xFF66BB6A),
            error = Color(0xFFEF5350),
            isDark = true
        )
        else -> KruzenPalette(
            cream = Color(0xFFF7F7FA),
            cardBg = Color(0xFFFFFFFF),
            dark = Color(0xFF1F2430),
            darkVariant = Color(0xFF343B4D),
            rose = Color(0xFF5D6B82),
            softRose = Color(0xFFE4E8F0),
            palePink = Color(0xFFEDF1F7),
            textDark = Color(0xFF1A1D24),
            textMuted = Color(0xFF667085),
            accent = Color(0xFFB88A44),
            border = Color(0xFFDDE2EB),
            success = BrandSuccess,
            error = BrandError,
            isDark = false
        )
    }

    val primaryOverride = parseHexColorOrNull(settings.customPrimaryHex)
    val secondaryOverride = parseHexColorOrNull(settings.customSecondaryHex)
    val accentOverride = parseHexColorOrNull(settings.customAccentHex)
    val textOverride = parseHexColorOrNull(settings.customTextHex)

    return base.copy(
        dark = primaryOverride ?: base.dark,
        rose = secondaryOverride ?: base.rose,
        accent = accentOverride ?: base.accent,
        textDark = textOverride ?: base.textDark
    )
}

private fun scaledTypography(scale: Float, textColor: Color, mutedColor: Color): Typography {
    val s = scale.coerceIn(0.8f, 1.4f)
    return Typography(
        headlineLarge = Typography.headlineLarge.copy(
            fontSize = Typography.headlineLarge.fontSize * s,
            lineHeight = Typography.headlineLarge.lineHeight * s,
            color = textColor
        ),
        headlineMedium = Typography.headlineMedium.copy(
            fontSize = Typography.headlineMedium.fontSize * s,
            lineHeight = Typography.headlineMedium.lineHeight * s,
            color = textColor
        ),
        headlineSmall = Typography.headlineSmall.copy(
            fontSize = Typography.headlineSmall.fontSize * s,
            lineHeight = Typography.headlineSmall.lineHeight * s,
            color = textColor
        ),
        titleLarge = Typography.titleLarge.copy(
            fontSize = Typography.titleLarge.fontSize * s,
            lineHeight = Typography.titleLarge.lineHeight * s,
            color = textColor
        ),
        titleMedium = Typography.titleMedium.copy(
            fontSize = Typography.titleMedium.fontSize * s,
            lineHeight = Typography.titleMedium.lineHeight * s,
            color = textColor
        ),
        bodyLarge = Typography.bodyLarge.copy(
            fontSize = Typography.bodyLarge.fontSize * s,
            lineHeight = Typography.bodyLarge.lineHeight * s,
            color = textColor
        ),
        bodyMedium = Typography.bodyMedium.copy(
            fontSize = Typography.bodyMedium.fontSize * s,
            lineHeight = Typography.bodyMedium.lineHeight * s,
            color = mutedColor
        ),
        bodySmall = Typography.bodySmall.copy(
            fontSize = Typography.bodySmall.fontSize * s,
            lineHeight = Typography.bodySmall.lineHeight * s,
            color = mutedColor
        ),
        labelLarge = Typography.labelLarge.copy(
            fontSize = Typography.labelLarge.fontSize * s,
            lineHeight = Typography.labelLarge.lineHeight * s
        ),
        labelSmall = Typography.labelSmall.copy(
            fontSize = Typography.labelSmall.fontSize * s
        )
    )
}

@Composable
fun XIYUUploaderTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    themeSettings: ThemeSettingsEntity = ThemeSettingsEntity(),
    content: @Composable () -> Unit
) {
    val palette = buildKruzenPalette(themeSettings, darkTheme)
    val colorScheme = if (palette.isDark) {
        darkColorScheme(
            primary = palette.dark,
            onPrimary = Color(0xFF1A1517),
            primaryContainer = palette.palePink,
            onPrimaryContainer = palette.textDark,
            secondary = palette.rose,
            onSecondary = Color(0xFF1A1517),
            secondaryContainer = palette.softRose,
            onSecondaryContainer = palette.textDark,
            tertiary = palette.accent,
            background = palette.cream,
            onBackground = palette.textDark,
            surface = palette.cardBg,
            onSurface = palette.textDark,
            surfaceVariant = palette.cream,
            onSurfaceVariant = palette.textMuted,
            outline = palette.border
        )
    } else {
        lightColorScheme(
            primary = palette.dark,
            onPrimary = palette.cream,
            primaryContainer = palette.palePink,
            onPrimaryContainer = palette.dark,
            secondary = palette.rose,
            onSecondary = palette.cream,
            secondaryContainer = palette.softRose,
            onSecondaryContainer = palette.dark,
            tertiary = palette.accent,
            background = palette.cream,
            onBackground = palette.textDark,
            surface = palette.cardBg,
            onSurface = palette.textDark,
            surfaceVariant = palette.cream,
            onSurfaceVariant = palette.textMuted,
            outline = palette.border
        )
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = colorScheme.background.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !palette.isDark
        }
    }

    val currentDensity = LocalDensity.current
    val customDensity = Density(
        density = currentDensity.density,
        fontScale = (currentDensity.fontScale * themeSettings.fontScale).coerceIn(0.75f, 1.8f)
    )

    CompositionLocalProvider(
        LocalKruzenPalette provides palette,
        LocalDensity provides customDensity
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = scaledTypography(themeSettings.fontScale, palette.textDark, palette.textMuted),
            content = content
        )
    }
}
