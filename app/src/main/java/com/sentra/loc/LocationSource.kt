package com.sentra.loc

import android.annotation.SuppressLint
import android.content.Context
import android.os.Looper
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.sentra.nav.Geo
import com.sentra.nav.GeoPoint
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flow

data class Fix(
    val lat: Double,
    val lon: Double,
    val accuracyM: Float,
    val speed: Float,
    val bearing: Float,
    val timeMs: Long,
) {
    val point: GeoPoint get() = GeoPoint(lat, lon)
}

/** Fused location every second at high accuracy (the LocationSensor of Tabel 21: TimeInterval 1000, DistanceInterval 0). */
@SuppressLint("MissingPermission") // The caller checks ACCESS_FINE_LOCATION before collecting.
fun gpsFixes(context: Context): Flow<Fix> = callbackFlow {
    val client = LocationServices.getFusedLocationProviderClient(context)
    val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1_000)
        .setMinUpdateIntervalMillis(1_000)
        .setMinUpdateDistanceMeters(0f)
        .setWaitForAccurateLocation(false)
        .build()
    val callback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            for (l in result.locations) {
                trySend(Fix(l.latitude, l.longitude, if (l.hasAccuracy()) l.accuracy else 999f, l.speed, l.bearing, l.time))
            }
        }
    }
    client.requestLocationUpdates(request, callback, Looper.getMainLooper())
        .addOnFailureListener { close(it) }
    awaitClose { client.removeLocationUpdates(callback) }
}

/**
 * A pretend walk along a route for testing navigation, the relay and SENTRA Care without going
 * outside: one fix a second, [speedMps] apart, starting a few metres before the first point.
 * While [detour] returns true the walker leaves the route sideways, which ends in "keluar rute".
 */
fun simulatedWalk(
    route: List<GeoPoint>,
    speedMps: Double = 3.0,
    now: () -> Long = System::currentTimeMillis,
    detour: () -> Boolean = { false },
): Flow<Fix> = flow {
    if (route.size < 2) return@flow
    var pos = Geo.offset(route[0], Geo.bearingDeg(route[1], route[0]), 5.0)
    var target = 1
    var heading = Geo.bearingDeg(pos, route[1])
    while (true) {
        emit(Fix(pos.lat, pos.lon, 5f, speedMps.toFloat(), heading.toFloat(), now()))
        delay(1_000)
        if (detour()) {
            // Walk off at a right angle to the route.
            pos = Geo.offset(pos, heading + 90, speedMps)
            continue
        }
        val goal = route[target]
        val d = Geo.distanceM(pos, goal)
        if (d <= speedMps) {
            pos = goal
            if (target < route.lastIndex) target++
        } else {
            heading = Geo.bearingDeg(pos, goal)
            pos = Geo.offset(pos, heading, speedMps)
        }
    }
}
