package com.sentra

import com.sentra.alert.AlertRules
import com.sentra.alert.CareAlert
import com.sentra.alert.CareEventType
import com.sentra.care.ChildStatus
import com.sentra.care.RemoteEvent
import com.sentra.data.EventEntity
import com.sentra.data.LocationEntity
import com.sentra.data.Outbox
import com.sentra.data.RouteEntity
import com.sentra.data.TelemetrySink
import com.sentra.data.Uploader
import com.sentra.nav.GeoPoint
import com.sentra.relay.ChildRelay
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The outbox, what goes over the wire to the relay, and what the parents' phone does with it. */
class UploaderTest {
    private class FakeOutbox : Outbox {
        val events = mutableListOf<EventEntity>()
        val uploaded = mutableListOf<Long>()
        val locations = mutableListOf<LocationEntity>()
        override suspend fun pendingEvents() = events.filter { it.id !in uploaded }
        override suspend fun markEventUploaded(id: Long, at: Long) { uploaded += id }
        override suspend fun pendingLocations(limit: Int) = locations.take(limit)
        override suspend fun deleteLocationsThrough(id: Long) { locations.removeAll { it.id <= id } }
    }

    private class FakeSink(override var configured: Boolean = true) : TelemetrySink {
        val events = mutableListOf<Long>()
        val batches = mutableListOf<Int>()
        var failEventId: Long? = null
        var failLocations = false
        override suspend fun upload(event: EventEntity): Boolean {
            if (event.id == failEventId) return false
            events += event.id
            return true
        }
        override suspend fun upload(locations: List<LocationEntity>): Boolean {
            if (failLocations) return false
            batches += locations.size
            return true
        }
    }

    private fun event(id: Long, type: String = "NAV_START") =
        EventEntity(id = id, deviceAddress = "AA:BB", type = type, batteryPct = 80, occurredAt = 1000 + id, lat = -6.9, lon = 107.6, message = "m$id")
    private fun fix(id: Long) = LocationEntity(id = id, at = 1000 * id, lat = -6.9, lon = 107.6, acc = 5f, speed = 1f, bearing = 90f)

    @Test fun sendsEventsOldestFirstThenFixesInBatches() = runTest {
        val out = FakeOutbox().apply { events += listOf(event(1), event(2)); locations += (1L..450L).map(::fix) }
        val sink = FakeSink()
        val r = Uploader(out, sink).flush()
        assertEquals(listOf(1L, 2L), sink.events)
        assertEquals(listOf(200, 200, 50), sink.batches)
        assertEquals(Uploader.Result(2, 450, true), r)
        assertTrue(out.locations.isEmpty())
    }

    @Test fun stopsAtTheFirstFailureSoOrderIsKept() = runTest {
        val out = FakeOutbox().apply { events += listOf(event(1), event(2), event(3)); locations += fix(1) }
        val sink = FakeSink().apply { failEventId = 2 }
        val r = Uploader(out, sink).flush()
        assertEquals(listOf(1L), sink.events)
        assertFalse(r.complete)
        assertEquals(1, out.locations.size) // fixes wait behind the stuck event
        // The relay is back: the rest goes out, nothing twice.
        sink.failEventId = null
        Uploader(out, sink).flush()
        assertEquals(listOf(1L, 2L, 3L), sink.events)
        assertTrue(out.locations.isEmpty())
    }

    @Test fun aFailedBatchStaysQueued() = runTest {
        val out = FakeOutbox().apply { locations += (1L..10L).map(::fix) }
        val sink = FakeSink().apply { failLocations = true }
        assertFalse(Uploader(out, sink).flush().complete)
        assertEquals(10, out.locations.size)
    }

    @Test fun nothingIsSentWithoutARelay() = runTest {
        val out = FakeOutbox().apply { events += event(1) }
        val sink = FakeSink(configured = false)
        assertEquals(Uploader.Result(0, 0, false), Uploader(out, sink).flush())
        assertTrue(sink.events.isEmpty())
    }

    // --- Wire format: must match relay/server.mjs ----------------------------------------------

    @Test fun eventJsonUsesThePhonesRowIdAsSeq() {
        val j = ChildRelay.eventJson(event(7, "KELUAR_RUTE"))
        assertEquals(7L, j.getLong("seq"))
        assertEquals("AA:BB", j.getString("deviceAddress"))
        assertEquals("KELUAR_RUTE", j.getString("type"))
        assertEquals(1007L, j.getLong("occurredAt"))
        assertEquals(-6.9, j.getDouble("lat"), 1e-9)
        assertFalse(ChildRelay.eventJson(event(8).copy(lat = null, lon = null)).has("lat"))
    }

    @Test fun locationsJsonIsABatchOfPoints() {
        val p = ChildRelay.locationsJson(listOf(fix(1), fix(2))).getJSONArray("points")
        assertEquals(2, p.length())
        assertEquals(2000L, p.getJSONObject(1).getLong("at"))
        assertEquals(107.6, p.getJSONObject(0).getDouble("lon"), 1e-9)
        assertEquals(5.0, p.getJSONObject(0).getDouble("acc"), 1e-9)
    }

    @Test fun routePointsSurviveStorage() {
        val points = listOf(GeoPoint(-6.9025, 107.6188), GeoPoint(-6.90321, 107.61961))
        assertEquals(points, RouteEntity.decodePoints(RouteEntity.encodePoints(points)))
        assertTrue(RouteEntity.decodePoints("").isEmpty())
        assertTrue(RouteEntity.decodePoints("abc;1,2,3").isEmpty())
    }

    // --- The parents' side ----------------------------------------------------------------

    @Test fun statusFromAHeartbeat() {
        val s = ChildStatus.from(
            JSONObject("""{"navigating":true,"navState":"OFF_ROUTE","sleeveConnected":false,"routeName":"Sekolah",
                "route":[[-6.9025,107.6188],[-6.9025,107.6196]],"nextPoint":2,"totalPoints":5,"distanceM":31,"batteryPct":76,"obstacleCm":999,"simulated":true}""")
        )
        assertEquals("OFF_ROUTE", s.navState)
        assertEquals(false, s.sleeveConnected)
        assertEquals(listOf(GeoPoint(-6.9025, 107.6188), GeoPoint(-6.9025, 107.6196)), s.route)
        assertEquals(31, s.distanceM)
        assertTrue(s.simulated)
        // An idle phone sends almost nothing.
        val idle = ChildStatus.from(JSONObject("""{"navigating":false}"""))
        assertNull(idle.sleeveConnected)
        assertTrue(idle.route.isEmpty())
        assertEquals(ChildStatus(), ChildStatus.from(null))
    }

    @Test fun eventFromTheRelay() {
        val e = RemoteEvent.from(
            JSONObject("""{"id":9,"wearerId":3,"type":"OFFLINE","batteryPct":null,"occurredAt":5000,"lat":-6.9,"lon":107.6,
                "message":"x","ackedBy":null,"ackedAt":null,"alarm":true}""")
        )
        assertEquals(CareEventType.OFFLINE, e.type)
        assertNull(e.batteryPct)
        assertNull(e.ackedBy)
        assertNull(e.ackedAt)
        assertEquals(-6.9, e.lat!!, 1e-9)
        assertEquals(CareEventType.UNKNOWN, RemoteEvent.from(JSONObject("""{"id":1,"wearerId":1,"type":"NEW_THING","occurredAt":1}""")).type)
    }

    @Test fun alarmsAreTheThreeTheRelayRePushes() {
        assertEquals(
            setOf(CareEventType.KELUAR_RUTE, CareEventType.TERPUTUS, CareEventType.OFFLINE),
            CareEventType.entries.filter { it.isAlarm }.toSet(),
        )
        CareEventType.entries.forEach { assertEquals(it.isAlarm, AlertRules.careAlert(it) == CareAlert.ALARM) }
        assertEquals(CareAlert.RING_ONCE, AlertRules.careAlert(CareEventType.ARRIVED))
        assertEquals(CareAlert.NOTIFY, AlertRules.careAlert(CareEventType.NAV_START))
        assertEquals(CareAlert.HISTORY, AlertRules.careAlert(CareEventType.BELOK_KIRI))
        assertEquals(CareAlert.HISTORY, AlertRules.careAlert(CareEventType.TERSAMBUNG))
        assertEquals("HP ANAK OFFLINE", AlertRules.alarmTitle(CareEventType.OFFLINE))
    }
}
