package com.sentra.service

import android.Manifest
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
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
import com.sentra.ble.LinkState
import com.sentra.child
import com.sentra.nav.NavSnapshot
import com.sentra.nav.NavState
import com.sentra.ui.MainActivity
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Keeps the process alive in the background, and with it the sleeve link, GPS, navigation, the
 * 2-second PING and the upload to the relay. This is what App Inventor could not do (§9.8): the
 * trip goes on with the screen off and the phone in a pocket.
 */
class SentraService : LifecycleService() {
    private var started = false

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        val types = foregroundTypes()
        try {
            ServiceCompat.startForeground(this, AlertDispatcher.SERVICE_ID, notification("Menyiapkan…"), types)
        } catch (e: Exception) {
            // Missing permission for every type, or a background-start restriction.
            Log.w(TAG, "Could not start in foreground", e)
            stopSelf()
            return START_NOT_STICKY
        }
        if (!started) {
            started = true
            val c = child
            lifecycleScope.launch {
                combine(c.sleeve.link, c.nav, c.navRoute) { link, nav, route -> statusText(link, nav, route?.name) }
                    .collect { text ->
                        @Suppress("MissingPermission")
                        runCatching { NotificationManagerCompat.from(this@SentraService).notify(AlertDispatcher.SERVICE_ID, notification(text)) }
                    }
            }
        }
        return START_STICKY
    }

    /**
     * Android 14 refuses a foreground type whose permission is missing, so only the ones that are
     * granted are declared. Location is added while a trip runs (startNavigation starts the service
     * again from the foreground, which is when Android allows it).
     */
    private fun foregroundTypes(): Int {
        if (Build.VERSION.SDK_INT < 29) return 0
        var types = 0
        if (child.canConnect()) types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        val location = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (location && child.nav.value.active) types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        return types
    }

    override fun onDestroy() {
        super.onDestroy()
        // The status updater can re-post after stopForeground; clear it so no stale notice stays.
        NotificationManagerCompat.from(this).cancel(AlertDispatcher.SERVICE_ID)
    }

    private fun notification(text: String) = NotificationCompat.Builder(this, AlertDispatcher.CH_SERVICE)
        .setSmallIcon(R.drawable.ic_stat_sentra)
        .setContentTitle("SENTRA")
        .setContentText(text)
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setContentIntent(PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
        .build()

    companion object {
        private const val TAG = "SentraService"

        fun statusText(link: LinkState, nav: NavSnapshot, routeName: String?): String {
            val sleeve = when (link) {
                LinkState.CONNECTED -> "Sleeve tersambung"
                LinkState.CONNECTING -> "Mencari sleeve…"
                LinkState.DISCONNECTED -> "Sleeve tidak tersambung"
            }
            val trip = when (nav.state) {
                NavState.NAVIGATING -> "Navigasi${routeName?.let { " $it" } ?: ""}: titik ${nav.targetNumber} dari ${nav.totalPoints}" +
                    (nav.distanceM?.let { ", ${it.roundToInt()} m lagi" } ?: "")
                NavState.OFF_ROUTE -> "KELUAR RUTE"
                else -> null
            }
            return listOfNotNull(trip, sleeve).joinToString(" · ")
        }

        /**
         * Android 14 only lets the service run with a foreground type whose permission is granted.
         * Starting it with none would crash the app a few seconds later, so then it isn't started
         * and the sleeve link or trip lasts only while the app is open.
         */
        private fun canRun(context: Context): Boolean {
            if (Build.VERSION.SDK_INT < 34) return true
            val location = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
            return context.child.canConnect() || (location && context.child.nav.value.active)
        }

        fun start(context: Context) {
            if (!canRun(context)) {
                Log.w(TAG, "not started: neither Bluetooth nor location permission for a foreground service")
                return
            }
            runCatching { ContextCompat.startForegroundService(context, Intent(context, SentraService::class.java)) }
                .onFailure { Log.w(TAG, "start failed", it) }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, SentraService::class.java))
        }
    }
}

/** Creating the Application on boot starts the managers, which reconnect and start the service. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        context.child.retryConnect()
    }
}
