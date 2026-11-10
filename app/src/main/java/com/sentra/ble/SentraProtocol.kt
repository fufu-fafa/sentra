package com.sentra.ble

import java.util.UUID

/**
 * The BLE contract with the sleeve firmware: Desain Teknis Bab 8 (Tabel 18, 19, 20). Both sides
 * must use exactly these names, UUIDs and message texts.
 */
object SentraProtocol {
    /** The sleeve advertises as SENTRA-01 (the number changes when there is more than one). */
    const val DEVICE_NAME_PREFIX = "SENTRA"

    val SERVICE: UUID = UUID.fromString("3d0dc036-a075-4f06-ba4a-9991669c7a8c")
    /** Phone to sleeve: command text. WRITE, WRITE_NR. */
    val CMD: UUID = UUID.fromString("003abf21-74a3-465f-b192-017cea87380b")
    /** Sleeve to phone: distance, button, ACK. READ, NOTIFY. */
    val STATUS: UUID = UUID.fromString("c8ae039a-04b6-42d5-9b16-4ea56ce8eb1b")
    val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    /** A message must fit one BLE packet (§8.3). */
    const val MAX_MESSAGE_LENGTH = 20
    /** D:999 means no obstacle in range. */
    const val NO_OBSTACLE_CM = 999
    /** The app must send PING this often while navigating; the sleeve stops navigating after 6 s of silence. */
    const val PING_INTERVAL_MS = 2_000L
    const val APP_TIMEOUT_MS = 6_000L
    /** The sleeve reports the distance this often. */
    const val STATUS_INTERVAL_MS = 250L

    fun encode(command: SleeveCommand): ByteArray = command.name.toByteArray(Charsets.US_ASCII)

    fun parse(bytes: ByteArray): SleeveMessage = parse(String(bytes, Charsets.UTF_8))

    /** Upper/lower case and surrounding whitespace don't matter, as on the firmware side. */
    fun parse(raw: String): SleeveMessage {
        val text = raw.trim().trimEnd('\u0000')
        val upper = text.uppercase()
        return when {
            upper.startsWith("D:") -> upper.substring(2).trim().toIntOrNull()
                ?.takeIf { it >= 0 }?.let { SleeveMessage.Distance(it) } ?: SleeveMessage.Unknown(text)
            upper.startsWith("ACK:") -> SleeveMessage.Ack(upper.substring(4).trim())
            upper.startsWith("ERR:") -> SleeveMessage.Error(text.substring(4).trim())
            upper == "BTN:SHORT" -> SleeveMessage.Button(long = false, intensity = null)
            upper == "BTN:LONG,HIGH" -> SleeveMessage.Button(long = true, intensity = Intensity.HIGH)
            upper == "BTN:LONG,LOW" -> SleeveMessage.Button(long = true, intensity = Intensity.LOW)
            upper == "READY" -> SleeveMessage.Ready
            else -> SleeveMessage.Unknown(text)
        }
    }
}

/** Tabel 19: what the app can tell the sleeve. The enum name is the text sent. */
enum class SleeveCommand(val label: String) {
    FORWARD("Lurus"),
    LEFT("Kiri"),
    RIGHT("Kanan"),
    STOP("Stop"),
    ARRIVED("Tiba"),
    /** Stops the navigation pattern without a vibration. */
    IDLE("Diam"),
    TEST("Tes"),
    LOW("Lemah"),
    HIGH("Kuat"),
    /** "The app is alive". The sleeve does not answer it with an ACK. */
    PING("Ping");

    init {
        require(name.length <= SentraProtocol.MAX_MESSAGE_LENGTH)
    }
}

enum class Intensity { LOW, HIGH }

/** Tabel 20: what the sleeve tells the app. */
sealed interface SleeveMessage {
    /** Obstacle distance in cm, every 250 ms. */
    data class Distance(val cm: Int) : SleeveMessage {
        val clear: Boolean get() = cm >= SentraProtocol.NO_OBSTACLE_CM
    }

    /** The command (upper case) was accepted, e.g. ACK:LEFT. */
    data class Ack(val command: String) : SleeveMessage

    /** The sleeve didn't know the command. */
    data class Error(val text: String) : SleeveMessage

    /** Short press: read out where we are. Long press: the sleeve switched intensity. */
    data class Button(val long: Boolean, val intensity: Intensity?) : SleeveMessage

    /** The STATUS value before the first notification. */
    data object Ready : SleeveMessage

    data class Unknown(val text: String) : SleeveMessage
}

enum class LinkState { DISCONNECTED, CONNECTING, CONNECTED }
