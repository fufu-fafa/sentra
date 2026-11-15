package com.sentra.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material.icons.rounded.Bluetooth
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ChildCare
import androidx.compose.material.icons.rounded.Flag
import androidx.compose.material.icons.rounded.GroupAdd
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.LinkOff
import androidx.compose.material.icons.rounded.LocationOn
import androidx.compose.material.icons.rounded.MyLocation
import androidx.compose.material.icons.rounded.Navigation
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.Router
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SmallFloatingActionButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.sentra.alert.CareEventType
import com.sentra.care.CareTrip
import com.sentra.care.HistoryMode
import com.sentra.care.RemoteEvent
import com.sentra.care.StreamState
import com.sentra.care.WatchedChild
import com.sentra.care.ago
import com.sentra.care.careTrips
import com.sentra.care.dayName
import com.sentra.care.historyPeriod
import com.sentra.care.localDay
import com.sentra.data.AppMode
import com.sentra.ui.theme.AppTheme
import com.sentra.ui.theme.Mono
import com.sentra.ui.theme.Tokens
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

private enum class CareTab(val label: String, val icon: ImageVector) {
    Home("Beranda", Icons.Rounded.Home),
    History("Riwayat", Icons.Rounded.Schedule),
    Alerts("Alarm", Icons.Rounded.Notifications),
    Settings("Setelan", Icons.Rounded.Settings),
}

private val ID = Locale("id", "ID")

/** Mode Orang tua: SENTRA Care. Link with an invite code, then dashboard, live map, trips and alarms. */
@Composable
fun CareScreen(vm: AppViewModel) {
    val settings by vm.settings.collectAsState()
    val children by vm.children.collectAsState()
    val linked = settings.parentToken != null
    var tab by rememberSaveable { mutableStateOf(CareTab.Home) }
    var mapOpen by rememberSaveable { mutableStateOf(false) }
    var selectedId by rememberSaveable { mutableLongStateOf(-1L) }
    // Keeps "12 dtk lalu" current.
    val now by produceState(System.currentTimeMillis()) {
        while (true) {
            delay(5_000)
            value = System.currentTimeMillis()
        }
    }

    if (!linked) {
        PageBackground(top = Tokens.PinkSoft) {
            Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().verticalScroll(rememberScrollState())) {
                Box(Modifier.fillMaxWidth().padding(top = 28.dp, bottom = 20.dp), contentAlignment = Alignment.Center) {
                    SentraLogo(130.dp, care = true)
                }
                Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    LinkCard(vm, settings.relayUrl, settings.parentName, onCancel = null) {}
                    SoftButton("Pakai HP ini sebagai HP anak", { vm.setMode(AppMode.CHILD) }, Modifier.fillMaxWidth(), icon = Icons.Rounded.SwapHoriz)
                    Spacer(Modifier.height(16.dp))
                }
            }
        }
        return
    }

    val child = children.firstOrNull { it.id == selectedId } ?: children.firstOrNull()

    if (mapOpen) {
        BackHandler { mapOpen = false }
        PageBackground(top = Tokens.PinkSoft) { LiveMap(child, now) { mapOpen = false } }
        return
    }

    val unseen = children.sumOf { c -> c.events.count { it.type.isAlarm && it.ackedAt == null } }
    Scaffold(
        containerColor = Tokens.Bg,
        contentWindowInsets = WindowInsets(0),
        bottomBar = {
            BottomTabs(CareTab.entries.map { Triple(if (it == CareTab.Alerts && unseen > 0) "Alarm ($unseen)" else it.label, it.icon, tab == it) }) {
                tab = CareTab.entries[it]
            }
        },
    ) { pad ->
        PageBackground(Modifier.padding(pad), top = Tokens.PinkSoft) {
            Column(Modifier.fillMaxSize()) {
                val picker: @Composable () -> Unit = {
                    if (children.size > 1) {
                        Row(
                            Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            children.forEach { c -> Chip(c.name, selected = c.id == child?.id) { selectedId = c.id } }
                        }
                    }
                }
                when (tab) {
                    CareTab.Home -> Dashboard(vm, child, now, picker, unseen, openAlerts = { tab = CareTab.Alerts }, openMap = { mapOpen = true })
                    CareTab.History -> TripHistory(child, now, picker)
                    CareTab.Alerts -> Alerts(child, now, picker, vm::acknowledge)
                    CareTab.Settings -> CareSettingsTab(vm)
                }
            }
        }
    }
}

@Composable
private fun NoChild() {
    Card(Modifier.padding(16.dp)) {
        Text("Belum mengikuti siapa pun. Hubungkan ke HP anak di tab Setelan.", color = AppTheme.extra.muted)
    }
}

private data class Look(val text: String, val fg: Color, val bg: Color)

private fun childLook(c: WatchedChild): Look = when {
    c.offline -> Look("HP offline", Tokens.Wrong, Tokens.WrongBg)
    c.navigating && c.status.navState == "OFF_ROUTE" -> Look("Keluar rute", Tokens.Wrong, Tokens.WrongBg)
    c.navigating -> Look("Di perjalanan", Tokens.Correct, Tokens.CorrectBg)
    c.lastHeartbeat != null -> Look("Online", Tokens.Correct, Tokens.CorrectBg)
    else -> Look("Belum ada kabar", Tokens.Muted, Tokens.AccentSoft)
}

// --- Dashboard -----------------------------------------------------------------------------------

@Composable
private fun Dashboard(
    vm: AppViewModel,
    child: WatchedChild?,
    now: Long,
    picker: @Composable () -> Unit,
    unseen: Int,
    openAlerts: () -> Unit,
    openMap: () -> Unit,
) {
    val stream by vm.streamState.collectAsState()
    val error by vm.careError.collectAsState()
    val settings by vm.settings.collectAsState()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        ScreenTitle("Dashboard") {
            IconButton(onClick = openAlerts) {
                BadgedBox(badge = { if (unseen > 0) Badge(containerColor = Tokens.Pink) { Text("$unseen") } }) {
                    Icon(
                        if (unseen > 0) Icons.Rounded.NotificationsActive else Icons.Rounded.Notifications,
                        contentDescription = if (unseen > 0) "$unseen alarm belum dilihat" else "Alarm",
                        tint = Tokens.Pink,
                    )
                }
            }
        }
        picker()
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            SetupWarnings(childMode = false)
            if (child == null) {
                NoChild()
            } else {
                val s = child.status
                val look = childLook(child)
                Card {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Avatar(child.name, 56.dp)
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f).semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite }) {
                            Text(child.name, style = MaterialTheme.typography.titleLarge, color = Tokens.Text)
                            StatusDot(look.text, look.fg)
                            Text(
                                (child.lastHeartbeat?.let { "Kabar terakhir ${ago(it, now)}" } ?: "Belum ada kabar") +
                                    if (child.offline) " · peta menunjukkan lokasi terakhir" else "",
                                color = if (child.offline) Tokens.Wrong else AppTheme.extra.muted,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
                Card {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconBubble(Icons.Rounded.Bluetooth, Color.White, if (s.sleeveConnected == true) Tokens.Brand else Tokens.Muted, 52.dp)
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text("Status Sleeve", style = MaterialTheme.typography.titleMedium, color = Tokens.Text)
                            when (s.sleeveConnected) {
                                true -> StatusDot("Tersambung", Tokens.Correct)
                                false -> StatusDot("Terputus", Tokens.Wrong)
                                null -> Text("—", color = AppTheme.extra.muted)
                            }
                            s.batteryPct?.let { BatteryBar(it) }
                        }
                    }
                }
                Card {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconBubble(Icons.Rounded.Navigation, Tokens.Accent, Tokens.AccentSoft, 52.dp)
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f).semantics(mergeDescendants = true) {}) {
                            Text("Aktivitas Sekarang", style = MaterialTheme.typography.titleMedium, color = Tokens.Text)
                            Text(activityLine(child), style = MaterialTheme.typography.bodyMedium, color = Tokens.Text)
                            if (child.navigating) {
                                s.nextPoint?.let { n ->
                                    Text(
                                        "Titik $n dari ${s.totalPoints ?: "?"}" + (s.distanceM?.let { " · $it m lagi" } ?: ""),
                                        style = MaterialTheme.typography.bodySmall, color = AppTheme.extra.muted,
                                    )
                                }
                                s.obstacleCm?.let { cm ->
                                    Text(if (cm >= 999) "Rintangan: kosong" else "Rintangan: $cm cm", color = AppTheme.extra.muted, style = MaterialTheme.typography.bodySmall)
                                }
                            }
                            if (s.simulated) Text("Mode uji (simulasi)", color = Tokens.Warn, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                val open = child.events.filter { it.type.isAlarm && it.ackedAt == null }
                if (open.isNotEmpty()) {
                    Card(background = Tokens.WrongBg, border = Tokens.Wrong) {
                        SectionLabel("Alarm belum dilihat", icon = Icons.Rounded.WarningAmber, tint = Tokens.Wrong)
                        open.take(3).forEach { e -> EventLine(e, showDate = true, now, vm::acknowledge) }
                    }
                }
                PrimaryButton("Lihat Peta Live", openMap, icon = Icons.Rounded.LocationOn, color = Tokens.Pink)
            }
            Card(padding = 12.dp) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Router, null, tint = AppTheme.extra.muted, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(settings.relayUrl, fontFamily = Mono, style = MaterialTheme.typography.bodySmall, color = AppTheme.extra.muted, modifier = Modifier.weight(1f))
                    when (stream) {
                        StreamState.LIVE -> Pill("Langsung", Tokens.Correct, Tokens.CorrectBg, dot = true)
                        StreamState.CONNECTING -> Pill("Menyambung…", Tokens.Muted, Tokens.AccentSoft)
                        StreamState.PUSH -> Pill("Push", Tokens.Correct, Tokens.CorrectBg)
                        StreamState.OFF -> Pill("Mati", Tokens.Wrong, Tokens.WrongBg)
                    }
                }
                error?.let { Text(it, color = Tokens.Wrong, style = MaterialTheme.typography.bodySmall) }
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

private fun activityLine(c: WatchedChild): String {
    val s = c.status
    return when {
        c.navigating && s.navState == "OFF_ROUTE" -> "Keluar rute" + (s.routeName?.let { " menuju $it" } ?: "")
        c.navigating -> "Menuju " + (s.routeName ?: "tujuan")
        s.navState == "ARRIVED" -> "Sudah sampai" + (s.routeName?.let { " di $it" } ?: "")
        else -> "Tidak sedang berjalan"
    }
}

@Composable
private fun BatteryBar(pct: Int) {
    Row(Modifier.padding(top = 4.dp).semantics(mergeDescendants = true) { contentDescription = "Baterai HP anak $pct persen" }, verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(36.dp).height(10.dp).background(Tokens.AccentSoft, RoundedCornerShape(3.dp))) {
            Box(
                Modifier.fillMaxWidth(pct.coerceIn(0, 100) / 100f).fillMaxHeight()
                    .background(if (pct <= 20) Tokens.Wrong else Color(0xFF2FB67C), RoundedCornerShape(3.dp))
            )
        }
        Spacer(Modifier.width(6.dp))
        Text("$pct% HP anak", style = MaterialTheme.typography.bodySmall, color = AppTheme.extra.muted)
    }
}

// --- Peta Live -----------------------------------------------------------------------------------

@Composable
private fun LiveMap(child: WatchedChild?, now: Long, onBack: () -> Unit) {
    var follow by rememberSaveable { mutableStateOf(true) }
    Column(Modifier.fillMaxSize().navigationBarsPadding()) {
        TopBar("Peta Live", onBack) {
            IconButton(onClick = { follow = true }) { Icon(Icons.Rounded.MyLocation, contentDescription = "Ke posisi anak", tint = Tokens.Text) }
        }
        if (child == null) { NoChild(); return@Column }
        val loc = child.location
        val route = if (child.navigating) child.status.route else emptyList()
        Box(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp).clip(RoundedCornerShape(24.dp))) {
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
            if (!follow && loc != null) {
                SmallFloatingActionButton(
                    onClick = { follow = true }, containerColor = Tokens.Card, contentColor = Tokens.Blue,
                    modifier = Modifier.align(Alignment.BottomEnd).padding(12.dp),
                ) { Icon(Icons.Rounded.MyLocation, contentDescription = "Ke posisi anak") }
            }
        }
        // What the map means, in words: for TalkBack and at a glance.
        Card(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite }) {
                val look = childLook(child)
                IconBubble(Icons.Rounded.Navigation, look.fg, look.bg, 46.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("${child.name} · ${look.text}", style = MaterialTheme.typography.titleMedium, color = Tokens.Text)
                    val s = child.status
                    if (child.navigating) {
                        Text(
                            listOfNotNull(s.routeName?.let { "Rute $it" }, s.distanceM?.let { "$it m ke titik ${s.nextPoint ?: "berikut"}" }).joinToString(" · "),
                            style = MaterialTheme.typography.bodyMedium, color = Tokens.Text,
                        )
                    }
                    Text(
                        if (loc == null) "Belum ada lokasi" else "Lokasi ${ago(loc.at, now)}" + (loc.accuracyM?.let { " · ±${it.toInt()} m" } ?: ""),
                        color = AppTheme.extra.muted, style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}

// --- Riwayat Perjalanan --------------------------------------------------------------------------

@Composable
private fun TripHistory(child: WatchedChild?, now: Long, picker: @Composable () -> Unit) {
    var mode by rememberSaveable { mutableStateOf(HistoryMode.ALL) }
    var offset by rememberSaveable { mutableIntStateOf(0) }
    var expanded by rememberSaveable { mutableLongStateOf(-1L) }
    val period = historyPeriod(mode, offset, now)
    val trips = remember(child?.events) { careTrips(child?.events.orEmpty()) }
    val shown = trips.filter { it.startedAt >= period.from && it.startedAt < period.until }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        ScreenTitle("Riwayat Perjalanan", child?.name)
        picker()
        if (child == null) { NoChild(); return@Column }
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(HistoryMode.ALL to "Semua", HistoryMode.DAY to "Hari ini", HistoryMode.WEEK to "Minggu ini").forEach { (m, label) ->
                    Chip(if (mode == m && offset != 0) period.title else label, Modifier.weight(1f), selected = mode == m) {
                        mode = m
                        offset = 0
                    }
                }
            }
            if (mode != HistoryMode.ALL) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { offset-- }) { Icon(Icons.AutoMirrored.Rounded.KeyboardArrowLeft, "Periode sebelumnya", tint = Tokens.Text) }
                    Text(period.title, color = Tokens.Text, modifier = Modifier.weight(1f), textAlign = TextAlign.Center, style = MaterialTheme.typography.titleSmall)
                    IconButton(onClick = { offset++ }, enabled = offset < 0) {
                        Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, "Periode berikutnya", tint = if (offset < 0) Tokens.Text else Tokens.Border)
                    }
                }
            }
            if (shown.isEmpty()) {
                Text(
                    if (trips.isEmpty()) "Belum ada perjalanan." else "Tidak ada perjalanan di periode ini.",
                    color = AppTheme.extra.muted, modifier = Modifier.padding(8.dp),
                )
            }
            shown.forEachIndexed { i, t ->
                val ongoing = i == 0 && offset == 0 && t.endedAt == null && child.navigating && t === trips.first()
                TripCard(t, ongoing, now, expanded == t.startedAt) { expanded = if (expanded == t.startedAt) -1L else t.startedAt }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun TripCard(t: CareTrip, ongoing: Boolean, now: Long, expanded: Boolean, onToggle: () -> Unit) {
    val today = localDay(now)
    val day = localDay(t.startedAt)
    val hm = SimpleDateFormat("HH:mm", ID)
    val dayText = if (day == today) "" else dayName(day, today) + ", "
    val timeText = dayText + hm.format(Date(t.startedAt)) + (t.endedAt?.let { " – " + hm.format(Date(it)) } ?: "")
    val minutes = t.endedAt?.let { ((it - t.startedAt) / 60_000.0).roundToInt().coerceAtLeast(1) }
    val (pillText, fg, bg) = when {
        ongoing -> Triple("Berjalan", Tokens.Accent, Tokens.AccentSoft)
        t.outcome == CareEventType.ARRIVED -> Triple("Sampai", Tokens.Correct, Tokens.CorrectBg)
        t.outcome == CareEventType.NAV_STOP -> Triple("Dihentikan", Tokens.Warn, Tokens.WarnBg)
        else -> Triple("Tanpa akhir", Tokens.Muted, Tokens.AccentSoft)
    }
    Card(onClick = onToggle, onClickLabel = if (expanded) "Tutup rincian" else "Lihat rincian", padding = 14.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.LocationOn, null, tint = Tokens.Pink, modifier = Modifier.size(28.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(t.routeName ?: "Perjalanan", style = MaterialTheme.typography.titleSmall, color = Tokens.Text)
                Text(timeText + (minutes?.let { "  ($it mnt)" } ?: ""), style = MaterialTheme.typography.bodySmall, color = AppTheme.extra.muted)
                if (t.alarms > 0) Text("${t.alarms} alarm", style = MaterialTheme.typography.bodySmall, color = Tokens.Wrong, fontWeight = FontWeight.SemiBold)
            }
            Pill(pillText, fg, bg)
        }
        if (expanded) {
            t.events.forEach { e -> EventLine(e, showDate = false, now, onAck = null) }
        }
    }
}

// --- Alarm ---------------------------------------------------------------------------------------

private val ROUTINE = setOf(CareEventType.NAV_START, CareEventType.BELOK_KIRI, CareEventType.BELOK_KANAN, CareEventType.PUTAR_BALIK)

@Composable
private fun Alerts(child: WatchedChild?, now: Long, picker: @Composable () -> Unit, onAck: (Long) -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        ScreenTitle("Alarm", child?.name)
        picker()
        if (child == null) { NoChild(); return@Column }
        val shown = child.events.filter { it.type !in ROUTINE }
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (shown.isEmpty()) Text("Belum ada alarm atau kabar.", color = AppTheme.extra.muted, modifier = Modifier.padding(8.dp))
            shown.take(100).forEach { AlertCard(it, now, onAck) }
            Spacer(Modifier.height(16.dp))
        }
    }
}

private fun alertLook(t: CareEventType): Triple<ImageVector, Color, Color> = when (t) {
    CareEventType.KELUAR_RUTE -> Triple(Icons.Rounded.WarningAmber, Tokens.Warn, Tokens.WarnBg)
    CareEventType.TERPUTUS, CareEventType.OFFLINE -> Triple(Icons.Rounded.LinkOff, Tokens.Accent, Tokens.AccentSoft)
    CareEventType.ARRIVED -> Triple(Icons.Rounded.CheckCircle, Tokens.Correct, Tokens.CorrectBg)
    CareEventType.TERSAMBUNG, CareEventType.KEMBALI_RUTE -> Triple(Icons.Rounded.CheckCircle, Tokens.Correct, Tokens.CorrectBg)
    CareEventType.NAV_STOP -> Triple(Icons.Rounded.Flag, Tokens.Warn, Tokens.WarnBg)
    else -> Triple(Icons.Rounded.Info, Tokens.Accent, Tokens.AccentSoft)
}

@Composable
private fun AlertCard(e: RemoteEvent, now: Long, onAck: (Long) -> Unit) {
    val (icon, fg, bg) = alertLook(e.type)
    val open = e.type.isAlarm && e.ackedAt == null
    Card(background = bg, border = if (open) Tokens.Wrong else bg, padding = 14.dp) {
        Row(verticalAlignment = Alignment.Top) {
            Icon(icon, null, tint = fg, modifier = Modifier.size(32.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(e.type.label, style = MaterialTheme.typography.titleMedium, color = Tokens.Text)
                Text(timeText(e.occurredAt, now), style = MaterialTheme.typography.bodySmall, color = AppTheme.extra.muted)
                if (e.message.isNotEmpty()) Text(e.message, style = MaterialTheme.typography.bodyMedium, color = Tokens.Text, modifier = Modifier.padding(top = 4.dp))
                if (e.ackedBy != null) {
                    Text("Dilihat oleh ${e.ackedBy} ${e.ackedAt?.let { ago(it, now) } ?: ""}", color = Tokens.Correct, style = MaterialTheme.typography.bodySmall)
                } else if (open) {
                    Spacer(Modifier.height(8.dp))
                    SoftButton("Tandai dilihat", { onAck(e.id) }, color = Tokens.Wrong, background = Tokens.Card)
                }
            }
        }
    }
}

private fun timeText(at: Long, now: Long): String {
    val day = localDay(at)
    val today = localDay(now)
    val hm = SimpleDateFormat("HH:mm", ID).format(Date(at))
    return if (day == today) hm else "${dayName(day, today)}, $hm"
}

@Composable
private fun EventLine(e: RemoteEvent, showDate: Boolean, now: Long, onAck: ((Long) -> Unit)?) {
    val (fg, bg) = when {
        e.type.isAlarm -> Tokens.Wrong to Tokens.WrongBg
        e.type.isGood -> Tokens.Correct to Tokens.CorrectBg
        else -> Tokens.Muted to Tokens.AccentSoft
    }
    Column(Modifier.padding(top = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Pill(e.type.label, fg, bg)
            Spacer(Modifier.width(10.dp))
            Text(
                SimpleDateFormat(if (showDate) "d MMM, HH:mm" else "HH:mm", ID).format(Date(e.occurredAt)),
                color = AppTheme.extra.muted, style = MaterialTheme.typography.bodySmall,
            )
        }
        if (e.message.isNotEmpty()) Text(e.message, color = Tokens.Text, style = MaterialTheme.typography.bodyMedium)
        if (e.ackedBy != null) {
            Text("Dilihat oleh ${e.ackedBy} ${e.ackedAt?.let { ago(it, now) } ?: ""}", color = Tokens.Correct, style = MaterialTheme.typography.bodySmall)
        } else if (e.type.isAlarm && onAck != null) {
            Spacer(Modifier.height(4.dp))
            SoftButton("Tandai dilihat", { onAck(e.id) }, color = Tokens.Wrong, background = Tokens.Card)
        }
    }
}

/** A rounded filter chip: Semua / Hari ini / Minggu ini, or a child's name. */
@Composable
private fun Chip(text: String, modifier: Modifier = Modifier, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier
            .heightIn(min = 40.dp)
            .clip(RoundedCornerShape(50))
            .background(if (selected) Tokens.PinkSoft else Tokens.Card, RoundedCornerShape(50))
            .border(1.dp, if (selected) Tokens.PinkLight else Tokens.Border, RoundedCornerShape(50))
            .semantics { role = Role.Tab; this.selected = selected }
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            color = if (selected) Tokens.Pink else Tokens.Text,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            style = MaterialTheme.typography.labelLarge,
            maxLines = 1,
        )
    }
}

// --- Setelan -------------------------------------------------------------------------------------

@Composable
private fun CareSettingsTab(vm: AppViewModel) {
    val settings by vm.settings.collectAsState()
    val children by vm.children.collectAsState()
    var linking by remember { mutableStateOf(false) }
    var confirmSignOut by remember { mutableStateOf(false) }
    var confirmMode by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        ScreenTitle("Setelan", if (settings.parentName.isNotBlank()) "Masuk sebagai ${settings.parentName}" else null)
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Card {
                SectionLabel("Anak yang diikuti", icon = Icons.Rounded.ChildCare, tint = Tokens.Pink)
                if (children.isEmpty()) Text("Belum ada.", color = AppTheme.extra.muted)
                children.forEach { c ->
                    Row(Modifier.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Avatar(c.name, 36.dp)
                        Spacer(Modifier.width(10.dp))
                        Text(c.name, style = MaterialTheme.typography.bodyLarge, color = Tokens.Text)
                    }
                }
                Spacer(Modifier.height(8.dp))
                SectionLabel("Relay", icon = Icons.Rounded.Router)
                Text(settings.relayUrl, fontFamily = Mono, style = MaterialTheme.typography.bodySmall, color = AppTheme.extra.muted)
            }
            if (linking) {
                LinkCard(vm, settings.relayUrl, settings.parentName, onCancel = { linking = false }) { linking = false }
            } else {
                SoftButton("Hubungkan anak lain", { vm.clearLinkResult(); linking = true }, Modifier.fillMaxWidth(), icon = Icons.Rounded.GroupAdd, color = Tokens.Pink, background = Tokens.PinkSoft)
            }
            SoftButton("Pakai HP ini sebagai HP anak", { confirmMode = true }, Modifier.fillMaxWidth(), icon = Icons.Rounded.SwapHoriz)
            SoftButton("Keluar dari HP ini", { confirmSignOut = true }, Modifier.fillMaxWidth(), icon = Icons.AutoMirrored.Rounded.Logout, color = Tokens.Wrong, background = Tokens.WrongBg)
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
        SectionLabel("Hubungkan ke HP anak", icon = Icons.Rounded.GroupAdd, tint = Tokens.Pink)
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
        Spacer(Modifier.height(10.dp))
        SoftButton(if (finding) "Mencari…" else "Cari relay di WiFi ini", { vm.findRelayForCare() }, enabled = !finding, icon = Icons.Rounded.Router)
        Spacer(Modifier.height(10.dp))
        PrimaryButton(
            "Hubungkan", { vm.link(url, code, name) },
            enabled = code.length == 6 && name.isNotBlank() && url.isNotBlank(),
            color = Tokens.Pink,
        )
        if (onCancel != null) {
            Spacer(Modifier.height(8.dp))
            SoftButton("Batal", onCancel, Modifier.fillMaxWidth())
        }
        result?.let {
            Text(
                it, color = if (it.startsWith(AppViewModel.LINKED)) Tokens.Correct else Tokens.Wrong,
                modifier = Modifier.padding(top = 8.dp).semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
    }
}
