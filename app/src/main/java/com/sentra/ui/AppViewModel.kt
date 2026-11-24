package com.sentra.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.sentra.ble.SleeveCommand
import com.sentra.care
import com.sentra.child
import com.sentra.data.AppMode
import com.sentra.nav.Geo
import com.sentra.nav.GeoPoint
import com.sentra.nav.NavEngine
import com.sentra.relay.Invite
import com.sentra.relay.LinkedParent
import com.sentra.relay.RelayDiscovery
import com.sentra.relay.RelayException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File
import kotlin.math.roundToInt

class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val m = app.child
    private val cg = app.care

    val settings = m.appSettings

    // --- Sleeve --------------------------------------------------------------------------

    val link = m.sleeve.link
    val scan = m.scan
    val obstacleCm = m.obstacleCm
    val intensity = m.intensity
    val lastAck = m.lastAck
    val sleeveError = m.sleeveError
    /** What the simulated sleeve's motors would be doing. */
    val fakePattern = m.fake.pattern

    fun connectSleeve() { m.connectSleeve() }
    fun forgetSleeve() { m.forgetSleeve() }
    fun pressFakeButton(long: Boolean) = m.fake.pressButton(long)

    /** After a permission or Bluetooth prompt: try again with whatever was granted. */
    fun retryConnect() = m.retryConnect()

    // --- Navigation ----------------------------------------------------------------------

    val nav = m.nav
    val navRoute = m.navRoute
    val navMessage = m.navMessage
    val fix = m.fix
    val routes = m.routes.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val events = m.events.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val trips = m.trips.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun selectRoute(id: Long?) = viewModelScope.launch { m.settings.setSelectedRoute(id) }
    fun startNavigation(routeId: Long) {
        viewModelScope.launch { m.settings.setSelectedRoute(routeId) }
        m.startNavigation(routeId)
    }
    fun clearTrips() = viewModelScope.launch { m.clearTrips() }
    /** The speaker button: reads out where the child is, like a short press on the sleeve. */
    fun speakInfo() = m.speakInfo()
    fun stopNavigation() { m.stopNavigation() }
    fun deleteRoute(id: Long) = viewModelScope.launch { m.deleteRoute(id) }

    private val _detour = MutableStateFlow(false)
    val detour: StateFlow<Boolean> = _detour.asStateFlow()
    fun setDetour(on: Boolean) {
        _detour.value = on
        m.setSimulatedDetour(on)
    }

    // --- Record Route (§9.4) -------------------------------------------------------------

    private val _draft = MutableStateFlow<List<GeoPoint>>(emptyList())
    /** The points marked so far: start, turns, and the destination once [draftDone]. */
    val draft: StateFlow<List<GeoPoint>> = _draft.asStateFlow()
    private val _draftDone = MutableStateFlow(false)
    val draftDone: StateFlow<Boolean> = _draftDone.asStateFlow()
    private val _recordMessage = MutableStateFlow<String?>(null)
    val recordMessage: StateFlow<String?> = _recordMessage.asStateFlow()

    /** GPS on while the recording screen is open. */
    fun setFixWanted(on: Boolean) = m.setFixWanted(on)

    /** Start point: starts a new list. */
    fun recordStart() {
        _draft.value = emptyList()
        _draftDone.value = false
        addPoint()
    }

    /** Mark turn. */
    fun recordTurn() {
        if (_draft.value.isEmpty() || _draftDone.value) return
        addPoint()
    }

    /** Destination: the last point. */
    fun recordEnd() {
        if (_draft.value.isEmpty() || _draftDone.value) return
        if (addPoint()) _draftDone.value = true
    }

    /** tambahTitik: refuses a weak GPS fix, as the blocks do. */
    private fun addPoint(): Boolean {
        val f = fix.value
        if (f == null || f.accuracyM > NavEngine.MAX_RECORD_ACCURACY_M) {
            m.speak("GPS signal is weak, please wait")
            _recordMessage.value = if (f == null) "No GPS signal yet."
            else "Weak GPS (±${f.accuracyM.roundToInt()} m). Wait until ±15 m."
            return false
        }
        val previous = _draft.value.lastOrNull()
        _draft.value = _draft.value + f.point
        m.speak("Point ${_draft.value.size} saved")
        val gap = previous?.let { Geo.distanceM(it, f.point) }
        // "When two turns are very close (< 15 m), GPS can hardly tell them apart." (§9.4)
        _recordMessage.value = if (gap != null && gap < 15) "Only ${gap.roundToInt()} m from the previous point; 20 m or more is better."
        else null
        return true
    }

    fun removeLastPoint() {
        _draft.value = _draft.value.dropLast(1)
        _draftDone.value = false
        _recordMessage.value = null
    }

    fun discardDraft() {
        _draft.value = emptyList()
        _draftDone.value = false
        _recordMessage.value = null
    }

    /** Save route. */
    fun saveRoute(name: String) = viewModelScope.launch {
        if (_draft.value.size < 2 || name.isBlank()) return@launch
        m.saveRoute(name, _draft.value)
        m.speak("Route saved")
        discardDraft()
    }

    /** A ready-made route for the simulated GPS, so navigation can be tried without recording one. */
    fun addSampleRoute() = viewModelScope.launch { m.saveRoute("Sample: around Gedung Sate", SAMPLE_ROUTE) }

    // --- Demo Mode (§9.6) ----------------------------------------------------------------

    val demoStep = m.demoStep
    fun demo(command: SleeveCommand) { m.demoCommand(command) }
    fun startDemo() = m.startDemo()
    fun stopDemo() = m.stopDemo()

    // --- Phone settings ------------------------------------------------------------------

    fun setMode(mode: AppMode) = viewModelScope.launch { m.setMode(mode) }
    fun setChildName(name: String) = viewModelScope.launch { m.settings.setChildName(name) }
    fun setSleeveName(name: String) = viewModelScope.launch { m.settings.setSleeveName(name) }
    fun setSimulatedSleeve(on: Boolean) = viewModelScope.launch { m.settings.setSimulatedSleeve(on) }
    fun setSimulatedGps(on: Boolean) = viewModelScope.launch { m.settings.setSimulatedGps(on) }

    // --- Relay, child side ---------------------------------------------------------------

    val relayHealth = combine(m.appSettings, m.relayError, m.parentCount, m.pending) { s, err, n, pending ->
        RelayHealth(s.relayUrl, s.childToken != null, err, n, pending)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    fun setRelayUrl(url: String) = viewModelScope.launch {
        m.settings.setRelay(url, RelayDiscovery.check(url.trim())?.relayId)
        m.syncNow()
    }

    private val _finding = MutableStateFlow(false)
    val finding: StateFlow<Boolean> = _finding.asStateFlow()

    /** Scans the WiFi for a relay and switches to it. */
    fun findRelay() = viewModelScope.launch {
        _finding.value = true
        val found = m.finder.findNow()
        _finding.value = false
        _relayMessage.value = if (found == null) "No relay found on this WiFi." else "Relay found at ${found.url}"
        if (found != null) m.syncNow()
    }

    private val _invite = MutableStateFlow<Invite?>(null)
    val invite: StateFlow<Invite?> = _invite.asStateFlow()
    private val _parents = MutableStateFlow<List<LinkedParent>>(emptyList())
    val parents: StateFlow<List<LinkedParent>> = _parents.asStateFlow()
    private val _relayMessage = MutableStateFlow<String?>(null)
    val relayMessage: StateFlow<String?> = _relayMessage.asStateFlow()

    fun newInvite() = viewModelScope.launch {
        runCatching { m.relay.ensureRegistered(); m.relay.invite() }
            .onSuccess { _invite.value = it; _relayMessage.value = null }
            .onFailure { _relayMessage.value = "Could not create invite code: ${it.message}" }
    }

    fun refreshParents() = viewModelScope.launch {
        if (!m.relay.configured) return@launch
        runCatching { m.relay.parents() }.onSuccess { _parents.value = it }
    }

    fun removeParent(id: Long) = viewModelScope.launch {
        runCatching { m.relay.removeParent(id) }.onFailure { _relayMessage.value = "Could not remove: ${it.message}" }
        refreshParents()
        m.syncNow()
    }

    fun sendTestAlert() {
        m.sendTestAlert()
        _relayMessage.value = "Test alarm sent."
    }

    fun clearHistory() = viewModelScope.launch { m.clearHistory() }

    // --- Relay, parent side (SENTRA Care) ------------------------------------------------

    val children = cg.children
    val streamState = cg.stream
    val careError = cg.error
    private val _linkResult = MutableStateFlow<String?>(null)
    val linkResult: StateFlow<String?> = _linkResult.asStateFlow()

    fun link(url: String, code: String, name: String) = viewModelScope.launch {
        _linkResult.value = cg.link(url, code, name).fold({ "$LINKED $it" }, {
            if ((it as? RelayException)?.status == 404) "Wrong or expired code. Ask for a new one."
            else "Could not link: ${it.message}"
        })
    }

    fun clearLinkResult() { _linkResult.value = null }

    private val _findingForCare = MutableStateFlow(false)
    val findingForCare: StateFlow<Boolean> = _findingForCare.asStateFlow()
    fun findRelayForCare() = viewModelScope.launch {
        _findingForCare.value = true
        val url = cg.findRelay()
        _findingForCare.value = false
        if (url == null) _linkResult.value = "No relay found on this WiFi."
    }

    fun signOutCare() = viewModelScope.launch {
        cg.signOut()
        clearLinkResult()
    }

    fun acknowledge(eventId: Long) { cg.acknowledge(eventId) }

    // --- Test log (Bab 10) ---------------------------------------------------------------

    val recording = m.recording
    private val _recordings = MutableStateFlow<List<File>>(emptyList())
    val recordings: StateFlow<List<File>> = _recordings.asStateFlow()

    init {
        refreshRecordings()
    }

    fun startRecording(label: String) {
        m.startRecording(label)
        refreshRecordings()
    }

    fun stopRecording() {
        m.stopRecording()
        refreshRecordings()
    }

    fun deleteRecording(f: File) {
        f.delete()
        refreshRecordings()
    }

    fun refreshRecordings() {
        _recordings.value = m.logger.list()
    }

    companion object {
        /** Prefix of a successful link result. */
        const val LINKED = "Linked to"

        /** The same loop server_relay/fake-child.mjs walks, around Gedung Sate in Bandung. */
        val SAMPLE_ROUTE = listOf(
            GeoPoint(-6.90250, 107.61880), GeoPoint(-6.90250, 107.61960), GeoPoint(-6.90320, 107.61960),
            GeoPoint(-6.90320, 107.62040), GeoPoint(-6.90390, 107.62040),
        )
    }
}
