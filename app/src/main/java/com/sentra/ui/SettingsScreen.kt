package com.sentra.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bluetooth
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.FamilyRestroom
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PersonAdd
import androidx.compose.material.icons.rounded.Science
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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

/** Settings of Child mode: the alias, the sleeve, the parents, and the tools for testing without hardware. */
@Composable
fun SettingsScreen(vm: AppViewModel) {
    val settings by vm.settings.collectAsState()
    var confirm by remember { mutableStateOf<String?>(null) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        ScreenTitle("Settings")
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Card {
                SectionLabel("Child", icon = Icons.Rounded.Person)
                SavedField("Nickname (seen by parents)", settings.childName, vm::setChildName)
            }
            ParentsCard(vm)
            Card {
                SectionLabel("Test without hardware", icon = Icons.Rounded.Science)
                SwitchRow("Simulated sleeve", "A pretend sleeve inside the app.", settings.simulatedSleeve, vm::setSimulatedSleeve)
                SwitchRow("Simulated GPS", "During navigation, the position walks the route by itself.", settings.simulatedGps, vm::setSimulatedGps)
                OutlinedButton(onClick = { vm.addSampleRoute() }, modifier = Modifier.fillMaxWidth()) { Text("Add sample route") }
                if (settings.simulatedSleeve || settings.simulatedGps) {
                    Text("Turn off before real use.", color = Tokens.Warn, style = MaterialTheme.typography.bodySmall)
                }
            }
            TestLogCard(vm)
            Card {
                SectionLabel("Sleeve", icon = Icons.Rounded.Bluetooth)
                Text(settings.deviceName ?: "No sleeve yet", style = MaterialTheme.typography.titleMedium)
                settings.deviceAddress?.let { Text(it, color = AppTheme.extra.muted, fontFamily = Mono, style = MaterialTheme.typography.bodySmall) }
                Spacer(Modifier.height(8.dp))
                SavedField("Only connect to this name", settings.sleeveName, vm::setSleeveName, placeholder = "SENTRA-01")
                Text("Leave empty for any SENTRA sleeve.", color = AppTheme.extra.muted, style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(12.dp))
                OutlinedButton(onClick = { confirm = "history" }, modifier = Modifier.fillMaxWidth()) { Text("Clear history") }
                OutlinedButton(onClick = { confirm = "forget" }, enabled = settings.deviceAddress != null, modifier = Modifier.fillMaxWidth()) {
                    Text("Forget this sleeve", color = Tokens.Wrong)
                }
                OutlinedButton(onClick = { confirm = "parent" }, modifier = Modifier.fillMaxWidth()) {
                    Text("Use this phone as a parent's phone")
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }

    val (title, body, action) = when (confirm) {
        "history" -> Triple("Clear history?", "Deletes the events and locations stored on this phone only.", { vm.clearHistory(); Unit })
        "forget" -> Triple("Forget sleeve?", "This phone stays disconnected until you tap Connect again.", { vm.forgetSleeve(); Unit })
        "parent" -> Triple(
            "Switch to Parent mode?",
            "Navigation stops and this phone disconnects from the sleeve. Routes are kept.",
            { vm.setMode(AppMode.PARENT); Unit },
        )
        else -> return
    }
    AlertDialog(
        onDismissRequest = { confirm = null },
        containerColor = Tokens.Card,
        title = { Text(title) },
        text = { Text(body, color = AppTheme.extra.muted) },
        confirmButton = { TextButton(onClick = { action(); confirm = null }) { Text("Yes", color = Tokens.Wrong) } },
        dismissButton = { TextButton(onClick = { confirm = null }) { Text("Cancel") } },
    )
}

/** A text field with a Save button that shows once it differs, so nothing is saved half-typed. */
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
        OutlinedButton(onClick = { onSave(text.trim()) }) { Text("Save") }
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

/** Invites and linked parents. */
@Composable
private fun ParentsCard(vm: AppViewModel) {
    val settings by vm.settings.collectAsState()
    val health by vm.relayHealth.collectAsState()
    val invite by vm.invite.collectAsState()
    val parents by vm.parents.collectAsState()
    val message by vm.relayMessage.collectAsState()
    LaunchedEffect(settings.childToken, health?.parents) { vm.refreshParents() }

    Card {
        SectionLabel("Parents (SENTRA Care)", icon = Icons.Rounded.FamilyRestroom, tint = Tokens.Pink)
        Text(
            "Linked parents see the location and alarms in SENTRA Care, through the relay.",
            color = AppTheme.extra.muted, style = MaterialTheme.typography.bodySmall,
        )
        Spacer(Modifier.height(12.dp))
        parents.forEach { p ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(p.name)
                    Text("Linked ${ago(p.linkedAt, System.currentTimeMillis())}", color = AppTheme.extra.muted, style = MaterialTheme.typography.bodySmall)
                }
                TextButton(onClick = { vm.removeParent(p.id) }) { Text("Remove", color = Tokens.Wrong) }
            }
        }
        if (parents.isEmpty() && settings.childToken != null) Text("No parents linked yet.")
        Spacer(Modifier.height(8.dp))
        PrimaryButton("Invite parent", { vm.newInvite() }, enabled = settings.relayUrl.isNotBlank(), icon = Icons.Rounded.PersonAdd, color = Tokens.Pink, height = 50.dp)
        invite?.let { inv ->
            if (inv.expiresAt > System.currentTimeMillis()) {
                Spacer(Modifier.height(8.dp))
                Text("Invite code", color = AppTheme.extra.muted, style = MaterialTheme.typography.bodySmall)
                Text(inv.code.chunked(3).joinToString(" "), style = MaterialTheme.typography.headlineMedium, fontFamily = Mono)
                Text(
                    "On the parent's phone, choose \"Parent\" and type this code. Single use, valid until " +
                        SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(inv.expiresAt)) + ".",
                    color = AppTheme.extra.muted, style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        if (parents.isNotEmpty()) {
            OutlinedButton(onClick = vm::sendTestAlert, modifier = Modifier.fillMaxWidth()) { Text("Send test alarm") }
        }
        message?.let { Text(it, color = AppTheme.extra.muted) }
        health?.error?.let { Text("Relay unreachable: $it", color = Tokens.Wrong, style = MaterialTheme.typography.bodySmall) }
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
        SectionLabel("Test logs", icon = Icons.Rounded.Description)
        Text("Obstacle distance, ACK timing and GPS to CSV, for Chapter 10.", color = AppTheme.extra.muted, style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(8.dp))
        if (recording == null) {
            OutlinedTextField(
                value = label, onValueChange = { label = it.take(40) },
                label = { Text("Test name") }, placeholder = { Text("ble-range-5m") },
                singleLine = true, modifier = Modifier.fillMaxWidth(),
            )
            OutlinedButton(onClick = { vm.startRecording(label) }, modifier = Modifier.fillMaxWidth()) { Text("Start recording") }
        } else {
            Text("Recording ${recording!!.name}", color = Tokens.Correct)
            OutlinedButton(onClick = vm::stopRecording, modifier = Modifier.fillMaxWidth()) { Text("Stop") }
        }
        files.take(10).forEach { f ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(f.name, fontFamily = Mono, style = MaterialTheme.typography.bodySmall)
                    Text("${f.length() / 1024} kB", color = AppTheme.extra.muted, style = MaterialTheme.typography.bodySmall)
                }
                TextButton(onClick = { share(context, f) }, enabled = f != recording) { Text("Share") }
                TextButton(onClick = { vm.deleteRecording(f) }, enabled = f != recording) { Text("Remove", color = Tokens.Wrong) }
            }
        }
    }
}

private fun share(context: Context, file: File) {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
    val send = Intent(Intent.ACTION_SEND).setType("text/csv").putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    runCatching { context.startActivity(Intent.createChooser(send, "Share test log")) }
}
