package com.sentra.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Settings
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sentra.care.HistoryMode
import com.sentra.care.RemoteEvent
import com.sentra.care.StreamState
import com.sentra.care.WatchedChild
import com.sentra.care.ago
import com.sentra.care.dayName
import com.sentra.care.historyPeriod
import com.sentra.care.localDay
import com.sentra.data.AppMode
import com.sentra.ui.theme.AppTheme
import com.sentra.ui.theme.Mono
import com.sentra.ui.theme.Tokens
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.util.Date
import java.util.Locale

private enum class CareTab(val label: String, val icon: ImageVector) {
    Map("Peta", Icons.Filled.LocationOn),
    Status("Status", Icons.Filled.Info),
    History("Riwayat", HistoryIcon),
    Settings("Setelan", Icons.Filled.Settings),
}

/** Mode Orang tua: SENTRA Care. Link with an invite code, then map, status, history and alarms. */
@Composable
fun CareScreen(vm: AppViewModel) {
    val settings by vm.settings.collectAsState()
    val children by vm.children.collectAsState()
    val linked = settings.caregiverToken != null
    var tab by rememberSaveable { mutableStateOf(CareTab.Map) }
    var selectedId by rememberSaveable { mutableLongStateOf(-1L) }
    // Keeps "12 dtk lalu" current.
    val now by produceState(System.currentTimeMillis()) {
        while (true) {
            delay(5_000)
            value = System.currentTimeMillis()
        }
    }

    if (!linked) {
        Scaffold(containerColor = Tokens.Bg) { pad ->
            Column(Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState())) {
                ScreenTitle("SENTRA Care", "Lokasi dan alarm anak di HP ini.")
                Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    LinkCard(vm, settings.relayUrl, settings.caregiverName, onCancel = null) {}
                    OutlinedButton(onClick = { vm.setMode(AppMode.CHILD) }, modifier = Modifier.fillMaxWidth()) {
                        Text("Pakai HP ini sebagai HP anak")
                    }
                    Spacer(Modifier.height(16.dp))
                }
            }
        }
        return
    }

    val child = children.firstOrNull { it.id == selectedId } ?: children.firstOrNull()
    Scaffold(
        containerColor = Tokens.Bg,
        bottomBar = { BottomTabs(CareTab.entries.map { Triple(it.label, it.icon, tab == it) }) { tab = CareTab.entries[it] } },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            if (tab != CareTab.Settings && children.size > 1) {
                Row(
                    Modifier.statusBarsPadding().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    children.forEach { c -> SmallButton(c.name, selected = c.id == child?.id) { selectedId = c.id } }
                }
            }
            val single = children.size <= 1
            when (tab) {
                CareTab.Map -> MapTab(child, now, single)
                CareTab.Status -> StatusTab(vm, child, now, single)
                CareTab.History -> HistoryTab(child, now, single, vm::acknowledge)
                CareTab.Settings -> CareSettingsTab(vm)
            }
        }
    }
}

@Composable
private fun NoChild(title: String, single: Boolean) {
    Column {
        if (single) ScreenTitle(title)
        Card(Modifier.padding(16.dp)) {
            Text("Belum mengikuti siapa pun. Hubungkan ke HP anak di tab Setelan.", color = AppTheme.extra.muted)
        }
    }
}

private fun childPill(c: WatchedChild): Triple<String, Color, Color> = when {
    c.offline -> Triple("HP offline", Tokens.Wrong, Tokens.WrongBg)
    c.navigating && c.status.navState == "OFF_ROUTE" -> Triple("Keluar rute", Tokens.Wrong, Tokens.WrongBg)
    c.navigating -> Triple("Di perjalanan", Tokens.Correct, Tokens.CorrectBg)
    c.lastHeartbeat != null -> Triple("Online", Tokens.Correct, Tokens.CorrectBg)
    else -> Triple("Belum ada kabar", Tokens.Muted, Tokens.Border)
}

// --- Peta ---------------------------------------------------------------------------------------

@Composable
private fun MapTab(child: WatchedChild?, now: Long, single: Boolean) {
    if (child == null) { NoChild("Peta", single); return }
    var follow by rememberSaveable { mutableStateOf(true) }
    val loc = child.location
    val route = if (child.navigating) child.status.route else emptyList()
    Box(Modifier.fillMaxSize()) {
        OsmMap(
            MapContent(
                marker = loc?.point,
                accuracyM = loc?.accuracyM,
                markerColor = when {
                    child.offline -> MapContent.GREY
                    child.navigating && child.status.navState == "OFF_ROUTE" -> MapContent.RED
                    else -> MapContent.BLUE
                },
                track = child.track.map { it.point },
                route = route,
            ),
            Modifier.fillMaxSize(),
            follow = follow,
            onUserMoved = { follow = false },
        )
        // What the map means, in words: for TalkBack and at a glance.
        Card(Modifier.align(Alignment.TopCenter).then(if (single) Modifier.statusBarsPadding() else Modifier).padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(child.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                val (text, fg, bg) = childPill(child)
                Pill(text, fg, bg, Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            }
            Text(
                when {
                    loc == null -> "Belum ada lokasi"
                    else -> "Lokasi ${ago(loc.at, now)}" + (loc.accuracyM?.let { " · ±${it.toInt()} m" } ?: "")
                },
                color = AppTheme.extra.muted, style = MaterialTheme.typography.bodySmall,
            )
            if (child.navigating) Text(tripLine(child), style = MaterialTheme.typography.bodySmall)
        }
        if (!follow && loc != null) {
            Box(Modifier.align(Alignment.BottomCenter).padding(16.dp)) {
                Button(onClick = { follow = true }) { Text("Ke posisi anak") }
            }
        }
    }
}

private fun tripLine(c: WatchedChild): String {
    val s = c.status
    val next = s.nextPoint?.let { n ->
        "titik $n dari ${s.totalPoints ?: "?"}" + (s.distanceM?.let { ", $it m lagi" } ?: "")
    }
    return listOfNotNull(s.routeName?.let { "Rute $it" }, next).joinToString(" · ")
}

// --- Status -------------------------------------------------------------------------------------

@Composable
private fun StatusTab(vm: AppViewModel, child: WatchedChild?, now: Long, single: Boolean) {
    val stream by vm.streamState.collectAsState()
    val error by vm.careError.collectAsState()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        if (single) ScreenTitle("Status")
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SetupWarnings(childMode = false)
            if (child == null) {
                Card { Text("Belum mengikuti siapa pun. Hubungkan ke HP anak di tab Setelan.", color = AppTheme.extra.muted) }
            } else {
                val s = child.status
                Card {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(child.name, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                        val (text, fg, bg) = childPill(child)
                        Pill(text, fg, bg)
                    }
                    Spacer(Modifier.height(12.dp))
                    Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        StatTile(
                            "Sleeve",
                            when (s.sleeveConnected) { true -> "Tersambung"; false -> "Terputus"; null -> "—" },
                            Modifier.weight(1f), valueColor = if (s.sleeveConnected == false) Tokens.Wrong else Tokens.Text,
                        )
                        val trip = when {
                            !child.navigating && s.navState == "ARRIVED" -> "Sampai"
                            !child.navigating -> "Tidak jalan"
                            s.navState == "OFF_ROUTE" -> "Keluar rute"
                            else -> "Di rute"
                        }
                        StatTile("Perjalanan", trip, Modifier.weight(1f), valueColor = if (trip == "Keluar rute") Tokens.Wrong else Tokens.Text)
                        StatTile("Baterai HP", s.batteryPct?.let { "$it%" } ?: "—", Modifier.weight(1f)) {
                            s.batteryPct?.let { battery ->
                                Spacer(Modifier.height(6.dp))
                                Box(Modifier.fillMaxWidth().height(6.dp).background(Tokens.Border, RoundedCornerShape(3.dp))) {
                                    Box(
                                        Modifier.fillMaxWidth(battery.coerceIn(0, 100) / 100f).fillMaxHeight()
                                            .background(if (battery <= 20) Tokens.Wrong else Tokens.Correct, RoundedCornerShape(3.dp))
                                    )
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    if (child.navigating) {
                        s.routeName?.let { Text("Rute $it") }
                        s.nextPoint?.let { n ->
                            Text("Titik $n dari ${s.totalPoints ?: "?"}" + (s.distanceM?.let { " · $it m lagi" } ?: ""))
                        }
                        s.obstacleCm?.let { cm ->
                            Text(if (cm >= 999) "Rintangan: kosong" else "Rintangan: $cm cm", color = AppTheme.extra.muted, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    if (s.simulated) Text("Mode uji (simulasi)", color = Tokens.Warn, style = MaterialTheme.typography.bodySmall)
                    Text(
                        (child.lastHeartbeat?.let { "Kabar terakhir ${ago(it, now)}" } ?: "Belum ada kabar") +
                            if (child.offline) " · peta menunjukkan lokasi terakhir" else "",
                        color = if (child.offline) Tokens.Wrong else AppTheme.extra.muted,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    )
                }
                val open = child.events.filter { it.type.isAlarm && it.ackedAt == null }
                if (open.isNotEmpty()) {
                    Card(background = Tokens.WrongBg, border = Tokens.Wrong) {
                        SectionLabel("Alarm belum dilihat")
                        open.take(5).forEach { e -> EventLine(e, showDate = true, now, vm::acknowledge) }
                    }
                }
            }
            Card {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        SectionLabel("Relay")
                        Text(vm.settings.collectAsState().value.relayUrl, fontFamily = Mono, style = MaterialTheme.typography.bodySmall)
                    }
                    when (stream) {
                        StreamState.LIVE -> Pill("Langsung", Tokens.Correct, Tokens.CorrectBg)
                        StreamState.CONNECTING -> Pill("Menyambung…", Tokens.Muted, Tokens.Border)
                        StreamState.PUSH -> Pill("Push", Tokens.Correct, Tokens.CorrectBg)
                        StreamState.OFF -> Pill("Mati", Tokens.Wrong, Tokens.WrongBg)
                    }
                }
                error?.let { Text(it, color = Tokens.Wrong, style = MaterialTheme.typography.bodySmall) }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun StatTile(label: String, value: String, modifier: Modifier = Modifier, valueColor: Color = Tokens.Text, extra: @Composable () -> Unit = {}) {
    Column(
        modifier.fillMaxHeight()
            .background(Tokens.Bg, RoundedCornerShape(10.dp))
            .border(1.dp, Tokens.Border, RoundedCornerShape(10.dp))
            .padding(horizontal = 8.dp, vertical = 8.dp)
    ) {
        Text(label, color = AppTheme.extra.muted, style = MaterialTheme.typography.bodySmall)
        // One line per word, so a long single word shrinks instead of breaking mid-word.
        BasicText(
            value,
            style = TextStyle(color = valueColor, fontWeight = FontWeight.Bold),
            maxLines = value.count { it == ' ' } + 1,
            autoSize = TextAutoSize.StepBased(minFontSize = 11.sp, maxFontSize = 17.sp),
        )
        extra()
    }
}

// --- Riwayat ------------------------------------------------------------------------------------

@Composable
private fun HistoryTab(child: WatchedChild?, now: Long, single: Boolean, onAck: (Long) -> Unit) {
    if (child == null) { NoChild("Riwayat", single); return }
    var mode by rememberSaveable { mutableStateOf(HistoryMode.DAY) }
    var offset by rememberSaveable { mutableIntStateOf(0) }
    val period = historyPeriod(mode, offset, now)
    val shown = remember(child.events, period) { child.events.filter { it.occurredAt >= period.from && it.occurredAt < period.until } }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        if (single) ScreenTitle("Riwayat", child.name)
        Column(Modifier.padding(horizontal = 16.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                HistoryMode.entries.forEach { m ->
                    SmallButton(m.label, Modifier.weight(1f), selected = mode == m) {
                        mode = m
                        offset = 0
                    }
                }
            }
            if (mode != HistoryMode.ALL) {
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SmallButton("‹", Modifier.width(44.dp), description = "Periode sebelumnya") { offset-- }
                    Text(
                        period.title, color = Tokens.Text, modifier = Modifier.weight(1f), textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.titleSmall,
                    )
                    SmallButton("›", Modifier.width(44.dp), enabled = offset < 0, description = "Periode berikutnya") { offset++ }
                }
            }
            val alarms = shown.count { it.type.isAlarm }
            Spacer(Modifier.height(8.dp))
            Text(
                if (shown.isNotEmpty()) "${shown.size} kejadian" + (if (alarms > 0) ", $alarms alarm" else "")
                else if (child.events.isEmpty()) "Belum ada kejadian." else "Tidak ada kejadian di periode ini.",
                color = AppTheme.extra.muted, style = MaterialTheme.typography.bodySmall,
            )
            val today = localDay(now)
            var lastDay: LocalDate? = null
            shown.forEach { e ->
                val day = localDay(e.occurredAt)
                if (mode != HistoryMode.DAY && day != lastDay) {
                    Text(
                        dayName(day, today), color = AppTheme.extra.muted, fontWeight = FontWeight.SemiBold,
                        style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 14.dp),
                    )
                } else if (lastDay != null) {
                    HorizontalDivider(Modifier.padding(top = 10.dp), color = Tokens.Border)
                }
                lastDay = day
                EventLine(e, showDate = mode != HistoryMode.DAY, now, onAck)
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun EventLine(e: RemoteEvent, showDate: Boolean, now: Long, onAck: (Long) -> Unit) {
    val (fg, bg) = when {
        e.type.isAlarm -> Tokens.Wrong to Tokens.WrongBg
        e.type.isGood -> Tokens.Correct to Tokens.CorrectBg
        else -> Tokens.Muted to Tokens.Border
    }
    Column(Modifier.padding(top = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Pill(e.type.label, fg, bg)
            Spacer(Modifier.width(10.dp))
            Text(
                SimpleDateFormat(if (showDate) "d MMM, HH:mm" else "HH:mm", Locale("id", "ID")).format(Date(e.occurredAt)),
                color = AppTheme.extra.muted, style = MaterialTheme.typography.bodySmall,
            )
        }
        if (e.message.isNotEmpty()) Text(e.message, color = Tokens.Text, style = MaterialTheme.typography.bodyMedium)
        if (e.ackedBy != null) {
            Text("Dilihat oleh ${e.ackedBy} ${e.ackedAt?.let { ago(it, now) } ?: ""}", color = Tokens.Correct, style = MaterialTheme.typography.bodySmall)
        } else if (e.type.isAlarm) {
            OutlinedButton(
                onClick = { onAck(e.id) },
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Tokens.Text),
                border = BorderStroke(1.dp, Tokens.Border),
            ) { Text("Tandai dilihat") }
        }
    }
}

@Composable
private fun SmallButton(
    text: String,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    enabled: Boolean = true,
    description: String? = null,
    onClick: () -> Unit,
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.heightIn(min = 36.dp).then(
            if (description != null) Modifier.semantics { contentDescription = description } else Modifier
        ),
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(1.dp, if (selected) Tokens.Accent else Tokens.Border),
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = if (selected) Tokens.Accent else Color.Transparent,
            contentColor = if (selected) Tokens.AccentText else Tokens.Text,
        ),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
    ) { Text(text) }
}

// --- Setelan ------------------------------------------------------------------------------------

@Composable
private fun CareSettingsTab(vm: AppViewModel) {
    val settings by vm.settings.collectAsState()
    val children by vm.children.collectAsState()
    var linking by remember { mutableStateOf(false) }
    var confirmSignOut by remember { mutableStateOf(false) }
    var confirmMode by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        ScreenTitle("Setelan", if (settings.caregiverName.isNotBlank()) "Masuk sebagai ${settings.caregiverName}" else null)
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Card {
                SectionLabel("Relay")
                Text(settings.relayUrl, fontFamily = Mono, style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(8.dp))
                SectionLabel("Anak yang diikuti")
                if (children.isEmpty()) Text("Belum ada.", color = AppTheme.extra.muted)
                children.forEach { Text(it.name) }
            }
            if (linking) {
                LinkCard(vm, settings.relayUrl, settings.caregiverName, onCancel = { linking = false }) { linking = false }
            } else {
                OutlinedButton(onClick = { vm.clearLinkResult(); linking = true }, modifier = Modifier.fillMaxWidth()) { Text("Hubungkan anak lain") }
            }
            OutlinedButton(onClick = { confirmSignOut = true }, modifier = Modifier.fillMaxWidth()) { Text("Keluar dari HP ini", color = Tokens.Wrong) }
            OutlinedButton(onClick = { confirmMode = true }, modifier = Modifier.fillMaxWidth()) { Text("Pakai HP ini sebagai HP anak") }
            Spacer(Modifier.height(16.dp))
        }
    }

    if (confirmSignOut) {
        AlertDialog(
            onDismissRequest = { confirmSignOut = false },
            containerColor = Tokens.Card,
            title = { Text("Keluar?") },
            text = { Text("Berhenti menerima alarm di HP ini?", color = AppTheme.extra.muted) },
            confirmButton = { TextButton(onClick = { vm.signOutCare(); confirmSignOut = false }) { Text("Keluar", color = Tokens.Wrong) } },
            dismissButton = { TextButton(onClick = { confirmSignOut = false }) { Text("Batal") } },
        )
    }
    if (confirmMode) {
        AlertDialog(
            onDismissRequest = { confirmMode = false },
            containerColor = Tokens.Card,
            title = { Text("Ganti ke mode Anak?") },
            text = { Text("HP ini berhenti menerima alarm anak.", color = AppTheme.extra.muted) },
            confirmButton = { TextButton(onClick = { vm.setMode(AppMode.CHILD); confirmMode = false }) { Text("Ganti") } },
            dismissButton = { TextButton(onClick = { confirmMode = false }) { Text("Batal") } },
        )
    }
}

@Composable
private fun LinkCard(vm: AppViewModel, relayUrl: String, savedName: String, onCancel: (() -> Unit)?, onDone: () -> Unit) {
    val result by vm.linkResult.collectAsState()
    val finding by vm.findingForCare.collectAsState()
    var url by remember { mutableStateOf(relayUrl) }
    var code by remember { mutableStateOf("") }
    var name by remember { mutableStateOf(savedName) }
    LaunchedEffect(result) { if (result?.startsWith(AppViewModel.LINKED) == true) onDone() }
    LaunchedEffect(relayUrl) { url = relayUrl } // "Cari relay" updates the stored address
    Card {
        SectionLabel("Hubungkan ke HP anak")
        Text("Di HP anak: Setelan → Undang orang tua. Ketik kode 6 angkanya di sini.", color = AppTheme.extra.muted)
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = code, onValueChange = { code = it.filter(Char::isDigit).take(6) },
            label = { Text("Kode undangan") },
            textStyle = CodeStyle, singleLine = true, modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        )
        OutlinedTextField(
            value = name, onValueChange = { name = it.take(40) },
            label = { Text("Nama Anda (tampil sebagai \"Dilihat oleh …\")") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = url, onValueChange = { url = it },
            label = { Text("Alamat relay") }, placeholder = { Text("https://…") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
        )
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = { vm.findRelayForCare() }, enabled = !finding) { Text(if (finding) "Mencari…" else "Cari relay di WiFi ini") }
        Spacer(Modifier.height(8.dp))
        Button(
            onClick = { vm.link(url, code, name) },
            enabled = code.length == 6 && name.isNotBlank() && url.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Hubungkan") }
        if (onCancel != null) {
            OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) { Text("Batal") }
        }
        result?.let {
            Text(
                it, color = if (it.startsWith(AppViewModel.LINKED)) Tokens.Correct else Tokens.Wrong,
                modifier = Modifier.padding(top = 8.dp).semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
    }
}
