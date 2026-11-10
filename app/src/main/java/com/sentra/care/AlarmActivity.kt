package com.sentra.care

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.sentra.alert.AlertRules
import com.sentra.care
import com.sentra.ui.MainActivity
import com.sentra.ui.theme.SentraTheme
import com.sentra.ui.theme.Tokens
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Full-screen alarm shown over the lock screen until a parent acknowledges it. */
class AlarmActivity : ComponentActivity() {
    private data class Shown(val id: Long, val title: String, val child: String, val message: String, val at: Long)

    private var shown by mutableStateOf(Shown(-1, "", "", "", 0))

    private fun show(i: Intent) {
        shown = Shown(
            i.getLongExtra(EXTRA_ID, -1), i.getStringExtra(EXTRA_TITLE).orEmpty(), i.getStringExtra(EXTRA_CHILD).orEmpty(),
            i.getStringExtra(EXTRA_MESSAGE).orEmpty(), i.getLongExtra(EXTRA_AT, 0),
        )
    }

    /** A newer alarm while this one is open replaces it; the older one keeps ringing in the shade. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        show(intent)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        show(intent)
        setContent {
            val (id, title, child, message, at) = shown
            SentraTheme {
                Column(
                    Modifier.fillMaxSize().background(Tokens.WrongBg).systemBarsPadding().padding(24.dp),
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(title, color = Tokens.Wrong, style = MaterialTheme.typography.headlineLarge, modifier = Modifier.semantics { heading() })
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "$child · ${SimpleDateFormat("d MMM HH:mm", Locale("id", "ID")).format(Date(at))}",
                        color = Tokens.Text,
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(message, color = Tokens.Text, style = MaterialTheme.typography.bodyLarge)
                    Spacer(Modifier.height(32.dp))
                    Button(
                        onClick = { care.acknowledge(id); finish() },
                        modifier = Modifier.fillMaxWidth().height(64.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Tokens.Wrong, contentColor = Tokens.AccentText),
                    ) { Text("Sudah saya lihat", style = MaterialTheme.typography.titleMedium) }
                    Spacer(Modifier.height(12.dp))
                    // The map is what a parent needs next; the alarm keeps ringing until acknowledged.
                    OutlinedButton(
                        onClick = {
                            startActivity(Intent(this@AlarmActivity, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP))
                            care.acknowledge(id)
                            finish()
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Lihat di peta", color = Tokens.Text) }
                }
            }
        }
    }

    companion object {
        private const val EXTRA_ID = "id"
        private const val EXTRA_TITLE = "title"
        private const val EXTRA_CHILD = "child"
        private const val EXTRA_MESSAGE = "message"
        private const val EXTRA_AT = "at"

        fun intent(context: Context, e: RemoteEvent, child: String): Intent =
            Intent(context, AlarmActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .putExtra(EXTRA_ID, e.id)
                .putExtra(EXTRA_TITLE, AlertRules.alarmTitle(e.type))
                .putExtra(EXTRA_CHILD, child.ifBlank { "Anak" })
                .putExtra(EXTRA_MESSAGE, e.message)
                .putExtra(EXTRA_AT, e.occurredAt)
    }
}
