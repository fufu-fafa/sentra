package com.sentra.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Blind
import androidx.compose.material.icons.rounded.FamilyRestroom
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sentra.ui.theme.AppTheme
import com.sentra.ui.theme.Tokens

private val CardShape = RoundedCornerShape(20.dp)

/** White rounded card with a soft lavender shadow. Clickable when [onClick] is given. */
@Composable
fun Card(
    modifier: Modifier = Modifier,
    background: Color = Tokens.Card,
    border: Color = AppTheme.extra.border,
    onClick: (() -> Unit)? = null,
    onClickLabel: String? = null,
    padding: Dp = 16.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .shadow(6.dp, CardShape, ambientColor = Tokens.Accent.copy(alpha = 0.12f), spotColor = Tokens.Accent.copy(alpha = 0.18f))
            .clip(CardShape)
            .then(if (onClick != null) Modifier.clickable(onClickLabel = onClickLabel, onClick = onClick) else Modifier),
        shape = CardShape,
        color = background,
        border = BorderStroke(1.dp, border),
    ) {
        Column(Modifier.padding(padding), content = content)
    }
}

/** A small heading inside a card, optionally with a pin-style icon in front. */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier, icon: ImageVector? = null, tint: Color = Tokens.Accent) {
    Row(modifier.padding(bottom = 8.dp).semantics(mergeDescendants = true) { heading() }, verticalAlignment = Alignment.CenterVertically) {
        if (icon != null) {
            Icon(icon, null, tint = tint, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
        }
        Text(text, style = MaterialTheme.typography.titleSmall, color = Tokens.Text)
    }
}

/** Small rounded label, e.g. "Tersambung" or "Keluar rute". */
@Composable
fun Pill(text: String, fg: Color, bg: Color, modifier: Modifier = Modifier, dot: Boolean = false) {
    Row(
        modifier
            .background(bg, RoundedCornerShape(50))
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (dot) {
            Box(Modifier.size(8.dp).background(fg, CircleShape))
            Spacer(Modifier.width(6.dp))
        }
        Text(text, color = fg, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
    }
}

/** A coloured dot and a word, like "● Tersambung". */
@Composable
fun StatusDot(text: String, color: Color, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).background(color, CircleShape))
        Spacer(Modifier.width(6.dp))
        Text(text, color = color, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
    }
}

/** Big bold title of a tab. */
@Composable
fun ScreenTitle(title: String, subtitle: String? = null, action: (@Composable () -> Unit)? = null) {
    Row(
        Modifier.statusBarsPadding().fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 16.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.headlineMedium, color = Tokens.Text, modifier = Modifier.semantics { heading() })
            if (subtitle != null) Text(subtitle, color = AppTheme.extra.muted, style = MaterialTheme.typography.bodyMedium)
        }
        action?.invoke()
    }
}

/** A sub-page's bar: back arrow, centred title, and an optional action on the right. */
@Composable
fun TopBar(title: String, onBack: (() -> Unit)?, action: (@Composable () -> Unit)? = null) {
    Box(Modifier.statusBarsPadding().fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 4.dp)) {
        if (onBack != null) {
            IconButton(onClick = onBack, modifier = Modifier.align(Alignment.CenterStart)) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Kembali", tint = Tokens.Text)
            }
        }
        Text(
            title,
            style = MaterialTheme.typography.titleLarge,
            color = Tokens.Text,
            modifier = Modifier.align(Alignment.Center).semantics { heading() },
        )
        Box(Modifier.align(Alignment.CenterEnd)) { action?.invoke() }
    }
}

/** The big rounded gradient button: Mulai Navigasi, Mulai Perjalanan, Selesai. */
@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    chevron: Boolean = false,
    color: Color = Tokens.Accent,
    height: Dp = 56.dp,
) {
    val shape = RoundedCornerShape(50)
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = height)
            .dropShadow(shape, Shadow(radius = 12.dp, color = color.copy(alpha = if (enabled) .28f else 0f), offset = DpOffset(0.dp, 4.dp)))
            .clip(shape)
            .background(color, shape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 22.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        // Disabled is shown by dimming the content, not with Modifier.alpha: switching that layer from
        // 0.45 to 1 made the fill vanish inside a Card (white text on white, seen on a Galaxy S21 FE).
        val content = if (enabled) Tokens.AccentText else Tokens.AccentText.copy(alpha = 0.55f)
        if (icon != null) {
            Icon(icon, null, tint = content, modifier = Modifier.size(24.dp))
            Spacer(Modifier.width(12.dp))
        }
        Text(
            text,
            color = content,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            modifier = if (chevron) Modifier.weight(1f) else Modifier,
        )
        if (chevron) Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = content)
    }
}

/** A quieter rounded button: lavender fill, purple text. */
@Composable
fun SoftButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    color: Color = Tokens.Accent,
    background: Color = Tokens.AccentSoft,
) {
    val shape = RoundedCornerShape(50)
    Row(
        modifier
            .heightIn(min = 48.dp)
            .clip(shape)
            .background(if (enabled) background else background.copy(alpha = background.alpha * 0.5f), shape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        // Dimmed colours rather than Modifier.alpha, for the same reason as PrimaryButton.
        val content = if (enabled) color else color.copy(alpha = 0.45f)
        if (icon != null) {
            Icon(icon, null, tint = content, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(text, color = content, style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center)
    }
}

/** An icon on a soft round background. */
@Composable
fun IconBubble(icon: ImageVector, tint: Color, background: Color, size: Dp = 44.dp, iconSize: Dp = size * 0.55f) {
    Box(Modifier.size(size).background(background, CircleShape), contentAlignment = Alignment.Center) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(iconSize))
    }
}

/** One tappable line in a list: icon, title, subtitle, chevron. */
@Composable
fun ListRow(
    title: String,
    subtitle: String? = null,
    icon: ImageVector? = null,
    tint: Color = Tokens.Pink,
    iconBackground: Color = Color.Transparent,
    selected: Boolean = false,
    chevron: Boolean = true,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    Card(
        onClick = onClick,
        padding = 14.dp,
        background = if (selected) Tokens.AccentSoft else Tokens.Card,
        border = if (selected) Tokens.Accent else Tokens.Border,
    ) {
        Row(Modifier.heightIn(min = 28.dp), verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) {
                if (iconBackground == Color.Transparent) Icon(icon, null, tint = tint, modifier = Modifier.size(26.dp))
                else IconBubble(icon, tint, iconBackground, 40.dp)
                Spacer(Modifier.width(12.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall, color = Tokens.Text)
                if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = AppTheme.extra.muted)
            }
            trailing?.invoke(this)
            if (chevron && onClick != null) Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = AppTheme.extra.muted)
        }
    }
}

/** The lavender wash at the top of every screen. */
@Composable
fun PageBackground(modifier: Modifier = Modifier, top: Color = Tokens.BgTop, content: @Composable BoxScope.() -> Unit) {
    Box(
        modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(0f to top, 0.35f to Tokens.Bg, 1f to Tokens.Bg)),
        content = content,
    )
}

/** Bottom bar with text under every icon, so nothing depends on recognising an icon (§9.7). */
@Composable
fun BottomTabs(tabs: List<Triple<String, ImageVector, Boolean>>, accent: Color = Tokens.Pink, onSelect: (Int) -> Unit) {
    NavigationBar(containerColor = Tokens.Card, tonalElevation = 0.dp, modifier = Modifier.shadow(12.dp, ambientColor = Tokens.Accent.copy(alpha = .2f))) {
        tabs.forEachIndexed { i, (label, icon, selected) ->
            NavigationBarItem(
                selected = selected,
                onClick = { onSelect(i) },
                icon = { Icon(icon, contentDescription = null) },
                label = { Text(label, fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium) },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = accent,
                    selectedTextColor = accent,
                    indicatorColor = if (accent == Tokens.Pink) Tokens.PinkSoft else Tokens.AccentSoft,
                    unselectedIconColor = Tokens.Muted,
                    unselectedTextColor = Tokens.Muted,
                ),
            )
        }
    }
}

// --- Brand ---------------------------------------------------------------------------------------

/** Four-pointed sparkle, as on the logo. */
private fun DrawScope.sparkle(center: Offset, r: Float, color: Color) {
    val p = Path().apply {
        moveTo(center.x, center.y - r)
        quadraticTo(center.x, center.y, center.x + r, center.y)
        quadraticTo(center.x, center.y, center.x, center.y + r)
        quadraticTo(center.x, center.y, center.x - r, center.y)
        quadraticTo(center.x, center.y, center.x, center.y - r)
        close()
    }
    drawPath(p, color)
}

/** The SENTRA mark: a walker with a cane inside a ring, with orbit swooshes and sparkles. */
@Composable
fun SentraMark(size: Dp, care: Boolean = false) {
    val main = if (care) Tokens.Pink else Tokens.Accent
    val light = if (care) Tokens.PinkLight else Tokens.AccentLight
    Box(Modifier.size(size).clearAndSetSemantics {}, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val w = this.size.width
            val c = Offset(w / 2, w / 2)
            if (care) {
                // A heart outline around the family.
                val h = Path().apply {
                    moveTo(w * .5f, w * .86f)
                    cubicTo(w * .08f, w * .58f, w * .08f, w * .16f, w * .32f, w * .16f)
                    cubicTo(w * .42f, w * .16f, w * .48f, w * .22f, w * .5f, w * .3f)
                    cubicTo(w * .52f, w * .22f, w * .58f, w * .16f, w * .68f, w * .16f)
                    cubicTo(w * .92f, w * .16f, w * .92f, w * .58f, w * .5f, w * .86f)
                    close()
                }
                drawPath(h, Brush.verticalGradient(listOf(light, main)), style = Stroke(w * .07f, cap = StrokeCap.Round))
            } else {
                drawCircle(Brush.verticalGradient(listOf(light.copy(alpha = .35f), main.copy(alpha = .08f))), w * .36f, c)
                drawCircle(Brush.verticalGradient(listOf(light, main)), w * .36f, c, style = Stroke(w * .06f))
                // Orbit swooshes left and right.
                drawArc(light, 150f, 60f, false, Offset(w * .04f, w * .2f), Size(w * .92f, w * .7f), style = Stroke(w * .035f, cap = StrokeCap.Round))
                drawArc(light, -30f, 60f, false, Offset(w * .04f, w * .2f), Size(w * .92f, w * .7f), style = Stroke(w * .035f, cap = StrokeCap.Round))
            }
            sparkle(Offset(w * .14f, w * .2f), w * .07f, Tokens.Sun)
            sparkle(Offset(w * .86f, w * .14f), w * .045f, light)
        }
        Icon(
            if (care) Icons.Rounded.FamilyRestroom else Icons.Rounded.Blind,
            null,
            tint = if (care) main else Tokens.Brand,
            modifier = Modifier.size(size * (if (care) 0.36f else 0.46f)).padding(top = if (care) size * 0.02f else 0.dp),
        )
    }
}

/** Logo, word mark and tagline, as on the splash and the home screen. */
@Composable
fun SentraLogo(markSize: Dp = 120.dp, care: Boolean = false, tagline: Boolean = true) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.semantics(mergeDescendants = true) {
        contentDescription = if (care) "SENTRA Care" else "SENTRA"
        heading()
    }) {
        SentraMark(markSize, care)
        Text(
            "SENTRA",
            style = TextStyle(
                brush = if (care) Tokens.CareText else Tokens.BrandText,
                fontSize = (markSize.value * 0.36f).coerceIn(30f, 52f).sp,
                fontWeight = FontWeight.Black,
                letterSpacing = 1.sp,
            ),
            modifier = Modifier.clearAndSetSemantics {},
        )
        if (care) {
            Text(
                "Care",
                style = TextStyle(brush = Tokens.CareText, fontSize = (markSize.value * 0.32f).coerceIn(28f, 46f).sp, fontWeight = FontWeight.Black),
                modifier = Modifier.clearAndSetSemantics {}.padding(bottom = 4.dp),
            )
        }
        if (tagline) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (care) "Selalu di sisi mereka" else "Navigasi dengan Percaya Diri",
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (care) Tokens.Pink else Tokens.Brand,
                    fontWeight = FontWeight.Medium,
                )
                Spacer(Modifier.width(4.dp))
                Icon(if (care) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder, null, tint = if (care) Tokens.Pink else Tokens.Brand, modifier = Modifier.size(16.dp))
            }
        }
    }
}

/** A rounded round avatar with the child's initial. */
@Composable
fun Avatar(name: String, size: Dp = 52.dp) {
    Box(
        Modifier.size(size).background(Brush.verticalGradient(listOf(Tokens.PinkSoft, Color(0xFFF9CFE1))), CircleShape)
            .border(2.dp, Tokens.Card, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            name.trim().firstOrNull()?.uppercase() ?: "?",
            color = Tokens.Pink,
            fontWeight = FontWeight.Black,
            fontSize = (size.value * 0.42f).sp,
        )
    }
}
