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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.DirectionsWalk
import androidx.compose.material.icons.rounded.Bluetooth
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.LinkOff
import androidx.compose.material.icons.rounded.LocationOn
import androidx.compose.material.icons.rounded.MyLocation
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.SportsEsports
import androidx.compose.material.icons.rounded.Straighten
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sentra.ScanState
import com.sentra.ble.Intensity
import com.sentra.ble.LinkState
import com.sentra.ble.SentraProtocol
import com.sentra.nav.NavState
import com.sentra.ui.theme.AppTheme
import com.sentra.ui.theme.Mono
import com.sentra.ui.theme.Tokens
import java.util.Locale
import kotlin.math.roundToInt

/** Lokasi Live (and the Peta tab): where this phone is, the route being walked, and time and distance left. */
@Composable
fun LiveLocationScreen(vm: AppViewModel, onBack: (() -> Unit)?, onResumeNav: () -> Unit) {
    val fix by vm.fix.collectAsState()
    val nav by vm.nav.collectAsState()
    val route by vm.navRoute.collectAsState()
    val settings by vm.settings.collectAsState()
    var follow by rememberSaveable { mutableStateOf(true) }

    // GPS on while the map is open, even without a trip.
    DisposableEffect(Unit) {
        vm.setFixWanted(true)
        onDispose { vm.setFixWanted(false) }
    }

    Column(Modifier.fillMaxSize().then(if (onBack != null) Modifier.navigationBarsPadding() else Modifier)) {
        TopBar("Lokasi Live", onBack)
        Box(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp).clip(RoundedCornerShape(24.dp))) {
            OsmMap(
                MapContent(
                    marker = fix?.point, accuracyM = fix?.accuracyM,
                    route = if (nav.active) route?.geoPoints().orEmpty() else emptyList(),
                    markerColor = if (nav.state == NavState.OFF_ROUTE) MapContent.RED else MapContent.BLUE,
                ),
                Modifier.fillMaxSize(),
                follow = follow,
                onUserMoved = { follow = false },
            )
            if (!follow) {
                SmallFloatingActionButton(
                    onClick = { follow = true },
                    containerColor = Tokens.Card, contentColor = Tokens.Blue,
                    modifier = Modifier.align(Alignment.BottomEnd).padding(12.dp),
                ) { Icon(Icons.Rounded.MyLocation, contentDescription = "Ke posisi saya") }
            }
        }
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Card(padding = 14.dp) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.semantics(mergeDescendants = true) {}) {
                    Icon(Icons.Rounded.LocationOn, null, tint = Tokens.Pink, modifier = Modifier.size(28.dp))
                    Spacer(Modifier.width(10.dp))
                    Column {
                        Text("Lokasi Saat Ini", style = MaterialTheme.typography.titleSmall, color = Tokens.Text)
                        val f = fix
                        Text(
                            if (f == null) {
                                if (settings.simulatedGps && !nav.active) "GPS simulasi berjalan saat navigasi" else "Menunggu GPS…"
                            } else String.format(Locale.US, "%.5f, %.5f", f.lat, f.lon) + " · ±${f.accuracyM.roundToInt()} m",
                            style = MaterialTheme.typography.bodyMedium, color = AppTheme.extra.muted,
                        )
                    }
                }
            }
            if (nav.active) {
                val left = remainingM(nav, route)
                Card(padding = 14.dp) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Mini(Icons.Rounded.Schedule, "Perkiraan", left?.let(::etaText) ?: "—", Modifier.weight(1f))
                        Mini(Icons.Rounded.Straighten, "Jarak", left?.let(::distanceText) ?: "—", Modifier.weight(1f))
                    }
                }
                SoftButton(
                    "Menuju ${route?.name ?: "tujuan"}", onResumeNav, Modifier.fillMaxWidth(),
                    icon = Icons.AutoMirrored.Rounded.DirectionsWalk,
                )
            }
        }
    }
}

@Composable
private fun Mini(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, value: String, modifier: Modifier) {
    Row(modifier.semantics(mergeDescendants = true) {}, verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = Tokens.Accent, modifier = Modifier.size(26.dp))
        Spacer(Modifier.width(10.dp))
        Column {
            Text(label, style = MaterialTheme.typography.bodySmall, color = AppTheme.extra.muted)
            Text(value, style = MaterialTheme.typography.titleMedium, color = Tokens.Text)
        }
    }
}

// --- Status Perangkat ----------------------------------------------------------------------------

/** Status: the sleeve, its sensor and replies, and the way into Mode Demo. */
@Composable
fun DeviceStatusScreen(vm: AppViewModel, enableBluetooth: () -> Unit, openDemo: () -> Unit) {
    val settings by vm.settings.collectAsState()
    val link by vm.link.collectAsState()
    val scan by vm.scan.collectAsState()
    val obstacle by vm.obstacleCm.collectAsState()
    val intensity by vm.intensity.collectAsState()
    val ack by vm.lastAck.collectAsState()
    var confirmForget by remember { mutableStateOf(false) }
    val connected = link == LinkState.CONNECTED

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        ScreenTitle("Status Perangkat")
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            SleeveArt(connected, Modifier.size(190.dp))
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite; heading() },
            ) {
                Text(
                    when (link) {
                        LinkState.CONNECTED -> "Tersambung!"
                        LinkState.CONNECTING -> "Mencari sleeve…"
                        LinkState.DISCONNECTED -> "Belum tersambung"
                    },
                    fontSize = 28.sp, fontWeight = FontWeight.Bold, color = Tokens.Text,
                )
                Text(
                    if (settings.simulatedSleeve) "Sleeve simulasi" else settings.deviceName ?: "Smart Haptic Sleeve",
                    style = MaterialTheme.typography.bodyLarge, color = AppTheme.extra.muted,
                )
            }
            Card {
                StatusRow("Jarak rintangan", when {
                    !connected || obstacle == null -> "—"
                    obstacle!! >= SentraProtocol.NO_OBSTACLE_CM -> "Kosong"
                    else -> "$obstacle cm"
                }, obstacleColor(obstacle))
                StatusRow("Getaran", if (intensity == Intensity.LOW) "Lemah" else "Kuat")
                StatusRow("Balasan terakhir", ack?.let { "ACK:${it.command}" + (it.roundTripMs?.let { ms -> " · $ms ms" } ?: "") } ?: "—", mono = true)
                Spacer(Modifier.height(4.dp))
                RelayLine(vm)
            }
            if (settings.simulatedSleeve) {
                Text("Sleeve simulasi menyala. Matikan di Setelan → Uji tanpa alat.", color = Tokens.Warn, style = MaterialTheme.typography.bodySmall)
            } else if (connected) {
                SoftButton("Putuskan", { confirmForget = true }, Modifier.fillMaxWidth(), icon = Icons.Rounded.LinkOff)
            } else {
                val searching = scan == ScanState.Searching
                PrimaryButton(if (searching) "Mencari…" else "Sambungkan", vm::connectSleeve, enabled = !searching, icon = Icons.Rounded.Bluetooth)
                if (scan is ScanState.Failed) SoftButton("Nyalakan Bluetooth", enableBluetooth, Modifier.fillMaxWidth())
            }
            ListRow(
                "Mode Demo", "Coba getaran tanpa berjalan, untuk booth 1×1 m",
                Icons.Rounded.SportsEsports, tint = Tokens.Accent, iconBackground = Tokens.AccentSoft,
                onClick = openDemo,
            )
            Spacer(Modifier.height(12.dp))
        }
    }

    if (confirmForget) {
        AlertDialog(
            onDismissRequest = { confirmForget = false },
            containerColor = Tokens.Card,
            title = { Text("Putuskan sleeve?") },
            text = { Text("HP ini berhenti tersambung sampai Anda menekan Sambungkan lagi.", color = AppTheme.extra.muted) },
            confirmButton = { TextButton(onClick = { vm.forgetSleeve(); confirmForget = false }) { Text("Putuskan", color = Tokens.Wrong) } },
            dismissButton = { TextButton(onClick = { confirmForget = false }) { Text("Batal") } },
        )
    }
}

@Composable
private fun StatusRow(label: String, value: String, color: Color = Tokens.Text, mono: Boolean = false) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp).semantics(mergeDescendants = true) {}, verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = AppTheme.extra.muted, modifier = Modifier.weight(1f))
        Text(value, color = color, fontWeight = FontWeight.SemiBold, fontFamily = if (mono) Mono else null)
    }
}

/** The sleeve drawn as a dark band with three motors, vibration marks, and a green tick or grey cross. */
@Composable
fun SleeveArt(connected: Boolean, modifier: Modifier = Modifier) {
    Box(modifier.clearAndSetSemantics {}) {
        Canvas(Modifier.fillMaxSize()) {
            val w = size.width
            drawCircle(Brush.radialGradient(listOf(Tokens.AccentSoft, Color.Transparent)), w * .5f, Offset(w * .5f, w * .5f))
            rotate(-30f, Offset(w * .5f, w * .5f)) {
                val body = Size(w * .66f, w * .3f)
                val topLeft = Offset(w * .17f, w * .35f)
                drawRoundRect(Brush.verticalGradient(listOf(Color(0xFF4A4670), Color(0xFF26233F)), startY = topLeft.y, endY = topLeft.y + body.height), topLeft, body, CornerRadius(body.height / 2))
                drawRoundRect(Color(0xFF9B93D6), Offset(topLeft.x + body.width * .78f, topLeft.y), Size(body.width * .22f, body.height), CornerRadius(body.height / 2))
                for (i in 0..2) drawCircle(Color(0xFF8C86B8), w * .025f, Offset(topLeft.x + body.width * (.22f + i * .2f), topLeft.y + body.height / 2))
            }
            // Vibration marks.
            val mark = Stroke(w * .018f, cap = StrokeCap.Round)
            drawArc(Tokens.AccentLight, 200f, 50f, false, Offset(w * .02f, w * .08f), Size(w * .3f, w * .3f), style = mark)
            drawArc(Tokens.AccentLight, 20f, 50f, false, Offset(w * .68f, w * .58f), Size(w * .3f, w * .3f), style = mark)
        }
        Box(
            Modifier.align(Alignment.BottomEnd).padding(end = 18.dp, bottom = 18.dp).size(56.dp)
                .background(if (connected) Color(0xFF2FB67C) else Tokens.Muted, CircleShape),
            contentAlignment = Alignment.Center,
        ) { Icon(if (connected) Icons.Rounded.Check else Icons.Rounded.Close, null, tint = Color.White, modifier = Modifier.size(34.dp)) }
    }
}
