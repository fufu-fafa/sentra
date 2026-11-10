package com.sentra.ble

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** A sleeve as the rest of the app sees it: the real one over BLE, or the simulated one. */
interface SleeveLink {
    val link: StateFlow<LinkState>
    val messages: SharedFlow<SleeveMessage>

    /** True once the sleeve has the command (for BLE: the write was confirmed). */
    suspend fun send(command: SleeveCommand): Boolean
}

/**
 * A pretend sleeve for testing without hardware. It behaves like the firmware of Desain Teknis
 * §7.5 as seen from the phone: ACK for every command except PING, ERR for unknown text, D: every
 * 250 ms, BTN: messages, and navigation that stops by itself after 6 s without a message.
 */
class FakeSleeve(
    private val scope: CoroutineScope,
    private val now: () -> Long = System::currentTimeMillis,
) : SleeveLink {
    private val _link = MutableStateFlow(LinkState.DISCONNECTED)
    override val link: StateFlow<LinkState> = _link.asStateFlow()

    private val _messages = MutableSharedFlow<SleeveMessage>(extraBufferCapacity = 64)
    override val messages: SharedFlow<SleeveMessage> = _messages.asSharedFlow()

    private val _pattern = MutableStateFlow<SleeveCommand?>(null)
    /** The navigation pattern the motors would be repeating: FORWARD, LEFT, RIGHT, STOP or none. */
    val pattern: StateFlow<SleeveCommand?> = _pattern.asStateFlow()

    private val _intensity = MutableStateFlow(Intensity.HIGH)
    val intensity: StateFlow<Intensity> = _intensity.asStateFlow()

    /** Set to place an obstacle at a fixed distance; null runs the built-in approach-and-leave loop. */
    @Volatile var obstacleCm: Int? = null

    private var job: Job? = null
    @Volatile private var lastRxMs = 0L

    fun connect() {
        if (job != null) return
        _link.value = LinkState.CONNECTING
        job = scope.launch {
            delay(CONNECT_DELAY_MS)
            lastRxMs = now()
            _link.value = LinkState.CONNECTED
            _messages.emit(SleeveMessage.Ready)
            var tick = 0L
            while (isActive) {
                delay(SentraProtocol.STATUS_INTERVAL_MS)
                tick++
                // "Aplikasi tidak merespons -> navigasi dihentikan"
                if (_pattern.value != null && now() - lastRxMs > SentraProtocol.APP_TIMEOUT_MS) _pattern.value = null
                _messages.emit(SleeveMessage.Distance(obstacleCm ?: loopDistance(tick)))
            }
        }
    }

    fun disconnect() {
        job?.cancel()
        job = null
        _pattern.value = null
        _link.value = LinkState.DISCONNECTED
    }

    /** As if the button on the sleeve was pressed. */
    fun pressButton(long: Boolean) {
        if (_link.value != LinkState.CONNECTED) return
        if (long) {
            _intensity.value = if (_intensity.value == Intensity.HIGH) Intensity.LOW else Intensity.HIGH
            _messages.tryEmit(SleeveMessage.Button(long = true, intensity = _intensity.value))
        } else {
            _messages.tryEmit(SleeveMessage.Button(long = false, intensity = null))
        }
    }

    override suspend fun send(command: SleeveCommand): Boolean = receive(command.name)

    /** What the firmware's onWrite + handleCommand do with any text. */
    suspend fun receive(text: String): Boolean {
        if (_link.value != LinkState.CONNECTED) return false
        val cmd = text.trim().uppercase()
        lastRxMs = now()
        when (cmd) {
            "PING" -> return true // only a sign of life: no ACK
            "FORWARD" -> _pattern.value = SleeveCommand.FORWARD
            "LEFT" -> _pattern.value = SleeveCommand.LEFT
            "RIGHT" -> _pattern.value = SleeveCommand.RIGHT
            "STOP" -> _pattern.value = SleeveCommand.STOP
            "ARRIVED", "IDLE" -> _pattern.value = null
            "TEST" -> Unit
            "LOW" -> _intensity.value = Intensity.LOW
            "HIGH" -> _intensity.value = Intensity.HIGH
            else -> {
                _messages.emit(SleeveMessage.Error(cmd))
                return true
            }
        }
        delay(ACK_DELAY_MS)
        _messages.emit(SleeveMessage.Ack(cmd))
        return true
    }

    /** Clear for 12 s, then something comes closer over 6 s (150 → 20 cm) and leaves again. */
    private fun loopDistance(tick: Long): Int {
        val t = tick % 80 // 20 s at 4 readings a second
        return when {
            t < 48 -> SentraProtocol.NO_OBSTACLE_CM
            t < 72 -> (150 - (t - 48) * 130 / 24).toInt()
            else -> 20
        }
    }

    companion object {
        const val ADDRESS = "SIMULASI"
        const val CONNECT_DELAY_MS = 600L
        const val ACK_DELAY_MS = 40L
    }
}

/** Switches between the real and the simulated sleeve without the rest of the app noticing. */
@OptIn(ExperimentalCoroutinesApi::class)
class SleeveHub(
    private val real: SleeveLink,
    private val fake: SleeveLink,
    scope: CoroutineScope,
) : SleeveLink {
    private val useFake = MutableStateFlow(false)

    fun select(fakeSleeve: Boolean) {
        useFake.value = fakeSleeve
    }

    val simulated: Boolean get() = useFake.value

    override val link: StateFlow<LinkState> =
        useFake.flatMapLatest { if (it) fake.link else real.link }.stateIn(scope, SharingStarted.Eagerly, LinkState.DISCONNECTED)

    /** Only the selected one is connected, so only it speaks. */
    override val messages: SharedFlow<SleeveMessage> =
        MutableSharedFlow<SleeveMessage>(extraBufferCapacity = 64).also { out ->
            scope.launch { merge(real.messages, fake.messages).collect { out.emit(it) } }
        }

    override suspend fun send(command: SleeveCommand): Boolean = (if (useFake.value) fake else real).send(command)
}
