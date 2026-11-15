package com.sentra

import com.sentra.alert.CareEventType
import com.sentra.care.RemoteEvent
import com.sentra.care.careTrips
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Riwayat Perjalanan on the parents' phone, built from the event feed. */
class CareTripsTest {
    private var nextId = 1L
    private fun ev(type: CareEventType, at: Long, message: String = "") =
        RemoteEvent(nextId++, 1, type, null, at, null, null, message, null, null)

    @Test fun startToArrivedIsOneTripWithItsRouteName() {
        val trips = careTrips(
            listOf(
                ev(CareEventType.NAV_START, 100, "Mulai jalan di rute Rumah ke sekolah, 5 titik."),
                ev(CareEventType.BELOK_KIRI, 150),
                ev(CareEventType.KELUAR_RUTE, 160),
                ev(CareEventType.ARRIVED, 200),
            ).reversed() // the feed comes newest first
        )
        assertEquals(1, trips.size)
        val t = trips[0]
        assertEquals("Rumah ke sekolah", t.routeName)
        assertEquals(100L, t.startedAt)
        assertEquals(200L, t.endedAt)
        assertEquals(CareEventType.ARRIVED, t.outcome)
        assertEquals(4, t.events.size)
        assertEquals(1, t.alarms)
    }

    @Test fun newestFirstAndAnUnfinishedTripStaysOpen() {
        val trips = careTrips(
            listOf(
                ev(CareEventType.TEST, 50),
                ev(CareEventType.NAV_START, 100, "Mulai jalan, 3 titik."),
                ev(CareEventType.NAV_STOP, 120),
                ev(CareEventType.NAV_START, 300, "Mulai jalan di rute Taman, 2 titik."),
                ev(CareEventType.BELOK_KANAN, 310),
            )
        )
        assertEquals(listOf(300L, 100L), trips.map { it.startedAt })
        assertNull(trips[0].endedAt)
        assertEquals("Taman", trips[0].routeName)
        assertNull(trips[1].routeName)
        assertEquals(CareEventType.NAV_STOP, trips[1].outcome)
    }
}
