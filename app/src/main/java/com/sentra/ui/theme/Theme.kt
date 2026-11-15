package com.sentra.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * The design tokens of the SENTRA look: lavender background, white cards, purple for SENTRA and
 * pink for SENTRA Care. Text colours keep 4.5:1 or more against white and the background.
 */
object Tokens {
    val Bg = Color(0xFFF8F3FF)
    val BgTop = Color(0xFFEEE3FF)
    val Card = Color(0xFFFFFFFF)
    val Text = Color(0xFF2B2350)
    val Muted = Color(0xFF6B6489)
    val Border = Color(0xFFE9DFFA)
    val Accent = Color(0xFF7653D3)
    val AccentLight = Color(0xFF9479E6)
    val AccentSoft = Color(0xFFEFE8FF)
    val AccentText = Color(0xFFFFFFFF)
    val Brand = Color(0xFF3B2C8C)
    val Pink = Color(0xFFD63F80)
    val PinkLight = Color(0xFFEE6FA3)
    val PinkSoft = Color(0xFFFDE7F0)
    val Blue = Color(0xFF2F74E8)
    val BlueSoft = Color(0xFFE6EFFF)
    val Correct = Color(0xFF16875A)
    val CorrectBg = Color(0xFFE2F5EC)
    val Wrong = Color(0xFFCF2E52)
    val WrongBg = Color(0xFFFDE8ED)
    val CodeBg = Color(0xFFF3EEFC)
    val Warn = Color(0xFFA35F00)
    val WarnBg = Color(0xFFFFF2DA)
    val Sun = Color(0xFFF6C445)

    val AccentGradient = Brush.horizontalGradient(listOf(AccentLight, Accent))
    val PinkGradient = Brush.horizontalGradient(listOf(PinkLight, Pink))
    val BrandText = Brush.verticalGradient(listOf(Color(0xFF6A52D6), Brand))
    val CareText = Brush.verticalGradient(listOf(Color(0xFFE85C98), Color(0xFFB8306D)))
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

private val scheme = lightColorScheme(
    primary = Tokens.Accent,
    onPrimary = Tokens.AccentText,
    primaryContainer = Tokens.AccentSoft,
    onPrimaryContainer = Tokens.Brand,
    secondary = Tokens.Pink,
    onSecondary = Tokens.AccentText,
    secondaryContainer = Tokens.PinkSoft,
    onSecondaryContainer = Tokens.Pink,
    tertiary = Tokens.Correct,
    background = Tokens.Bg,
    onBackground = Tokens.Text,
    surface = Tokens.Card,
    onSurface = Tokens.Text,
    surfaceVariant = Tokens.AccentSoft,
    onSurfaceVariant = Tokens.Muted,
    surfaceContainerLowest = Tokens.Card,
    surfaceContainerLow = Tokens.Card,
    surfaceContainer = Tokens.Card,
    surfaceContainerHigh = Tokens.Card,
    surfaceContainerHighest = Tokens.AccentSoft,
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
        displaySmall = displaySmall.copy(fontWeight = FontWeight.ExtraBold),
        headlineLarge = headlineLarge.copy(fontWeight = FontWeight.Bold),
        headlineMedium = headlineMedium.copy(fontWeight = FontWeight.Bold),
        headlineSmall = headlineSmall.copy(fontWeight = FontWeight.Bold),
        titleLarge = titleLarge.copy(fontWeight = FontWeight.Bold),
        titleMedium = titleMedium.copy(fontWeight = FontWeight.SemiBold),
        titleSmall = titleSmall.copy(fontWeight = FontWeight.SemiBold),
        labelLarge = labelLarge.copy(fontWeight = FontWeight.SemiBold, fontSize = 15.sp),
        labelSmall = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.6.sp),
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
