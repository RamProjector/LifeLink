package com.lifelink.app.core.location

import android.view.MotionEvent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
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
    latitude: Double?,
    longitude: Double?,
    onLocationSelected: (Double, Double) -> Unit,
    recenterRequest: Int = 0,
    onLoadingChanged: (Boolean) -> Unit = {},
    onMapError: (String) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val latestLatitude by rememberUpdatedState(latitude)
    val latestLongitude by rememberUpdatedState(longitude)
    var marker by remember { mutableStateOf<Marker?>(null) }
    var appliedLatitude by remember { mutableStateOf<Double?>(null) }
    var appliedLongitude by remember { mutableStateOf<Double?>(null) }
    var appliedRecenterRequest by remember { mutableStateOf(-1) }
    var mapLoading by remember { mutableStateOf(true) }
    var mapError by remember { mutableStateOf<String?>(null) }
    var retryRequest by remember { mutableStateOf(0) }
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

    Box(modifier.fillMaxWidth().heightIn(min = 260.dp)) {
        AndroidView(
            modifier = Modifier.fillMaxWidth().heightIn(min = 260.dp),
            factory = {
                mapView.apply {
                addOnDidFailLoadingMapListener {
                    mapLoading = false
                    mapError = "Map preview unavailable. You can still enter an approximate location manually."
                    onLoadingChanged(false)
                    onMapError("Map tiles could not be loaded. Your location is still saved; retry the preview or use the full map.")
                }
                setOnTouchListener { view, event ->
                    when (event.actionMasked) {
                        MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> view.parent?.requestDisallowInterceptTouchEvent(true)
                        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> view.parent?.requestDisallowInterceptTouchEvent(false)
                    }
                    false
                }
                getMapAsync { map ->
                    mapLoading = true
                    mapError = null
                    onLoadingChanged(true)
                    map.setStyle(OPEN_FREE_MAP_STYLE) {
                        mapLoading = false
                        mapError = null
                        onLoadingChanged(false)
                        val currentPosition = latestLatitude?.let { lat -> latestLongitude?.let { lon -> LatLng(lat, lon) } }
                        map.cameraPosition = CameraPosition.Builder()
                            .target(currentPosition ?: LatLng(0.0, 0.0))
                            .zoom(if (currentPosition == null) 2.0 else 15.0)
                            .build()
                        appliedLatitude = latestLatitude
                        appliedLongitude = latestLongitude
                        appliedRecenterRequest = recenterRequest
                        marker = currentPosition?.let {
                            map.addMarker(MarkerOptions().position(it).title("Selected approximate location"))
                        }
                        fun select(position: LatLng) {
                            appliedLatitude = position.latitude
                            appliedLongitude = position.longitude
                            marker?.let { it.position = position; map.updateMarker(it) }
                                ?: run { marker = map.addMarker(MarkerOptions().position(position).title("Selected approximate location")) }
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
                    val target = latitude?.let { lat -> longitude?.let { lon -> LatLng(lat, lon) } }
                    if (retryRequest > 0) {
                        mapLoading = true
                        mapError = null
                        map.setStyle(OPEN_FREE_MAP_STYLE) { mapLoading = false }
                        retryRequest = 0
                    }
                    if (target != null && (appliedLatitude != latitude || appliedLongitude != longitude || appliedRecenterRequest != recenterRequest)) {
                        appliedLatitude = latitude
                        appliedLongitude = longitude
                        appliedRecenterRequest = recenterRequest
                        marker?.let { it.position = target; map.updateMarker(it) }
                        map.cameraPosition = CameraPosition.Builder()
                            .target(target)
                            .zoom(map.cameraPosition.zoom.coerceAtLeast(12.0))
                            .build()
                    }
                }
            }
        )
        when {
            mapLoading -> Card(
                Modifier.align(androidx.compose.ui.Alignment.Center).padding(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                RowLoading()
            }
            mapError != null -> Card(
                Modifier.align(androidx.compose.ui.Alignment.Center).padding(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
            ) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(mapError.orEmpty(), color = MaterialTheme.colorScheme.onErrorContainer, style = MaterialTheme.typography.bodySmall)
                    Button(onClick = { retryRequest++ }) { Text("Retry map") }
                }
            }
        }
    }
}

@Composable
private fun RowLoading() {
    androidx.compose.foundation.layout.Row(
        Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
    ) {
        CircularProgressIndicator(Modifier.height(20.dp), strokeWidth = 2.dp)
        Text("Loading map preview…", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
fun MapLibrePrivacySafeDonorMap(
    latitude: Double,
    longitude: Double,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val center = remember(latitude, longitude) { LatLng(latitude, longitude) }
    var mapLoading by remember { mutableStateOf(true) }
    var mapError by remember { mutableStateOf(false) }
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
    Box(modifier.fillMaxWidth().height(280.dp)) {
        AndroidView(
            modifier = Modifier.fillMaxWidth().height(280.dp),
            factory = {
                mapView.apply {
                    addOnDidFailLoadingMapListener {
                        mapLoading = false
                        mapError = true
                    }
                getMapAsync { map ->
                    mapLoading = true
                    map.setStyle(OPEN_FREE_MAP_STYLE) {
                        mapLoading = false
                        mapError = false
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
        when {
            mapLoading -> Card(
                Modifier.align(androidx.compose.ui.Alignment.Center).padding(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) { RowLoading() }
            mapError -> Card(
                Modifier.align(androidx.compose.ui.Alignment.Center).padding(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
            ) {
                Text("Map preview unavailable. Donor results remain available in the list.", Modifier.padding(14.dp), color = MaterialTheme.colorScheme.onErrorContainer, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
