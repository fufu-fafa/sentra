package com.sentra.care

import com.sentra.alert.CareEventType
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToLong

/** The Riwayat filter, the same periods as the web page (server_relay/web/index.html). */
enum class HistoryMode(val label: String) { DAY("Hari"), WEEK("Minggu"), ALL("Semua") }

/** Events with [from] <= occurredAt < [until]. */
data class HistoryPeriod(val from: Long, val until: Long, val title: String)

private val ID = Locale("id", "ID")

fun localDay(at: Long, zone: ZoneId = ZoneId.systemDefault()): LocalDate =
    Instant.ofEpochMilli(at).atZone(zone).toLocalDate()

private fun LocalDate.startMs(zone: ZoneId) = atStartOfDay(zone).toInstant().toEpochMilli()

fun dayName(day: LocalDate, today: LocalDate): String = when (day) {
    today -> "Hari ini"
    today.minusDays(1) -> "Kemarin"
    else -> day.format(DateTimeFormatter.ofPattern("EEEE, d MMM", ID))
}

/** [offset] 0 is today or this week, -1 the one before. Weeks run Monday to Sunday. */
fun historyPeriod(mode: HistoryMode, offset: Int, now: Long, zone: ZoneId = ZoneId.systemDefault()): HistoryPeriod {
    val today = localDay(now, zone)
    return when (mode) {
        HistoryMode.ALL -> HistoryPeriod(0, Long.MAX_VALUE, "Semua")
        HistoryMode.DAY -> {
            val day = today.plusDays(offset.toLong())
            HistoryPeriod(day.startMs(zone), day.plusDays(1).startMs(zone), dayName(day, today))
        }
        HistoryMode.WEEK -> {
            val monday = today.minusDays(today.dayOfWeek.value - 1L).plusWeeks(offset.toLong())
            val format = DateTimeFormatter.ofPattern("d MMM", ID)
            val title = when (offset) {
                0 -> "Minggu ini"
                -1 -> "Minggu lalu"
                else -> "${monday.format(format)} – ${monday.plusDays(6).format(format)}"
            }
            HistoryPeriod(monday.startMs(zone), monday.plusDays(7).startMs(zone), title)
        }
    }
}

/** "baru saja", "12 dtk lalu", "5 mnt lalu", "3 jam lalu", "2 hari lalu". Seconds matter during a trip. */
fun ago(at: Long, now: Long): String {
    val s = ((now - at) / 1000.0).roundToLong()
    return when {
        s < 10 -> "baru saja"
        s < 60 -> "$s dtk lalu"
        s < 3600 -> "${(s / 60.0).roundToLong()} mnt lalu"
        s < 86400 -> "${(s / 3600.0).roundToLong()} jam lalu"
        else -> "${(s / 86400.0).roundToLong()} hari lalu"
    }
}

/** One walk as the parents see it: from NAV_START to ARRIVED or NAV_STOP, with what happened on the way. */
data class CareTrip(
    val routeName: String?,
    val startedAt: Long,
    /** Null while the trip has no end yet (still walking, or the phone went quiet). */
    val endedAt: Long?,
    /** ARRIVED or NAV_STOP; null without an end. */
    val outcome: CareEventType?,
    /** Everything from the start to the end, oldest first. */
    val events: List<RemoteEvent>,
) {
    val alarms: Int get() = events.count { it.type.isAlarm }
}

private val ROUTE_IN_START = Regex(" di rute (.+), \\d+ titik\\.$")

/** Groups the event feed into trips, newest first. Events outside a trip are left out. */
fun careTrips(events: List<RemoteEvent>): List<CareTrip> {
    val trips = mutableListOf<CareTrip>()
    var open: CareTrip? = null
    for (e in events.sortedWith(compareBy({ it.occurredAt }, { it.id }))) {
        when (e.type) {
            CareEventType.NAV_START -> {
                open?.let { trips += it }
                open = CareTrip(ROUTE_IN_START.find(e.message)?.groupValues?.get(1), e.occurredAt, null, null, listOf(e))
            }
            CareEventType.ARRIVED, CareEventType.NAV_STOP -> open?.let {
                trips += it.copy(endedAt = e.occurredAt, outcome = e.type, events = it.events + e)
                open = null
            }
            else -> open?.let { open = it.copy(events = it.events + e) }
        }
    }
    open?.let { trips += it }
    return trips.reversed()
}
