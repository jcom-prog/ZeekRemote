package com.openzeekr.app.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.MotionEvent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Navigation
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.foundation.background
import com.openzeekr.app.Deps
import com.openzeekr.app.remote.CallResult
import com.openzeekr.app.ui.theme.Brand
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapView

/**
 * Free, key-less map (MapLibre GL + OpenFreeMap vector tiles) centred on the car.
 * The pin is drawn as a Compose overlay (no annotation plugin needed). The MapView's
 * lifecycle is tied to composition — created when this screen is shown, destroyed when
 * it leaves (the tab pager composes a page only while it is on screen).
 */
private const val OPENFREEMAP_STYLE = "https://tiles.openfreemap.org/styles/liberty"

@Composable
fun CarMap(lat: Double?, lng: Double?, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val mapView = remember {
        MapLibre.getInstance(ctx)
        MapView(ctx).apply {
            onCreate(null)
            // A drag that starts on the map pans the map: without this the tab pager (swipe to the next
            // tab, 0.3.3) and the page's vertical scroll would take the gesture over.
            setOnTouchListener { view, event ->
                if (event.actionMasked == MotionEvent.ACTION_DOWN) view.parent?.requestDisallowInterceptTouchEvent(true)
                false
            }
        }
    }
    DisposableEffect(Unit) {
        mapView.onStart(); mapView.onResume()
        onDispose { mapView.onPause(); mapView.onStop(); mapView.onDestroy() }
    }
    Box(modifier) {
        AndroidView(factory = { mapView }, modifier = Modifier.matchParentSize()) { mv ->
            mv.getMapAsync { map ->
                map.setStyle(OPENFREEMAP_STYLE)
                if (lat != null && lng != null) {
                    map.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(lat, lng), 15.5))
                }
            }
        }
        if (lat != null && lng != null) {
            // Pin: tip sits at the map centre (offset up by half its height).
            Icon(
                Icons.Filled.LocationOn, contentDescription = "Car location",
                tint = Brand.accent,
                modifier = Modifier.align(Alignment.Center).size(38.dp).offset(y = (-19).dp),
            )
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

/** "Where's my car" — the map centred on the parked car + a Navigate-to-car deeplink. */
@Composable
fun CarLocationSection(deps: Deps, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    var pos by remember { mutableStateOf<Pair<Double, Double>?>(null) }
    LaunchedEffect(Unit) {
        when (val r = deps.control.status()) {
            is CallResult.Ok -> {
                val p = r.value.basicVehicleStatus?.position
                val la = p?.latitude; val lo = p?.longitude
                if (la != null && lo != null) pos = la to lo
            }
            is CallResult.Err -> {}
        }
    }
    CockpitCard(modifier) {
        SectionHeader("Where's my car")
        Box(Modifier.fillMaxWidth().height(190.dp).clip(RoundedCornerShape(14.dp))) {
            CarMap(pos?.first, pos?.second, Modifier.matchParentSize())
        }
        if (pos != null) PrimaryButton("Navigate to car", Modifier.fillMaxWidth(), tint = Brand.good) { navigateToCar(ctx, pos!!.first, pos!!.second) }
        else GhostButton("Location unavailable", Modifier.fillMaxWidth(), enabled = false, tint = Brand.muted) {}
    }
    // How to send a destination to the car's navigation (SendToCarActivity is a share target, there
    // is no button for it). Moved here from Security, where its English one-liner was unclear (12/10).
    CockpitCard(Modifier.fillMaxWidth()) {
        SectionHeader("Bestemming naar de auto sturen")
        Text("Open een plek in Google Maps, tik op Delen en kies \"Send to Zeekr\". Na bevestigen " +
            "staat de bestemming in de navigatie van de auto.", color = Brand.muted, fontSize = 12.sp)
    }
}
