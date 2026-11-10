package com.sentra

import android.app.Application
import com.sentra.alert.AlertDispatcher
import com.sentra.care.CareManager
import com.sentra.data.AppDatabase
import com.sentra.data.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.osmdroid.config.Configuration
import java.io.File

class SentraApp : Application() {
    /** Mode Anak. */
    lateinit var child: ChildManager
        private set
    /** Mode Orang tua (SENTRA Care). */
    lateinit var care: CareManager
        private set

    override fun onCreate() {
        super.onCreate()
        // OpenStreetMap's tile servers ask for an identifying user agent; tiles are cached in the app's own folder.
        Configuration.getInstance().apply {
            userAgentValue = packageName
            osmdroidBasePath = File(cacheDir, "osmdroid")
            osmdroidTileCache = File(cacheDir, "osmdroid/tiles")
        }
        val alerts = AlertDispatcher(this).apply { createChannels() }
        val settings = SettingsRepository(this)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        child = ChildManager(this, settings, AppDatabase.create(this), scope)
        care = CareManager(this, settings, alerts, scope, child.finder) { child.appSettings.value }
        child.start()
        care.start()
    }
}

val android.content.Context.child: ChildManager
    get() = (applicationContext as SentraApp).child

val android.content.Context.care: CareManager
    get() = (applicationContext as SentraApp).care
