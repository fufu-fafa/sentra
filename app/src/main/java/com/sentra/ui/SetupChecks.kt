package com.sentra.ui

import android.annotation.SuppressLint
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.core.location.LocationManagerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.sentra.child
import com.sentra.ui.theme.AppTheme
import com.sentra.ui.theme.Tokens
import kotlinx.coroutines.delay

/** Something that stops SENTRA working as expected, with the fix if the app can offer one. */
data class SetupProblem(val text: String, val fixLabel: String? = null, val fix: ((Context) -> Unit)? = null)

/** What the child's phone last learned from the relay. */
data class RelayHealth(val url: String, val registered: Boolean, val error: String?, val parents: Int?, val pending: Int)

/** [childMode] true: the child's phone (sleeve, GPS). False: a parent's phone (alarms). */
fun setupProblems(context: Context, childMode: Boolean, simulatedSleeve: Boolean = false, simulatedGps: Boolean = false): List<SetupProblem> = buildList {
    if (childMode) {
        if (!simulatedSleeve && !context.child.canConnect()) {
            add(SetupProblem("Nearby devices permission is off, so the app can't reach the sleeve.", "Open app settings", ::openAppSettings))
        }
        if (!simulatedGps) {
            if (!context.child.hasLocationPermission()) {
                add(SetupProblem("Location permission is off, so navigation can't read GPS.", "Open app settings", ::openAppSettings))
            } else if (!LocationManagerCompat.isLocationEnabled(context.getSystemService(LocationManager::class.java))) {
                add(SetupProblem("Location (GPS) is off on this phone.", "Turn on location", ::openLocationSettings))
            }
        }
    }
    if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) {
        add(
            SetupProblem(
                if (childMode) "Notifications are off, so the sleeve status isn't shown."
                else "Notifications are off, so alarms won't appear on this phone.",
                "Turn on notifications", ::openNotificationSettings,
            )
        )
    }
    val power = context.getSystemService(PowerManager::class.java)
    if (!power.isIgnoringBatteryOptimizations(context.packageName)) {
        add(
            SetupProblem(
                if (childMode) "Battery saver may stop navigation while the screen is off."
                else "Battery saver may cause missed alarms.",
                "Allow in background", ::requestBatteryExemption,
            )
        )
    }
    if (!childMode) {
        if (Build.VERSION.SDK_INT >= 34 && !context.getSystemService(NotificationManager::class.java).canUseFullScreenIntent()) {
            add(SetupProblem("Full-screen alarms are blocked, so alarms only show as notifications.", "Allow full-screen alarms", ::openFullScreenSettings))
        }
        if (!hasInternet(context)) add(SetupProblem("No internet, so the child's alarms won't get through."))
    }
}

private fun openFullScreenSettings(context: Context) {
    runCatching {
        context.startActivity(Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:${context.packageName}")))
    }.onFailure { openAppSettings(context) }
}

private fun hasInternet(context: Context): Boolean {
    val cm = context.getSystemService(ConnectivityManager::class.java)
    val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
    return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
}

// The whole point of the app is to run in the background, which is the permitted use case.
@SuppressLint("BatteryLife")
private fun requestBatteryExemption(context: Context) {
    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}"))
    runCatching { context.startActivity(intent) }
        .onFailure { runCatching { context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) } }
}

private fun openNotificationSettings(context: Context) {
    runCatching {
        context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
    }
}

private fun openLocationSettings(context: Context) {
    runCatching { context.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) }
}

private fun openAppSettings(context: Context) {
    runCatching {
        context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")))
    }
}

/** Lists what needs fixing on this phone. Re-checked on resume and every 15 s. */
@Composable
fun SetupWarnings(childMode: Boolean, simulatedSleeve: Boolean = false, simulatedGps: Boolean = false) {
    val context = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) tick++ }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(Unit) {
        while (true) { delay(15_000); tick++ }
    }
    val problems = remember(tick, childMode, simulatedSleeve, simulatedGps) { setupProblems(context, childMode, simulatedSleeve, simulatedGps) }
    if (problems.isEmpty()) return

    Card(background = Tokens.WrongBg, border = Tokens.Wrong) {
        SectionLabel("Needs fixing")
        problems.forEachIndexed { i, p ->
            if (i > 0) Spacer(Modifier.height(10.dp))
            Text(p.text, style = MaterialTheme.typography.bodyMedium)
            if (p.fix != null) {
                OutlinedButton(onClick = { p.fix.invoke(context) }) { Text(p.fixLabel ?: "Fix") }
            }
        }
        Spacer(Modifier.height(4.dp))
        Text("Disappears once fixed.", color = AppTheme.extra.muted, style = MaterialTheme.typography.bodySmall)
    }
}
