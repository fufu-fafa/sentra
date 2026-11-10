package com.sentra.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** The design tokens, one to one with the web theme's CSS variables. */
object Tokens {
    val Bg = Color(0xFF0D1117)
    val Card = Color(0xFF161B22)
    val Text = Color(0xFFE6EDF3)
    val Muted = Color(0xFF8D96A0)
    val Border = Color(0xFF30363D)
    val Accent = Color(0xFF4F8CFF)
    val AccentText = Color(0xFFFFFFFF)
    val Correct = Color(0xFF3FB950)
    val CorrectBg = Color(0xFF12261E)
    val Wrong = Color(0xFFF85149)
    val WrongBg = Color(0xFF2D1416)
    val CodeBg = Color(0xFF0D1117)
    val Warn = Color(0xFFD29922)
    val WarnBg = Color(0xFF2B2111)
}

/** Tokens Material's color scheme has no slot for. */
@Immutable
data class ExtraColors(
    val muted: Color = Tokens.Muted,
    val border: Color = Tokens.Border,
    val good: Color = Tokens.Correct,
    val goodBg: Color = Tokens.CorrectBg,
    val bad: Color = Tokens.Wrong,
    val badBg: Color = Tokens.WrongBg,
    val codeBg: Color = Tokens.CodeBg,
)

val LocalExtraColors = staticCompositionLocalOf { ExtraColors() }

private val scheme = darkColorScheme(
    primary = Tokens.Accent,
    onPrimary = Tokens.AccentText,
    primaryContainer = Tokens.Accent,
    onPrimaryContainer = Tokens.AccentText,
    secondary = Tokens.Accent,
    onSecondary = Tokens.AccentText,
    secondaryContainer = Tokens.Border,
    onSecondaryContainer = Tokens.Text,
    tertiary = Tokens.Correct,
    background = Tokens.Bg,
    onBackground = Tokens.Text,
    surface = Tokens.Bg,
    onSurface = Tokens.Text,
    surfaceVariant = Tokens.Card,
    onSurfaceVariant = Tokens.Muted,
    surfaceContainerLowest = Tokens.Bg,
    surfaceContainerLow = Tokens.Card,
    surfaceContainer = Tokens.Card,
    surfaceContainerHigh = Tokens.Card,
    surfaceContainerHighest = Tokens.Border,
    outline = Tokens.Border,
    outlineVariant = Tokens.Border,
    error = Tokens.Wrong,
    onError = Tokens.AccentText,
    errorContainer = Tokens.WrongBg,
    onErrorContainer = Tokens.Wrong,
)

val Mono = FontFamily.Monospace

private val typography = Typography().run {
    copy(
        headlineMedium = headlineMedium.copy(fontWeight = FontWeight.SemiBold),
        titleLarge = titleLarge.copy(fontWeight = FontWeight.SemiBold),
        titleMedium = titleMedium.copy(fontWeight = FontWeight.SemiBold),
        labelSmall = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.8.sp),
    )
}

@Composable
fun SentraTheme(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalExtraColors provides ExtraColors()) {
        MaterialTheme(colorScheme = scheme, typography = typography, content = content)
    }
}

object AppTheme {
    val extra: ExtraColors @Composable get() = LocalExtraColors.current
}
