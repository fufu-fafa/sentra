package com.sentra.care

import android.content.Context
import android.util.Log
import com.google.firebase.messaging.FirebaseMessaging
import com.sentra.alert.AlertDispatcher
import com.sentra.alert.AlertRules
import com.sentra.alert.CareAlert
import com.sentra.alert.CareEventType
import com.sentra.data.AppMode
import com.sentra.data.AppSettings
import com.sentra.data.SettingsRepository
import com.sentra.nav.GeoPoint
import com.sentra.relay.RelayClient
import com.sentra.relay.RelayDiscovery
import com.sentra.relay.RelayException
import com.sentra.relay.RelayFinder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** One event as the relay tells the parents about it. */
data class RemoteEvent(
    val id: Long,
    val childId: Long,
    val type: CareEventType,
    val batteryPct: Int?,
    val occurredAt: Long,
    val lat: Double?,
    val lon: Double?,
    val message: String,
    val ackedBy: String?,
    val ackedAt: Long?,
) {
    companion object {
        fun from(o: JSONObject) = RemoteEvent(
            id = o.getLong("id"),
            childId = o.getLong("wearerId"),
            type = CareEventType.from(o.optString("type")),
            batteryPct = if (o.isNull("batteryPct")) null else o.optInt("batteryPct"),
            occurredAt = o.getLong("occurredAt"),
            lat = if (o.isNull("lat")) null else o.optDouble("lat"),
            lon = if (o.isNull("lon")) null else o.optDouble("lon"),
            message = o.optString("message"),
            ackedBy = o.optString("ackedBy").takeIf { it.isNotEmpty() && it != "null" },
            ackedAt = o.optLong("ackedAt").takeIf { it > 0 },
        )
    }
}

/** Where the child was at a moment. */
data class TrackPoint(val at: Long, val lat: Double, val lon: Double, val accuracyM: Float?) {
    val point: GeoPoint get() = GeoPoint(lat, lon)

    companion object {
        fun from(o: JSONObject) = TrackPoint(
            at = o.getLong("at"), lat = o.getDouble("lat"), lon = o.getDouble("lon"),
            accuracyM = if (o.isNull("acc")) null else o.optDouble("acc").toFloat(),
        )
    }
}

/** What the child's phone last said about itself (the heartbeat's status). */
data class ChildStatus(
    val sleeveConnected: Boolean? = null,
    /** IDLE, NAVIGATING, OFF_ROUTE or ARRIVED. */
    val navState: String? = null,
    val routeName: String? = null,
    /** The recorded route being walked, while a trip runs. */
    val route: List<GeoPoint> = emptyList(),
    val nextPoint: Int? = null,
    val totalPoints: Int? = null,
    val distanceM: Int? = null,
    val batteryPct: Int? = null,
    val obstacleCm: Int? = null,
    val simulated: Boolean = false,
) {
    companion object {
        fun from(st: JSONObject?): ChildStatus {
            if (st == null) return ChildStatus()
            val route = st.optJSONArray("route")
            return ChildStatus(
                sleeveConnected = if (st.has("sleeveConnected")) st.optBoolean("sleeveConnected") else null,
                navState = st.optString("navState").takeIf { it.isNotEmpty() },
                routeName = st.optString("routeName").takeIf { it.isNotEmpty() },
                route = if (route == null) emptyList() else List(route.length()) {
                    val p = route.getJSONArray(it)
                    GeoPoint(p.getDouble(0), p.getDouble(1))
                },
                nextPoint = if (st.has("nextPoint")) st.optInt("nextPoint") else null,
                totalPoints = if (st.has("totalPoints")) st.optInt("totalPoints") else null,
                distanceM = if (st.has("distanceM")) st.optInt("distanceM") else null,
                batteryPct = if (st.has("batteryPct")) st.optInt("batteryPct") else null,
                obstacleCm = if (st.has("obstacleCm")) st.optInt("obstacleCm") else null,
                simulated = st.optBoolean("simulated"),
            )
        }
    }
}

/** A child this parent follows, as last heard from the relay. */
data class WatchedChild(
    val id: Long,
    val name: String,
    val offline: Boolean,
    val navigating: Boolean,
    val lastHeartbeat: Long?,
    val status: ChildStatus,
    val location: TrackPoint?,
    /** Where the child has been in the last two hours, oldest first. */
    val track: List<TrackPoint>,
    val events: List<RemoteEvent>,
) {
    companion object {
        fun from(o: JSONObject, track: List<TrackPoint> = emptyList()): WatchedChild {
            val ev = o.optJSONArray("events")
            return WatchedChild(
                id = o.getLong("id"),
                name = o.getString("name"),
                offline = o.optBoolean("offline"),
                navigating = o.optBoolean("navigating"),
                lastHeartbeat = o.optLong("lastHeartbeat").takeIf { it > 0 },
                status = ChildStatus.from(o.optJSONObject("status")),
                location = o.optJSONObject("location")?.let(TrackPoint::from),
                track = track,
                events = if (ev == null) emptyList() else List(ev.length()) { RemoteEvent.from(ev.getJSONObject(it)) },
            )
        }
    }
}

/** PUSH: no live connection because SENTRA Care is closed; alarms arrive by FCM. */
enum class StreamState { OFF, CONNECTING, LIVE, PUSH }

/**
 * Mode Orang tua (SENTRA Care): follows the child on the relay's live stream and rings for
 * KELUAR_RUTE, TERPUTUS and OFFLINE. From fall-bracelet's CaregiverManager, with the map data
 * added. Lives as long as the process; [CareService] keeps the process alive while linked when
 * push isn't available.
 */
class CareManager(
    private val context: Context,
    private val settings: SettingsRepository,
    private val alerts: AlertDispatcher,
    private val scope: CoroutineScope,
    private val finder: RelayFinder,
    private val current: () -> AppSettings,
) {
    private val _children = MutableStateFlow<List<WatchedChild>>(emptyList())
    val children: StateFlow<List<WatchedChild>> = _children.asStateFlow()
    private val _stream = MutableStateFlow(StreamState.OFF)
    val stream: StateFlow<StreamState> = _stream.asStateFlow()
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private var streamJob: Job? = null

    /** SENTRA Care is on screen. */
    private val visible = MutableStateFlow(false)
    /** The relay has this phone's FCM token, so alarms can wake it without a live connection. */
    private val _pushReady = MutableStateFlow(false)
    val pushReady: StateFlow<Boolean> = _pushReady.asStateFlow()

    /** Called by the activity on start and stop. */
    fun setVisible(on: Boolean) { visible.value = on }

    /**
     * Push (FCM) wakes the phone for alarms, so the live stream only runs while SENTRA Care is
     * open. If push isn't set up (the app was built without google-services.json, no Google Play
     * services, no internet when linking), the stream and its foreground service stay on in the
     * background instead.
     */
    fun start() {
        val link = settings.settings.map { s -> (s.caregiverToken.takeIf { s.mode == AppMode.PARENT }) to s.relayUrl }
            .distinctUntilChanged()
        scope.launch {
            link.collect { (token, url) -> if (token != null && url.isNotBlank()) registerPush() else _pushReady.value = false }
        }
        scope.launch {
            combine(link, visible, _pushReady) { (token, url), shown, push ->
                val linked = token != null && url.isNotBlank()
                Triple(if (linked && (shown || !push)) url to token!! else null, linked && !push, linked)
            }.distinctUntilChanged().collect { (wantStream, wantService, _) ->
                streamJob?.cancel()
                if (wantService) CareService.start(context) else CareService.stop(context)
                if (wantStream == null) {
                    _stream.value = if (_pushReady.value) StreamState.PUSH else StreamState.OFF
                } else {
                    streamJob = scope.launch { streamLoop(wantStream.first, wantStream.second) }
                }
            }
        }
    }

    // --- Push (FCM) ----------------------------------------------------------------------

    /** Gets this phone's FCM token and gives it to the relay. Without Firebase the stream stays on. */
    private suspend fun registerPush() {
        val token = runCatching { FirebaseMessaging.getInstance().token.await() }
            .onFailure { Log.w(TAG, "no FCM token, staying on the live stream: ${it.message}") }
            .getOrNull() ?: return
        sendPushToken(token)
    }

    /** Firebase rotated the token (FcmService.onNewToken). */
    fun onNewPushToken(token: String) = scope.launch { sendPushToken(token) }

    private suspend fun sendPushToken(token: String) {
        var backoff = 5_000L
        while (scope.isActive) {
            val s = current()
            if (s.caregiverToken == null || s.relayUrl.isBlank() || s.mode != AppMode.PARENT) return
            val ok = runCatching { client().put("/v1/push-token", JSONObject().put("token", token)) }
                .onFailure { Log.w(TAG, "relay didn't take the FCM token: ${it.message}") }.isSuccess
            if (ok) { _pushReady.value = true; return }
            delay(backoff)
            backoff = (backoff * 2).coerceAtMost(5 * 60_000L)
        }
    }

    /** A data message from FcmService. Same handling as the stream, so nothing rings twice. */
    fun handlePush(data: Map<String, String>) {
        // The relay keeps the FCM token after a sign-out or a switch to mode Anak.
        if (current().caregiverToken == null || current().mode != AppMode.PARENT) return
        when (data["kind"]) {
            "event" -> {
                val e = RemoteEvent(
                    id = data["id"]?.toLongOrNull() ?: return,
                    childId = data["wearerId"]?.toLongOrNull() ?: 0,
                    type = CareEventType.from(data["type"].orEmpty()),
                    batteryPct = data["batteryPct"]?.toIntOrNull(),
                    occurredAt = data["occurredAt"]?.toLongOrNull() ?: System.currentTimeMillis(),
                    lat = data["lat"]?.toDoubleOrNull(),
                    lon = data["lon"]?.toDoubleOrNull(),
                    message = data["message"].orEmpty(),
                    ackedBy = null,
                    ackedAt = null,
                )
                onEvent(e, data["wearerName"].orEmpty(), data["escalated"] == "1", data["escalations"]?.toIntOrNull() ?: 0)
            }
            "ack" -> data["eventId"]?.toLongOrNull()?.let { alerts.cancelCareAlarm(it) }
            // Silence during a trip also arrives as an OFFLINE alarm event, which is what rings.
            "offline" -> if (data["navigating"] != "1") data["wearerId"]?.toLongOrNull()?.let { alerts.notifyChildOffline(it, data["name"].orEmpty()) }
            "online" -> data["wearerId"]?.toLongOrNull()?.let { alerts.clearChildOffline(it) }
        }
        if (visible.value) scope.launch { runCatching { refresh() } }
    }

    private fun client() = current().let { RelayClient(it.relayUrl, it.caregiverToken) }

    /** Links to a child's phone with its invite code. A phone already linked keeps its key and follows one more. */
    suspend fun link(relayUrl: String, code: String, name: String): Result<String> = runCatching {
        if (relayUrl.trim() != current().relayUrl) settings.setRelay(relayUrl, RelayDiscovery.check(relayUrl.trim())?.relayId)
        val token = current().caregiverToken
        val r = RelayClient(relayUrl.trim(), token).post("/v1/link", JSONObject().put("code", code.trim()).put("name", name.trim()))
        settings.setCaregiver(r.optString("token").takeIf { it.isNotEmpty() && it != "null" }, name.trim())
        runCatching { refresh() }
        r.getJSONObject("wearer").getString("name")
    }.onFailure { Log.w(TAG, "link failed", it) }

    /** "Cari relay" on the link screen: scans the WiFi and returns the address it found. */
    suspend fun findRelay(): String? = finder.findNow()?.url

    suspend fun refresh() {
        val c = client()
        val a = c.get("/v1/dashboard?limit=1000").getJSONArray("wearers")
        val since = System.currentTimeMillis() - TRAIL_MS
        _children.value = List(a.length()) { i ->
            val o = a.getJSONObject(i)
            val track = runCatching {
                val p = c.get("/v1/wearers/${o.getLong("id")}/track?since=$since").getJSONArray("points")
                List(p.length()) { TrackPoint.from(p.getJSONObject(it)) }
            }.getOrElse { _children.value.firstOrNull { it.id == o.getLong("id") }?.track.orEmpty() }
            WatchedChild.from(o, track)
        }
    }

    /** Stops alarms on this phone. The name is kept for the next link. */
    suspend fun signOut() {
        _children.value.flatMap { it.events }.filter { it.type.isAlarm && it.ackedAt == null }
            .forEach { alerts.cancelCareAlarm(it.id) }
        settings.clearCaregiverLink()
        _children.value = emptyList()
        _error.value = null
    }

    fun acknowledge(eventId: Long) = scope.launch {
        alerts.cancelCareAlarm(eventId)
        runCatching { client().post("/v1/events/$eventId/ack") }
            .onFailure { _error.value = "Tanda \"sudah dilihat\" tidak terkirim: ${it.message}" }
        runCatching { refresh() }
    }

    // --- Live stream ---------------------------------------------------------------------

    private suspend fun streamLoop(url: String, token: String) {
        var backoff = 2_000L
        while (scope.isActive) {
            _stream.value = StreamState.CONNECTING
            try {
                refresh()
                readStream(url, token)
                backoff = 2_000L
            } catch (e: RelayException) {
                _error.value = if (e.status == 401) "Sambungan HP ini sudah dihapus. Hubungkan lagi." else "Relay tidak terjangkau: ${e.message}"
                // Found at a new address: the settings change restarts the stream from start().
                if (e.status == 0 && finder.afterFailure()?.url.let { it != null && it != url }) return
            } catch (e: Exception) {
                _error.value = "Relay tidak terjangkau: ${e.message}"
                if (finder.afterFailure()?.url.let { it != null && it != url }) return
            }
            _stream.value = StreamState.CONNECTING
            delay(backoff)
            backoff = (backoff * 2).coerceAtMost(60_000L)
        }
    }

    /** Blocks until the stream drops. */
    private suspend fun readStream(url: String, token: String) = withContext(Dispatchers.IO) {
        val c = RelayClient(url, token).open("/v1/stream")
        c.connectTimeout = 10_000
        c.readTimeout = 60_000 // the relay sends a keepalive every 25 s
        c.setRequestProperty("Accept", "text/event-stream")
        current().lastStreamEventId.takeIf { it > 0 }?.let { c.setRequestProperty("Last-Event-ID", it.toString()) }
        try {
            if (c.responseCode != 200) throw RelayException(c.responseCode, "stream ditolak (HTTP ${c.responseCode})")
            _stream.value = StreamState.LIVE
            _error.value = null
            val reader = c.inputStream.bufferedReader()
            var type = "message"
            var id: Long? = null
            val data = StringBuilder()
            while (true) {
                val line = reader.readLine() ?: break
                when {
                    line.isEmpty() -> {
                        if (data.isNotEmpty()) dispatch(type, JSONObject(data.toString()), id)
                        type = "message"; id = null; data.clear()
                    }
                    line.startsWith("event:") -> type = line.substringAfter(':').trim()
                    line.startsWith("data:") -> data.append(line.substringAfter(':').trim())
                    line.startsWith("id:") -> id = line.substringAfter(':').trim().toLongOrNull()
                }
            }
        } finally {
            c.disconnect()
        }
    }

    private fun updateChild(id: Long, change: (WatchedChild) -> WatchedChild) =
        _children.update { list -> list.map { if (it.id == id) change(it) else it } }

    private suspend fun dispatch(type: String, d: JSONObject, id: Long?) {
        val now = System.currentTimeMillis()
        when (type) {
            "event" -> onEvent(RemoteEvent.from(d), d.optString("wearerName"), d.optBoolean("escalated"), d.optInt("escalations"))
            "status" -> updateChild(d.getLong("wearerId")) {
                it.copy(
                    offline = false, lastHeartbeat = d.getLong("at"), navigating = d.optBoolean("navigating"),
                    status = ChildStatus.from(d.getJSONObject("status")),
                )
            }
            "location" -> {
                val p = TrackPoint.from(d)
                updateChild(d.getLong("wearerId")) {
                    it.copy(
                        offline = false, lastHeartbeat = now, location = p,
                        track = (it.track + p).dropWhile { old -> old.at < now - TRAIL_MS },
                    )
                }
            }
            "offline" -> {
                updateChild(d.getLong("wearerId")) { it.copy(offline = true) }
                if (!d.optBoolean("navigating")) alerts.notifyChildOffline(d.getLong("wearerId"), d.optString("name"))
            }
            "online" -> {
                updateChild(d.getLong("wearerId")) { it.copy(offline = false) }
                alerts.clearChildOffline(d.getLong("wearerId"))
            }
            "ack" -> {
                alerts.cancelCareAlarm(d.getLong("eventId"))
                _children.update { l ->
                    l.map { w ->
                        w.copy(events = w.events.map { e ->
                            if (e.id == d.getLong("eventId")) e.copy(ackedBy = d.getString("by"), ackedAt = d.getLong("at")) else e
                        })
                    }
                }
            }
            "unlinked", "hello" -> runCatching { refresh() }
        }
        if (id != null && id > current().lastStreamEventId) settings.setLastStreamEventId(id)
    }

    /** Alerts already raised, as "eventId:escalations": push and stream can both deliver one. */
    private val alerted = object : LinkedHashMap<String, Unit>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Unit>) = size > 200
    }

    private fun onEvent(e: RemoteEvent, childName: String, escalated: Boolean, escalations: Int) {
        updateChild(e.childId) { w ->
            w.copy(
                events = (listOf(e) + w.events.filter { it.id != e.id })
                    .sortedWith(compareByDescending<RemoteEvent> { it.occurredAt }.thenByDescending { it.id }),
            )
        }
        if (e.ackedAt != null) return
        // Something old replayed after a reconnect is history, not news.
        if (System.currentTimeMillis() - e.occurredAt > STALE_ALERT_MS) return
        synchronized(alerted) { if (alerted.put("${e.id}:$escalations", Unit) != null) return }
        when (AlertRules.careAlert(e.type)) {
            CareAlert.ALARM -> alerts.careAlarm(e, childName, escalated)
            CareAlert.RING_ONCE -> alerts.careRingOnce(e, childName)
            CareAlert.NOTIFY -> alerts.careInfo(e, childName)
            CareAlert.HISTORY -> Unit
        }
    }

    private companion object {
        const val TAG = "CareManager"
        /** How much of the child's track the map draws. */
        const val TRAIL_MS = 2 * 3600_000L
        const val STALE_ALERT_MS = 30 * 60_000L
    }
}
