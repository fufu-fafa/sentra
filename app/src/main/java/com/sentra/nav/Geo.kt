package com.sentra.nav

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sqrt

data class GeoPoint(val lat: Double, val lon: Double)

enum class Turn { STRAIGHT, LEFT, RIGHT, U_TURN }

/** The short formulas of Desain Teknis §9.5: the earth is flat over a few kilometres (error < 0.1%). */
object Geo {
    private const val M_PER_DEG_LON = 111_320.0
    private const val M_PER_DEG_LAT = 110_540.0

    /** Metres east of [a]. */
    private fun east(a: GeoPoint, b: GeoPoint) =
        (b.lon - a.lon) * M_PER_DEG_LON * cos(Math.toRadians((a.lat + b.lat) / 2))

    /** Metres north of [a]. */
    private fun north(a: GeoPoint, b: GeoPoint) = (b.lat - a.lat) * M_PER_DEG_LAT

    fun distanceM(a: GeoPoint, b: GeoPoint): Double {
        val x = east(a, b)
        val y = north(a, b)
        return sqrt(x * x + y * y)
    }

    /** Compass bearing from [a] to [b]: 0° north, 90° east, always in 0 until 360. */
    fun bearingDeg(a: GeoPoint, b: GeoPoint): Double {
        val deg = Math.toDegrees(atan2(east(a, b), north(a, b)))
        return if (deg < 0) deg + 360 else deg
    }

    /** How far the heading changes at a point, in -180 (exclusive) to 180: positive is to the right. */
    fun turnDelta(bearingIn: Double, bearingOut: Double): Double {
        var d = bearingOut - bearingIn
        if (d > 180) d -= 360
        if (d <= -180) d += 360
        return d
    }

    /** More than ±30° is a turn; more than ±150° means turning back. */
    fun classify(delta: Double): Turn = when {
        abs(delta) > 150 -> Turn.U_TURN
        delta > 30 -> Turn.RIGHT
        delta < -30 -> Turn.LEFT
        else -> Turn.STRAIGHT
    }

    /** The turn to make at [at] when arriving from [from] and leaving for [to]. */
    fun turnAt(from: GeoPoint, at: GeoPoint, to: GeoPoint): Turn =
        classify(turnDelta(bearingDeg(from, at), bearingDeg(at, to)))

    /** A point [metres] away from [from] on compass bearing [bearingDeg]. Inverse of the formulas above. */
    fun offset(from: GeoPoint, bearingDeg: Double, metres: Double): GeoPoint {
        val r = Math.toRadians(bearingDeg)
        val dLat = metres * cos(r) / M_PER_DEG_LAT
        val dLon = metres * kotlin.math.sin(r) / (M_PER_DEG_LON * cos(Math.toRadians(from.lat)))
        return GeoPoint(from.lat + dLat, from.lon + dLon)
    }
}
