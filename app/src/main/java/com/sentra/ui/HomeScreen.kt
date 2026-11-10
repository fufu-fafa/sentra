package com.sentra.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.sentra.ScanState
import com.sentra.ble.LinkState
import com.sentra.ble.SentraProtocol
import com.sentra.ble.SleeveCommand
import com.sentra.data.RouteEntity
import com.sentra.nav.NavSnapshot
import com.sentra.nav.NavState
import com.sentra.nav.Turn
import com.sentra.ui.theme.AppTheme
import com.sentra.ui.theme.Mono
import com.sentra.ui.theme.Tokens
import kotlin.math.roundToInt

/** Beranda: connect the sleeve, pick a route, start navigation, and watch it run. */
@Composable
fun HomeScreen(vm: AppViewModel, enableBluetooth: () -> Unit, openRoutes: () -> Unit) {
    val settings by vm.settings.collectAsState()
    val nav by vm.nav.collectAsState()

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        ScreenTitle("SENTRA ${settings.childName}")
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SetupWarnings(childMode = true, simulatedSleeve = settings.simulatedSleeve, simulatedGps = settings.simulatedGps)
            SleeveCard(vm, settings.simulatedSleeve, settings.deviceName, enableBluetooth)
            if (nav.active || nav.state == NavState.ARRIVED) TripCard(vm, nav, settings.simulatedGps)
            if (!nav.active) RoutePickerCard(vm, settings.selectedRouteId, openRoutes)
            RelayLine(vm)
            Spacer(Modifier.height(16.dp))
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
            Column(Modifier.weight(1f)) {
                SectionLabel("Sleeve")
                Text(if (simulated) "Sleeve simulasi" else deviceName ?: "Belum ada sleeve", style = MaterialTheme.typography.titleMedium)
            }
            // Announced by TalkBack when it changes, like the spoken "Sleeve tersambung".
            val live = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
            when (link) {
                LinkState.CONNECTED -> Pill("Tersambung", Tokens.Correct, Tokens.CorrectBg, live)
                LinkState.CONNECTING -> Pill("Mencari…", Tokens.Muted, Tokens.Border, live)
                LinkState.DISCONNECTED -> Pill("Terputus", Tokens.Wrong, Tokens.WrongBg, live)
            }
        }
        if (link == LinkState.CONNECTED) {
            Spacer(Modifier.height(12.dp))
            ObstacleReadout(obstacle)
            if (simulated) {
                Text("Motor: " + (pattern?.let { patternText(it) } ?: "diam"), color = AppTheme.extra.muted, style = MaterialTheme.typography.bodySmall)
            }
        } else if (!simulated) {
            Spacer(Modifier.height(12.dp))
            val searching = scan == ScanState.Searching
            Button(onClick = vm::connectSleeve, enabled = !searching, modifier = Modifier.fillMaxWidth()) {
                Text(if (searching) "Mencari…" else "Sambungkan")
            }
            val hint = when (val s = scan) {
                ScanState.NotFound -> "Sleeve tidak ditemukan. Pastikan menyala dan dekat."
                is ScanState.Failed -> s.reason
                ScanState.Searching -> null
                ScanState.Idle -> if (link == LinkState.CONNECTING) "Menunggu sleeve…" else null
            }
            hint?.let {
                val bad = scan is ScanState.Failed || scan == ScanState.NotFound
                Text(it, color = if (bad) Tokens.Wrong else AppTheme.extra.muted, style = MaterialTheme.typography.bodySmall)
            }
            if (scan is ScanState.Failed) {
                OutlinedButton(onClick = enableBluetooth, modifier = Modifier.fillMaxWidth()) { Text("Nyalakan Bluetooth") }
            }
        }
    }
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
            color = when {
                clear -> Tokens.Text
                cm < 50 -> Tokens.Wrong
                cm < 100 -> Tokens.Warn
                else -> Tokens.Text
            },
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

@Composable
private fun TripCard(vm: AppViewModel, nav: NavSnapshot, simulatedGps: Boolean) {
    val route by vm.navRoute.collectAsState()
    val fix by vm.fix.collectAsState()
    val detour by vm.detour.collectAsState()
    val offRoute = nav.state == NavState.OFF_ROUTE
    Card(
        background = if (offRoute) Tokens.WrongBg else Tokens.Card,
        border = if (offRoute) Tokens.Wrong else Tokens.Border,
    ) {
        SectionLabel("Navigasi" + (route?.let { " · ${it.name}" } ?: ""))
        Column(Modifier.semantics { liveRegion = LiveRegionMode.Polite }) {
            when (nav.state) {
                NavState.ARRIVED -> Text("Sudah sampai", style = MaterialTheme.typography.titleLarge, color = Tokens.Correct)
                NavState.OFF_ROUTE -> {
                    Text("Keluar rute", style = MaterialTheme.typography.titleLarge, color = Tokens.Wrong)
                    Text("Sleeve memberi tanda berhenti.")
                }
                else -> {
                    Text(
                        if (nav.targetNumber == nav.totalPoints) "Menuju tujuan" else "Titik ${nav.targetNumber} dari ${nav.totalPoints}",
                        style = MaterialTheme.typography.titleLarge,
                    )
                    val turn = when (nav.turnAtTarget) {
                        Turn.LEFT -> "lalu belok kiri"
                        Turn.RIGHT -> "lalu belok kanan"
                        Turn.U_TURN -> "lalu putar balik"
                        Turn.STRAIGHT -> "lalu lurus"
                        null -> null
                    }
                    Text((nav.distanceM?.let { "${it.roundToInt()} m lagi" } ?: "Menunggu GPS…") + (turn?.let { ", $it" } ?: ""))
                }
            }
        }
        if (nav.active) {
            val f = fix
            val weak = !simulatedGps && (f == null || f.accuracyM > 20)
            Text(
                when {
                    simulatedGps -> "GPS simulasi"
                    f == null -> "Belum ada sinyal GPS"
                    f.accuracyM > 20 -> "GPS lemah (±${f.accuracyM.roundToInt()} m), posisi diabaikan"
                    else -> "GPS ±${f.accuracyM.roundToInt()} m"
                },
                color = if (weak) Tokens.Warn else AppTheme.extra.muted,
                style = MaterialTheme.typography.bodySmall,
            )
            if (simulatedGps) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Simulasikan keluar rute", Modifier.weight(1f))
                    Switch(checked = detour, onCheckedChange = vm::setDetour)
                }
            }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = vm::stopNavigation, modifier = Modifier.fillMaxWidth()) { Text("Hentikan navigasi") }
        }
    }
}

@Composable
private fun RoutePickerCard(vm: AppViewModel, selectedId: Long?, openRoutes: () -> Unit) {
    val routes by vm.routes.collectAsState()
    val message by vm.navMessage.collectAsState()
    val selected = routes.firstOrNull { it.id == selectedId } ?: routes.singleOrNull()
    Card {
        SectionLabel("Rute")
        if (routes.isEmpty()) {
            Text("Belum ada rute.", color = AppTheme.extra.muted)
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = openRoutes, modifier = Modifier.fillMaxWidth()) { Text("Rekam rute") }
        } else {
            routes.forEach { r -> RouteChoice(r, r.id == selected?.id) { vm.selectRoute(r.id) } }
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = { selected?.let { vm.startNavigation(it.id) } },
                enabled = selected != null,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Mulai navigasi") }
        }
        message?.let { Text(it, color = Tokens.Wrong) }
    }
}

@Composable
private fun RouteChoice(route: RouteEntity, selected: Boolean, onSelect: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).selectable(selected = selected, role = Role.RadioButton, onClick = onSelect),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null, colors = RadioButtonDefaults.colors(selectedColor = Tokens.Accent, unselectedColor = Tokens.Muted))
        Spacer(Modifier.width(12.dp))
        Column {
            Text(route.name)
            Text(routeSummary(route), color = AppTheme.extra.muted, style = MaterialTheme.typography.bodySmall)
        }
    }
}

/** One line on what reaches the parents; the relay is an add-on, so this is never an error for navigation. */
@Composable
private fun RelayLine(vm: AppViewModel) {
    val health by vm.relayHealth.collectAsState()
    val h = health ?: return
    val text = when {
        h.url.isBlank() -> "Orang tua: belum diatur"
        h.error != null -> "Orang tua: relay tidak terjangkau, ${h.pending} menunggu"
        !h.registered || h.parents == null -> "Orang tua: menyambung…"
        h.parents == 0 -> "Orang tua: belum ada yang terhubung"
        else -> "Orang tua: ${h.parents} terhubung" + if (h.pending > 0) ", ${h.pending} menunggu" else ""
    }
    Text(text, color = AppTheme.extra.muted, style = MaterialTheme.typography.bodySmall)
}
