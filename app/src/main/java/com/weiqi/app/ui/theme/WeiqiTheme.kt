package com.weiqi.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * 应用根主题。
 *
 * 根据 [darkTheme] 选择 Material3 浅色/深色 [ColorScheme]，
 * 颜色取自围棋棋盘的木质色调，保证与棋盘主题视觉协调。
 *
 * @param darkTheme 是否使用深色主题，默认跟随系统。
 * @param content 主题包裹的内容。
 */
@Composable
fun WeiqiTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme
    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography(),
        content = content
    )
}

/** 浅色配色：原木米黄主调。 */
private val LightColorScheme = lightColorScheme(
    primary = Color(0xFF6B5A3F),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFE8B964),
    onPrimaryContainer = Color(0xFF3A2E1C),
    secondary = Color(0xFFA89570),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFD9C28A),
    onSecondaryContainer = Color(0xFF3A2E1C),
    tertiary = Color(0xFF5A4A30),
    onTertiary = Color(0xFFFFFFFF),
    background = Color(0xFFFAF6EC),
    onBackground = Color(0xFF1B1408),
    surface = Color(0xFFFAF6EC),
    onSurface = Color(0xFF1B1408),
    surfaceVariant = Color(0xFFE0D6BE),
    onSurfaceVariant = Color(0xFF4A4030),
    error = Color(0xFFB3261E),
    onError = Color(0xFFFFFFFF),
    outline = Color(0xFF6B5A3F)
)

/** 深色配色：深褐底，浅金线条。 */
private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFFD4B876),
    onPrimary = Color(0xFF3A2E1C),
    primaryContainer = Color(0xFF3D3320),
    onPrimaryContainer = Color(0xFFE8B964),
    secondary = Color(0xFFA89570),
    onSecondary = Color(0xFF1B1408),
    secondaryContainer = Color(0xFF3A2E1C),
    onSecondaryContainer = Color(0xFFD9C28A),
    tertiary = Color(0xFFC8B074),
    onTertiary = Color(0xFF1B1408),
    background = Color(0xFF14100A),
    onBackground = Color(0xFFE6DEC8),
    surface = Color(0xFF14100A),
    onSurface = Color(0xFFE6DEC8),
    surfaceVariant = Color(0xFF3D3320),
    onSurfaceVariant = Color(0xFFD4C4A0),
    error = Color(0xFFF2B8B5),
    onError = Color(0xFF601410),
    outline = Color(0xFFA89570)
)
