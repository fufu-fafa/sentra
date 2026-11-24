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
    Home("Home", Icons.Rounded.Home),
    History("History", Icons.Rounded.Schedule),
    Alerts("Alerts", Icons.Rounded.Notifications),
    Settings("Settings", Icons.Rounded.Settings),
}

private val EN = Locale.ENGLISH

/** Parent mode: SENTRA Care. Link with an invite code, then dashboard, live map, trips and alarms. */
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
                    SoftButton("Use this phone as the child's phone", { vm.setMode(AppMode.CHILD) }, Modifier.fillMaxWidth(), icon = Icons.Rounded.SwapHoriz)
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
            BottomTabs(CareTab.entries.map { Triple(if (it == CareTab.Alerts && unseen > 0) "Alerts ($unseen)" else it.label, it.icon, tab == it) }) {
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
        Text("Not following anyone yet. Link to the child's phone in the Settings tab.", color = AppTheme.extra.muted)
    }
}

private data class Look(val text: String, val fg: Color, val bg: Color)

private fun childLook(c: WatchedChild): Look = when {
    c.offline -> Look("Phone offline", Tokens.Wrong, Tokens.WrongBg)
    c.navigating && c.status.navState == "OFF_ROUTE" -> Look("Off route", Tokens.Wrong, Tokens.WrongBg)
    c.navigating -> Look("On the way", Tokens.Correct, Tokens.CorrectBg)
    c.lastHeartbeat != null -> Look("Online", Tokens.Correct, Tokens.CorrectBg)
    else -> Look("No word yet", Tokens.Muted, Tokens.AccentSoft)
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
                        contentDescription = if (unseen > 0) "$unseen unseen alerts" else "Alerts",
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
                                (child.lastHeartbeat?.let { "Last heard ${ago(it, now)}" } ?: "No word yet") +
                                    if (child.offline) " · map shows last known location" else "",
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
                                true -> StatusDot("Connected", Tokens.Correct)
                                false -> StatusDot("Disconnected", Tokens.Wrong)
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
                            Text("Current Activity", style = MaterialTheme.typography.titleMedium, color = Tokens.Text)
                            Text(activityLine(child), style = MaterialTheme.typography.bodyMedium, color = Tokens.Text)
                            if (child.navigating) {
                                s.nextPoint?.let { n ->
                                    Text(
                                        "Point $n of ${s.totalPoints ?: "?"}" + (s.distanceM?.let { " · $it m to go" } ?: ""),
                                        style = MaterialTheme.typography.bodySmall, color = AppTheme.extra.muted,
                                    )
                                }
                                s.obstacleCm?.let { cm ->
                                    Text(if (cm >= 999) "Obstacle: clear" else "Obstacle: $cm cm", color = AppTheme.extra.muted, style = MaterialTheme.typography.bodySmall)
                                }
                            }
                            if (s.simulated) Text("Test mode (simulated)", color = Tokens.Warn, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                val open = child.events.filter { it.type.isAlarm && it.ackedAt == null }
                if (open.isNotEmpty()) {
                    Card(background = Tokens.WrongBg, border = Tokens.Wrong) {
                        SectionLabel("Unseen alerts", icon = Icons.Rounded.WarningAmber, tint = Tokens.Wrong)
                        open.take(3).forEach { e -> EventLine(e, showDate = true, now, vm::acknowledge) }
                    }
                }
                PrimaryButton("View Live Map", openMap, icon = Icons.Rounded.LocationOn, color = Tokens.Pink)
            }
            Card(padding = 12.dp) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Router, null, tint = AppTheme.extra.muted, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(settings.relayUrl, fontFamily = Mono, style = MaterialTheme.typography.bodySmall, color = AppTheme.extra.muted, modifier = Modifier.weight(1f))
                    when (stream) {
                        StreamState.LIVE -> Pill("Live", Tokens.Correct, Tokens.CorrectBg, dot = true)
                        StreamState.CONNECTING -> Pill("Connecting…", Tokens.Muted, Tokens.AccentSoft)
                        StreamState.PUSH -> Pill("Push", Tokens.Correct, Tokens.CorrectBg)
                        StreamState.OFF -> Pill("Off", Tokens.Wrong, Tokens.WrongBg)
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
        c.navigating && s.navState == "OFF_ROUTE" -> "Off route" + (s.routeName?.let { " to $it" } ?: "")
        c.navigating -> "Heading to " + (s.routeName ?: "destination")
        s.navState == "ARRIVED" -> "Arrived" + (s.routeName?.let { " at $it" } ?: "")
        else -> "Not walking"
    }
}

@Composable
private fun BatteryBar(pct: Int) {
    Row(Modifier.padding(top = 4.dp).semantics(mergeDescendants = true) { contentDescription = "Child's phone battery $pct percent" }, verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(36.dp).height(10.dp).background(Tokens.AccentSoft, RoundedCornerShape(3.dp))) {
            Box(
                Modifier.fillMaxWidth(pct.coerceIn(0, 100) / 100f).fillMaxHeight()
                    .background(if (pct <= 20) Tokens.Wrong else Color(0xFF2FB67C), RoundedCornerShape(3.dp))
            )
        }
        Spacer(Modifier.width(6.dp))
        Text("$pct% child's phone", style = MaterialTheme.typography.bodySmall, color = AppTheme.extra.muted)
    }
}

// --- Live Map ------------------------------------------------------------------------------------

@Composable
private fun LiveMap(child: WatchedChild?, now: Long, onBack: () -> Unit) {
    var follow by rememberSaveable { mutableStateOf(true) }
    Column(Modifier.fillMaxSize().navigationBarsPadding()) {
        TopBar("Live Map", onBack) {
            IconButton(onClick = { follow = true }) { Icon(Icons.Rounded.MyLocation, contentDescription = "Go to child's position", tint = Tokens.Text) }
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
                ) { Icon(Icons.Rounded.MyLocation, contentDescription = "Go to child's position") }
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
                            listOfNotNull(s.routeName?.let { "Route $it" }, s.distanceM?.let { "$it m to point ${s.nextPoint ?: "next"}" }).joinToString(" · "),
                            style = MaterialTheme.typography.bodyMedium, color = Tokens.Text,
                        )
                    }
                    Text(
                        if (loc == null) "No location yet" else "Location ${ago(loc.at, now)}" + (loc.accuracyM?.let { " · ±${it.toInt()} m" } ?: ""),
                        color = AppTheme.extra.muted, style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}

// --- Trip History --------------------------------------------------------------------------------

@Composable
private fun TripHistory(child: WatchedChild?, now: Long, picker: @Composable () -> Unit) {
    var mode by rememberSaveable { mutableStateOf(HistoryMode.ALL) }
    var offset by rememberSaveable { mutableIntStateOf(0) }
    var expanded by rememberSaveable { mutableLongStateOf(-1L) }
    val period = historyPeriod(mode, offset, now)
    val trips = remember(child?.events) { careTrips(child?.events.orEmpty()) }
    val shown = trips.filter { it.startedAt >= period.from && it.startedAt < period.until }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        ScreenTitle("Trip History", child?.name)
        picker()
        if (child == null) { NoChild(); return@Column }
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(HistoryMode.ALL to "All", HistoryMode.DAY to "Today", HistoryMode.WEEK to "This week").forEach { (m, label) ->
                    Chip(if (mode == m && offset != 0) period.title else label, Modifier.weight(1f), selected = mode == m) {
                        mode = m
                        offset = 0
                    }
                }
            }
            if (mode != HistoryMode.ALL) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { offset-- }) { Icon(Icons.AutoMirrored.Rounded.KeyboardArrowLeft, "Previous period", tint = Tokens.Text) }
                    Text(period.title, color = Tokens.Text, modifier = Modifier.weight(1f), textAlign = TextAlign.Center, style = MaterialTheme.typography.titleSmall)
                    IconButton(onClick = { offset++ }, enabled = offset < 0) {
                        Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, "Next period", tint = if (offset < 0) Tokens.Text else Tokens.Border)
                    }
                }
            }
            if (shown.isEmpty()) {
                Text(
                    if (trips.isEmpty()) "No trips yet." else "No trips in this period.",
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
    val hm = SimpleDateFormat("HH:mm", EN)
    val dayText = if (day == today) "" else dayName(day, today) + ", "
    val timeText = dayText + hm.format(Date(t.startedAt)) + (t.endedAt?.let { " – " + hm.format(Date(it)) } ?: "")
    val minutes = t.endedAt?.let { ((it - t.startedAt) / 60_000.0).roundToInt().coerceAtLeast(1) }
    val (pillText, fg, bg) = when {
        ongoing -> Triple("Walking", Tokens.Accent, Tokens.AccentSoft)
        t.outcome == CareEventType.ARRIVED -> Triple("Arrived", Tokens.Correct, Tokens.CorrectBg)
        t.outcome == CareEventType.NAV_STOP -> Triple("Stopped", Tokens.Warn, Tokens.WarnBg)
        else -> Triple("No end", Tokens.Muted, Tokens.AccentSoft)
    }
    Card(onClick = onToggle, onClickLabel = if (expanded) "Hide details" else "Show details", padding = 14.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.LocationOn, null, tint = Tokens.Pink, modifier = Modifier.size(28.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(t.routeName ?: "Trip", style = MaterialTheme.typography.titleSmall, color = Tokens.Text)
                Text(timeText + (minutes?.let { "  ($it min)" } ?: ""), style = MaterialTheme.typography.bodySmall, color = AppTheme.extra.muted)
                if (t.alarms > 0) Text("${t.alarms} ${if (t.alarms == 1) "alarm" else "alarms"}", style = MaterialTheme.typography.bodySmall, color = Tokens.Wrong, fontWeight = FontWeight.SemiBold)
            }
            Pill(pillText, fg, bg)
        }
        if (expanded) {
            t.events.forEach { e -> EventLine(e, showDate = false, now, onAck = null) }
        }
    }
}

// --- Alarm ---------------------------------------------------------------------------------------

private val ROUTINE = setOf(CareEventType.NAV_START, CareEventType.TURN_LEFT, CareEventType.TURN_RIGHT, CareEventType.U_TURN)

@Composable
private fun Alerts(child: WatchedChild?, now: Long, picker: @Composable () -> Unit, onAck: (Long) -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        ScreenTitle("Alerts", child?.name)
        picker()
        if (child == null) { NoChild(); return@Column }
        val shown = child.events.filter { it.type !in ROUTINE }
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (shown.isEmpty()) Text("No alerts or updates yet.", color = AppTheme.extra.muted, modifier = Modifier.padding(8.dp))
            shown.take(100).forEach { AlertCard(it, now, onAck) }
            Spacer(Modifier.height(16.dp))
        }
    }
}

private fun alertLook(t: CareEventType): Triple<ImageVector, Color, Color> = when (t) {
    CareEventType.OFF_ROUTE -> Triple(Icons.Rounded.WarningAmber, Tokens.Warn, Tokens.WarnBg)
    CareEventType.SLEEVE_DISCONNECTED, CareEventType.OFFLINE -> Triple(Icons.Rounded.LinkOff, Tokens.Accent, Tokens.AccentSoft)
    CareEventType.ARRIVED -> Triple(Icons.Rounded.CheckCircle, Tokens.Correct, Tokens.CorrectBg)
    CareEventType.SLEEVE_CONNECTED, CareEventType.BACK_ON_ROUTE -> Triple(Icons.Rounded.CheckCircle, Tokens.Correct, Tokens.CorrectBg)
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
                    Text("Seen by ${e.ackedBy} ${e.ackedAt?.let { ago(it, now) } ?: ""}", color = Tokens.Correct, style = MaterialTheme.typography.bodySmall)
                } else if (open) {
                    Spacer(Modifier.height(8.dp))
                    SoftButton("Mark as seen", { onAck(e.id) }, color = Tokens.Wrong, background = Tokens.Card)
                }
            }
        }
    }
}

private fun timeText(at: Long, now: Long): String {
    val day = localDay(at)
    val today = localDay(now)
    val hm = SimpleDateFormat("HH:mm", EN).format(Date(at))
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
                SimpleDateFormat(if (showDate) "d MMM, HH:mm" else "HH:mm", EN).format(Date(e.occurredAt)),
                color = AppTheme.extra.muted, style = MaterialTheme.typography.bodySmall,
            )
        }
        if (e.message.isNotEmpty()) Text(e.message, color = Tokens.Text, style = MaterialTheme.typography.bodyMedium)
        if (e.ackedBy != null) {
            Text("Seen by ${e.ackedBy} ${e.ackedAt?.let { ago(it, now) } ?: ""}", color = Tokens.Correct, style = MaterialTheme.typography.bodySmall)
        } else if (e.type.isAlarm && onAck != null) {
            Spacer(Modifier.height(4.dp))
            SoftButton("Mark as seen", { onAck(e.id) }, color = Tokens.Wrong, background = Tokens.Card)
        }
    }
}

/** A rounded filter chip: All / Today / This week, or a child's name. */
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

// --- Settings ------------------------------------------------------------------------------------

@Composable
private fun CareSettingsTab(vm: AppViewModel) {
    val settings by vm.settings.collectAsState()
    val children by vm.children.collectAsState()
    var linking by remember { mutableStateOf(false) }
    var confirmSignOut by remember { mutableStateOf(false) }
    var confirmMode by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        ScreenTitle("Settings", if (settings.parentName.isNotBlank()) "Signed in as ${settings.parentName}" else null)
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Card {
                SectionLabel("Children you follow", icon = Icons.Rounded.ChildCare, tint = Tokens.Pink)
                if (children.isEmpty()) Text("None yet.", color = AppTheme.extra.muted)
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
                SoftButton("Link another child", { vm.clearLinkResult(); linking = true }, Modifier.fillMaxWidth(), icon = Icons.Rounded.GroupAdd, color = Tokens.Pink, background = Tokens.PinkSoft)
            }
            SoftButton("Use this phone as the child's phone", { confirmMode = true }, Modifier.fillMaxWidth(), icon = Icons.Rounded.SwapHoriz)
            SoftButton("Sign out of this phone", { confirmSignOut = true }, Modifier.fillMaxWidth(), icon = Icons.AutoMirrored.Rounded.Logout, color = Tokens.Wrong, background = Tokens.WrongBg)
            Spacer(Modifier.height(16.dp))
        }
    }

    if (confirmSignOut) {
        AlertDialog(
            onDismissRequest = { confirmSignOut = false },
            containerColor = Tokens.Card,
            title = { Text("Sign out?") },
            text = { Text("Stop receiving alarms on this phone?", color = AppTheme.extra.muted) },
            confirmButton = { TextButton(onClick = { vm.signOutCare(); confirmSignOut = false }) { Text("Sign out", color = Tokens.Wrong) } },
            dismissButton = { TextButton(onClick = { confirmSignOut = false }) { Text("Cancel") } },
        )
    }
    if (confirmMode) {
        AlertDialog(
            onDismissRequest = { confirmMode = false },
            containerColor = Tokens.Card,
            title = { Text("Switch to Child mode?") },
            text = { Text("This phone will stop receiving the child's alarms.", color = AppTheme.extra.muted) },
            confirmButton = { TextButton(onClick = { vm.setMode(AppMode.CHILD); confirmMode = false }) { Text("Switch") } },
            dismissButton = { TextButton(onClick = { confirmMode = false }) { Text("Cancel") } },
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
    LaunchedEffect(relayUrl) { url = relayUrl } // "Find relay" updates the stored address
    Card {
        SectionLabel("Link to the child's phone", icon = Icons.Rounded.GroupAdd, tint = Tokens.Pink)
        Text("On the child's phone: Settings → Invite parent. Type the 6-digit code here.", color = AppTheme.extra.muted)
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = code, onValueChange = { code = it.filter(Char::isDigit).take(6) },
            label = { Text("Invite code") },
            textStyle = CodeStyle, singleLine = true, modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        )
        OutlinedTextField(
            value = name, onValueChange = { name = it.take(40) },
            label = { Text("Your name (shown as \"Seen by …\")") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = url, onValueChange = { url = it },
            label = { Text("Relay address") }, placeholder = { Text("https://…") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
        )
        Spacer(Modifier.height(10.dp))
        SoftButton(if (finding) "Searching…" else "Find relay on this WiFi", { vm.findRelayForCare() }, enabled = !finding, icon = Icons.Rounded.Router)
        Spacer(Modifier.height(10.dp))
        PrimaryButton(
            "Link", { vm.link(url, code, name) },
            enabled = code.length == 6 && name.isNotBlank() && url.isNotBlank(),
            color = Tokens.Pink,
        )
        if (onCancel != null) {
            Spacer(Modifier.height(8.dp))
            SoftButton("Cancel", onCancel, Modifier.fillMaxWidth())
        }
        result?.let {
            Text(
                it, color = if (it.startsWith(AppViewModel.LINKED)) Tokens.Correct else Tokens.Wrong,
                modifier = Modifier.padding(top = 8.dp).semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
    }
}
