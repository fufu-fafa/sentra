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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import com.sentra.loc.Fix
import com.sentra.nav.Geo
import com.sentra.nav.GeoPoint
import com.sentra.nav.Turn
import com.sentra.ui.theme.AppTheme
import com.sentra.ui.theme.Tokens
import kotlin.math.roundToInt

fun routeLengthM(points: List<GeoPoint>): Double = points.zipWithNext { a, b -> Geo.distanceM(a, b) }.sum()

fun routeSummary(route: RouteEntity): String {
    val points = route.geoPoints()
    return "${points.size} titik · ${routeLengthM(points).roundToInt()} m"
}

/** Rute: the saved routes, and Rekam Rute (§9.4) to make a new one by walking it once. */
@Composable
fun RouteScreen(vm: AppViewModel) {
    var recording by rememberSaveable { mutableStateOf(false) }
    val nav by vm.nav.collectAsState()
    if (recording && !nav.active) {
        RecordRoute(vm) { recording = false }
        return
    }
    val routes by vm.routes.collectAsState()
    var confirmDelete by remember { mutableStateOf<RouteEntity?>(null) }
    var shown by remember { mutableStateOf<RouteEntity?>(null) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        ScreenTitle("Rute")
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = { vm.discardDraft(); recording = true }, enabled = !nav.active, modifier = Modifier.fillMaxWidth()) {
                Text("Rekam rute baru")
            }
            if (nav.active) Text("Hentikan navigasi dulu untuk merekam.", color = AppTheme.extra.muted, style = MaterialTheme.typography.bodySmall)
            if (routes.isEmpty()) Card { Text("Belum ada rute.", color = AppTheme.extra.muted) }
            routes.forEach { r ->
                Card {
                    Text(r.name, style = MaterialTheme.typography.titleMedium)
                    Text(routeSummary(r), color = AppTheme.extra.muted, style = MaterialTheme.typography.bodySmall)
                    if (shown?.id == r.id) {
                        Spacer(Modifier.height(10.dp))
                        OsmMap(
                            MapContent(route = r.geoPoints()),
                            Modifier.fillMaxWidth().height(240.dp).clip(RoundedCornerShape(10.dp)),
                            follow = false,
                        )
                        Spacer(Modifier.height(6.dp))
                        PointList(r.geoPoints())
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { shown = if (shown?.id == r.id) null else r }, modifier = Modifier.weight(1f)) {
                            Text(if (shown?.id == r.id) "Tutup" else "Lihat")
                        }
                        OutlinedButton(onClick = { confirmDelete = r }, enabled = !nav.active, modifier = Modifier.weight(1f)) {
                            Text("Hapus", color = Tokens.Wrong)
                        }
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }

    confirmDelete?.let { r ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            containerColor = Tokens.Card,
            title = { Text("Hapus rute?") },
            text = { Text("\"${r.name}\" dihapus dari HP ini.", color = AppTheme.extra.muted) },
            confirmButton = { TextButton(onClick = { vm.deleteRoute(r.id); confirmDelete = null }) { Text("Hapus", color = Tokens.Wrong) } },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Batal") } },
        )
    }
}

/** The points with the distance between them and the turn the app will announce at each. */
@Composable
private fun PointList(points: List<GeoPoint>) {
    points.forEachIndexed { i, p ->
        val name = if (i == 0) "Mulai" else "Titik ${i + 1}"
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

@Composable
private fun RecordRoute(vm: AppViewModel, onClose: () -> Unit) {
    val fix by vm.fix.collectAsState()
    val draft by vm.draft.collectAsState()
    val done by vm.draftDone.collectAsState()
    val message by vm.recordMessage.collectAsState()
    var name by rememberSaveable { mutableStateOf("") }
    var confirmDiscard by remember { mutableStateOf(false) }

    // GPS runs only while this screen is open.
    DisposableEffect(Unit) {
        vm.setFixWanted(true)
        onDispose { vm.setFixWanted(false) }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        ScreenTitle("Rekam rute", "Tandai titik mulai, tiap belokan, dan tujuan.")
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            GpsCard(fix)
            if (draft.isNotEmpty() || fix != null) {
                OsmMap(
                    MapContent(marker = fix?.point, accuracyM = fix?.accuracyM, route = draft),
                    Modifier.fillMaxWidth().height(240.dp).clip(RoundedCornerShape(10.dp)),
                )
            }
            when {
                draft.isEmpty() -> Button(onClick = vm::recordStart, modifier = Modifier.fillMaxWidth()) { Text("Titik mulai") }
                !done -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = vm::recordTurn, modifier = Modifier.weight(1f)) { Text("Tandai belok") }
                    Button(onClick = vm::recordEnd, modifier = Modifier.weight(1f)) { Text("Titik tujuan") }
                }
            }
            message?.let {
                Text(it, color = Tokens.Warn, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive })
            }
            if (draft.isNotEmpty()) {
                Card {
                    SectionLabel("${draft.size} titik · ${routeLengthM(draft).roundToInt()} m")
                    PointList(draft)
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(onClick = vm::removeLastPoint, modifier = Modifier.fillMaxWidth()) { Text("Hapus titik terakhir") }
                }
            }
            if (done) {
                Card {
                    OutlinedTextField(
                        value = name, onValueChange = { name = it.take(40) },
                        label = { Text("Nama rute") }, placeholder = { Text("Rumah ke sekolah") },
                        singleLine = true, modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(12.dp))
                    Button(
                        onClick = { vm.saveRoute(name); onClose() },
                        enabled = name.isNotBlank() && draft.size >= 2,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Simpan rute") }
                }
            }
            OutlinedButton(
                onClick = { if (draft.isEmpty()) onClose() else confirmDiscard = true },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(if (draft.isEmpty()) "Kembali" else "Buang rekaman") }
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
        acc == null -> Triple("Mencari…", Tokens.Muted, Tokens.Border)
        acc <= 15 -> Triple("±${acc.roundToInt()} m", Tokens.Correct, Tokens.CorrectBg)
        else -> Triple("Lemah ±${acc.roundToInt()} m", Tokens.Wrong, Tokens.WrongBg)
    }
    Card {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("GPS", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Pill(text, fg, bg, Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        }
    }
}
