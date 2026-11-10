package com.sentra

import com.sentra.ble.SleeveCommand
import com.sentra.nav.Geo
import com.sentra.nav.GeoPoint
import com.sentra.nav.NavAction
import com.sentra.nav.NavEngine
import com.sentra.nav.NavState
import com.sentra.nav.Turn
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Distance, bearing, turn classification and the navigation state machine of §9.5. */
class NavEngineTest {
    private val start = GeoPoint(-6.9025, 107.6188) // near Gedung Sate, Bandung
    private fun from(p: GeoPoint, bearing: Double, m: Double) = Geo.offset(p, bearing, m)

    // 100 m east, then right (south) 100 m.
    private val b = from(start, 90.0, 100.0)
    private val c = from(b, 180.0, 100.0)
    private val route = listOf(start, b, c)

    private fun List<NavAction>.sent() = filterIsInstance<NavAction.Send>().map { it.command }
    private fun List<NavAction>.reports() = filterIsInstance<NavAction.Report>().map { it.type }
    private fun List<NavAction>.spoken() = filterIsInstance<NavAction.Speak>().map { it.text }

    // --- Formulas ---------------------------------------------------------------------------

    @Test fun distanceAndBearing() {
        assertEquals(100.0, Geo.distanceM(start, b), 0.5)
        assertEquals(90.0, Geo.bearingDeg(start, b), 0.5)          // "ke timur harus ±90°"
        assertEquals(0.0, Geo.bearingDeg(start, from(start, 0.0, 50.0)), 0.5)
        assertEquals(180.0, Geo.bearingDeg(b, c), 0.5)
        assertEquals(270.0, Geo.bearingDeg(b, start), 0.5)         // never negative
        assertEquals(111.3, Geo.distanceM(GeoPoint(0.0, 0.0), GeoPoint(0.0, 0.001)), 0.1)
    }

    @Test fun turnDeltaWrapsAround() {
        assertEquals(90.0, Geo.turnDelta(90.0, 180.0), 1e-9)
        assertEquals(-90.0, Geo.turnDelta(90.0, 0.0), 1e-9)
        assertEquals(20.0, Geo.turnDelta(350.0, 10.0), 1e-9)
        assertEquals(-20.0, Geo.turnDelta(10.0, 350.0), 1e-9)
        assertEquals(180.0, Geo.turnDelta(0.0, 180.0), 1e-9)
    }

    @Test fun turnsAreClassifiedAt30And150Degrees() {
        assertEquals(Turn.STRAIGHT, Geo.classify(30.0))
        assertEquals(Turn.RIGHT, Geo.classify(31.0))
        assertEquals(Turn.LEFT, Geo.classify(-31.0))
        assertEquals(Turn.STRAIGHT, Geo.classify(-30.0))
        assertEquals(Turn.RIGHT, Geo.classify(150.0))
        assertEquals(Turn.U_TURN, Geo.classify(151.0))
        assertEquals(Turn.U_TURN, Geo.classify(-170.0))
        assertEquals(Turn.RIGHT, Geo.turnAt(start, b, c))
        assertEquals(Turn.LEFT, Geo.turnAt(start, b, from(b, 0.0, 100.0)))
    }

    // --- State machine ----------------------------------------------------------------------

    @Test fun startSendsForwardAndSaysSo() {
        val e = NavEngine(route, "Sekolah")
        val out = e.start()
        assertEquals(listOf(SleeveCommand.FORWARD), out.sent())
        assertEquals(listOf("Navigasi dimulai"), out.spoken())
        assertEquals(listOf("NAV_START"), out.reports())
        assertEquals(NavState.NAVIGATING, e.snapshot.state)
        assertEquals(2, e.snapshot.targetNumber)
        assertEquals(listOf(null, Turn.RIGHT, null), e.turns)
    }

    @Test fun aWholeTrip() {
        val e = NavEngine(route)
        e.start()
        var t = 0L
        // Far from the turn: nothing to do.
        assertTrue(e.onFix(from(start, 90.0, 50.0), 5f, t).isEmpty())
        assertEquals(50.0, e.snapshot.distanceM!!, 0.5)

        // Inside 15 m: the turn is announced once.
        t += 1000
        val near = e.onFix(from(start, 90.0, 88.0), 5f, t)
        assertEquals(listOf(SleeveCommand.RIGHT), near.sent())
        assertEquals(listOf("Sebentar lagi belok kanan"), near.spoken())
        assertEquals(listOf("BELOK_KANAN"), near.reports())
        assertEquals(SleeveCommand.RIGHT, e.currentCommand)
        t += 1000
        assertTrue(e.onFix(from(start, 90.0, 91.0), 5f, t).isEmpty())

        // Inside 6 m: on to the next point, and FORWARD follows 4 s later.
        t += 1000
        assertTrue(e.onFix(from(start, 90.0, 96.0), 5f, t).isEmpty())
        assertEquals(3, e.snapshot.targetNumber)
        assertTrue(e.onTick(t + 3_999).isEmpty())
        assertEquals(listOf(SleeveCommand.FORWARD), e.onTick(t + 4_000).sent())
        assertTrue(e.onTick(t + 5_000).isEmpty())

        // Walking south to the destination.
        t += 10_000
        assertTrue(e.onFix(from(b, 180.0, 60.0), 5f, t).isEmpty())
        val end = e.onFix(from(b, 180.0, 96.0), 5f, t + 1000)
        assertEquals(listOf(SleeveCommand.ARRIVED), end.sent())
        assertEquals(listOf("Kamu sudah sampai"), end.spoken())
        assertEquals(listOf("ARRIVED"), end.reports())
        assertEquals(NavState.ARRIVED, e.snapshot.state)
        assertNull(e.currentCommand)
        // Nothing more after arriving.
        assertTrue(e.onFix(c, 5f, t + 2000).isEmpty())
    }

    @Test fun weakGpsIsIgnored() {
        val e = NavEngine(route)
        e.start()
        assertTrue(e.onFix(from(start, 90.0, 90.0), 21f, 0).isEmpty())
        assertNull(e.snapshot.distanceM)
        assertEquals(listOf(SleeveCommand.RIGHT), e.onFix(from(start, 90.0, 90.0), 20f, 1000).sent())
    }

    @Test fun offRouteIsSaidOnceAndRecovers() {
        val e = NavEngine(route)
        e.start()
        e.onFix(from(start, 90.0, 50.0), 5f, 0)                 // closest so far: 50 m from the turn
        assertTrue(e.onFix(from(start, 90.0, 36.0), 5f, 1000).isEmpty()) // 64 m: within the 15 m margin
        val out = e.onFix(from(start, 90.0, 30.0), 5f, 2000)    // 70 m: more than 15 m farther
        assertEquals(listOf(SleeveCommand.STOP), out.sent())
        assertEquals(listOf("KELUAR_RUTE"), out.reports())
        assertEquals(listOf("Kamu keluar rute, berhenti dan minta bantuan"), out.spoken())
        assertEquals(NavState.OFF_ROUTE, e.snapshot.state)
        assertEquals(SleeveCommand.STOP, e.currentCommand)
        // Still out: not repeated.
        assertTrue(e.onFix(from(start, 90.0, 20.0), 5f, 3000).isEmpty())
        assertTrue(e.onFix(from(start, 90.0, 37.0), 5f, 4000).isEmpty()) // 63 m: not yet inside the hysteresis
        // Back within the limit: resume.
        val back = e.onFix(from(start, 90.0, 42.0), 5f, 5000)
        assertEquals(listOf(SleeveCommand.FORWARD), back.sent())
        assertEquals(listOf("KEMBALI_RUTE"), back.reports())
        assertEquals(NavState.NAVIGATING, e.snapshot.state)
    }

    @Test fun walkingAwayFromTheStartCountsAsOffRoute() {
        val e = NavEngine(route)
        e.start()
        e.onFix(start, 5f, 0)
        assertTrue(e.onFix(from(start, 270.0, 14.0), 5f, 1000).isEmpty())
        assertEquals(listOf("KELUAR_RUTE"), e.onFix(from(start, 270.0, 16.0), 5f, 2000).reports())
    }

    @Test fun aStraightPointIsNotAnnounced() {
        val mid = from(start, 90.0, 100.0)
        val e = NavEngine(listOf(start, mid, from(mid, 100.0, 100.0))) // 10° off: still straight
        e.start()
        assertTrue(e.onFix(from(start, 90.0, 90.0), 5f, 0).isEmpty())
        assertTrue(e.onFix(from(start, 90.0, 97.0), 5f, 1000).isEmpty())
        assertEquals(3, e.snapshot.targetNumber)
        assertEquals(listOf(SleeveCommand.FORWARD), e.onTick(5000).sent())
    }

    @Test fun turningBackSendsStopAndSaysPutarBalik() {
        val e = NavEngine(listOf(start, b, from(start, 90.0, 20.0)))
        e.start()
        val out = e.onFix(from(start, 90.0, 90.0), 5f, 0)
        assertEquals(listOf(SleeveCommand.STOP), out.sent())
        assertEquals(listOf("Putar balik"), out.spoken())
        assertEquals(listOf("PUTAR_BALIK"), out.reports())
    }

    @Test fun delayedForwardDoesNotCancelATurnAnnouncedMeanwhile() {
        // Two turns 12 m apart: the second is announced right after the first point is passed.
        val c2 = from(b, 180.0, 12.0)
        val e = NavEngine(listOf(start, b, c2, from(c2, 90.0, 100.0)))
        e.start()
        e.onFix(from(start, 90.0, 90.0), 5f, 0)      // RIGHT announced
        e.onFix(from(start, 90.0, 97.0), 5f, 1000)   // point passed, FORWARD due at 5000
        val next = e.onFix(from(b, 180.0, 2.0), 5f, 2000)
        assertEquals(listOf(SleeveCommand.LEFT), next.sent())
        assertTrue(e.onTick(5000).isEmpty())
        assertEquals(SleeveCommand.LEFT, e.currentCommand)
    }

    @Test fun stoppingEarlyIdlesTheSleeve() {
        val e = NavEngine(route)
        assertTrue(e.stop().isEmpty())
        e.start()
        val out = e.stop()
        assertEquals(listOf(SleeveCommand.IDLE), out.sent())
        assertEquals(listOf("NAV_STOP"), out.reports())
        assertEquals(NavState.IDLE, e.snapshot.state)
        assertTrue(e.onFix(b, 5f, 0).isEmpty())
    }

    @Test fun buttonInfoReadsTheNextPointAndDistance() {
        val e = NavEngine(route)
        assertEquals("Navigasi belum dimulai", e.info())
        e.start()
        assertEquals("Titik berikutnya 2, menunggu sinyal GPS", e.info())
        e.onFix(from(start, 90.0, 60.0), 5f, 0)
        assertEquals("Titik berikutnya 2, 40 meter lagi", e.info())
    }

    @Test(expected = IllegalArgumentException::class)
    fun aRouteNeedsTwoPoints() {
        NavEngine(listOf(start))
    }
}
