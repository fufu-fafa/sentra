package com.sentra.ui

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.DirectionsWalk
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Flag
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.LocationOn
import androidx.compose.material.icons.rounded.Route
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Straighten
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.sentra.data.RouteEntity
import com.sentra.data.TripEntity
import com.sentra.loc.Fix
import com.sentra.nav.Geo
import com.sentra.nav.GeoPoint
import com.sentra.nav.Turn
import com.sentra.ui.theme.AppTheme
import com.sentra.ui.theme.Tokens
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

fun routeLengthM(points: List<GeoPoint>): Double = points.zipWithNext { a, b -> Geo.distanceM(a, b) }.sum()

fun routeSummary(route: RouteEntity): String {
    val points = route.geoPoints()
    val m = routeLengthM(points)
    return "${distanceText(m)} · ${etaText(m)}"
}

private val EN = Locale.ENGLISH

/** Routes: the saved routes as destinations, with a filter box, like "Search destinations…". */
@Composable
fun RoutePicker(vm: AppViewModel, onBack: () -> Unit, onOpen: (Long) -> Unit, onRecord: () -> Unit, onHistory: () -> Unit) {
    val routes by vm.routes.collectAsState()
    val settings by vm.settings.collectAsState()
    val nav by vm.nav.collectAsState()
    var query by rememberSaveable { mutableStateOf("") }
    var picked by rememberSaveable { mutableStateOf(settings.selectedRouteId ?: -1L) }
    val shown = routes.filter { query.isBlank() || it.name.contains(query.trim(), ignoreCase = true) }

    Column(Modifier.fillMaxSize().navigationBarsPadding()) {
        TopBar("Routes", onBack) {
            IconButton(onClick = onHistory) { Icon(Icons.Rounded.History, contentDescription = "Route history", tint = Tokens.Text) }
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (routes.isNotEmpty()) {
                OutlinedTextField(
                    value = query, onValueChange = { query = it.take(40) },
                    placeholder = { Text("Search destinations…") },
                    leadingIcon = { Icon(Icons.Rounded.Search, null, tint = AppTheme.extra.muted) },
                    singleLine = true, shape = RoundedCornerShape(50),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = Tokens.Card, unfocusedContainerColor = Tokens.Card,
                        unfocusedBorderColor = Tokens.Border, focusedBorderColor = Tokens.Accent,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (routes.isEmpty()) {
                Card {
                    Text("No routes yet.", style = MaterialTheme.typography.titleMedium, color = Tokens.Text)
                    Text(
                        "Record a route by walking it once with the child: mark the start, every turn, and the destination.",
                        color = AppTheme.extra.muted, style = MaterialTheme.typography.bodyMedium,
                    )
                }
            } else if (shown.isEmpty()) {
                Text("No route named \"${query.trim()}\".", color = AppTheme.extra.muted, modifier = Modifier.padding(8.dp))
            }
            shown.forEach { r ->
                ListRow(
                    r.name, routeSummary(r), Icons.Rounded.LocationOn,
                    tint = if (picked == r.id) Tokens.Accent else Tokens.Pink,
                    selected = picked == r.id, chevron = false,
                    onClick = { picked = r.id },
                )
            }
            SoftButton("Record new route", onRecord, Modifier.fillMaxWidth(), icon = Icons.Rounded.Add, enabled = !nav.active)
            if (nav.active) Text("Stop navigation first to record.", color = AppTheme.extra.muted, style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(8.dp))
        }
        Box(Modifier.padding(16.dp)) {
            PrimaryButton("Choose Destination", { onOpen(picked) }, enabled = routes.any { it.id == picked })
        }
    }
}

/** One route: the map, how long and how far, and Start Trip. */
@Composable
fun RouteDetail(vm: AppViewModel, id: Long, onBack: () -> Unit, onStarted: () -> Unit) {
    val routes by vm.routes.collectAsState()
    val nav by vm.nav.collectAsState()
    val message by vm.navMessage.collectAsState()
    val route = routes.firstOrNull { it.id == id }
    var confirmDelete by remember { mutableStateOf(false) }
    var showPoints by rememberSaveable { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().navigationBarsPadding()) {
        TopBar("Routes", onBack) {
            if (route != null) {
                IconButton(onClick = { confirmDelete = true }, enabled = !nav.active) {
                    Icon(Icons.Rounded.DeleteOutline, contentDescription = "Delete route", tint = Tokens.Text)
                }
            }
        }
        if (route == null) {
            Text("This route no longer exists.", color = AppTheme.extra.muted, modifier = Modifier.padding(16.dp))
            return@Column
        }
        val points = route.geoPoints()
        val length = routeLengthM(points)
        Box(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp).clip(RoundedCornerShape(24.dp))) {
            OsmMap(MapContent(route = points), Modifier.fillMaxSize(), follow = false)
        }
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Card {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.LocationOn, null, tint = Tokens.Pink, modifier = Modifier.size(26.dp))
                    Spacer(Modifier.width(10.dp))
                    Text(route.name, style = MaterialTheme.typography.titleMedium, color = Tokens.Text)
                }
                Spacer(Modifier.height(8.dp))
                InfoLine(Icons.Rounded.Schedule, etaText(length))
                InfoLine(Icons.Rounded.Straighten, "${distanceText(length)} · ${points.size} points")
                TextButton(onClick = { showPoints = !showPoints }) { Text(if (showPoints) "Hide points" else "Show points and turns") }
                if (showPoints) PointList(points)
            }
            message?.let { Text(it, color = Tokens.Wrong) }
            PrimaryButton(
                if (nav.active) "Navigation is running" else "Start Trip",
                { vm.startNavigation(route.id); onStarted() },
                icon = Icons.AutoMirrored.Rounded.DirectionsWalk,
                enabled = !nav.active,
            )
        }
    }

    if (confirmDelete && route != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            containerColor = Tokens.Card,
            title = { Text("Delete route?") },
            text = { Text("\"${route.name}\" will be deleted from this phone.", color = AppTheme.extra.muted) },
            confirmButton = { TextButton(onClick = { vm.deleteRoute(route.id); confirmDelete = false; onBack() }) { Text("Delete", color = Tokens.Wrong) } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun InfoLine(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String) {
    Row(Modifier.padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = Tokens.Accent, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(10.dp))
        Text(text, style = MaterialTheme.typography.bodyLarge, color = Tokens.Text)
    }
}

/** The points with the distance between them and the turn the app will announce at each. */
@Composable
private fun PointList(points: List<GeoPoint>) {
    points.forEachIndexed { i, p ->
        val name = when (i) {
            0 -> "Start"
            points.lastIndex -> "Destination"
            else -> "Point ${i + 1}"
        }
        val turn = if (i in 1 until points.lastIndex) {
            when (Geo.turnAt(points[i - 1], p, points[i + 1])) {
                Turn.LEFT -> "left"
                Turn.RIGHT -> "right"
                Turn.U_TURN -> "U-turn"
                Turn.STRAIGHT -> "straight"
            }
        } else null
        val gap = if (i > 0) "${Geo.distanceM(points[i - 1], p).roundToInt()} m" else null
        Text(
            listOfNotNull(name, gap, turn).joinToString(" · "),
            color = AppTheme.extra.muted,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

// --- Route History -----------------------------------------------------------------------------

@Composable
fun RouteHistoryScreen(vm: AppViewModel, onBack: () -> Unit) {
    val trips by vm.trips.collectAsState()
    var confirmClear by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().navigationBarsPadding()) {
        TopBar("Route History", onBack) {
            if (trips.isNotEmpty()) {
                IconButton(onClick = { confirmClear = true }) { Icon(Icons.Rounded.DeleteOutline, contentDescription = "Clear history", tint = Tokens.Text) }
            }
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (trips.isEmpty()) {
                Card {
                    Text("No trips yet.", style = MaterialTheme.typography.titleMedium, color = Tokens.Text)
                    Text("Finished trips are saved here.", color = AppTheme.extra.muted)
                }
            }
            trips.forEach { TripRow(it) }
            Spacer(Modifier.height(8.dp))
        }
        if (trips.isNotEmpty()) {
            Box(Modifier.padding(16.dp)) {
                SoftButton("Clear history", { confirmClear = true }, Modifier.fillMaxWidth(), icon = Icons.Rounded.DeleteOutline)
            }
        }
    }
    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            containerColor = Tokens.Card,
            title = { Text("Clear route history?") },
            text = { Text("${trips.size} trips will be deleted from this phone. Routes are kept.", color = AppTheme.extra.muted) },
            confirmButton = { TextButton(onClick = { vm.clearTrips(); confirmClear = false }) { Text("Delete", color = Tokens.Wrong) } },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun TripRow(t: TripEntity) {
    val minutes = (t.durationMs / 60_000.0).roundToInt().coerceAtLeast(1)
    val whenText = SimpleDateFormat("d MMM, HH:mm", EN).format(Date(t.startedAt))
    ListRow(
        t.routeName,
        "$whenText  •  $minutes min",
        if (t.arrived) Icons.Rounded.Flag else Icons.Rounded.WarningAmber,
        tint = if (t.arrived) Tokens.Pink else Tokens.Warn,
        chevron = false,
        trailing = {
            if (t.arrived) Pill("Arrived", Tokens.Correct, Tokens.CorrectBg) else Pill("Stopped", Tokens.Warn, Tokens.WarnBg)
        },
    )
}

// --- Record Route (§9.4) -----------------------------------------------------------------------

@Composable
fun RecordRoute(vm: AppViewModel, onClose: () -> Unit) {
    val fix by vm.fix.collectAsState()
    val draft by vm.draft.collectAsState()
    val done by vm.draftDone.collectAsState()
    val message by vm.recordMessage.collectAsState()
    var name by rememberSaveable { mutableStateOf("") }
    var confirmDiscard by remember { mutableStateOf(false) }
    val leave = { if (draft.isEmpty()) onClose() else confirmDiscard = true }

    // GPS runs only while this screen is open.
    DisposableEffect(Unit) {
        vm.setFixWanted(true)
        onDispose { vm.setFixWanted(false) }
    }

    Column(Modifier.fillMaxSize().navigationBarsPadding().verticalScroll(rememberScrollState())) {
        TopBar("Record Route", leave)
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Mark the start, every turn, and the destination.", color = AppTheme.extra.muted)
            GpsCard(fix)
            if (draft.isNotEmpty() || fix != null) {
                OsmMap(
                    MapContent(marker = fix?.point, accuracyM = fix?.accuracyM, route = draft),
                    Modifier.fillMaxWidth().height(240.dp).clip(RoundedCornerShape(24.dp)),
                )
            }
            when {
                draft.isEmpty() -> PrimaryButton("Start point", vm::recordStart, icon = Icons.Rounded.LocationOn)
                !done -> Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    PrimaryButton("Mark turn", vm::recordTurn, Modifier.weight(1f), icon = Icons.Rounded.Route)
                    PrimaryButton("Destination", vm::recordEnd, Modifier.weight(1f), icon = Icons.Rounded.Flag, color = Tokens.Pink)
                }
            }
            message?.let {
                Text(it, color = Tokens.Warn, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive })
            }
            if (draft.isNotEmpty()) {
                Card {
                    SectionLabel("${draft.size} points · ${routeLengthM(draft).roundToInt()} m", icon = Icons.Rounded.Route)
                    PointList(draft)
                    Spacer(Modifier.height(8.dp))
                    SoftButton("Remove last point", vm::removeLastPoint, Modifier.fillMaxWidth())
                }
            }
            if (done) {
                Card {
                    OutlinedTextField(
                        value = name, onValueChange = { name = it.take(40) },
                        label = { Text("Destination name") }, placeholder = { Text("Home to school") },
                        singleLine = true, modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(12.dp))
                    PrimaryButton("Save route", { vm.saveRoute(name); onClose() }, enabled = name.isNotBlank() && draft.size >= 2)
                }
            }
            SoftButton(if (draft.isEmpty()) "Back" else "Discard recording", leave, Modifier.fillMaxWidth())
            Text(
                "Wait until GPS is ±10 m. Mark in the middle of each turn, with 20 m or more between points.",
                color = AppTheme.extra.muted, style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(16.dp))
        }
    }

    if (confirmDiscard) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            containerColor = Tokens.Card,
            title = { Text("Discard recording?") },
            text = { Text("${draft.size} points will be lost.", color = AppTheme.extra.muted) },
            confirmButton = { TextButton(onClick = { vm.discardDraft(); confirmDiscard = false; onClose() }) { Text("Discard", color = Tokens.Wrong) } },
            dismissButton = { TextButton(onClick = { confirmDiscard = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun GpsCard(fix: Fix?) {
    val acc = fix?.accuracyM
    val (text, fg, bg) = when {
        acc == null -> Triple("Searching…", Tokens.Muted, Tokens.AccentSoft)
        acc <= 15 -> Triple("±${acc.roundToInt()} m", Tokens.Correct, Tokens.CorrectBg)
        else -> Triple("Weak ±${acc.roundToInt()} m", Tokens.Wrong, Tokens.WrongBg)
    }
    Card {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("GPS", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Pill(text, fg, bg, Modifier.semantics { liveRegion = LiveRegionMode.Polite }, dot = true)
        }
    }
}
