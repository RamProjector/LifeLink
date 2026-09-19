package com.lifelink.app.core.location

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import org.maplibre.android.MapLibre
import org.maplibre.android.annotations.Marker
import org.maplibre.android.annotations.MarkerOptions
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapView

private const val OPEN_FREE_MAP_STYLE = "https://tiles.openfreemap.org/styles/liberty"

/**
 * Real native MapLibre map. No WebView or JavaScript is used. OpenFreeMap
 * supplies the documented Liberty vector-tile style; exact coordinates remain
 * private and only the selected approximate point is sent to LifeLink.
 */
@Composable
fun MapLibreLocationPicker(
    latitude: Double,
    longitude: Double,
    onLocationSelected: (Double, Double) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val initialPosition = remember { LatLng(latitude, longitude) }
    val mapView = remember {
        MapLibre.getInstance(context.applicationContext)
        MapView(context).also { it.onCreate(null) }
    }

    DisposableEffect(mapView) {
        mapView.onStart()
        mapView.onResume()
        onDispose {
            mapView.onPause()
            mapView.onStop()
            mapView.onDestroy()
        }
    }

    AndroidView(
        modifier = modifier.fillMaxWidth().height(260.dp),
        factory = {
            mapView.apply {
                getMapAsync { map ->
                    map.setStyle(OPEN_FREE_MAP_STYLE) {
                        map.cameraPosition = CameraPosition.Builder()
                            .target(initialPosition)
                            .zoom(15.0)
                            .build()
                        var marker: Marker? = map.addMarker(
                            MarkerOptions().position(initialPosition).title("Selected approximate location")
                        )
                        fun select(position: LatLng) {
                            marker?.let { map.removeMarker(it) }
                            marker = map.addMarker(
                                MarkerOptions().position(position).title("Selected approximate location")
                            )
                            onLocationSelected(position.latitude, position.longitude)
                        }
                        map.addOnMapClickListener { position ->
                            select(position)
                            true
                        }
                        map.addOnMapLongClickListener { position ->
                            select(position)
                            true
                        }
                    }
                }
            }
        },
        update = { view ->
            view.getMapAsync { map ->
                val target = LatLng(latitude, longitude)
                map.cameraPosition = CameraPosition.Builder()
                    .target(target)
                    .zoom(map.cameraPosition.zoom.coerceAtLeast(12.0))
                    .build()
            }
        }
    )
}

@Composable
fun MapLibrePrivacySafeDonorMap(
    latitude: Double,
    longitude: Double,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val center = remember(latitude, longitude) { LatLng(latitude, longitude) }
    val mapView = remember {
        MapLibre.getInstance(context.applicationContext)
        MapView(context).also { it.onCreate(null) }
    }
    DisposableEffect(mapView) {
        mapView.onStart()
        mapView.onResume()
        onDispose {
            mapView.onPause()
            mapView.onStop()
            mapView.onDestroy()
        }
    }
    AndroidView(
        modifier = modifier.fillMaxWidth().height(280.dp),
        factory = {
            mapView.apply {
                getMapAsync { map ->
                    map.setStyle(OPEN_FREE_MAP_STYLE) {
                        map.cameraPosition = CameraPosition.Builder().target(center).zoom(12.0).build()
                        map.addMarker(MarkerOptions().position(center).title("Your request location"))
                    }
                }
            }
        },
        update = { view ->
            view.getMapAsync { map ->
                map.cameraPosition = CameraPosition.Builder()
                    .target(LatLng(latitude, longitude))
                    .zoom(map.cameraPosition.zoom.coerceAtLeast(10.0))
                    .build()
            }
        }
    )
}
