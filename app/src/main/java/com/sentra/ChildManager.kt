package com.sentra

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.BatteryManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.sentra.ble.BleScanner
import com.sentra.ble.FakeSleeve
import com.sentra.ble.Intensity
import com.sentra.ble.LinkState
import com.sentra.ble.SentraConnection
import com.sentra.ble.SentraProtocol
import com.sentra.ble.SleeveCommand
import com.sentra.ble.SleeveHub
import com.sentra.ble.SleeveMessage
import com.sentra.data.AppDatabase
import com.sentra.data.AppMode
import com.sentra.data.AppSettings
import com.sentra.data.EventEntity
import com.sentra.data.LocationEntity
import com.sentra.data.RouteEntity
import com.sentra.data.SettingsRepository
import com.sentra.data.TestLogger
import com.sentra.data.Uploader
import com.sentra.loc.Fix
import com.sentra.loc.gpsFixes
import com.sentra.loc.simulatedWalk
import com.sentra.nav.GeoPoint
import com.sentra.nav.NavAction
import com.sentra.nav.NavEngine
import com.sentra.nav.NavSnapshot
import com.sentra.nav.NavState
import com.sentra.relay.ChildRelay
import com.sentra.relay.RelayException
import com.sentra.relay.RelayFinder
import com.sentra.service.SentraService
import com.sentra.voice.Speaker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** SAMBUNGKAN, from the press to a sleeve being chosen. */
sealed interface ScanState {
    data object Idle : ScanState
    data object Searching : ScanState
    data object NotFound : ScanState
    data class Failed(val reason: String) : ScanState
}

/** The last command the sleeve confirmed and how long the round trip took (§10.3). */
data class AckInfo(val command: String, val roundTripMs: Long?)

/**
 * Mode Anak: owns the sleeve link, GPS, the navigation engine, voice, and the upload to the relay.
 * Lives as long as the process; the foreground [SentraService] keeps the process alive while a
 * sleeve is connected or a trip is running, so navigation survives a dark screen (§9.8).
 *
 * The relay is an add-on: nothing here waits for the network, so navigation and the sleeve keep
 * working with no internet. Events and fixes go to the local outbox first and are sent when the
 * relay can be reached.
 */
class ChildManager(
    private val context: Context,
    val settings: SettingsRepository,
    private val db: AppDatabase,
    private val scope: CoroutineScope,
) {
    val appSettings: StateFlow<AppSettings> = settings.settings.stateIn(scope, SharingStarted.Eagerly, AppSettings())

    private val connection = SentraConnection(context, scope)
    val fake = FakeSleeve(scope)
    val sleeve = SleeveHub(connection, fake, scope)
    private val scanner = BleScanner(context)
    private val speaker = Speaker(context)
    val logger = TestLogger(context)

    val relay = ChildRelay(settings) { appSettings.value }
    /** Re-finds a relay on the local network when its address changes (e.g. a phone hotspot at the booth). */
    val finder = RelayFinder(settings) { appSettings.value }
    private val uploader = Uploader(db.outbox(), relay)

    val routes = db.routes().all()
    val events = db.outbox().recentEvents()

    // --- Sleeve state ------------------------------------------------------------------------

    private val _scan = MutableStateFlow<ScanState>(ScanState.Idle)
    val scan: StateFlow<ScanState> = _scan.asStateFlow()
    private val _obstacleCm = MutableStateFlow<Int?>(null)
    /** The last D: reading; null while no sleeve is connected, 999 when nothing is in range. */
    val obstacleCm: StateFlow<Int?> = _obstacleCm.asStateFlow()
    private val _intensity = MutableStateFlow(Intensity.HIGH)
    val intensity: StateFlow<Intensity> = _intensity.asStateFlow()
    private val _lastAck = MutableStateFlow<AckInfo?>(null)
    val lastAck: StateFlow<AckInfo?> = _lastAck.asStateFlow()
    private val _sleeveError = MutableStateFlow<String?>(null)
    /** The last ERR: from the sleeve. */
    val sleeveError: StateFlow<String?> = _sleeveError.asStateFlow()

    /** When each command still waiting for its ACK was sent. */
    private val sentAt = HashMap<String, Long>()

    // --- Navigation state --------------------------------------------------------------------

    private var engine: NavEngine? = null
    private val navLock = Mutex()
    private val _nav = MutableStateFlow(NavSnapshot())
    val nav: StateFlow<NavSnapshot> = _nav.asStateFlow()
    private val _navRoute = MutableStateFlow<RouteEntity?>(null)
    /** The route being walked, kept after arriving until the next start. */
    val navRoute: StateFlow<RouteEntity?> = _navRoute.asStateFlow()
    private val _navMessage = MutableStateFlow<String?>(null)
    /** Why navigation can't run, e.g. no location permission. */
    val navMessage: StateFlow<String?> = _navMessage.asStateFlow()
    private val _fix = MutableStateFlow<Fix?>(null)
    val fix: StateFlow<Fix?> = _fix.asStateFlow()
    private val _demoStep = MutableStateFlow<Int?>(null)
    /** Position in the automatic demo sequence; null when it isn't running. */
    val demoStep: StateFlow<Int?> = _demoStep.asStateFlow()

    private var locationJob: Job? = null
    private var locationKind: String? = null
    private var tickJob: Job? = null
    private var demoJob: Job? = null
    /** A screen (Rekam Rute) wants live GPS even without a trip. */
    private val fixWanted = MutableStateFlow(false)
    @Volatile private var simDetour = false
    @Volatile private var lostReported = false

    // --- Relay state -------------------------------------------------------------------------

    private val _relayError = MutableStateFlow<String?>(null)
    /** Null when the last relay call worked. */
    val relayError: StateFlow<String?> = _relayError.asStateFlow()
    private val _parentCount = MutableStateFlow<Int?>(null)
    /** Linked parents, as of the last heartbeat; null before the first one. */
    val parentCount: StateFlow<Int?> = _parentCount.asStateFlow()
    private val _pending = MutableStateFlow(0)
    /** Events and fixes still waiting for the relay. */
    val pending: StateFlow<Int> = _pending.asStateFlow()
    /** Wakes the relay loop for an upload right now. */
    private val kick = Channel<Unit>(Channel.CONFLATED)
    @Volatile private var heartbeatDue = true

    private val _recording = MutableStateFlow<File?>(null)
    val recording: StateFlow<File?> = _recording.asStateFlow()

    fun start() {
        scope.launch {
            // Only the child's phone talks to the sleeve; a parent's phone never connects.
            settings.settings
                .map { s -> Triple(s.mode == AppMode.CHILD, s.simulatedSleeve, s.deviceAddress) }
                .distinctUntilChanged()
                .collect { (child, simulated, address) ->
                    sleeve.select(simulated)
                    if (!child) {
                        connection.disconnect(); fake.disconnect()
                    } else if (simulated) {
                        connection.disconnect(); fake.connect()
                    } else {
                        fake.disconnect()
                        if (address == null) connection.disconnect() else if (canConnect()) connection.connect(address)
                    }
                }
        }
        scope.launch { sleeve.messages.collect(::onSleeveMessage) }
        scope.launch { pingLoop() }
        scope.launch { watchLink() }
        scope.launch { watchService() }
        scope.launch { relayLoop() }
        scope.launch { fixWanted.collect { updateLocation() } }
        scope.launch {
            // A new alias on this phone is the name the parents see.
            appSettings.map { it.childName }.distinctUntilChanged().drop(1).collect { runCatching { relay.rename(it) } }
        }
    }

    // --- Sleeve ------------------------------------------------------------------------------

    fun canConnect(): Boolean = Build.VERSION.SDK_INT < 31 ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    /**
     * SAMBUNGKAN: one button, as §9.3 asks. Looks for a SENTRA sleeve for up to [SCAN_TIMEOUT_MS],
     * connects to the first one found and remembers it, so later reconnects need no button.
     */
    fun connectSleeve() = scope.launch {
        if (appSettings.value.simulatedSleeve) return@launch
        if (_scan.value == ScanState.Searching) return@launch
        _scan.value = ScanState.Searching
        val wanted = appSettings.value.sleeveName
        val found = runCatching {
            withTimeoutOrNull(SCAN_TIMEOUT_MS) { scanner.scan(wanted).first() }
        }
        val device = found.getOrNull()
        _scan.value = when {
            found.isFailure -> ScanState.Failed(found.exceptionOrNull()?.message ?: "Pencarian gagal")
            device == null -> ScanState.NotFound
            else -> ScanState.Idle
        }
        if (device != null) {
            settings.setDevice(device.address, device.name)
            // The settings collector connects; a second press on the same sleeve reconnects at once.
            if (canConnect()) connection.connect(device.address)
        }
    }

    /** Called after the user grants a permission or switches Bluetooth on, and on boot. */
    fun retryConnect() {
        val s = appSettings.value
        if (s.mode != AppMode.CHILD || s.simulatedSleeve) return
        val address = s.deviceAddress ?: return
        if (canConnect()) connection.connect(address)
    }

    fun forgetSleeve() = scope.launch {
        settings.setDevice(null, null)
        _scan.value = ScanState.Idle
    }

    /** Sends one command and notes when, so the ACK's round trip can be measured. */
    suspend fun send(command: SleeveCommand): Boolean {
        if (command != SleeveCommand.PING) {
            synchronized(sentAt) { sentAt[command.name] = System.nanoTime() }
            logger.log("CMD", command.name)
        }
        return sleeve.send(command)
    }

    private fun onSleeveMessage(m: SleeveMessage) {
        when (m) {
            is SleeveMessage.Distance -> {
                _obstacleCm.value = m.cm
                logger.log("D", m.cm)
            }
            is SleeveMessage.Ack -> {
                val sent = synchronized(sentAt) { sentAt.remove(m.command) }
                val ms = sent?.let { (System.nanoTime() - it) / 1_000_000 }
                _lastAck.value = AckInfo(m.command, ms)
                _sleeveError.value = null
                logger.log("ACK", m.command, ms)
                when (m.command) {
                    "LOW" -> _intensity.value = Intensity.LOW
                    "HIGH" -> _intensity.value = Intensity.HIGH
                }
            }
            is SleeveMessage.Error -> _sleeveError.value = m.text
            is SleeveMessage.Button -> if (m.long) {
                m.intensity?.let { _intensity.value = it }
                speaker.speak(if (m.intensity == Intensity.LOW) "Getar lemah" else "Getar kuat")
            } else {
                // BTN:SHORT: read out where we are (bacakanInfo).
                speaker.speak(engine?.info() ?: "Navigasi belum dimulai")
            }
            SleeveMessage.Ready, is SleeveMessage.Unknown -> Unit
        }
    }

    /** PING every 2 s while connected; without it the sleeve ends navigation after 6 s. */
    private suspend fun pingLoop() {
        sleeve.link.collectLatest { link ->
            if (link != LinkState.CONNECTED) return@collectLatest
            while (true) {
                delay(SentraProtocol.PING_INTERVAL_MS)
                sleeve.send(SleeveCommand.PING)
            }
        }
    }

    /** Says when the sleeve connects or drops, and tells the parents if it drops during a trip. */
    private suspend fun watchLink() {
        sleeve.link.map { it == LinkState.CONNECTED }.distinctUntilChanged().drop(1).collectLatest { connected ->
            logger.log("LINK", sleeve.link.value.name)
            if (connected) {
                speaker.speak("Sleeve tersambung")
                // The firmware drops its navigation pattern on disconnect; put it back.
                engine?.currentCommand?.let { send(it) }
                if (lostReported) {
                    lostReported = false
                    record("TERSAMBUNG", "Sleeve tersambung lagi.")
                }
                heartbeatNow()
            } else {
                _obstacleCm.value = null
                speaker.speak("Sleeve terputus")
                heartbeatNow()
                // A brief Bluetooth dropout isn't worth an alarm; a reconnect cancels this wait.
                delay(LINK_SETTLE_MS)
                if (_nav.value.active) {
                    lostReported = true
                    record("TERPUTUS", "Sleeve terputus dari HP saat navigasi berjalan. Anak tidak mendapat getaran arah.")
                }
            }
        }
    }

    /** The foreground service runs while there is a sleeve to hold or a trip to finish. */
    private suspend fun watchService() {
        combine(settings.settings.map { it.mode }, sleeve.link, nav) { mode, link, n ->
            mode == AppMode.CHILD && (link != LinkState.DISCONNECTED || n.active)
        }.distinctUntilChanged().collect { wanted ->
            if (wanted) SentraService.start(context) else SentraService.stop(context)
        }
    }

    // --- Navigation --------------------------------------------------------------------------

    /** MULAI NAVIGASI on the chosen route. */
    fun startNavigation(routeId: Long) = scope.launch {
        val route = db.routes().byId(routeId) ?: return@launch
        val points = route.geoPoints()
        if (points.size < 2) {
            _navMessage.value = "Rute ini belum punya titik mulai dan tujuan."
            return@launch
        }
        if (!appSettings.value.simulatedGps && !hasLocationPermission()) {
            _navMessage.value = "Izin lokasi belum diberikan, jadi navigasi tidak bisa membaca GPS."
            return@launch
        }
        stopDemo()
        _navMessage.value = null
        simDetour = false
        lostReported = false
        navLock.withLock {
            val e = NavEngine(points, route.name)
            engine = e
            _navRoute.value = route
            perform(e.start())
            _nav.value = e.snapshot
        }
        tickJob?.cancel()
        tickJob = scope.launch {
            while (true) {
                delay(1_000)
                navLock.withLock { engine?.let { perform(it.onTick(System.currentTimeMillis())) } }
            }
        }
        updateLocation()
        SentraService.start(context) // re-declares the foreground type now that location is in use
        heartbeatNow()
    }

    /** SELESAI: the companion ends the trip early. */
    fun stopNavigation() = scope.launch {
        navLock.withLock {
            val e = engine ?: return@withLock
            perform(e.stop())
            _nav.value = e.snapshot
        }
        endTrip()
    }

    private fun endTrip() {
        tickJob?.cancel()
        tickJob = null
        simDetour = false
        updateLocation()
        heartbeatNow()
    }

    /** With simulated GPS: makes the pretend walker leave the route, to try the "keluar rute" path. */
    fun setSimulatedDetour(on: Boolean) {
        simDetour = on
    }

    val simulatedDetour: Boolean get() = simDetour

    /** Live GPS for a screen that needs it without a trip (Rekam Rute). */
    fun setFixWanted(on: Boolean) {
        fixWanted.value = on
    }

    /** Starts, switches or stops the location source to match what is going on. */
    @Synchronized
    private fun updateLocation() {
        val navigating = _nav.value.active
        val route = _navRoute.value
        val kind = when {
            navigating && appSettings.value.simulatedGps && route != null -> "sim:${route.id}"
            (navigating || fixWanted.value) && hasLocationPermission() -> "gps"
            else -> null
        }
        if (kind == locationKind) return
        locationKind = kind
        locationJob?.cancel()
        locationJob = null
        if (kind == null) {
            _fix.value = null
            return
        }
        val source = if (kind == "gps") gpsFixes(context) else simulatedWalk(route!!.geoPoints(), detour = { simDetour })
        locationJob = scope.launch {
            source.catch { e ->
                Log.w(TAG, "location stopped", e)
                _navMessage.value = "GPS tidak bisa dibaca: ${e.message}"
            }.collect(::onFix)
        }
    }

    private suspend fun onFix(fix: Fix) {
        _fix.value = fix
        logger.log("GPS", fix.lat, fix.lon, fix.accuracyM, fix.speed)
        var finished = false
        navLock.withLock {
            val e = engine ?: return@withLock
            if (!e.snapshot.active) return@withLock
            perform(e.onFix(fix.point, fix.accuracyM, System.currentTimeMillis()))
            _nav.value = e.snapshot
            finished = !e.snapshot.active
        }
        // Parents follow the child only during a trip; nothing is uploaded while the phone idles.
        if ((_nav.value.active || finished) && appSettings.value.relayUrl.isNotBlank()) {
            db.outbox().insert(LocationEntity(at = fix.timeMs, lat = fix.lat, lon = fix.lon, acc = fix.accuracyM, speed = fix.speed, bearing = fix.bearing))
        }
        if (finished) endTrip()
    }

    /** Carries out what the engine decided. Called with [navLock] held, so order is kept. */
    private suspend fun perform(actions: List<NavAction>) {
        for (a in actions) {
            when (a) {
                is NavAction.Send -> {
                    logger.log("NAV", a.command.name)
                    scope.launch { send(a.command) } // never let a slow Bluetooth write hold up navigation
                }
                is NavAction.Speak -> speaker.speak(a.text)
                is NavAction.Report -> {
                    logger.log("NAV", a.type)
                    record(a.type, a.message)
                }
            }
        }
    }

    /** Stores an event for the parents and asks for an upload now. */
    private suspend fun record(type: String, message: String) {
        val s = appSettings.value
        val at = _fix.value
        db.outbox().insert(
            EventEntity(
                deviceAddress = if (s.simulatedSleeve) FakeSleeve.ADDRESS else s.deviceAddress ?: "HP",
                type = type, batteryPct = batteryPct(), occurredAt = System.currentTimeMillis(),
                lat = at?.lat, lon = at?.lon, message = message,
            )
        )
        kick.trySend(Unit)
    }

    /** "Tes alarm" in Setelan: lets the parents check that their phone rings. */
    fun sendTestAlert() = scope.launch { record("TEST", "Ini hanya tes dari HP ${appSettings.value.childName}.") }

    // --- Mode Demo (§9.6) --------------------------------------------------------------------

    /** A manual button: KIRI, KANAN, LURUS, STOP, TIBA, TES, LEMAH, KUAT. */
    fun demoCommand(command: SleeveCommand) = scope.launch {
        if (_nav.value.active) return@launch
        send(command)
        DEMO_VOICE[command]?.let(speaker::speak)
    }

    /** SIMULASI RUTE OTOMATIS: FORWARD, LEFT, FORWARD, RIGHT, FORWARD, ARRIVED, one every 4 s, with voice. */
    fun startDemo() {
        if (_nav.value.active || demoJob?.isActive == true) return
        demoJob = scope.launch {
            try {
                DEMO_SEQUENCE.forEachIndexed { i, command ->
                    _demoStep.value = i
                    send(command)
                    DEMO_VOICE[command]?.let(speaker::speak)
                    delay(DEMO_STEP_MS)
                }
            } finally {
                _demoStep.value = null
            }
        }
    }

    fun stopDemo() {
        if (demoJob?.isActive == true) scope.launch { send(SleeveCommand.IDLE) }
        demoJob?.cancel()
        demoJob = null
    }

    // --- Routes ------------------------------------------------------------------------------

    suspend fun saveRoute(name: String, points: List<GeoPoint>): Long {
        val id = db.routes().insert(RouteEntity(name = name.trim(), points = RouteEntity.encodePoints(points), createdAt = System.currentTimeMillis()))
        settings.setSelectedRoute(id)
        return id
    }

    suspend fun deleteRoute(id: Long) {
        db.routes().delete(id)
        if (appSettings.value.selectedRouteId == id) settings.setSelectedRoute(null)
    }

    fun speak(text: String) = speaker.speak(text)

    // --- Relay -------------------------------------------------------------------------------

    /**
     * Registers once, then keeps emptying the outbox: every few seconds during a trip, twice a
     * minute otherwise. Heartbeats go every 20 s during a trip and every 5 min when idle; the
     * relay raises "HP anak offline" after 60 s of silence during a trip.
     */
    private suspend fun relayLoop() {
        var lastHeartbeat = 0L
        while (true) {
            // Read the stored settings, not the StateFlow's initial placeholder.
            val s = settings.settings.first()
            val navigating = _nav.value.active
            if (s.mode == AppMode.CHILD && s.relayUrl.isNotBlank()) {
                val result = runCatching {
                    relay.ensureRegistered()
                    val sent = uploader.flush()
                    val every = if (navigating) HEARTBEAT_TRIP_MS else HEARTBEAT_IDLE_MS
                    if (heartbeatDue || System.currentTimeMillis() - lastHeartbeat >= every) {
                        heartbeatDue = false
                        lastHeartbeat = System.currentTimeMillis()
                        heartbeat()
                    }
                    if (!sent.complete && relay.configured) throw RelayException(0, "relay tidak terjangkau")
                }
                val error = result.exceptionOrNull()
                _relayError.value = error?.message
                if (error != null) {
                    heartbeatDue = true
                    // Unreachable: on a local network the relay may have moved to another address.
                    if ((error as? RelayException)?.status == 0) finder.afterFailure()
                }
                val out = db.outbox()
                if (out.locationCount() > MAX_QUEUED_FIXES) out.trimLocations(MAX_QUEUED_FIXES)
                _pending.value = out.pendingEvents().size + out.locationCount()
            }
            withTimeoutOrNull(if (navigating) UPLOAD_TRIP_MS else UPLOAD_IDLE_MS) { kick.receive() }
        }
    }

    private fun heartbeatNow() {
        heartbeatDue = true
        kick.trySend(Unit)
    }

    private suspend fun heartbeat() {
        val reply = relay.heartbeat(statusJson(), appSettings.value.acksSince)
        for (a in reply.acks) db.outbox().setAck(a.seq, a.by, a.at)
        reply.acks.maxOfOrNull { it.at }?.let { settings.setAcksSince(it) }
        _parentCount.value = reply.parents
    }

    /** What SENTRA Care shows on its status panel and map. */
    private fun statusJson(): JSONObject {
        val n = _nav.value
        val s = appSettings.value
        val route = _navRoute.value
        return JSONObject()
            .put("navigating", n.active)
            .put("navState", n.state.name)
            .put("sleeveConnected", sleeve.link.value == LinkState.CONNECTED)
            .put("batteryPct", batteryPct())
            .put("simulated", s.simulatedSleeve || s.simulatedGps)
            .apply {
                _obstacleCm.value?.let { put("obstacleCm", it) }
                if (route != null && (n.active || n.state == NavState.ARRIVED)) put("routeName", route.name)
                if (n.active && route != null) {
                    put("nextPoint", n.targetNumber)
                    put("totalPoints", n.totalPoints)
                    n.distanceM?.let { put("distanceM", Math.round(it)) }
                    // The recorded route, so the parents' map can draw it under the child's track.
                    put("route", JSONArray().apply { route.geoPoints().forEach { put(JSONArray().put(it.lat).put(it.lon)) } })
                }
            }
    }

    private fun batteryPct(): Int = runCatching {
        context.getSystemService(BatteryManager::class.java).getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
    }.getOrDefault(0)

    /** After the relay address changes, or when an upload is wanted right now (e.g. a parent just linked). */
    fun syncNow() = heartbeatNow()

    // --- Mode and test tools -----------------------------------------------------------------

    suspend fun setMode(mode: AppMode) {
        if (mode == AppMode.PARENT) {
            stopDemo()
            stopNavigation().join()
            stopRecording()
        }
        settings.setMode(mode)
    }

    fun startRecording(label: String) {
        _recording.value = logger.start(label)
    }

    fun stopRecording() {
        logger.stop()
        _recording.value = null
    }

    suspend fun clearHistory() {
        db.outbox().clearEvents()
        db.outbox().clearLocations()
    }

    companion object {
        private const val TAG = "ChildManager"
        const val SCAN_TIMEOUT_MS = 15_000L
        const val LINK_SETTLE_MS = 8_000L
        const val HEARTBEAT_TRIP_MS = 20_000L
        const val HEARTBEAT_IDLE_MS = 5 * 60_000L
        const val UPLOAD_TRIP_MS = 3_000L
        const val UPLOAD_IDLE_MS = 30_000L
        const val MAX_QUEUED_FIXES = 20_000
        const val DEMO_STEP_MS = 4_000L

        val DEMO_SEQUENCE = listOf(
            SleeveCommand.FORWARD, SleeveCommand.LEFT, SleeveCommand.FORWARD,
            SleeveCommand.RIGHT, SleeveCommand.FORWARD, SleeveCommand.ARRIVED,
        )
        val DEMO_VOICE = mapOf(
            SleeveCommand.FORWARD to "Jalan lurus",
            SleeveCommand.LEFT to "Belok kiri",
            SleeveCommand.RIGHT to "Belok kanan",
            SleeveCommand.STOP to "Berhenti",
            SleeveCommand.ARRIVED to "Kamu sudah sampai",
            SleeveCommand.TEST to "Tes getar",
            SleeveCommand.LOW to "Getar lemah",
            SleeveCommand.HIGH to "Getar kuat",
        )
    }
}
