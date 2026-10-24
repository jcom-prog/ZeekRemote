package com.openzeekr.app.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.MotionEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.openzeekr.app.Deps
import com.openzeekr.app.remote.CallResult
import com.openzeekr.app.ui.theme.Brand
import com.openzeekr.app.util.CarMapSupport
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import kotlin.math.roundToInt

/**
 * Free, key-less map (MapLibre GL + OpenFreeMap vector tiles) centred on the car. The pin is a Compose
 * overlay that follows the car's point through the map's projection, so it stays on the car while the
 * map is zoomed or panned (0.4.0e; before it sat fixed in the centre of the view). The MapView's lifecycle
 * is tied to composition — created when this screen is shown, destroyed when it leaves.
 */
private const val OPENFREEMAP_STYLE = "https://tiles.openfreemap.org/styles/liberty"
/** The small preview in the Location tab. */
private const val PREVIEW_ZOOM = 16.0
/** The fullscreen map opens closer; the owner can zoom further in or out from there. */
private const val FULL_ZOOM = 17.5

private enum class MapLoad { LOADING, READY, FAILED }

/**
 * [interactive] false: a still preview whose touches go to [onTap] (the fullscreen map); true: pan and
 * pinch-zoom on the map itself. [onMap] hands the map out once it is ready (the fullscreen buttons use it).
 */
@Composable
fun CarMap(
    lat: Double?, lng: Double?, interactive: Boolean, modifier: Modifier = Modifier,
    zoom: Double = PREVIEW_ZOOM, onTap: (() -> Unit)? = null, onMap: ((MapLibreMap) -> Unit)? = null,
) {
    val ctx = LocalContext.current
    val density = LocalDensity.current
    var load by remember { mutableStateOf(MapLoad.LOADING) }
    var map by remember { mutableStateOf<MapLibreMap?>(null) }
    var pin by remember { mutableStateOf<Offset?>(null) }   // the car's point on the view (px)
    val mapView = remember {
        MapLibre.getInstance(ctx)
        MapView(ctx).apply {
            onCreate(null)
            if (interactive) {
                // A drag that starts on the map pans the map: without this the tab pager (swipe to the
                // next tab, 0.3.3) and the page's vertical scroll would take the gesture over.
                setOnTouchListener { view, event ->
                    if (event.actionMasked == MotionEvent.ACTION_DOWN) view.parent?.requestDisallowInterceptTouchEvent(true)
                    false
                }
            }
            addOnDidFinishLoadingStyleListener { load = MapLoad.READY }
            addOnDidFailLoadingMapListener { load = MapLoad.FAILED }
            getMapAsync { m ->
                m.uiSettings.setAllGesturesEnabled(interactive)
                m.uiSettings.setCompassEnabled(interactive)
                m.setStyle(OPENFREEMAP_STYLE)
                map = m
            }
        }
    }
    DisposableEffect(Unit) {
        mapView.onStart(); mapView.onResume()
        onDispose { mapView.onPause(); mapView.onStop(); mapView.onDestroy() }
    }
    // Camera on the car, the pin following every camera move (listeners added once per map + position).
    LaunchedEffect(map, lat, lng) {
        val m = map ?: return@LaunchedEffect
        if (lat == null || lng == null) { pin = null; return@LaunchedEffect }
        val car = LatLng(lat, lng)
        m.moveCamera(CameraUpdateFactory.newLatLngZoom(car, zoom))
        val place = { val p = m.projection.toScreenLocation(car); pin = Offset(p.x, p.y) }
        place()
        m.addOnCameraMoveListener { place() }
        m.addOnCameraIdleListener { place() }
        onMap?.invoke(m)
    }
    Box(modifier) {
        AndroidView(factory = { mapView }, modifier = Modifier.matchParentSize())
        val p = pin
        if (p != null) {
            // The pin's tip sits on the car's point: the icon is placed by its bottom centre.
            val iconPx = with(density) { 40.dp.toPx() }
            val (x, y) = CarMapSupport.pinTopLeft(p.x, p.y, iconPx)
            Icon(
                Icons.Filled.LocationOn, contentDescription = "Car location", tint = Brand.accent,
                modifier = Modifier.offset { IntOffset(x, y) }.size(40.dp),
            )
        }
        if (load != MapLoad.READY) {
            Box(
                Modifier.align(Alignment.TopCenter).padding(top = 10.dp).clip(RoundedCornerShape(10.dp))
                    .background(Color.Black.copy(alpha = 0.55f)).padding(horizontal = 12.dp, vertical = 6.dp),
            ) {
                Text(
                    if (load == MapLoad.LOADING) "Kaart wordt geladen…" else "Kaart niet beschikbaar (geen verbinding?)",
                    color = Color.White, fontSize = 12.sp,
                )
            }
        }
        if (lat == null || lng == null) {
            Box(Modifier.align(Alignment.Center).clip(RoundedCornerShape(10.dp)).background(Color.Black.copy(alpha = 0.55f))
                .padding(horizontal = 12.dp, vertical = 6.dp)) {
                Text("Positie van de auto nog niet bekend", color = Color.White, fontSize = 12.sp)
            }
        }
        if (!interactive && onTap != null) {
            // The preview takes every touch itself (no map gesture fights the page) and opens the big map.
            Box(Modifier.matchParentSize().clickable(onClick = onTap))
            Box(
                Modifier.align(Alignment.BottomEnd).padding(8.dp).clip(RoundedCornerShape(10.dp))
                    .background(Color.Black.copy(alpha = 0.55f)).padding(horizontal = 10.dp, vertical = 5.dp),
            ) { Text("Tik om te vergroten", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.SemiBold) }
        }
    }
}

/** A round map control (zoom in/out, centre on the car, close). */
@Composable
private fun MapButton(icon: androidx.compose.ui.graphics.vector.ImageVector, description: String, onClick: () -> Unit) {
    Box(
        Modifier.size(46.dp).clip(RoundedCornerShape(23.dp)).background(Brand.surface2.copy(alpha = 0.92f))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, contentDescription = description, tint = Brand.accent, modifier = Modifier.size(24.dp)) }
}

/**
 * The fullscreen map (0.4.0e): pinch and drag, zoom buttons, "centre on the car", Navigate to car and close.
 * Its own MapView; the preview underneath keeps its own.
 */
@Composable
private fun CarMapDialog(lat: Double, lng: Double, ageText: String?, onClose: () -> Unit, onNavigate: () -> Unit) {
    var map by remember { mutableStateOf<MapLibreMap?>(null) }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(Brand.surface2)) {
            CarMap(lat, lng, interactive = true, modifier = Modifier.fillMaxSize(), zoom = FULL_ZOOM, onMap = { map = it })
            // Title and close, top.
            Row(
                Modifier.align(Alignment.TopStart).fillMaxWidth().padding(12.dp),
                horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(
                    Modifier.clip(RoundedCornerShape(12.dp)).background(Brand.surface2.copy(alpha = 0.92f))
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                ) {
                    Text("Waar staat mijn auto", color = Brand.accent, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    if (ageText != null) Text(ageText, color = Brand.muted, fontSize = 12.sp)
                }
                MapButton(Icons.Filled.Close, "Sluiten", onClose)
            }
            // Zoom and centre, right.
            Column(
                Modifier.align(Alignment.CenterEnd).padding(end = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                MapButton(Icons.Filled.Add, "Inzoomen") { map?.animateCamera(CameraUpdateFactory.zoomIn()) }
                MapButton(Icons.Filled.Remove, "Uitzoomen") { map?.animateCamera(CameraUpdateFactory.zoomOut()) }
                MapButton(Icons.Filled.MyLocation, "Centreer op de auto") {
                    map?.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(lat, lng), FULL_ZOOM))
                }
            }
            // Navigate, bottom (the same hand-off as the button in the tab).
            Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(16.dp)) {
                PrimaryButton("Navigate to car", Modifier.fillMaxWidth(), tint = Brand.good, onClick = onNavigate)
            }
        }
    }
}

/**
 * Hand off turn-by-turn navigation to the phone's nav app (free): Google Maps
 * navigation intent first, plain geo: fallback otherwise.
 */
fun navigateToCar(context: Context, lat: Double, lng: Double, label: String = "My Zeekr") {
    val nav = Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=$lat,$lng"))
        .setPackage("com.google.android.apps.maps")
    val geo = Intent(Intent.ACTION_VIEW, Uri.parse("geo:$lat,$lng?q=$lat,$lng(${Uri.encode(label)})"))
    runCatching { context.startActivity(nav) }
        .recoverCatching { context.startActivity(geo) }
}

/** "Where's my car" — the map centred on the parked car (tap: fullscreen, zoomable) + a Navigate-to-car deeplink. */
@Composable
fun CarLocationSection(deps: Deps, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    var pos by remember { mutableStateOf<Pair<Double, Double>?>(null) }
    var updatedAt by remember { mutableStateOf<Long?>(null) }
    var showFull by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        when (val r = deps.control.status()) {
            is CallResult.Ok -> {
                val p = r.value.basicVehicleStatus?.position
                val la = p?.latitude; val lo = p?.longitude
                if (la != null && lo != null) { pos = la to lo; updatedAt = r.value.updateTime }
            }
            is CallResult.Err -> {}
        }
    }
    val ageText = CarMapSupport.positionAgeText(updatedAt, System.currentTimeMillis())
    CockpitCard(modifier) {
        SectionHeader("Where's my car")
        Box(Modifier.fillMaxWidth().height(220.dp).clip(RoundedCornerShape(14.dp))) {
            CarMap(pos?.first, pos?.second, interactive = false, modifier = Modifier.matchParentSize(),
                onTap = if (pos != null) ({ showFull = true }) else null)
        }
        Text(
            if (pos != null) "Tik op de kaart om te vergroten en in te zoomen." + (ageText?.let { " Positie volgens de cloud: $it." } ?: "")
            else "De positie komt uit de cloudstatus van de auto.",
            color = Brand.muted, fontSize = 12.sp,
        )
        if (pos != null) PrimaryButton("Navigate to car", Modifier.fillMaxWidth(), tint = Brand.good) { navigateToCar(ctx, pos!!.first, pos!!.second) }
        else GhostButton("Location unavailable", Modifier.fillMaxWidth(), enabled = false, tint = Brand.muted) {}
    }
    if (showFull && pos != null) {
        CarMapDialog(pos!!.first, pos!!.second, ageText?.let { "Positie volgens de cloud: $it" },
            onClose = { showFull = false }, onNavigate = { navigateToCar(ctx, pos!!.first, pos!!.second) })
    }
    // How to send a destination to the car's navigation (SendToCarActivity is a share target, there
    // is no button for it). Moved here from Security, where its English one-liner was unclear (12/10).
    CockpitCard(Modifier.fillMaxWidth()) {
        SectionHeader("Bestemming naar de auto sturen")
        Text("Open een plek in Google Maps, tik op Delen en kies \"Send to Zeekr\". Na bevestigen " +
            "staat de bestemming in de navigatie van de auto.", color = Brand.muted, fontSize = 12.sp)
    }
}
