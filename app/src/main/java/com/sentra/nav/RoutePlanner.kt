package com.sentra.nav

import kotlin.math.cos
import kotlin.math.sqrt

/** A place found by search, to walk to. */
data class Place(val name: String, val detail: String, val point: GeoPoint)

/**
 * A walking route from the routing service: the whole line in walking order, and the indices on it
 * where a maneuver (a turn, the start, the destination) begins.
 */
data class PlannedRoute(
    val shape: List<GeoPoint>,
    val maneuvers: List<Int>,
    val lengthM: Double,
    val timeS: Int,
)

/**
 * Turns a routing service's line, which has a point every few metres along every curve, into the
 * kind of route [NavEngine] walks: start, turns, destination, like a recorded route.
 *
 *  - Every maneuver point is kept, so each turn the router knows about is a point.
 *  - Bends the router didn't call a maneuver are kept when the line strays more than
 *    [TOLERANCE_M] from a straight walk (Douglas–Peucker). Without them a long curved street
 *    would look like walking away from the next point, which [NavEngine] reads as off route.
 *  - Points are at least [MIN_GAP_M] apart, as Record Route asks of the companion, so a turn
 *    isn't announced while the previous point is still being reached.
 */
object RoutePlanner {
    const val TOLERANCE_M = 8.0
    const val MIN_GAP_M = 20.0

    fun toNavPoints(route: PlannedRoute, toleranceM: Double = TOLERANCE_M, minGapM: Double = MIN_GAP_M): List<GeoPoint> {
        val shape = route.shape.zipWithNext().filter { (a, b) -> a != b }.map { it.first } + listOfNotNull(route.shape.lastOrNull())
        if (shape.size < 2) return shape
        // Indices shift when duplicate points are dropped, so match maneuvers by position.
        val pinned = route.maneuvers.mapNotNull { route.shape.getOrNull(it) }.toSet()
        val keep = BooleanArray(shape.size)
        keep[0] = true
        keep[shape.lastIndex] = true
        shape.forEachIndexed { i, p -> if (p in pinned) keep[i] = true }
        simplify(shape, 0, shape.lastIndex, toleranceM, keep)
        return spaceOut(shape.indices.filter { keep[it] }, shape, pinned, minGapM)
    }

    /** Douglas–Peucker between [from] and [to], itself split at every point already kept. */
    private fun simplify(shape: List<GeoPoint>, from: Int, to: Int, toleranceM: Double, keep: BooleanArray) {
        var start = from
        for (i in from + 1..to) {
            if (!keep[i]) continue
            dp(shape, start, i, toleranceM, keep)
            start = i
        }
    }

    private fun dp(shape: List<GeoPoint>, a: Int, b: Int, toleranceM: Double, keep: BooleanArray) {
        if (b - a < 2) return
        var worst = -1
        var worstD = toleranceM
        for (i in a + 1 until b) {
            val d = offLine(shape[i], shape[a], shape[b])
            if (d > worstD) { worst = i; worstD = d }
        }
        if (worst < 0) return
        keep[worst] = true
        dp(shape, a, worst, toleranceM, keep)
        dp(shape, worst, b, toleranceM, keep)
    }

    /**
     * Drops points closer than [minGapM] to the one before. A bend gives way to a maneuver; of two
     * maneuvers the later one goes, except that start and destination always stay.
     */
    private fun spaceOut(indices: List<Int>, shape: List<GeoPoint>, pinned: Set<GeoPoint>, minGapM: Double): List<GeoPoint> {
        val out = ArrayList<Int>()
        for ((n, i) in indices.withIndex()) {
            val last = n == indices.lastIndex
            val prev = out.lastOrNull()
            if (prev == null || Geo.distanceM(shape[prev], shape[i]) >= minGapM) { out += i; continue }
            val prevIsEnd = out.size == 1
            val prevPinned = shape[prev] in pinned
            when {
                last && prevIsEnd -> out += i // a route this short is just start and destination
                last -> { out[out.lastIndex] = i } // the destination wins over whatever is just before it
                !prevIsEnd && !prevPinned && shape[i] in pinned -> { out[out.lastIndex] = i }
                else -> Unit // drop this one
            }
        }
        return out.map { shape[it] }
    }

    /** Metres from [p] to the segment [a]–[b], on the same flat earth as [Geo]. */
    private fun offLine(p: GeoPoint, a: GeoPoint, b: GeoPoint): Double {
        val k = cos(Math.toRadians(a.lat)) * 111_320.0
        val x = (p.lon - a.lon) * k; val y = (p.lat - a.lat) * 110_540.0
        val bx = (b.lon - a.lon) * k; val by = (b.lat - a.lat) * 110_540.0
        val len2 = bx * bx + by * by
        val t = if (len2 == 0.0) 0.0 else ((x * bx + y * by) / len2).coerceIn(0.0, 1.0)
        val dx = x - t * bx; val dy = y - t * by
        return sqrt(dx * dx + dy * dy)
    }
}
