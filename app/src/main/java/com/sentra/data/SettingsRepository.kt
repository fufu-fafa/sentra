package com.sentra.data

import android.content.Context
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.sentra.R
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Which side of SENTRA this phone is. One APK, two modes. */
enum class AppMode {
    /** Mode Anak: the child's phone, connected to the sleeve. */
    CHILD,
    /** Mode Orang tua: SENTRA Care. */
    PARENT,
}

data class AppSettings(
    /** Null until the first-launch choice is made. */
    val mode: AppMode? = null,
    /** The sleeve this phone reconnects to; null until SAMBUNGKAN found one. */
    val deviceAddress: String? = null,
    val deviceName: String? = null,
    /** Connect only to a sleeve with exactly this name (e.g. SENTRA-01); blank = any SENTRA sleeve. */
    val sleeveName: String = "",
    /** What the parents see. An alias, not the child's real name. */
    val childName: String = "Anak",
    /** The relay, e.g. https://relay.example.id; blank = no relay, everything stays on this phone. */
    val relayUrl: String = "",
    /** The relay's own id, so a new address for the same relay keeps this phone's links. */
    val relayId: String? = null,
    /** This phone's key as the child's phone on the relay, once registered. */
    val wearerToken: String? = null,
    /** This phone's key as a parent on the relay, once linked. */
    val caregiverToken: String? = null,
    val caregiverName: String = "",
    /** Newest acknowledgement the child phone has seen, so heartbeats only fetch newer ones. */
    val acksSince: Long = 0,
    /** Last parent stream event id, so a reconnect replays what was missed. */
    val lastStreamEventId: Long = 0,
    /** The route chosen on Beranda. */
    val selectedRouteId: Long? = null,
    /** Test without hardware: a pretend sleeve instead of Bluetooth. */
    val simulatedSleeve: Boolean = false,
    /** Test without walking: navigation is fed a walk along the chosen route instead of GPS. */
    val simulatedGps: Boolean = false,
)

private val Context.dataStore by preferencesDataStore("settings")

class SettingsRepository(private val context: Context) {
    /** Debug builds point at a relay on the development computer; release builds start with none. */
    private val defaultRelayUrl = context.getString(R.string.default_relay_url)

    private object Keys {
        val mode = stringPreferencesKey("app_mode")
        val address = stringPreferencesKey("device_address")
        val name = stringPreferencesKey("device_name")
        val sleeveName = stringPreferencesKey("sleeve_name")
        val childName = stringPreferencesKey("child_name")
        val relayUrl = stringPreferencesKey("relay_url")
        val relayId = stringPreferencesKey("relay_id")
        val wearerToken = stringPreferencesKey("wearer_token")
        val caregiverToken = stringPreferencesKey("caregiver_token")
        val caregiverName = stringPreferencesKey("caregiver_name")
        val acksSince = longPreferencesKey("acks_since")
        val lastStreamEventId = longPreferencesKey("last_stream_event_id")
        val selectedRoute = longPreferencesKey("selected_route")
        val simulatedSleeve = booleanPreferencesKey("simulated_sleeve")
        val simulatedGps = booleanPreferencesKey("simulated_gps")
    }

    val settings: Flow<AppSettings> = context.dataStore.data.map { p ->
        AppSettings(
            mode = p[Keys.mode]?.let { runCatching { AppMode.valueOf(it) }.getOrNull() },
            deviceAddress = p[Keys.address],
            deviceName = p[Keys.name],
            sleeveName = p[Keys.sleeveName] ?: "",
            childName = p[Keys.childName] ?: "Anak",
            relayUrl = p[Keys.relayUrl] ?: defaultRelayUrl,
            relayId = p[Keys.relayId],
            wearerToken = p[Keys.wearerToken],
            caregiverToken = p[Keys.caregiverToken],
            caregiverName = p[Keys.caregiverName] ?: "",
            acksSince = p[Keys.acksSince] ?: 0,
            lastStreamEventId = p[Keys.lastStreamEventId] ?: 0,
            selectedRouteId = p[Keys.selectedRoute],
            simulatedSleeve = p[Keys.simulatedSleeve] ?: false,
            simulatedGps = p[Keys.simulatedGps] ?: false,
        )
    }

    suspend fun setMode(mode: AppMode) = edit { it[Keys.mode] = mode.name }

    suspend fun setDevice(address: String?, name: String?) = edit {
        if (address == null) { it.remove(Keys.address); it.remove(Keys.name) }
        else { it[Keys.address] = address; it[Keys.name] = name ?: address }
    }

    suspend fun setSleeveName(name: String) = edit { it[Keys.sleeveName] = name.trim() }
    suspend fun setChildName(name: String) = edit { it[Keys.childName] = name.trim().ifEmpty { "Anak" } }
    suspend fun setSelectedRoute(id: Long?) = edit { if (id == null) it.remove(Keys.selectedRoute) else it[Keys.selectedRoute] = id }
    suspend fun setSimulatedSleeve(on: Boolean) = edit { it[Keys.simulatedSleeve] = on }
    suspend fun setSimulatedGps(on: Boolean) = edit { it[Keys.simulatedGps] = on }

    /**
     * Points the app at a relay. The same relay at a new address (a different WiFi, or the move to
     * the VPS) keeps this phone's links; only a relay known to be a different one starts over. An
     * address that can't be reached right now (relayId null) keeps them too.
     */
    suspend fun setRelay(url: String, relayId: String?) = edit {
        val known = it[Keys.relayId]
        val sameRelay = relayId == null || known == null || known == relayId
        if (!sameRelay) {
            it.remove(Keys.wearerToken); it.remove(Keys.caregiverToken); it.remove(Keys.acksSince); it.remove(Keys.lastStreamEventId)
        }
        it[Keys.relayUrl] = url.trim()
        if (relayId != null) it[Keys.relayId] = relayId
    }

    suspend fun setWearerToken(token: String?) = edit { if (token == null) it.remove(Keys.wearerToken) else it[Keys.wearerToken] = token }
    suspend fun setCaregiver(token: String?, name: String) = edit {
        if (token != null) it[Keys.caregiverToken] = token
        it[Keys.caregiverName] = name
    }
    suspend fun clearCaregiverLink() = edit {
        it.remove(Keys.caregiverToken)
        it.remove(Keys.lastStreamEventId)
    }
    suspend fun setAcksSince(at: Long) = edit { it[Keys.acksSince] = at }
    suspend fun setLastStreamEventId(id: Long) = edit { it[Keys.lastStreamEventId] = id }

    private suspend fun edit(block: (MutablePreferences) -> Unit) {
        context.dataStore.edit { block(it) }
    }
}
