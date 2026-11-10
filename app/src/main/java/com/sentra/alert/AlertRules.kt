package com.sentra.alert

/** The events the relay passes on, with the label SENTRA Care shows. */
enum class CareEventType(val label: String) {
    NAV_START("Mulai jalan"),
    BELOK_KIRI("Belok kiri"),
    BELOK_KANAN("Belok kanan"),
    PUTAR_BALIK("Putar balik"),
    KELUAR_RUTE("Keluar rute"),
    KEMBALI_RUTE("Kembali ke rute"),
    ARRIVED("Sampai"),
    NAV_STOP("Navigasi dihentikan"),
    TERPUTUS("Sleeve terputus"),
    TERSAMBUNG("Sleeve tersambung"),
    /** Raised by the relay: no word from the child's phone for 60 s during a trip. */
    OFFLINE("HP anak offline"),
    TEST("Tes alarm"),
    UNKNOWN("Kejadian");

    /** Rings full-screen until a parent acknowledges it. Must match ALARM_TYPES in relay/server.mjs. */
    val isAlarm: Boolean get() = this == KELUAR_RUTE || this == TERPUTUS || this == OFFLINE

    /** Good news, shown in green in the history. */
    val isGood: Boolean get() = this == ARRIVED || this == TERSAMBUNG || this == KEMBALI_RUTE

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
        CareEventType.KELUAR_RUTE, CareEventType.TERPUTUS, CareEventType.OFFLINE -> CareAlert.ALARM
        CareEventType.ARRIVED, CareEventType.TEST -> CareAlert.RING_ONCE
        CareEventType.NAV_START -> CareAlert.NOTIFY
        else -> CareAlert.HISTORY
    }

    /** The big words on the alarm screen. */
    fun alarmTitle(type: CareEventType): String = when (type) {
        CareEventType.KELUAR_RUTE -> "KELUAR RUTE"
        CareEventType.TERPUTUS -> "SLEEVE TERPUTUS"
        CareEventType.OFFLINE -> "HP ANAK OFFLINE"
        else -> type.label.uppercase()
    }
}
