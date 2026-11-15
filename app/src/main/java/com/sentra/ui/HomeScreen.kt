package com.sentra.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.Bluetooth
import androidx.compose.material.icons.rounded.BluetoothConnected
import androidx.compose.material.icons.rounded.BluetoothSearching
import androidx.compose.material.icons.rounded.LocationOn
import androidx.compose.material.icons.rounded.Navigation
import androidx.compose.material.icons.rounded.Route
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.sentra.ScanState
import com.sentra.ble.LinkState
import com.sentra.ble.SentraProtocol
import com.sentra.ble.SleeveCommand
import com.sentra.nav.NavState
import com.sentra.ui.theme.AppTheme
import com.sentra.ui.theme.Mono
import com.sentra.ui.theme.Tokens

/** Beranda: the sleeve, Mulai Navigasi, Lokasi Live and Rute, and what navigation is doing. */
@Composable
fun HomeScreen(
    vm: AppViewModel,
    enableBluetooth: () -> Unit,
    openRoutes: () -> Unit,
    openLive: () -> Unit,
    openSettings: () -> Unit,
    resumeNav: () -> Unit,
) {
    val settings by vm.settings.collectAsState()
    val nav by vm.nav.collectAsState()
    val routes by vm.routes.collectAsState()
    val message by vm.navMessage.collectAsState()
    val selected = routes.firstOrNull { it.id == settings.selectedRouteId } ?: routes.singleOrNull()

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Box(Modifier.fillMaxWidth().statusBarsPadding()) {
            IconButton(onClick = openSettings, modifier = Modifier.align(Alignment.TopEnd).padding(4.dp)) {
                Icon(Icons.Rounded.Settings, contentDescription = "Setelan", tint = Tokens.Brand)
            }
            Box(Modifier.fillMaxWidth().padding(top = 12.dp), contentAlignment = Alignment.Center) {
                SentraLogo(118.dp)
            }
        }
        Spacer(Modifier.height(16.dp))
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            SetupWarnings(childMode = true, simulatedSleeve = settings.simulatedSleeve, simulatedGps = settings.simulatedGps)
            SleeveCard(vm, settings.simulatedSleeve, settings.deviceName, enableBluetooth)
            when {
                nav.active -> PrimaryButton("Lanjutkan Navigasi", resumeNav, icon = Icons.Rounded.Navigation, chevron = true)
                selected != null -> PrimaryButton("Mulai Navigasi", { vm.startNavigation(selected.id) }, icon = Icons.Rounded.Navigation, chevron = true)
                else -> PrimaryButton("Mulai Navigasi", openRoutes, icon = Icons.Rounded.Navigation, chevron = true)
            }
            message?.let { Text(it, color = Tokens.Wrong, style = MaterialTheme.typography.bodyMedium) }
            Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                FeatureTile("Lokasi Live", Icons.Rounded.LocationOn, Tokens.Blue, Tokens.BlueSoft, Modifier.weight(1f), openLive)
                FeatureTile("Rute", Icons.Rounded.Route, Tokens.Pink, Tokens.PinkSoft, Modifier.weight(1f), openRoutes)
            }
            NavStatusCard(vm, selected?.name)
            RelayLine(vm)
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
private fun FeatureTile(label: String, icon: ImageVector, tint: Color, bg: Color, modifier: Modifier, onClick: () -> Unit) {
    Card(modifier.fillMaxHeight(), onClick = onClick, onClickLabel = "Buka $label") {
        Column(
            Modifier.fillMaxWidth().heightIn(min = 84.dp).semantics { role = Role.Button },
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            IconBubble(icon, tint, bg, 52.dp)
            Spacer(Modifier.height(8.dp))
            Text(label, style = MaterialTheme.typography.titleMedium, color = Tokens.Text, textAlign = TextAlign.Center)
        }
    }
}

@Composable
private fun SleeveCard(vm: AppViewModel, simulated: Boolean, deviceName: String?, enableBluetooth: () -> Unit) {
    val link by vm.link.collectAsState()
    val scan by vm.scan.collectAsState()
    val obstacle by vm.obstacleCm.collectAsState()
    val pattern by vm.fakePattern.collectAsState()

    Card {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBubble(
                when (link) {
                    LinkState.CONNECTED -> Icons.Rounded.BluetoothConnected
                    LinkState.CONNECTING -> Icons.Rounded.BluetoothSearching
                    LinkState.DISCONNECTED -> Icons.Rounded.Bluetooth
                },
                Color.White, if (link == LinkState.CONNECTED) Tokens.Blue else Tokens.Muted, 48.dp,
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    if (simulated) "Sleeve simulasi" else deviceName ?: "Smart Haptic Sleeve",
                    style = MaterialTheme.typography.titleMedium, color = Tokens.Text,
                )
                // Announced by TalkBack when it changes, like the spoken "Sleeve tersambung".
                val live = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
                when (link) {
                    LinkState.CONNECTED -> StatusDot("Tersambung", Tokens.Correct, live)
                    LinkState.CONNECTING -> StatusDot("Mencari…", Tokens.Muted, live)
                    LinkState.DISCONNECTED -> StatusDot("Terputus", Tokens.Wrong, live)
                }
            }
            if (link == LinkState.CONNECTED) ObstacleBadge(obstacle)
        }
        if (link == LinkState.CONNECTED && simulated) {
            Spacer(Modifier.height(8.dp))
            Text("Motor: " + (pattern?.let { patternText(it) } ?: "diam"), color = AppTheme.extra.muted, style = MaterialTheme.typography.bodySmall)
        }
        if (link != LinkState.CONNECTED && !simulated) {
            Spacer(Modifier.height(12.dp))
            val searching = scan == ScanState.Searching
            PrimaryButton(if (searching) "Mencari…" else "Sambungkan", vm::connectSleeve, enabled = !searching, icon = Icons.Rounded.Bluetooth, height = 48.dp)
            val hint = when (val s = scan) {
                ScanState.NotFound -> "Sleeve tidak ditemukan. Pastikan menyala dan dekat."
                is ScanState.Failed -> s.reason
                ScanState.Searching -> null
                ScanState.Idle -> if (link == LinkState.CONNECTING) "Menunggu sleeve…" else null
            }
            hint?.let {
                val bad = scan is ScanState.Failed || scan == ScanState.NotFound
                Text(it, color = if (bad) Tokens.Wrong else AppTheme.extra.muted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp))
            }
            if (scan is ScanState.Failed) {
                Spacer(Modifier.height(8.dp))
                SoftButton("Nyalakan Bluetooth", enableBluetooth, Modifier.fillMaxWidth())
            }
        }
    }
}

/** The obstacle distance as a small right-aligned figure on the sleeve card. */
@Composable
private fun ObstacleBadge(cm: Int?) {
    val clear = cm == null || cm >= SentraProtocol.NO_OBSTACLE_CM
    Column(horizontalAlignment = Alignment.End, modifier = Modifier.semantics(mergeDescendants = true) {}) {
        Text("Rintangan", color = AppTheme.extra.muted, style = MaterialTheme.typography.labelSmall)
        Text(
            when {
                cm == null -> "—"
                clear -> "Kosong"
                else -> "$cm cm"
            },
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = obstacleColor(cm),
        )
    }
}

fun obstacleColor(cm: Int?): Color = when {
    cm == null || cm >= SentraProtocol.NO_OBSTACLE_CM -> Tokens.Text
    cm < 50 -> Tokens.Wrong
    cm < 100 -> Tokens.Warn
    else -> Tokens.Text
}

@Composable
fun ObstacleReadout(cm: Int?) {
    val clear = cm == null || cm >= SentraProtocol.NO_OBSTACLE_CM
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Jarak rintangan", color = AppTheme.extra.muted, modifier = Modifier.weight(1f))
        Text(
            when {
                cm == null -> "—"
                clear -> "Kosong"
                else -> "$cm cm"
            },
            style = MaterialTheme.typography.titleLarge,
            fontFamily = Mono,
            color = obstacleColor(cm),
        )
    }
}

fun patternText(command: SleeveCommand): String = when (command) {
    SleeveCommand.FORWARD -> "lurus"
    SleeveCommand.LEFT -> "kiri"
    SleeveCommand.RIGHT -> "kanan"
    SleeveCommand.STOP -> "stop"
    else -> command.label.lowercase()
}

/** Status Navigasi: the instruction of the moment, with a button that reads it out. */
@Composable
private fun NavStatusCard(vm: AppViewModel, selectedRoute: String?) {
    val nav by vm.nav.collectAsState()
    val route by vm.navRoute.collectAsState()
    val (icon, title, subtitle) = when {
        nav.active -> navInstruction(nav, route)
        nav.state == NavState.ARRIVED -> Instruction(Icons.Rounded.LocationOn, "Sudah sampai", route?.name ?: "")
        else -> Instruction(Icons.Rounded.Navigation, "Siap navigasi", selectedRoute?.let { "Rute: $it" } ?: "Pilih rute dulu di menu Rute")
    }
    val offRoute = nav.state == NavState.OFF_ROUTE
    Card(background = if (offRoute) Tokens.WrongBg else Tokens.Card, border = if (offRoute) Tokens.Wrong else Tokens.Border) {
        SectionLabel("Status Navigasi", icon = Icons.Rounded.LocationOn, tint = Tokens.Accent)
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBubble(icon, if (offRoute) Tokens.Wrong else Tokens.Blue, if (offRoute) Color.White else Tokens.BlueSoft, 48.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f).semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite }) {
                Text(title, style = MaterialTheme.typography.titleLarge, color = if (offRoute) Tokens.Wrong else Tokens.Text)
                if (subtitle.isNotEmpty()) Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = AppTheme.extra.muted)
            }
            SpeakButton(vm)
        }
    }
}

@Composable
fun SpeakButton(vm: AppViewModel) {
    IconButton(onClick = vm::speakInfo, modifier = Modifier.size(52.dp)) {
        IconBubble(Icons.AutoMirrored.Rounded.VolumeUp, Tokens.Accent, Tokens.AccentSoft, 46.dp)
    }
}

/** One line on what reaches the parents; the relay is an add-on, so this is never an error for navigation. */
@Composable
fun RelayLine(vm: AppViewModel) {
    val health by vm.relayHealth.collectAsState()
    val h = health ?: return
    val text = when {
        h.url.isBlank() -> "Orang tua: belum diatur"
        h.error != null -> "Orang tua: relay tidak terjangkau, ${h.pending} menunggu"
        !h.registered || h.parents == null -> "Orang tua: menyambung…"
        h.parents == 0 -> "Orang tua: belum ada yang terhubung"
        else -> "Orang tua: ${h.parents} terhubung" + if (h.pending > 0) ", ${h.pending} menunggu" else ""
    }
    Text(text, color = AppTheme.extra.muted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 4.dp))
}
