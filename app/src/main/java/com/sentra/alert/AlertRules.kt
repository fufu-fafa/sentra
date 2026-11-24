package com.sentra.alert

/** The events the relay passes on, with the label SENTRA Care shows. */
enum class CareEventType(val label: String) {
    NAV_START("Started walking"),
    TURN_LEFT("Turn left"),
    TURN_RIGHT("Turn right"),
    U_TURN("U-turn"),
    OFF_ROUTE("Off route"),
    BACK_ON_ROUTE("Back on route"),
    ARRIVED("Arrived"),
    NAV_STOP("Navigation stopped"),
    SLEEVE_DISCONNECTED("Sleeve disconnected"),
    SLEEVE_CONNECTED("Sleeve connected"),
    /** Raised by the relay: no word from the child's phone for 60 s during a trip. */
    OFFLINE("Child's phone offline"),
    TEST("Test alarm"),
    UNKNOWN("Event");

    /** Rings full-screen until a parent acknowledges it. Must match ALARM_TYPES in server_relay/server.mjs. */
    val isAlarm: Boolean get() = this == OFF_ROUTE || this == SLEEVE_DISCONNECTED || this == OFFLINE

    /** Good news, shown in green in the history. */
    val isGood: Boolean get() = this == ARRIVED || this == SLEEVE_CONNECTED || this == BACK_ON_ROUTE

    companion object {
        fun from(name: String): CareEventType = entries.firstOrNull { it.name == name } ?: UNKNOWN
    }
}

/** What a parent's phone does with each event type. */
enum class CareAlert {
    /** Full-screen alarm that rings until acknowledged. */
    ALARM,
    /** Rings once. */
    RING_ONCE,
    /** Quiet notification. */
    NOTIFY,
    /** Map, status and history only. */
    HISTORY,
}

/** Alert decisions, kept free of Android types so they can be unit tested. */
object AlertRules {
    fun careAlert(type: CareEventType): CareAlert = when (type) {
        CareEventType.OFF_ROUTE, CareEventType.SLEEVE_DISCONNECTED, CareEventType.OFFLINE -> CareAlert.ALARM
        CareEventType.ARRIVED, CareEventType.TEST -> CareAlert.RING_ONCE
        CareEventType.NAV_START -> CareAlert.NOTIFY
        else -> CareAlert.HISTORY
    }

    /** The big words on the alarm screen. */
    fun alarmTitle(type: CareEventType): String = when (type) {
        CareEventType.OFF_ROUTE -> "OFF ROUTE"
        CareEventType.SLEEVE_DISCONNECTED -> "SLEEVE DISCONNECTED"
        CareEventType.OFFLINE -> "CHILD'S PHONE OFFLINE"
        else -> type.label.uppercase()
    }
}
