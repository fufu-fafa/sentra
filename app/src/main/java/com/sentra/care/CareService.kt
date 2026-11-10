package com.sentra.care

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.sentra.R
import com.sentra.alert.AlertDispatcher
import com.sentra.care
import com.sentra.ui.MainActivity
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicInteger

/**
 * Android kills the app if a service started with startForegroundService() is stopped before it
 * has called startForeground(). That happens when push connects right after the service was asked
 * for, so a stop that arrives during startup is left for the service to carry out itself.
 */
internal class ForegroundStartGate {
    private val pending = AtomicInteger()
    @Volatile private var wanted = false

    fun onStartRequested() {
        wanted = true
        pending.incrementAndGet()
    }

    /** A requested start reached startForeground(), or failed. True: nothing wants the service now. */
    fun onStartFinished(): Boolean = pending.updateAndGet { (it - 1).coerceAtLeast(0) } == 0 && !wanted

    /** True when it's safe to stop the service from outside. */
    fun onStopRequested(): Boolean {
        wanted = false
        return pending.get() == 0
    }
}

/** Keeps SENTRA Care's live stream to the relay open in the background when push isn't available. */
class CareService : LifecycleService() {
    private var started = false

    override fun onDestroy() {
        super.onDestroy()
        NotificationManagerCompat.from(this).cancel(AlertDispatcher.CARE_SERVICE_ID)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        val requested = intent != null // null: the system restarted us, nobody called start()
        try {
            ServiceCompat.startForeground(
                this, AlertDispatcher.CARE_SERVICE_ID, notification("Menyambung ke relay…"),
                if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING else 0,
            )
        } catch (e: Exception) {
            Log.w(TAG, "Could not start in foreground", e)
            if (requested) gate.onStartFinished()
            stopSelf(startId)
            return START_NOT_STICKY
        }
        // stopSelf(startId) does nothing if a newer start is already on its way.
        if (requested && gate.onStartFinished()) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        if (!started) {
            started = true
            val c = care
            lifecycleScope.launch {
                combine(c.stream, c.children) { s, w ->
                    val names = w.joinToString { it.name }.ifEmpty { "belum ada" }
                    when (s) {
                        StreamState.LIVE -> "Memantau $names"
                        else -> "Menyambung lagi ke relay…"
                    }
                }.collect { text ->
                    @Suppress("MissingPermission")
                    runCatching { NotificationManagerCompat.from(this@CareService).notify(AlertDispatcher.CARE_SERVICE_ID, notification(text)) }
                }
            }
        }
        return START_STICKY
    }

    private fun notification(text: String) = NotificationCompat.Builder(this, AlertDispatcher.CH_SERVICE)
        .setSmallIcon(R.drawable.ic_stat_sentra)
        .setContentTitle("SENTRA Care")
        .setContentText(text)
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setContentIntent(PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
        .build()

    companion object {
        private const val TAG = "CareService"

        private val gate = ForegroundStartGate()

        fun start(context: Context) {
            gate.onStartRequested()
            runCatching { ContextCompat.startForegroundService(context, Intent(context, CareService::class.java)) }
                .onFailure {
                    Log.w(TAG, "start failed", it)
                    gate.onStartFinished()
                }
        }

        fun stop(context: Context) {
            if (gate.onStopRequested()) context.stopService(Intent(context, CareService::class.java))
        }
    }
}

/** The Acknowledge button on an alarm notification. */
class AckReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getLongExtra(EXTRA_EVENT_ID, -1)
        if (id >= 0) context.care.acknowledge(id)
    }

    companion object {
        const val EXTRA_EVENT_ID = "eventId"
    }
}
