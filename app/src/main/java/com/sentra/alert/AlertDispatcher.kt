package com.sentra.alert

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.sentra.R
import com.sentra.care.AckReceiver
import com.sentra.care.AlarmActivity
import com.sentra.care.RemoteEvent
import com.sentra.ui.MainActivity

/** Turns what the relay reports into notifications and alarms on a parent's phone. */
class AlertDispatcher(private val context: Context) {

    fun createChannels() {
        val nm = context.getSystemService(NotificationManager::class.java)
        val alarm = NotificationChannel(CH_ALARM, "Alarm anak", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Anak keluar rute, sleeve terputus, atau HP anak offline saat navigasi"
            setSound(
                RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM),
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build(),
            )
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 800, 400, 800, 400, 800)
            setBypassDnd(true)
        }
        val arrival = NotificationChannel(CH_ARRIVAL, "Sampai tujuan", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Berbunyi sekali saat anak sampai, dan untuk tes alarm"
        }
        val info = NotificationChannel(CH_INFO, "Kabar perjalanan", NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "Anak mulai jalan, HP anak lama tidak ada kabar"
        }
        val service = NotificationChannel(CH_SERVICE, "Sambungan", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Tampil selama aplikasi menjaga sambungan ke sleeve atau ke relay"
        }
        nm.createNotificationChannels(listOf(alarm, arrival, info, service))
    }

    /** KELUAR_RUTE, TERPUTUS or OFFLINE: full-screen, rings until acknowledged. */
    fun careAlarm(e: RemoteEvent, child: String, escalated: Boolean) {
        val title = "${e.type.label} — ${child.ifBlank { "Anak" }}"
        val text = (if (escalated) "Belum ada yang melihat. " else "") + e.message
        val open = alarmScreen(e, child)
        val n = NotificationCompat.Builder(context, CH_ALARM)
            .setSmallIcon(R.drawable.ic_stat_sentra)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setFullScreenIntent(open, true)
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(0, "Sudah saya lihat", ackIntent(e.id))
            .build()
        n.flags = n.flags or android.app.Notification.FLAG_INSISTENT // keep ringing until acknowledged
        post(alarmId(e.id), n)
        // With the app on screen Android shows a banner instead of the full-screen alarm; open it ourselves.
        if (appInForeground()) runCatching { context.startActivity(AlarmActivity.intent(context, e, child)) }
    }

    /** ARRIVED and TEST: rings once. */
    fun careRingOnce(e: RemoteEvent, child: String) {
        val id = INFO_BASE + (e.id % 100_000).toInt()
        val n = NotificationCompat.Builder(context, CH_ARRIVAL)
            .setSmallIcon(R.drawable.ic_stat_sentra)
            .setContentTitle("${e.type.label} — ${child.ifBlank { "Anak" }}")
            .setContentText(e.message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(e.message))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(openApp(id))
            .setAutoCancel(true)
            .build()
        post(id, n)
    }

    /** NAV_START: a quiet notification. */
    fun careInfo(e: RemoteEvent, child: String) {
        val id = INFO_BASE + (e.id % 100_000).toInt()
        val n = NotificationCompat.Builder(context, CH_INFO)
            .setSmallIcon(R.drawable.ic_stat_sentra)
            .setContentTitle("${e.type.label} — ${child.ifBlank { "Anak" }}")
            .setContentText(e.message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(e.message))
            .setContentIntent(openApp(id))
            .setAutoCancel(true)
            .build()
        post(id, n)
    }

    fun cancelCareAlarm(eventId: Long) = NotificationManagerCompat.from(context).cancel(alarmId(eventId))

    /** The child's phone went quiet while no trip was running (15 min): worth knowing, not an alarm. */
    fun notifyChildOffline(childId: Long, name: String) {
        val n = NotificationCompat.Builder(context, CH_INFO)
            .setSmallIcon(R.drawable.ic_stat_sentra)
            .setContentTitle("HP ${name.ifBlank { "anak" }} offline")
            .setContentText("Tidak ada kabar selama 15 menit. Lokasi dan alarm tidak sampai sebelum HP itu online lagi.")
            .setContentIntent(openApp(OFFLINE_BASE + childId.toInt()))
            .setAutoCancel(true)
            .build()
        post(OFFLINE_BASE + childId.toInt(), n)
    }

    fun clearChildOffline(childId: Long) = NotificationManagerCompat.from(context).cancel(OFFLINE_BASE + childId.toInt())

    private fun appInForeground(): Boolean {
        val info = android.app.ActivityManager.RunningAppProcessInfo()
        android.app.ActivityManager.getMyMemoryState(info)
        return info.importance == android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
    }

    private fun alarmId(eventId: Long) = ALARM_BASE + (eventId % 100_000).toInt()

    private fun alarmScreen(e: RemoteEvent, child: String): PendingIntent = PendingIntent.getActivity(
        context, alarmId(e.id),
        AlarmActivity.intent(context, e, child),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun ackIntent(eventId: Long): PendingIntent = PendingIntent.getBroadcast(
        context, alarmId(eventId),
        Intent(context, AckReceiver::class.java).putExtra(AckReceiver.EXTRA_EVENT_ID, eventId),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun openApp(requestCode: Int): PendingIntent = PendingIntent.getActivity(
        context, requestCode,
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    @SuppressLint("MissingPermission")
    private fun post(id: Int, n: android.app.Notification) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        NotificationManagerCompat.from(context).notify(id, n)
    }

    companion object {
        const val CH_ALARM = "alarm"
        const val CH_ARRIVAL = "arrival"
        const val CH_INFO = "info"
        const val CH_SERVICE = "service"
        const val SERVICE_ID = 1
        const val CARE_SERVICE_ID = 3
        private const val ALARM_BASE = 100_000
        private const val INFO_BASE = 200_000
        private const val OFFLINE_BASE = 300_000
    }
}
