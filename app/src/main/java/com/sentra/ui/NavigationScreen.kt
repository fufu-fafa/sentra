package com.sentra.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Flag
import androidx.compose.material.icons.rounded.MyLocation
import androidx.compose.material.icons.rounded.Route
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Straight
import androidx.compose.material.icons.rounded.TurnLeft
import androidx.compose.material.icons.rounded.TurnRight
import androidx.compose.material.icons.rounded.UTurnLeft
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sentra.data.RouteEntity
import com.sentra.nav.Geo
import com.sentra.nav.NavSnapshot
import com.sentra.nav.NavState
import com.sentra.nav.Turn
import com.sentra.ui.theme.AppTheme
import com.sentra.ui.theme.Tokens
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.roundToInt

data class Instruction(val icon: ImageVector, val title: String, val subtitle: String)

/** Walking pace for the estimates; slower than an adult's, as the child walks with a cane. */
const val WALK_SPEED_MS = 0.9

/** "350 m" or "1,8 km". */
fun distanceText(m: Double): String =
    if (m < 1000) "${m.roundToInt()} m" else String.format(Locale.US, "%.1f km", m / 1000)

/** "~ 7 mnt" for [m] metres at [WALK_SPEED_MS]. */
fun etaText(m: Double): String {
    val min = ceil(m / WALK_SPEED_MS / 60).toInt().coerceAtLeast(1)
    return if (min < 60) "~ $min min" else "~ ${min / 60} h ${min % 60} min"
}

/** Metres still to walk: to the target point, then along the rest of the route. */
fun remainingM(nav: NavSnapshot, route: RouteEntity?): Double? {
    val d = nav.distanceM ?: return null
    val points = route?.geoPoints() ?: return d
    val from = (nav.targetNumber - 1).coerceIn(0, points.lastIndex)
    return d + points.drop(from).zipWithNext { a, b -> Geo.distanceM(a, b) }.sum()
}

/** What to do now, in the words of the mockups: "Turn left / now", "Straight / for 500 m". */
fun navInstruction(nav: NavSnapshot, route: RouteEntity?): Instruction {
    if (nav.state == NavState.OFF_ROUTE) return Instruction(Icons.Rounded.WarningAmber, "Off route", "Stop, the sleeve signals stop")
    val d = nav.distanceM ?: return Instruction(Icons.Rounded.MyLocation, "Waiting for GPS…", route?.name ?: "")
    val soon = d < 15
    val atDestination = nav.targetNumber == nav.totalPoints
    return when {
        atDestination -> Instruction(Icons.Rounded.Flag, if (soon) "Almost there" else "Straight to destination", "for ${distanceText(d)}")
        nav.turnAtTarget == Turn.LEFT -> Instruction(Icons.Rounded.TurnLeft, "Turn left", if (soon) "now" else "in ${distanceText(d)}")
        nav.turnAtTarget == Turn.RIGHT -> Instruction(Icons.Rounded.TurnRight, "Turn right", if (soon) "now" else "in ${distanceText(d)}")
        nav.turnAtTarget == Turn.U_TURN -> Instruction(Icons.Rounded.UTurnLeft, "Turn around", if (soon) "now" else "in ${distanceText(d)}")
        else -> Instruction(Icons.Rounded.Straight, "Straight", "for ${distanceText(d)}")
    }
}

/** Navigation: the instruction on top, the map, and time and distance left. Over everything during a trip. */
@Composable
fun NavigationScreen(vm: AppViewModel, onBack: () -> Unit) {
    val nav by vm.nav.collectAsState()
    val route by vm.navRoute.collectAsState()
    val fix by vm.fix.collectAsState()
    val settings by vm.settings.collectAsState()
    val detour by vm.detour.collectAsState()
    var follow by rememberSaveable { mutableStateOf(true) }
    val offRoute = nav.state == NavState.OFF_ROUTE
    val instruction = navInstruction(nav, route)

    PageBackground {
        Column(Modifier.fillMaxSize().navigationBarsPadding()) {
            TopBar("Navigation", onBack)
            Card(
                Modifier.padding(horizontal = 16.dp),
                background = if (offRoute) Tokens.WrongBg else Tokens.Card,
                border = if (offRoute) Tokens.Wrong else Tokens.Border,
            ) {
                Row(
                    Modifier.semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite; heading() },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(instruction.icon, null, tint = if (offRoute) Tokens.Wrong else Tokens.Blue, modifier = Modifier.size(64.dp))
                    Spacer(Modifier.width(14.dp))
                    Column {
                        Text(instruction.title, fontSize = 28.sp, fontWeight = FontWeight.Bold, color = if (offRoute) Tokens.Wrong else Tokens.Text, lineHeight = 32.sp)
                        Text(instruction.subtitle, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = if (offRoute) Tokens.Wrong else Tokens.Text)
                    }
                }
            }
            Box(Modifier.weight(1f).fillMaxWidth().padding(16.dp).clip(RoundedCornerShape(24.dp))) {
                OsmMap(
                    MapContent(marker = fix?.point, accuracyM = fix?.accuracyM, route = route?.geoPoints().orEmpty(), markerColor = if (offRoute) MapContent.RED else MapContent.BLUE),
                    Modifier.fillMaxSize(),
                    follow = follow,
                    onUserMoved = { follow = false },
                )
                if (!follow) {
                    SmallFloatingActionButton(
                        onClick = { follow = true },
                        containerColor = Tokens.Card, contentColor = Tokens.Blue,
                        modifier = Modifier.align(Alignment.BottomEnd).padding(12.dp),
                    ) { Icon(Icons.Rounded.MyLocation, contentDescription = "Go to my position") }
                }
            }
            Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Card(padding = 12.dp) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val left = remainingM(nav, route)
                        Stat(Icons.Rounded.Schedule, left?.let(::etaText) ?: "—", "left", Modifier.weight(1f))
                        Box(Modifier.width(1.dp).height(36.dp).background(Tokens.Border))
                        Spacer(Modifier.width(12.dp))
                        Stat(Icons.Rounded.Route, left?.let(::distanceText) ?: "—", "to destination", Modifier.weight(1f))
                        SpeakButton(vm)
                    }
                    GpsLine(fix?.accuracyM, settings.simulatedGps)
                    if (settings.simulatedGps) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Simulate going off route", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                            Switch(checked = detour, onCheckedChange = vm::setDetour)
                        }
                    }
                }
                SoftButton("Stop navigation", vm::stopNavigation, Modifier.fillMaxWidth(), color = Tokens.Wrong, background = Tokens.WrongBg)
                Spacer(Modifier.height(4.dp))
            }
        }
    }
}

@Composable
private fun Stat(icon: ImageVector, value: String, label: String, modifier: Modifier) {
    Row(modifier.semantics(mergeDescendants = true) {}, verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = Tokens.Accent, modifier = Modifier.size(26.dp))
        Spacer(Modifier.width(8.dp))
        Column {
            Text(value, style = MaterialTheme.typography.titleLarge, color = Tokens.Text)
            Text(label, style = MaterialTheme.typography.bodySmall, color = AppTheme.extra.muted)
        }
    }
}

@Composable
fun GpsLine(accuracyM: Float?, simulated: Boolean) {
    val weak = !simulated && (accuracyM == null || accuracyM > 20)
    Text(
        when {
            simulated -> "Simulated GPS"
            accuracyM == null -> "No GPS signal yet"
            accuracyM > 20 -> "Weak GPS (±${accuracyM.roundToInt()} m), position ignored"
            else -> "GPS ±${accuracyM.roundToInt()} m"
        },
        color = if (weak) Tokens.Warn else AppTheme.extra.muted,
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.padding(top = 6.dp),
    )
}

// --- You've Arrived! ---------------------------------------------------------------------------------

@Composable
fun ArrivedScreen(vm: AppViewModel, onDone: () -> Unit) {
    val route by vm.navRoute.collectAsState()
    PageBackground {
        Confetti(Modifier.fillMaxSize())
        Column(
            Modifier.fillMaxSize().navigationBarsPadding().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.weight(0.6f))
            Box(
                Modifier.size(120.dp).background(Tokens.AccentGradient, CircleShape),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Rounded.Check, null, tint = Color.White, modifier = Modifier.size(72.dp)) }
            Spacer(Modifier.height(24.dp))
            Column(
                Modifier.semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Assertive; heading() },
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("You've Arrived!", fontSize = 32.sp, fontWeight = FontWeight.Black, color = Tokens.Text, textAlign = TextAlign.Center)
                route?.let { Text(it.name, style = MaterialTheme.typography.titleMedium, color = Tokens.Accent, textAlign = TextAlign.Center) }
            }
            Spacer(Modifier.height(24.dp))
            Hill(Modifier.fillMaxWidth().height(170.dp))
            Spacer(Modifier.weight(1f))
            PrimaryButton("Done", onDone)
        }
    }
}

/** A few pastel confetti strips at the top of the screen. */
@Composable
private fun Confetti(modifier: Modifier) {
    val colors = listOf(Tokens.PinkLight, Tokens.Sun, Tokens.AccentLight, Tokens.Blue, Color(0xFFF59E8B))
    // Fixed positions: the same picture every time, nothing moves for a screen reader user to miss.
    val bits = listOf(
        .08f to .06f, .22f to .12f, .38f to .05f, .55f to .1f, .72f to .04f, .88f to .11f,
        .14f to .22f, .32f to .26f, .68f to .24f, .84f to .2f, .5f to .2f, .94f to .3f, .05f to .32f,
    )
    Canvas(modifier.clearAndSetSemantics {}) {
        bits.forEachIndexed { i, (x, y) ->
            val c = Offset(size.width * x, size.height * y)
            rotate((i * 47 % 180).toFloat() - 90f, c) {
                drawRoundRect(colors[i % colors.size], Offset(c.x - 4.dp.toPx(), c.y - 9.dp.toPx()), Size(8.dp.toPx(), 18.dp.toPx()), androidx.compose.ui.geometry.CornerRadius(4.dp.toPx()))
            }
        }
    }
}

/** A green hill with a purple flag on top. */
@Composable
private fun Hill(modifier: Modifier) {
    Canvas(modifier.clearAndSetSemantics {}) {
        val w = size.width
        val h = size.height
        val far = Path().apply {
            moveTo(0f, h); cubicTo(w * .2f, h * .45f, w * .45f, h * .5f, w * .6f, h * .7f); cubicTo(w * .75f, h * .5f, w * .92f, h * .6f, w, h); close()
        }
        drawPath(far, Color(0xFFCDEBC4))
        val hill = Path().apply {
            moveTo(w * .1f, h); cubicTo(w * .25f, h * .35f, w * .75f, h * .35f, w * .9f, h); close()
        }
        drawPath(hill, Brush.verticalGradient(listOf(Color(0xFF9BD67E), Color(0xFF5DAE55)), startY = h * .4f, endY = h))
        val top = Offset(w * .5f, h * .5f)
        drawLine(Tokens.Brand, top, Offset(top.x, top.y - h * .42f), strokeWidth = 4.dp.toPx())
        val flag = Path().apply {
            moveTo(top.x, top.y - h * .42f); lineTo(top.x + w * .16f, top.y - h * .34f); lineTo(top.x, top.y - h * .25f); close()
        }
        drawPath(flag, Tokens.AccentGradient)
        drawCircle(Color.White.copy(alpha = .8f), h * .06f, Offset(w * .2f, h * .3f))
        drawCircle(Color.White.copy(alpha = .8f), h * .08f, Offset(w * .25f, h * .27f))
        drawCircle(Tokens.AccentLight.copy(alpha = .5f), h * .03f, Offset(w * .8f, h * .25f))
    }
}
