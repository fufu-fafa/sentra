package com.sentra

import com.sentra.nav.Geo
import com.sentra.nav.GeoPoint
import com.sentra.nav.NavEngine
import com.sentra.nav.PlannedRoute
import com.sentra.nav.RoutePlanner
import com.sentra.nav.Turn
import com.sentra.relay.ChildRelay
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RoutePlannerTest {
    private val start = GeoPoint(-6.9025, 107.6188)

    /** A line walked from [from]: each leg is (bearing, metres), with a shape point every [step] m. */
    private fun walk(from: GeoPoint, vararg legs: Pair<Double, Double>, step: Double = 5.0): Pair<List<GeoPoint>, List<Int>> {
        val shape = mutableListOf(from)
        val corners = mutableListOf(0)
        for ((bearing, metres) in legs) {
            val n = (metres / step).toInt()
            repeat(n) { shape += Geo.offset(shape.last(), bearing, step) }
            corners += shape.lastIndex
        }
        return shape to corners
    }

    private fun plan(shape: List<GeoPoint>, maneuvers: List<Int>) =
        RoutePlanner.toNavPoints(PlannedRoute(shape, maneuvers, 0.0, 0))

    @Test
    fun `an L-shaped route becomes start, turn, destination`() {
        val (shape, corners) = walk(start, 90.0 to 100.0, 180.0 to 80.0)
        val points = plan(shape, corners)
        assertEquals(listOf(shape[0], shape[corners[1]], shape.last()), points)
        assertEquals(Turn.RIGHT, Geo.turnAt(points[0], points[1], points[2]))
    }

    @Test
    fun `straight shape points without a maneuver are dropped`() {
        val (shape, _) = walk(start, 90.0 to 300.0)
        assertEquals(listOf(shape.first(), shape.last()), plan(shape, listOf(0, shape.lastIndex)))
    }

    @Test
    fun `a long gentle curve keeps points so it never looks like walking away`() {
        // A quarter circle of 150 m radius with no maneuver on it.
        val centre = Geo.offset(start, 180.0, 150.0)
        val shape = (0..90 step 2).map { Geo.offset(centre, it.toDouble(), 150.0) }
        val points = plan(shape, listOf(0, shape.lastIndex))
        assertTrue("curve needs middle points, got ${points.size}", points.size > 3)
        // Walking the real line, the distance to the next point must never grow by the off-route margin.
        var target = 1
        var closest = Double.MAX_VALUE
        for (p in shape) {
            while (target < points.lastIndex && Geo.distanceM(p, points[target]) < NavEngine.R_ARRIVE) { target++; closest = Double.MAX_VALUE }
            val d = Geo.distanceM(p, points[target])
            closest = minOf(closest, d)
            assertTrue("would be off route at $p", d < closest + NavEngine.R_OFF_ROUTE)
        }
    }

    @Test
    fun `points are at least 20 m apart and a maneuver wins over a bend`() {
        val (shape, corners) = walk(start, 90.0 to 100.0, 180.0 to 10.0, 90.0 to 100.0)
        val points = plan(shape, corners)
        points.zipWithNext().forEach { (a, b) -> assertTrue(Geo.distanceM(a, b) >= RoutePlanner.MIN_GAP_M) }
        assertEquals(shape.first(), points.first())
        assertEquals(shape.last(), points.last())
        assertTrue("the first corner is a maneuver and stays", shape[corners[1]] in points)
    }

    @Test
    fun `a turn just before the destination gives way to it`() {
        val (shape, corners) = walk(start, 90.0 to 100.0, 0.0 to 10.0)
        assertEquals(listOf(shape.first(), shape.last()), plan(shape, corners))
    }

    @Test
    fun `a very short route is still start and destination`() {
        val (shape, corners) = walk(start, 90.0 to 10.0)
        assertEquals(listOf(shape.first(), shape.last()), plan(shape, corners))
    }

    @Test
    fun `repeated shape points are ignored`() {
        val (shape, corners) = walk(start, 90.0 to 60.0, 0.0 to 60.0)
        val doubled = shape.flatMap { listOf(it, it) }
        val points = plan(doubled, corners.map { it * 2 })
        assertEquals(listOf(shape[0], shape[corners[1]], shape.last()), points)
    }

    @Test
    fun `the relay's reply is read into a planned route and places`() {
        val plan = ChildRelay.parsePlan(JSONObject(
            """{"lengthM":166,"timeS":130,"shape":[[-6.9025,107.6188],[-6.9025,107.6196]],"maneuvers":[{"at":0,"type":2},{"at":1,"type":4}]}""",
        ))
        assertEquals(PlannedRoute(listOf(GeoPoint(-6.9025, 107.6188), GeoPoint(-6.9025, 107.6196)), listOf(0, 1), 166.0, 130), plan)
        val places = ChildRelay.parsePlaces(JSONObject("""{"places":[{"name":"Gedung Sate","detail":"Bandung","lat":-6.9025,"lon":107.6187}]}"""))
        assertEquals("Gedung Sate", places.single().name)
        assertEquals(GeoPoint(-6.9025, 107.6187), places.single().point)
    }
}
