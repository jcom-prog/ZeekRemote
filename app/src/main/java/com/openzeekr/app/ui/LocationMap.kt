package com.openzeekr.app.ui

import android.content.Context
import android.content.Intent
import android.location.Geocoder
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
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Satellite
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.RasterLayer
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.RasterSource
import org.maplibre.android.style.sources.TileSet
import java.util.Locale

/**
 * Free, key-less map (MapLibre GL + OpenFreeMap vector tiles) centred on the car. The pin is a Compose
 * overlay that follows the car's point through the map's projection, so it stays on the car while the
 * map is zoomed or panned (0.4.0e; before it sat fixed in the centre of the view). The MapView's lifecycle
 * is tied to composition — created when this screen is shown, destroyed when it leaves.
 */
private const val OPENFREEMAP_STYLE = "https://tiles.openfreemap.org/styles/liberty"
/** The small preview in the Location tab. */
private const val PREVIEW_ZOOM = 16.0
/** The fullscreen map opens close and tilted (0.4.0f): house numbers appear from zoom 17, the building blocks with the tilt. */
private const val FULL_ZOOM = 18.0
private const val FULL_TILT = 45.0
/** 0.4.0f: road names on the fullscreen map are drawn this much larger than the style's own size. */
private const val ROAD_LABEL_SCALE = 1.2f
/** The pin: a dark badge with a car icon, as the stock app draws its car marker (0.4.0f). */
private val PIN_DARK = Color(0xFF202124)
/**
 * 0.4.0f: the Dutch government's aerial photography (PDOK, open data CC BY 4.0, no key; the Netherlands only) as
 * web-mercator WMTS tiles (25 cm). Shown on request under the map's labels; outside the coverage the map stays the map.
 */
private const val PDOK_LUCHTFOTO_TILES = "https://service.pdok.nl/hwh/luchtfotorgb/wmts/v1_0?SERVICE=WMTS&REQUEST=GetTile&VERSION=1.0.0" +
    "&LAYER=Actueel_ortho25&TILEMATRIXSET=EPSG:3857&TILEMATRIX={z}&TILEROW={y}&TILECOL={x}&FORMAT=image/jpeg"
private const val AERIAL_ID = "pdok-luchtfoto"
private const val AERIAL_ATTRIBUTION = "Luchtfoto: PDOK (CC BY 4.0)"
/** App-private preference: the owner's last choice for the aerial photo on the fullscreen map. */
private const val MAP_PREFS = "car_map"
private const val AERIAL_PREF = "aerial"

private enum class MapLoad { LOADING, READY, FAILED }

/**
 * [interactive] false: a still preview whose touches go to [onTap] (the fullscreen map); true: pan and
 * pinch-zoom on the map itself. [onMap] hands the map out once it is ready (the fullscreen buttons use it).
 */
@Composable
fun CarMap(
    lat: Double?, lng: Double?, interactive: Boolean, modifier: Modifier = Modifier,
    zoom: Double = PREVIEW_ZOOM, tilt: Double = 0.0, onTap: (() -> Unit)? = null, onMap: ((MapLibreMap) -> Unit)? = null,
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
                // 0.4.0f: the fullscreen map draws road names larger than the style does (the preview keeps the style's size).
                m.setStyle(OPENFREEMAP_STYLE) { style -> if (interactive) CarMapStyle.enlargeRoadLabels(style, ROAD_LABEL_SCALE) }
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
        m.moveCamera(CameraUpdateFactory.newCameraPosition(CameraPosition.Builder().target(car).zoom(zoom).tilt(tilt).build()))
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
            // The pin's tip sits on the car's point: the badge is placed by its bottom centre (0.4.0f: a dark pin
            // with a car icon in its head, like the stock app's marker).
            val iconPx = with(density) { 44.dp.toPx() }
            val (x, y) = CarMapSupport.pinTopLeft(p.x, p.y, iconPx)
            Box(Modifier.offset { IntOffset(x, y) }.size(44.dp)) {
                Icon(Icons.Filled.LocationOn, contentDescription = "Car location", tint = PIN_DARK, modifier = Modifier.size(44.dp))
                Icon(Icons.Filled.DirectionsCar, contentDescription = null, tint = Color.White,
                    modifier = Modifier.align(Alignment.TopCenter).padding(top = 8.dp).size(16.dp))
            }
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

/** A round map control (zoom in/out, centre on the car, aerial photo, close); [active] inverts its colours. */
@Composable
private fun MapButton(icon: androidx.compose.ui.graphics.vector.ImageVector, description: String, active: Boolean = false, onClick: () -> Unit) {
    Box(
        Modifier.size(46.dp).clip(RoundedCornerShape(23.dp)).background((if (active) Brand.accent else Brand.surface2).copy(alpha = 0.92f))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, contentDescription = description, tint = if (active) Color.White else Brand.accent, modifier = Modifier.size(24.dp)) }
}

/**
 * The fullscreen map (0.4.0e): pinch and drag, zoom buttons, "centre on the car", Navigate to car and close.
 * Its own MapView; the preview underneath keeps its own.
 */
@Composable
private fun CarMapDialog(lat: Double, lng: Double, ageText: String?, addressText: String?, onClose: () -> Unit, onNavigate: () -> Unit) {
    var map by remember { mutableStateOf<MapLibreMap?>(null) }
    val prefs = LocalContext.current.getSharedPreferences(MAP_PREFS, Context.MODE_PRIVATE)
    var aerial by remember { mutableStateOf(prefs.getBoolean(AERIAL_PREF, false)) }
    // The aerial photo follows the switch once the map and its style are there (getStyle waits for the style).
    LaunchedEffect(map, aerial) {
        val m = map ?: return@LaunchedEffect
        m.getStyle { style -> CarMapStyle.setAerial(style, aerial) }
    }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(Brand.surface2)) {
            CarMap(lat, lng, interactive = true, modifier = Modifier.fillMaxSize(), zoom = FULL_ZOOM, tilt = FULL_TILT, onMap = { map = it })
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
                    if (addressText != null) Text(addressText, color = Color.White, fontSize = 12.sp)
                    if (ageText != null) Text(ageText, color = Brand.muted, fontSize = 12.sp)
                }
                MapButton(Icons.Filled.Close, "Sluiten", onClick = onClose)
            }
            // Zoom and centre, right.
            Column(
                Modifier.align(Alignment.CenterEnd).padding(end = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                MapButton(Icons.Filled.Add, "Inzoomen") { map?.animateCamera(CameraUpdateFactory.zoomIn()) }
                MapButton(Icons.Filled.Remove, "Uitzoomen") { map?.animateCamera(CameraUpdateFactory.zoomOut()) }
                MapButton(Icons.Filled.MyLocation, "Centreer op de auto") {
                    map?.animateCamera(CameraUpdateFactory.newCameraPosition(
                        CameraPosition.Builder().target(LatLng(lat, lng)).zoom(FULL_ZOOM).tilt(FULL_TILT).build()))
                }
                MapButton(Icons.Filled.Satellite, if (aerial) "Luchtfoto uit" else "Luchtfoto aan", active = aerial) {
                    aerial = !aerial
                    prefs.edit().putBoolean(AERIAL_PREF, aerial).apply()
                }
            }
            if (aerial) {
                Box(
                    Modifier.align(Alignment.BottomStart).padding(start = 12.dp, bottom = 76.dp).clip(RoundedCornerShape(8.dp))
                        .background(Color.Black.copy(alpha = 0.55f)).padding(horizontal = 8.dp, vertical = 4.dp),
                ) { Text(AERIAL_ATTRIBUTION, color = Color.White, fontSize = 11.sp) }
            }
            // Navigate, bottom (the same hand-off as the button in the tab).
            Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(16.dp)) {
                PrimaryButton("Navigate to car", Modifier.fillMaxWidth(), tint = Brand.good, onClick = onNavigate)
            }
        }
    }
}

/** 0.4.0f: runtime tweaks to the loaded map style. */
private object CarMapStyle {
    /** The aerial photo under the style's labels (a raster layer below the first symbol layer), or removed again. */
    fun setAerial(style: Style, on: Boolean) {
        val present = style.getLayer(AERIAL_ID) != null
        if (on && !present) {
            if (style.getSource(AERIAL_ID) == null) {
                val tiles = TileSet("2.2.0", PDOK_LUCHTFOTO_TILES).apply { attribution = AERIAL_ATTRIBUTION; maxZoom = 19f }
                style.addSource(RasterSource(AERIAL_ID, tiles, 256))
            }
            val firstSymbol = style.layers.firstOrNull { it is SymbolLayer }?.id
            val layer = RasterLayer(AERIAL_ID, AERIAL_ID)
            if (firstSymbol != null) style.addLayerBelow(layer, firstSymbol) else style.addLayer(layer)
        } else if (!on && present) {
            style.removeLayer(AERIAL_ID)
            style.removeSource(AERIAL_ID)
        }
        // 0.4.0g: the style's building layers (footprints and the 3D blocks) draw over the photo - MapLibre renders
        // extrusions in their own pass, whatever the layer order (owner screenshot 24/10) - so they are hidden while
        // the photo is on; the photo shows the real roofs. Restored when the photo goes off.
        style.layers.forEach { layer ->
            if (CarMapSupport.isBuildingLayer(layer.id))
                layer.setProperties(PropertyFactory.visibility(if (on) Property.NONE else Property.VISIBLE))
        }
    }

    /** Draws the style's road-name layers [scale] times larger (a constant size or a zoom expression, both kept). */
    fun enlargeRoadLabels(style: Style, scale: Float) {
        style.layers.forEach { layer ->
            if (layer !is SymbolLayer || !CarMapSupport.isRoadLabelLayer(layer.id)) return@forEach
            val size = layer.textSize
            when {
                size.isExpression -> size.expression?.let { e ->
                    layer.setProperties(PropertyFactory.textSize(Expression.product(Expression.literal(scale), e)))
                }
                size.value != null -> layer.setProperties(PropertyFactory.textSize(size.value!! * scale))
            }
        }
    }
}

/**
 * 0.4.0f: the car's street address from the phone's own geocoder (no key). The coordinates go to the phone's
 * geocoding service for this, as the map's area goes to the tile server; the answer is shown only, never stored or
 * logged. null without a geocoder, without network or without a match.
 */
private suspend fun fetchCarAddress(context: Context, lat: Double, lng: Double): String? = withContext(Dispatchers.IO) {
    if (!Geocoder.isPresent()) return@withContext null
    val lines = runCatching {
        @Suppress("DEPRECATION")
        Geocoder(context, Locale.getDefault()).getFromLocation(lat, lng, 1)
    }.getOrNull()?.firstOrNull()?.let { a -> (0..a.maxAddressLineIndex).map { a.getAddressLine(it) } } ?: emptyList()
    CarMapSupport.addressLine(lines)
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
    var address by remember { mutableStateOf<String?>(null) }   // 0.4.0f: the street address from the phone's geocoder
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
    LaunchedEffect(pos) {
        val p = pos ?: return@LaunchedEffect
        address = fetchCarAddress(ctx, p.first, p.second)
    }
    val ageText = CarMapSupport.positionAgeText(updatedAt, System.currentTimeMillis())
    CockpitCard(modifier) {
        SectionHeader("Where's my car")
        Box(Modifier.fillMaxWidth().height(220.dp).clip(RoundedCornerShape(14.dp))) {
            CarMap(pos?.first, pos?.second, interactive = false, modifier = Modifier.matchParentSize(),
                onTap = if (pos != null) ({ showFull = true }) else null)
        }
        if (address != null) Text("Positie van de auto: $address", color = Color.White, fontSize = 13.sp)
        Text(
            if (pos != null) "Tik op de kaart om te vergroten en in te zoomen." + (ageText?.let { " Positie volgens de cloud: $it." } ?: "")
            else "De positie komt uit de cloudstatus van de auto.",
            color = Brand.muted, fontSize = 12.sp,
        )
        if (pos != null) PrimaryButton("Navigate to car", Modifier.fillMaxWidth(), tint = Brand.good) { navigateToCar(ctx, pos!!.first, pos!!.second) }
        else GhostButton("Location unavailable", Modifier.fillMaxWidth(), enabled = false, tint = Brand.muted) {}
    }
    if (showFull && pos != null) {
        CarMapDialog(pos!!.first, pos!!.second, ageText?.let { "Positie volgens de cloud: $it" }, address?.let { "Positie van de auto: $it" },
            onClose = { showFull = false }, onNavigate = { navigateToCar(ctx, pos!!.first, pos!!.second) })
    }
    // How to send a destination to the car's navigation (SendToCarActivity is a share target, there
    // is no button for it). Moved here from Security, where its English one-liner was unclear (12/10).
    CockpitCard(Modifier.fillMaxWidth()) {
        SectionHeader("Bestemming naar de auto sturen")
        Text("Open een plek in Google Maps, tik op Delen en kies \"Send to Zeekr\". ZeekRemote leest de plek, " +
            "vraagt om bevestiging en meldt of de bestemming is verzonden; daarna staat hij in de navigatie van de auto.",
            color = Brand.muted, fontSize = 12.sp)
    }
}
