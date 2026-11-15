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

private val ID = Locale("id", "ID")

/** Rute: the saved routes as destinations, with a filter box, like "Cari tujuan…". */
@Composable
fun RoutePicker(vm: AppViewModel, onBack: () -> Unit, onOpen: (Long) -> Unit, onRecord: () -> Unit, onHistory: () -> Unit) {
    val routes by vm.routes.collectAsState()
    val settings by vm.settings.collectAsState()
    val nav by vm.nav.collectAsState()
    var query by rememberSaveable { mutableStateOf("") }
    var picked by rememberSaveable { mutableStateOf(settings.selectedRouteId ?: -1L) }
    val shown = routes.filter { query.isBlank() || it.name.contains(query.trim(), ignoreCase = true) }

    Column(Modifier.fillMaxSize().navigationBarsPadding()) {
        TopBar("Rute", onBack) {
            IconButton(onClick = onHistory) { Icon(Icons.Rounded.History, contentDescription = "Riwayat rute", tint = Tokens.Text) }
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (routes.isNotEmpty()) {
                OutlinedTextField(
                    value = query, onValueChange = { query = it.take(40) },
                    placeholder = { Text("Cari tujuan…") },
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
                    Text("Belum ada rute.", style = MaterialTheme.typography.titleMedium, color = Tokens.Text)
                    Text(
                        "Rekam rute dengan berjalan sekali bersama anak: tandai titik mulai, tiap belokan, dan tujuan.",
                        color = AppTheme.extra.muted, style = MaterialTheme.typography.bodyMedium,
                    )
                }
            } else if (shown.isEmpty()) {
                Text("Tidak ada rute bernama \"${query.trim()}\".", color = AppTheme.extra.muted, modifier = Modifier.padding(8.dp))
            }
            shown.forEach { r ->
                ListRow(
                    r.name, routeSummary(r), Icons.Rounded.LocationOn,
                    tint = if (picked == r.id) Tokens.Accent else Tokens.Pink,
                    selected = picked == r.id, chevron = false,
                    onClick = { picked = r.id },
                )
            }
            SoftButton("Rekam rute baru", onRecord, Modifier.fillMaxWidth(), icon = Icons.Rounded.Add, enabled = !nav.active)
            if (nav.active) Text("Hentikan navigasi dulu untuk merekam.", color = AppTheme.extra.muted, style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(8.dp))
        }
        Box(Modifier.padding(16.dp)) {
            PrimaryButton("Pilih Tujuan", { onOpen(picked) }, enabled = routes.any { it.id == picked })
        }
    }
}

/** One route: the map, how long and how far, and Mulai Perjalanan. */
@Composable
fun RouteDetail(vm: AppViewModel, id: Long, onBack: () -> Unit, onStarted: () -> Unit) {
    val routes by vm.routes.collectAsState()
    val nav by vm.nav.collectAsState()
    val message by vm.navMessage.collectAsState()
    val route = routes.firstOrNull { it.id == id }
    var confirmDelete by remember { mutableStateOf(false) }
    var showPoints by rememberSaveable { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().navigationBarsPadding()) {
        TopBar("Rute", onBack) {
            if (route != null) {
                IconButton(onClick = { confirmDelete = true }, enabled = !nav.active) {
                    Icon(Icons.Rounded.DeleteOutline, contentDescription = "Hapus rute", tint = Tokens.Text)
                }
            }
        }
        if (route == null) {
            Text("Rute tidak ada lagi.", color = AppTheme.extra.muted, modifier = Modifier.padding(16.dp))
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
                InfoLine(Icons.Rounded.Straighten, "${distanceText(length)} · ${points.size} titik")
                TextButton(onClick = { showPoints = !showPoints }) { Text(if (showPoints) "Sembunyikan titik" else "Lihat titik dan belokan") }
                if (showPoints) PointList(points)
            }
            message?.let { Text(it, color = Tokens.Wrong) }
            PrimaryButton(
                if (nav.active) "Navigasi sedang berjalan" else "Mulai Perjalanan",
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
            title = { Text("Hapus rute?") },
            text = { Text("\"${route.name}\" dihapus dari HP ini.", color = AppTheme.extra.muted) },
            confirmButton = { TextButton(onClick = { vm.deleteRoute(route.id); confirmDelete = false; onBack() }) { Text("Hapus", color = Tokens.Wrong) } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Batal") } },
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
            0 -> "Mulai"
            points.lastIndex -> "Tujuan"
            else -> "Titik ${i + 1}"
        }
        val turn = if (i in 1 until points.lastIndex) {
            when (Geo.turnAt(points[i - 1], p, points[i + 1])) {
                Turn.LEFT -> "kiri"
                Turn.RIGHT -> "kanan"
                Turn.U_TURN -> "putar balik"
                Turn.STRAIGHT -> "lurus"
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

// --- Riwayat Rute ------------------------------------------------------------------------------

@Composable
fun RouteHistoryScreen(vm: AppViewModel, onBack: () -> Unit) {
    val trips by vm.trips.collectAsState()
    var confirmClear by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().navigationBarsPadding()) {
        TopBar("Riwayat Rute", onBack) {
            if (trips.isNotEmpty()) {
                IconButton(onClick = { confirmClear = true }) { Icon(Icons.Rounded.DeleteOutline, contentDescription = "Hapus riwayat", tint = Tokens.Text) }
            }
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (trips.isEmpty()) {
                Card {
                    Text("Belum ada perjalanan.", style = MaterialTheme.typography.titleMedium, color = Tokens.Text)
                    Text("Perjalanan yang selesai tersimpan di sini.", color = AppTheme.extra.muted)
                }
            }
            trips.forEach { TripRow(it) }
            Spacer(Modifier.height(8.dp))
        }
        if (trips.isNotEmpty()) {
            Box(Modifier.padding(16.dp)) {
                SoftButton("Hapus riwayat", { confirmClear = true }, Modifier.fillMaxWidth(), icon = Icons.Rounded.DeleteOutline)
            }
        }
    }
    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            containerColor = Tokens.Card,
            title = { Text("Hapus riwayat rute?") },
            text = { Text("${trips.size} perjalanan dihapus dari HP ini. Rute tidak ikut terhapus.", color = AppTheme.extra.muted) },
            confirmButton = { TextButton(onClick = { vm.clearTrips(); confirmClear = false }) { Text("Hapus", color = Tokens.Wrong) } },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Batal") } },
        )
    }
}

@Composable
private fun TripRow(t: TripEntity) {
    val minutes = (t.durationMs / 60_000.0).roundToInt().coerceAtLeast(1)
    val whenText = SimpleDateFormat("d MMM, HH:mm", ID).format(Date(t.startedAt))
    ListRow(
        t.routeName,
        "$whenText  •  $minutes mnt",
        if (t.arrived) Icons.Rounded.Flag else Icons.Rounded.WarningAmber,
        tint = if (t.arrived) Tokens.Pink else Tokens.Warn,
        chevron = false,
        trailing = {
            if (t.arrived) Pill("Sampai", Tokens.Correct, Tokens.CorrectBg) else Pill("Dihentikan", Tokens.Warn, Tokens.WarnBg)
        },
    )
}

// --- Rekam Rute (§9.4) -------------------------------------------------------------------------

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
        TopBar("Rekam Rute", leave)
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Tandai titik mulai, tiap belokan, dan tujuan.", color = AppTheme.extra.muted)
            GpsCard(fix)
            if (draft.isNotEmpty() || fix != null) {
                OsmMap(
                    MapContent(marker = fix?.point, accuracyM = fix?.accuracyM, route = draft),
                    Modifier.fillMaxWidth().height(240.dp).clip(RoundedCornerShape(24.dp)),
                )
            }
            when {
                draft.isEmpty() -> PrimaryButton("Titik mulai", vm::recordStart, icon = Icons.Rounded.LocationOn)
                !done -> Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    PrimaryButton("Tandai belok", vm::recordTurn, Modifier.weight(1f), icon = Icons.Rounded.Route)
                    PrimaryButton("Tujuan", vm::recordEnd, Modifier.weight(1f), icon = Icons.Rounded.Flag, brush = Tokens.PinkGradient)
                }
            }
            message?.let {
                Text(it, color = Tokens.Warn, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive })
            }
            if (draft.isNotEmpty()) {
                Card {
                    SectionLabel("${draft.size} titik · ${routeLengthM(draft).roundToInt()} m", icon = Icons.Rounded.Route)
                    PointList(draft)
                    Spacer(Modifier.height(8.dp))
                    SoftButton("Hapus titik terakhir", vm::removeLastPoint, Modifier.fillMaxWidth())
                }
            }
            if (done) {
                Card {
                    OutlinedTextField(
                        value = name, onValueChange = { name = it.take(40) },
                        label = { Text("Nama tujuan") }, placeholder = { Text("Rumah ke sekolah") },
                        singleLine = true, modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(12.dp))
                    PrimaryButton("Simpan rute", { vm.saveRoute(name); onClose() }, enabled = name.isNotBlank() && draft.size >= 2)
                }
            }
            SoftButton(if (draft.isEmpty()) "Kembali" else "Buang rekaman", leave, Modifier.fillMaxWidth())
            Text(
                "Tunggu sampai GPS ±10 m. Tandai di tengah belokan, dengan jarak 20 m atau lebih antar titik.",
                color = AppTheme.extra.muted, style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(16.dp))
        }
    }

    if (confirmDiscard) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            containerColor = Tokens.Card,
            title = { Text("Buang rekaman?") },
            text = { Text("${draft.size} titik akan hilang.", color = AppTheme.extra.muted) },
            confirmButton = { TextButton(onClick = { vm.discardDraft(); confirmDiscard = false; onClose() }) { Text("Buang", color = Tokens.Wrong) } },
            dismissButton = { TextButton(onClick = { confirmDiscard = false }) { Text("Batal") } },
        )
    }
}

@Composable
private fun GpsCard(fix: Fix?) {
    val acc = fix?.accuracyM
    val (text, fg, bg) = when {
        acc == null -> Triple("Mencari…", Tokens.Muted, Tokens.AccentSoft)
        acc <= 15 -> Triple("±${acc.roundToInt()} m", Tokens.Correct, Tokens.CorrectBg)
        else -> Triple("Lemah ±${acc.roundToInt()} m", Tokens.Wrong, Tokens.WrongBg)
    }
    Card {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("GPS", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Pill(text, fg, bg, Modifier.semantics { liveRegion = LiveRegionMode.Polite }, dot = true)
        }
    }
}
