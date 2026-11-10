package com.sentra.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.sentra.ChildManager
import com.sentra.ble.Intensity
import com.sentra.ble.LinkState
import com.sentra.ble.SleeveCommand
import com.sentra.ui.theme.AppTheme
import com.sentra.ui.theme.Mono
import com.sentra.ui.theme.Tokens

/**
 * Mode Demo (§9.6), for the 1×1 m booth where GPS doesn't work: every command by hand so a judge
 * can wear the sleeve and feel it, an automatic route simulation, the live obstacle distance, and
 * the intensity switch.
 */
@Composable
fun DemoScreen(vm: AppViewModel) {
    val settings by vm.settings.collectAsState()
    val link by vm.link.collectAsState()
    val nav by vm.nav.collectAsState()
    val obstacle by vm.obstacleCm.collectAsState()
    val intensity by vm.intensity.collectAsState()
    val ack by vm.lastAck.collectAsState()
    val error by vm.sleeveError.collectAsState()
    val step by vm.demoStep.collectAsState()
    val pattern by vm.fakePattern.collectAsState()
    val connected = link == LinkState.CONNECTED
    val free = connected && !nav.active
    val manual = free && step == null

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        ScreenTitle("Demo")
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            when {
                !connected -> Text("Sambungkan sleeve di Beranda dulu.", color = Tokens.Warn)
                nav.active -> Text("Navigasi sedang berjalan.", color = Tokens.Warn)
            }
            Card {
                ObstacleReadout(obstacle)
                Text("Dekatkan tangan ke sensor.", color = AppTheme.extra.muted, style = MaterialTheme.typography.bodySmall)
            }
            Card {
                SectionLabel("Perintah")
                CommandRow(vm, manual, SleeveCommand.LEFT to "Kiri", SleeveCommand.RIGHT to "Kanan")
                CommandRow(vm, manual, SleeveCommand.FORWARD to "Lurus", SleeveCommand.STOP to "Stop")
                CommandRow(vm, manual, SleeveCommand.ARRIVED to "Tiba", SleeveCommand.TEST to "Tes")
                OutlinedButton(onClick = { vm.demo(SleeveCommand.IDLE) }, enabled = manual, modifier = Modifier.fillMaxWidth()) {
                    Text("Diam")
                }
            }
            Card {
                SectionLabel("Getaran")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(Intensity.LOW to "Lemah", Intensity.HIGH to "Kuat").forEach { (level, text) ->
                        FilterChip(
                            selected = intensity == level,
                            onClick = { vm.demo(if (level == Intensity.LOW) SleeveCommand.LOW else SleeveCommand.HIGH) },
                            enabled = free,
                            label = { Text(text) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = Tokens.Accent,
                                selectedLabelColor = Tokens.AccentText,
                            ),
                        )
                    }
                }
            }
            Card {
                SectionLabel("Simulasi rute")
                Text("Lurus, kiri, lurus, kanan, lurus, tiba; tiap 4 detik.", color = AppTheme.extra.muted, style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(8.dp))
                val current = step
                if (current == null) {
                    Button(onClick = vm::startDemo, enabled = free, modifier = Modifier.fillMaxWidth()) { Text("Mulai simulasi") }
                } else {
                    Text(
                        "Langkah ${current + 1} dari ${ChildManager.DEMO_SEQUENCE.size}: ${ChildManager.DEMO_SEQUENCE[current].label}",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    OutlinedButton(onClick = vm::stopDemo, modifier = Modifier.fillMaxWidth()) { Text("Hentikan") }
                }
            }
            Card {
                SectionLabel("Balasan sleeve")
                Text(
                    ack?.let { "ACK:${it.command}" + (it.roundTripMs?.let { ms -> " · $ms ms" } ?: "") } ?: "Belum ada.",
                    fontFamily = Mono,
                )
                error?.let { Text("ERR:$it", color = Tokens.Wrong, fontFamily = Mono) }
            }
            if (settings.simulatedSleeve) {
                Card {
                    SectionLabel("Sleeve simulasi")
                    Text("Motor: " + (pattern?.let { patternText(it) } ?: "diam"))
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { vm.pressFakeButton(false) }, modifier = Modifier.weight(1f)) { Text("Tekan singkat") }
                        OutlinedButton(onClick = { vm.pressFakeButton(true) }, modifier = Modifier.weight(1f)) { Text("Tekan lama") }
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun CommandRow(vm: AppViewModel, enabled: Boolean, left: Pair<SleeveCommand, String>, right: Pair<SleeveCommand, String>) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(left, right).forEach { (command, text) ->
            OutlinedButton(onClick = { vm.demo(command) }, enabled = enabled, modifier = Modifier.weight(1f)) {
                Text(text, color = if (command == SleeveCommand.STOP && enabled) Tokens.Wrong else androidx.compose.ui.graphics.Color.Unspecified)
            }
        }
    }
}
