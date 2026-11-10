package com.sentra.ui

import android.annotation.SuppressLint
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.drawable.GradientDrawable
import android.view.MotionEvent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polygon
import org.osmdroid.views.overlay.Polyline
import com.sentra.nav.GeoPoint as LatLon
import org.osmdroid.util.GeoPoint as OsmPoint

/** What the map draws. Colors are ARGB ints. */
data class MapContent(
    /** The child (or, when recording, the companion) right now. */
    val marker: LatLon? = null,
    val accuracyM: Float? = null,
    val markerColor: Int = BLUE,
    /** Where the child has walked, oldest first. */
    val track: List<LatLon> = emptyList(),
    /** The recorded route: start, turns, destination. */
    val route: List<LatLon> = emptyList(),
) {
    companion object {
        const val BLUE = 0xFF4F8CFF.toInt()
        const val GREEN = 0xFF3FB950.toInt()
        const val RED = 0xFFF85149.toInt()
        const val GREY = 0xFF8D96A0.toInt()
    }
}

/**
 * An OpenStreetMap view (osmdroid: no API key, no billing). While [follow] is on the map stays
 * centred on the marker; dragging the map calls [onUserMoved] so the caller can switch it off.
 */
@SuppressLint("ClickableViewAccessibility")
@Composable
fun OsmMap(content: MapContent, modifier: Modifier = Modifier, follow: Boolean = true, onUserMoved: () -> Unit = {}) {
    val context = LocalContext.current
    val density = LocalDensity.current.density
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val state = remember { MapState() }
    val map = remember {
        MapView(context).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            zoomController.setVisibility(CustomZoomButtonsController.Visibility.SHOW_AND_FADEOUT)
            minZoomLevel = 4.0
            controller.setZoom(17.5)
            // Bandung until the first position arrives, so the map never opens on the ocean at 0°, 0°.
            controller.setCenter(OsmPoint(-6.9025, 107.6188))
            contentDescription = "Peta"
        }
    }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_RESUME) map.onResume() else if (e == Lifecycle.Event.ON_PAUSE) map.onPause()
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            map.onDetach()
        }
    }
    AndroidView(
        factory = { map },
        modifier = modifier,
        update = { view ->
            view.setOnTouchListener { _, ev ->
                if (ev.actionMasked == MotionEvent.ACTION_MOVE) onUserMoved()
                false
            }
            state.draw(view, content, density)
            val at = content.marker
            when {
                at != null && (follow || !state.placed) -> {
                    val p = OsmPoint(at.lat, at.lon)
                    if (state.placed) view.controller.animateTo(p) else view.controller.setCenter(p)
                    state.placed = true
                }
                at == null && content.route.size >= 2 && state.fittedRoute != content.route -> {
                    // No position yet: show the whole route. Needs the view's size, so after layout.
                    state.fittedRoute = content.route
                    val box = BoundingBox.fromGeoPoints(content.route.map { OsmPoint(it.lat, it.lon) })
                    view.post { runCatching { view.zoomToBoundingBox(box.increaseByScale(1.4f), false) } }
                    state.placed = true
                }
            }
        },
    )
}

/** The overlays, rebuilt when the content changes. */
private class MapState {
    var placed = false
    var fittedRoute: List<LatLon>? = null
    private var last: MapContent? = null

    fun draw(map: MapView, c: MapContent, density: Float) {
        if (c == last) return
        last = c
        map.overlays.clear()
        if (c.route.size >= 2) {
            map.overlays += Polyline(map).apply {
                setPoints(c.route.map { OsmPoint(it.lat, it.lon) })
                outlinePaint.color = MapContent.BLUE
                outlinePaint.alpha = 170
                outlinePaint.strokeWidth = 5 * density
                outlinePaint.strokeCap = Paint.Cap.ROUND
                outlinePaint.pathEffect = DashPathEffect(floatArrayOf(10 * density, 8 * density), 0f)
                setInfoWindow(null)
            }
        }
        c.route.forEachIndexed { i, p ->
            map.overlays += Marker(map).apply {
                position = OsmPoint(p.lat, p.lon)
                icon = dot(MapContent.BLUE, Color.WHITE, (if (i == 0 || i == c.route.lastIndex) 16 else 12) * density, 2 * density, filled = i == c.route.lastIndex)
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                title = when (i) {
                    0 -> "Titik mulai"
                    c.route.lastIndex -> "Tujuan"
                    else -> "Titik ${i + 1}"
                }
            }
        }
        if (c.track.size >= 2) {
            map.overlays += Polyline(map).apply {
                setPoints(c.track.map { OsmPoint(it.lat, it.lon) })
                outlinePaint.color = MapContent.GREEN
                outlinePaint.strokeWidth = 4 * density
                outlinePaint.strokeCap = Paint.Cap.ROUND
                setInfoWindow(null)
            }
        }
        val at = c.marker
        if (at != null) {
            val p = OsmPoint(at.lat, at.lon)
            val acc = c.accuracyM
            if (acc != null && acc > 3) {
                map.overlays += Polygon(map).apply {
                    points = Polygon.pointsAsCircle(p, acc.toDouble())
                    fillPaint.color = (c.markerColor and 0x00FFFFFF) or 0x22000000
                    outlinePaint.color = c.markerColor
                    outlinePaint.strokeWidth = 1 * density
                    setInfoWindow(null)
                }
            }
            map.overlays += Marker(map).apply {
                position = p
                icon = dot(c.markerColor, Color.WHITE, 22 * density, 3 * density, filled = true)
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                setInfoWindow(null)
            }
        }
        map.invalidate()
    }

    private fun dot(color: Int, ring: Int, sizePx: Float, ringPx: Float, filled: Boolean) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(if (filled) color else ring)
        setStroke(ringPx.toInt().coerceAtLeast(1), if (filled) ring else color)
        setSize(sizePx.toInt(), sizePx.toInt())
        setBounds(0, 0, sizePx.toInt(), sizePx.toInt())
    }
}
