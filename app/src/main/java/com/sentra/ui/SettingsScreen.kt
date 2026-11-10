package com.sentra.ui

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.sentra.care.ago
import com.sentra.data.AppMode
import com.sentra.ui.theme.AppTheme
import com.sentra.ui.theme.Mono
import com.sentra.ui.theme.Tokens
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** The invite code field on a parent's phone, as in fall-bracelet. */
val CodeStyle = TextStyle(fontSize = 24.sp, fontFamily = Mono, letterSpacing = 7.sp, textAlign = TextAlign.Center)

/** Setelan of mode Anak: the alias, the sleeve, the parents, and the tools for testing without hardware. */
@Composable
fun SettingsScreen(vm: AppViewModel) {
    val settings by vm.settings.collectAsState()
    var confirm by remember { mutableStateOf<String?>(null) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        ScreenTitle("Setelan")
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Card {
                SectionLabel("Anak")
                SavedField("Nama panggilan (dilihat orang tua)", settings.childName, vm::setChildName)
            }
            ParentsCard(vm)
            Card {
                SectionLabel("Uji tanpa alat")
                SwitchRow("Sleeve simulasi", "Sleeve pura-pura di dalam aplikasi.", settings.simulatedSleeve, vm::setSimulatedSleeve)
                SwitchRow("GPS simulasi", "Saat navigasi, posisi berjalan sendiri di rute.", settings.simulatedGps, vm::setSimulatedGps)
                OutlinedButton(onClick = { vm.addSampleRoute() }, modifier = Modifier.fillMaxWidth()) { Text("Tambah rute contoh") }
                if (settings.simulatedSleeve || settings.simulatedGps) {
                    Text("Matikan sebelum dipakai sungguhan.", color = Tokens.Warn, style = MaterialTheme.typography.bodySmall)
                }
            }
            TestLogCard(vm)
            Card {
                SectionLabel("Sleeve")
                Text(settings.deviceName ?: "Belum ada sleeve", style = MaterialTheme.typography.titleMedium)
                settings.deviceAddress?.let { Text(it, color = AppTheme.extra.muted, fontFamily = Mono, style = MaterialTheme.typography.bodySmall) }
                Spacer(Modifier.height(8.dp))
                SavedField("Hanya sambung ke nama ini", settings.sleeveName, vm::setSleeveName, placeholder = "SENTRA-01")
                Text("Kosongkan untuk sleeve SENTRA mana pun.", color = AppTheme.extra.muted, style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(12.dp))
                OutlinedButton(onClick = { confirm = "history" }, modifier = Modifier.fillMaxWidth()) { Text("Hapus riwayat") }
                OutlinedButton(onClick = { confirm = "forget" }, enabled = settings.deviceAddress != null, modifier = Modifier.fillMaxWidth()) {
                    Text("Lupakan sleeve ini", color = Tokens.Wrong)
                }
                OutlinedButton(onClick = { confirm = "parent" }, modifier = Modifier.fillMaxWidth()) {
                    Text("Pakai HP ini sebagai HP orang tua")
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }

    val (title, body, action) = when (confirm) {
        "history" -> Triple("Hapus riwayat?", "Menghapus kejadian dan lokasi yang tersimpan di HP ini saja.", { vm.clearHistory(); Unit })
        "forget" -> Triple("Lupakan sleeve?", "HP ini berhenti tersambung sampai Anda menekan Sambungkan lagi.", { vm.forgetSleeve(); Unit })
        "parent" -> Triple(
            "Ganti ke mode Orang tua?",
            "Navigasi dihentikan dan HP ini berhenti tersambung ke sleeve. Rute tidak dihapus.",
            { vm.setMode(AppMode.PARENT); Unit },
        )
        else -> return
    }
    AlertDialog(
        onDismissRequest = { confirm = null },
        containerColor = Tokens.Card,
        title = { Text(title) },
        text = { Text(body, color = AppTheme.extra.muted) },
        confirmButton = { TextButton(onClick = { action(); confirm = null }) { Text("Ya", color = Tokens.Wrong) } },
        dismissButton = { TextButton(onClick = { confirm = null }) { Text("Batal") } },
    )
}

/** A text field with a Simpan button that shows once it differs, so nothing is saved half-typed. */
@Composable
fun SavedField(label: String, saved: String, onSave: (String) -> Unit, placeholder: String = "", keyboard: KeyboardType = KeyboardType.Text) {
    var text by rememberSaveable(saved) { mutableStateOf(saved) }
    OutlinedTextField(
        value = text, onValueChange = { text = it.take(120) },
        label = { Text(label) }, placeholder = { if (placeholder.isNotEmpty()) Text(placeholder) },
        singleLine = true, modifier = Modifier.fillMaxWidth(),
        keyboardOptions = KeyboardOptions(keyboardType = keyboard),
    )
    if (text.trim() != saved) {
        OutlinedButton(onClick = { onSave(text.trim()) }) { Text("Simpan") }
    }
}

@Composable
fun SwitchRow(title: String, body: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().toggleable(value = checked, role = Role.Switch, onValueChange = onChange).padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title)
            Text(body, color = AppTheme.extra.muted, style = MaterialTheme.typography.bodySmall)
        }
        Switch(checked = checked, onCheckedChange = null, modifier = Modifier.padding(start = 12.dp))
    }
}

/** Relay address, invites and linked parents. */
@Composable
private fun ParentsCard(vm: AppViewModel) {
    val settings by vm.settings.collectAsState()
    val health by vm.relayHealth.collectAsState()
    val invite by vm.invite.collectAsState()
    val parents by vm.parents.collectAsState()
    val message by vm.relayMessage.collectAsState()
    val finding by vm.finding.collectAsState()
    var url by remember(settings.relayUrl) { mutableStateOf(settings.relayUrl) }
    LaunchedEffect(settings.wearerToken, health?.parents) { vm.refreshParents() }

    Card {
        SectionLabel("Orang tua")
        Text(
            "Orang tua yang terhubung melihat lokasi dan alarm di SENTRA Care, lewat relay.",
            color = AppTheme.extra.muted, style = MaterialTheme.typography.bodySmall,
        )
        Spacer(Modifier.height(12.dp))
        parents.forEach { p ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(p.name)
                    Text("Terhubung ${ago(p.linkedAt, System.currentTimeMillis())}", color = AppTheme.extra.muted, style = MaterialTheme.typography.bodySmall)
                }
                TextButton(onClick = { vm.removeParent(p.id) }) { Text("Hapus", color = Tokens.Wrong) }
            }
        }
        if (parents.isEmpty() && settings.wearerToken != null) Text("Belum ada orang tua terhubung.")
        Spacer(Modifier.height(8.dp))
        Button(onClick = { vm.newInvite() }, enabled = settings.relayUrl.isNotBlank(), modifier = Modifier.fillMaxWidth()) {
            Text("Undang orang tua")
        }
        invite?.let { inv ->
            if (inv.expiresAt > System.currentTimeMillis()) {
                Spacer(Modifier.height(8.dp))
                Text("Kode undangan", color = AppTheme.extra.muted, style = MaterialTheme.typography.bodySmall)
                Text(inv.code.chunked(3).joinToString(" "), style = MaterialTheme.typography.headlineMedium, fontFamily = Mono)
                Text(
                    "Di HP orang tua, pilih \"Orang tua\" dan ketik kode ini. Sekali pakai, berlaku sampai " +
                        SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(inv.expiresAt)) + ".",
                    color = AppTheme.extra.muted, style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        if (parents.isNotEmpty()) {
            OutlinedButton(onClick = vm::sendTestAlert, modifier = Modifier.fillMaxWidth()) { Text("Kirim tes alarm") }
        }
        message?.let { Text(it, color = AppTheme.extra.muted) }
        health?.error?.let { Text("Relay tidak terjangkau: $it", color = Tokens.Wrong, style = MaterialTheme.typography.bodySmall) }
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = url, onValueChange = { url = it },
            label = { Text("Alamat relay") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { vm.findRelay() }, enabled = !finding) { Text(if (finding) "Mencari…" else "Cari relay di WiFi ini") }
            if (url.trim() != settings.relayUrl) {
                OutlinedButton(onClick = { vm.setRelayUrl(url) }) { Text("Simpan") }
            }
        }
    }
}

/** CSV recordings for the measurements of Desain Teknis Bab 10. */
@Composable
private fun TestLogCard(vm: AppViewModel) {
    val recording by vm.recording.collectAsState()
    val files by vm.recordings.collectAsState()
    val context = LocalContext.current
    var label by rememberSaveable { mutableStateOf("") }
    Card {
        SectionLabel("Catatan uji")
        Text("Jarak rintangan, waktu ACK dan GPS ke CSV, untuk Bab 10.", color = AppTheme.extra.muted, style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(8.dp))
        if (recording == null) {
            OutlinedTextField(
                value = label, onValueChange = { label = it.take(40) },
                label = { Text("Nama uji") }, placeholder = { Text("jangkauan-ble-5m") },
                singleLine = true, modifier = Modifier.fillMaxWidth(),
            )
            OutlinedButton(onClick = { vm.startRecording(label) }, modifier = Modifier.fillMaxWidth()) { Text("Mulai merekam") }
        } else {
            Text("Merekam ${recording!!.name}", color = Tokens.Correct)
            OutlinedButton(onClick = vm::stopRecording, modifier = Modifier.fillMaxWidth()) { Text("Berhenti") }
        }
        files.take(10).forEach { f ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(f.name, fontFamily = Mono, style = MaterialTheme.typography.bodySmall)
                    Text("${f.length() / 1024} kB", color = AppTheme.extra.muted, style = MaterialTheme.typography.bodySmall)
                }
                TextButton(onClick = { share(context, f) }, enabled = f != recording) { Text("Bagikan") }
                TextButton(onClick = { vm.deleteRecording(f) }, enabled = f != recording) { Text("Hapus", color = Tokens.Wrong) }
            }
        }
    }
}

private fun share(context: Context, file: File) {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
    val send = Intent(Intent.ACTION_SEND).setType("text/csv").putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    runCatching { context.startActivity(Intent.createChooser(send, "Bagikan catatan uji")) }
}
