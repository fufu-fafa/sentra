package com.sentra.nav

import com.sentra.ble.SleeveCommand
import kotlin.math.roundToInt

enum class NavState { IDLE, NAVIGATING, OFF_ROUTE, ARRIVED }

/** What the app has to do after a navigation step; the engine itself touches nothing. */
sealed interface NavAction {
    /** A command for the sleeve. */
    data class Send(val command: SleeveCommand) : NavAction
    /** A short voice prompt. */
    data class Speak(val text: String) : NavAction
    /** Something the parents should see: an event type of the relay and its text. */
    data class Report(val type: String, val message: String) : NavAction
}

data class NavSnapshot(
    val state: NavState = NavState.IDLE,
    /** The point being walked to, counted from 1 as in the voice prompts. */
    val targetNumber: Int = 0,
    val totalPoints: Int = 0,
    /** Metres to the target at the last usable fix. */
    val distanceM: Double? = null,
    /** What happens at the target; null at the destination. */
    val turnAtTarget: Turn? = null,
) {
    val active: Boolean get() = state == NavState.NAVIGATING || state == NavState.OFF_ROUTE
}

/**
 * The navigation state machine of Desain Teknis §9.5, free of Android types so it can be unit
 * tested. Feed it GPS fixes and a clock; it answers with commands, voice prompts and reports.
 *
 * Two things the pseudo-blocks leave open are settled here:
 *  - "keluar rute" is sent once, not on every fix, and when the child is back within the limit the
 *    engine says so and resumes (the blocks would repeat STOP forever).
 *  - the delayed FORWARD after a point (ClockLurus) is dropped if the next turn was announced in
 *    the meantime, so it can't cancel that turn's vibration.
 */
class NavEngine(private val route: List<GeoPoint>, private val routeName: String = "") {
    init {
        require(route.size >= 2) { "a route needs a start and a destination" }
    }

    /** The turn at each point; start and destination have none. */
    val turns: List<Turn?> = route.indices.map { i ->
        if (i == 0 || i == route.lastIndex) null else Geo.turnAt(route[i - 1], route[i], route[i + 1])
    }

    private var state = NavState.IDLE
    /** Index of the point being walked to. The blocks' idx = 2 is index 1 here. */
    private var idx = 1
    private var announced = false
    private var minDistance = Double.MAX_VALUE
    private var lastDistance: Double? = null
    private var forwardDueAt: Long? = null

    val snapshot: NavSnapshot
        get() = NavSnapshot(state, idx + 1, route.size, lastDistance, turns.getOrNull(idx))

    /** What the sleeve should be showing right now, e.g. to restore it after a reconnect. */
    val currentCommand: SleeveCommand?
        get() = when (state) {
            NavState.OFF_ROUTE -> SleeveCommand.STOP
            NavState.NAVIGATING -> if (announced) commandFor(turns[idx]) ?: SleeveCommand.FORWARD else SleeveCommand.FORWARD
            else -> null
        }

    fun start(): List<NavAction> {
        state = NavState.NAVIGATING
        idx = 1
        announced = false
        minDistance = Double.MAX_VALUE
        lastDistance = null
        forwardDueAt = null
        val name = if (routeName.isBlank()) "" else " di rute $routeName"
        return listOf(
            NavAction.Send(SleeveCommand.FORWARD),
            NavAction.Speak("Navigasi dimulai"),
            NavAction.Report("NAV_START", "Mulai jalan$name, ${route.size} titik."),
        )
    }

    /** One GPS fix. Fixes worse than 20 m are ignored, as in the blocks. */
    fun onFix(position: GeoPoint, accuracyM: Float, nowMs: Long): List<NavAction> {
        if (!snapshot.active || accuracyM > MAX_ACCURACY_M) return onTick(nowMs)
        val out = onTick(nowMs).toMutableList()
        val d = Geo.distanceM(position, route[idx])
        lastDistance = d
        if (d < minDistance) minDistance = d

        if (state == NavState.OFF_ROUTE) {
            if (d > minDistance + R_KELUAR - OFF_ROUTE_HYSTERESIS_M) return out
            state = NavState.NAVIGATING
            out += NavAction.Send(currentCommand ?: SleeveCommand.FORWARD)
            out += NavAction.Speak("Kamu sudah kembali ke rute")
            out += NavAction.Report("KEMBALI_RUTE", "Sudah kembali ke rute, ${d.roundToInt()} m dari titik $targetLabel.")
        }

        if (idx == route.lastIndex) {
            if (d < R_TIBA) {
                state = NavState.ARRIVED
                forwardDueAt = null
                out += NavAction.Send(SleeveCommand.ARRIVED)
                out += NavAction.Speak("Kamu sudah sampai")
                out += NavAction.Report("ARRIVED", "Sudah sampai di tujuan${if (routeName.isBlank()) "" else " rute $routeName"}.")
                return out
            }
        } else {
            val turn = turns[idx]
            if (d < R_UMUMKAN && !announced && turn != null && turn != Turn.STRAIGHT) {
                announced = true
                forwardDueAt = null
                out += NavAction.Send(commandFor(turn)!!)
                when (turn) {
                    Turn.LEFT -> {
                        out += NavAction.Speak("Sebentar lagi belok kiri")
                        out += NavAction.Report("BELOK_KIRI", "Belok kiri di titik $targetLabel.")
                    }
                    Turn.RIGHT -> {
                        out += NavAction.Speak("Sebentar lagi belok kanan")
                        out += NavAction.Report("BELOK_KANAN", "Belok kanan di titik $targetLabel.")
                    }
                    else -> {
                        out += NavAction.Speak("Putar balik")
                        out += NavAction.Report("PUTAR_BALIK", "Putar balik di titik $targetLabel.")
                    }
                }
            }
            if (d < R_TIBA) {
                idx++
                announced = false
                minDistance = Double.MAX_VALUE
                lastDistance = Geo.distanceM(position, route[idx])
                forwardDueAt = nowMs + FORWARD_DELAY_MS
                return out
            }
        }

        if (d > minDistance + R_KELUAR) {
            state = NavState.OFF_ROUTE
            forwardDueAt = null
            out += NavAction.Send(SleeveCommand.STOP)
            out += NavAction.Speak("Kamu keluar rute, berhenti dan minta bantuan")
            out += NavAction.Report("KELUAR_RUTE", "Keluar rute: ${d.roundToInt()} m dari titik $targetLabel dan makin jauh. Sleeve memberi tanda berhenti.")
        }
        return out
    }

    /** Call about once a second: sends the FORWARD that follows a passed point by 4 s. */
    fun onTick(nowMs: Long): List<NavAction> {
        val due = forwardDueAt ?: return emptyList()
        if (nowMs < due) return emptyList()
        forwardDueAt = null
        return if (state == NavState.NAVIGATING && !announced) listOf(NavAction.Send(SleeveCommand.FORWARD)) else emptyList()
    }

    /** The companion ended the trip before the destination. */
    fun stop(): List<NavAction> {
        if (!snapshot.active) return emptyList()
        state = NavState.IDLE
        forwardDueAt = null
        return listOf(
            NavAction.Send(SleeveCommand.IDLE),
            NavAction.Speak("Navigasi dihentikan"),
            NavAction.Report("NAV_STOP", "Navigasi dihentikan di HP anak sebelum sampai tujuan."),
        )
    }

    /** What BTN:SHORT reads out (bacakanInfo). */
    fun info(): String {
        val d = lastDistance
        return when {
            state == NavState.ARRIVED -> "Kamu sudah sampai"
            !snapshot.active -> "Navigasi belum dimulai"
            d == null -> "Titik berikutnya $targetLabel, menunggu sinyal GPS"
            state == NavState.OFF_ROUTE -> "Kamu keluar rute. Titik berikutnya $targetLabel, ${d.roundToInt()} meter lagi"
            else -> "Titik berikutnya $targetLabel, ${d.roundToInt()} meter lagi"
        }
    }

    private val targetLabel: String get() = if (idx == route.lastIndex) "tujuan" else "${idx + 1}"

    private fun commandFor(turn: Turn?): SleeveCommand? = when (turn) {
        Turn.LEFT -> SleeveCommand.LEFT
        Turn.RIGHT -> SleeveCommand.RIGHT
        Turn.U_TURN -> SleeveCommand.STOP
        else -> null
    }

    companion object {
        /** Announce a turn this far before its point. */
        const val R_UMUMKAN = 15.0
        /** A point counts as reached inside this radius. */
        const val R_TIBA = 6.0
        /** Off route when this much farther from the target than the closest the child has been. */
        const val R_KELUAR = 15.0
        const val OFF_ROUTE_HYSTERESIS_M = 5.0
        /** GPS fixes worse than this are ignored while navigating. */
        const val MAX_ACCURACY_M = 20f
        /** GPS fixes worse than this are refused when recording a route (§9.4). */
        const val MAX_RECORD_ACCURACY_M = 15f
        const val FORWARD_DELAY_MS = 4_000L
    }
}
