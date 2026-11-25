package com.sentra.data

import android.content.Context
import androidx.datastore.core.DataMigration
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
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
    /** Child mode: the child's phone, connected to the sleeve. */
    CHILD,
    /** Parent mode: SENTRA Care. */
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
    val childName: String = "Child",
    /** The VPS relay (`default_relay_url`). */
    val relayUrl: String = "",
    /** This phone's key as the child's phone on the relay, once registered. */
    val childToken: String? = null,
    /** This phone's key as a parent on the relay, once linked. */
    val parentToken: String? = null,
    val parentName: String = "",
    /** Newest acknowledgement the child phone has seen, so heartbeats only fetch newer ones. */
    val acksSince: Long = 0,
    /** Last parent stream event id, so a reconnect replays what was missed. */
    val lastStreamEventId: Long = 0,
    /** The route chosen on Home. */
    val selectedRouteId: Long? = null,
    /** Test without hardware: a pretend sleeve instead of Bluetooth. */
    val simulatedSleeve: Boolean = false,
    /** Test without walking: navigation is fed a walk along the chosen route instead of GPS. */
    val simulatedGps: Boolean = false,
)

private val Context.dataStore by preferencesDataStore(
    name = "settings",
    produceMigrations = { listOf(MoveToVpsRelay(it.getString(R.string.default_relay_url))) },
)

/**
 * Earlier builds saved a relay address. The app now always uses the VPS, so a saved one is dropped,
 * and if it was a different relay, so are that relay's keys and cursors.
 */
private class MoveToVpsRelay(private val vpsUrl: String) : DataMigration<Preferences> {
    private val savedUrl = stringPreferencesKey("relay_url")

    override suspend fun shouldMigrate(currentData: Preferences) = savedUrl in currentData

    override suspend fun migrate(currentData: Preferences): Preferences = currentData.toMutablePreferences().apply {
        if (this[savedUrl]?.trimEnd('/') != vpsUrl) {
            // "wearer_token" and "caregiver_token" are the child and parent keys (see Keys).
            listOf("wearer_token", "caregiver_token").forEach { remove(stringPreferencesKey(it)) }
            listOf("acks_since", "last_stream_event_id").forEach { remove(longPreferencesKey(it)) }
        }
        remove(savedUrl)
        remove(stringPreferencesKey("relay_id"))
        remove(booleanPreferencesKey("moved_to_vps_relay"))
    }

    override suspend fun cleanUp() = Unit
}

class SettingsRepository(private val context: Context) {
    private val vpsRelayUrl = context.getString(R.string.default_relay_url)

    private object Keys {
        val mode = stringPreferencesKey("app_mode")
        val address = stringPreferencesKey("device_address")
        val name = stringPreferencesKey("device_name")
        val sleeveName = stringPreferencesKey("sleeve_name")
        val childName = stringPreferencesKey("child_name")
        // Stored under fall-bracelet's old names, so phones that were already linked stay linked.
        val childToken = stringPreferencesKey("wearer_token")
        val parentToken = stringPreferencesKey("caregiver_token")
        val parentName = stringPreferencesKey("caregiver_name")
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
            childName = p[Keys.childName] ?: "Child",
            relayUrl = vpsRelayUrl,
            childToken = p[Keys.childToken],
            parentToken = p[Keys.parentToken],
            parentName = p[Keys.parentName] ?: "",
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
    suspend fun setChildName(name: String) = edit { it[Keys.childName] = name.trim().ifEmpty { "Child" } }
    suspend fun setSelectedRoute(id: Long?) = edit { if (id == null) it.remove(Keys.selectedRoute) else it[Keys.selectedRoute] = id }
    suspend fun setSimulatedSleeve(on: Boolean) = edit { it[Keys.simulatedSleeve] = on }
    suspend fun setSimulatedGps(on: Boolean) = edit { it[Keys.simulatedGps] = on }

    suspend fun setChildToken(token: String?) = edit { if (token == null) it.remove(Keys.childToken) else it[Keys.childToken] = token }
    suspend fun setParent(token: String?, name: String) = edit {
        if (token != null) it[Keys.parentToken] = token
        it[Keys.parentName] = name
    }
    suspend fun clearParentLink() = edit {
        it.remove(Keys.parentToken)
        it.remove(Keys.lastStreamEventId)
    }
    suspend fun setAcksSince(at: Long) = edit { it[Keys.acksSince] = at }
    suspend fun setLastStreamEventId(id: Long) = edit { it[Keys.lastStreamEventId] = id }

    private suspend fun edit(block: (MutablePreferences) -> Unit) {
        context.dataStore.edit { block(it) }
    }
}
