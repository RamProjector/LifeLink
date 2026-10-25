@file:Suppress("DEPRECATION")

package com.lifelink.app.core.location

import android.view.MotionEvent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.lifelink.app.domain.DonorMapArea
import kotlinx.coroutines.delay
import org.maplibre.android.MapLibre
import org.maplibre.android.annotations.Marker
import org.maplibre.android.annotations.MarkerOptions
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapView

private const val OPEN_FREE_MAP_STYLE = "https://tiles.openfreemap.org/styles/liberty"

// If MapLibre never reports success or failure, the loading state must still end
// so the picker does not spin forever.
private const val MAP_LOAD_TIMEOUT_MS = 30_000L

/**
 * Real native MapLibre map. No WebView or JavaScript is used. OpenFreeMap
 * supplies the documented Liberty vector-tile style; exact coordinates remain
 * private and only the selected approximate point is sent to LifeLink.
 */
@Suppress("LongMethod", "CyclomaticComplexMethod")
@Composable
fun MapLibreLocationPicker(
    latitude: Double?,
    longitude: Double?,
    onLocationSelected: (Double, Double) -> Unit,
    recenterRequest: Int = 0,
    onLoadingChanged: (Boolean) -> Unit = {},
    onMapError: (String) -> Unit = {},
    modifier: Modifier = Modifier,
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
    var recenterBump by remember { mutableStateOf(0) }
    val effectiveRecenterRequest = recenterRequest + recenterBump
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

    // A stalled style load (no success and no failure callback) would otherwise
    // leave the loading UI visible indefinitely. End it with a retryable error.
    LaunchedEffect(mapLoading) {
        if (!mapLoading) return@LaunchedEffect
        delay(MAP_LOAD_TIMEOUT_MS)
        if (mapLoading) {
            mapLoading = false
            mapError = "The map is taking too long to load. You can enter coordinates manually or retry the map."
            onLoadingChanged(false)
            onMapError("Map tiles could not be loaded. You can enter coordinates manually or retry the map.")
        }
    }

    Box(modifier.fillMaxWidth().heightIn(min = 260.dp)) {
        AndroidView(
            modifier = Modifier.fillMaxWidth().heightIn(min = 260.dp),
            factory = {
                mapView.apply {
                    addOnDidFailLoadingMapListener {
                        mapLoading = false
                        mapError = "Map preview unavailable. You can enter coordinates manually or retry the map."
                        onLoadingChanged(false)
                        onMapError("Map tiles could not be loaded. You can enter coordinates manually or retry the map.")
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
                            currentPosition?.let { position ->
                                map.cameraPosition = CameraPosition.Builder()
                                    .target(position)
                                    .zoom(15.0)
                                    .build()
                            }
                            appliedLatitude = latestLatitude
                            appliedLongitude = latestLongitude
                            appliedRecenterRequest = effectiveRecenterRequest
                            marker = currentPosition?.let {
                                map.addMarker(MarkerOptions().position(it).title("Selected approximate location"))
                            }
                            fun select(position: LatLng) {
                                appliedLatitude = position.latitude
                                appliedLongitude = position.longitude
                                marker?.let {
                                    it.position = position
                                    map.updateMarker(it)
                                }
                                    ?: run {
                                        marker =
                                            map.addMarker(
                                                MarkerOptions().position(position).title("Selected approximate location"),
                                            )
                                    }
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
                    if (target != null) {
                        val targetChanged = appliedLatitude != latitude || appliedLongitude != longitude
                        val recenterChanged = appliedRecenterRequest != effectiveRecenterRequest
                        if (targetChanged || recenterChanged) {
                            appliedLatitude = latitude
                            appliedLongitude = longitude
                            appliedRecenterRequest = effectiveRecenterRequest
                            marker?.let {
                                it.position = target
                                map.updateMarker(it)
                            }
                            map.cameraPosition = CameraPosition.Builder()
                                .target(target)
                                .zoom(map.cameraPosition.zoom.coerceAtLeast(12.0))
                                .build()
                        }
                    }
                }
            },
        )
        when {
            mapLoading -> Card(
                Modifier.align(androidx.compose.ui.Alignment.Center).padding(16.dp).semantics { liveRegion = LiveRegionMode.Polite },
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 3.dp),
            ) {
                RowLoading()
            }
            mapError != null -> Card(
                Modifier.align(androidx.compose.ui.Alignment.Center).padding(16.dp).semantics { liveRegion = LiveRegionMode.Assertive },
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                elevation = CardDefaults.cardElevation(defaultElevation = 3.dp),
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        "Map unavailable",
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.semantics { heading() },
                    )
                    Text(mapError.orEmpty(), color = MaterialTheme.colorScheme.onErrorContainer, style = MaterialTheme.typography.bodySmall)
                    Button(onClick = { retryRequest++ }) { Text("Retry map") }
                }
            }
        }

        if (!mapLoading && mapError == null) {
            if (latitude != null && longitude != null) {
                IconButton(
                    onClick = { recenterBump++ },
                    modifier = Modifier.align(androidx.compose.ui.Alignment.BottomEnd).padding(12.dp),
                ) {
                    Icon(Icons.Default.MyLocation, contentDescription = "Recenter map on selected location")
                }
            }
        }
    }
}

@Composable
private fun RowLoading(label: String = "Loading map preview…") {
    androidx.compose.foundation.layout.Row(
        Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
    ) {
        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        Text(label, style = MaterialTheme.typography.bodySmall)
    }
}

@Suppress("LongMethod", "CyclomaticComplexMethod")
@Composable
fun MapLibrePrivacySafeDonorMap(
    latitude: Double,
    longitude: Double,
    areas: List<DonorMapArea> = emptyList(),
    modifier: Modifier = Modifier,
    fullScreen: Boolean = false,
) {
    val context = LocalContext.current
    val center = remember(latitude, longitude) { LatLng(latitude, longitude) }
    var mapLoading by remember { mutableStateOf(true) }
    var mapError by remember { mutableStateOf(false) }
    var mapStyleLoaded by remember { mutableStateOf(false) }
    var mapRendered by remember { mutableStateOf(false) }
    var retryRequest by remember { mutableStateOf(0) }
    var recenterRequest by remember { mutableStateOf(0) }
    var appliedRecenterRequest by remember { mutableStateOf(-1) }
    var appliedCenter by remember { mutableStateOf<LatLng?>(null) }
    var appliedAreasSignature by remember { mutableStateOf("") }
    var appliedAreasCount by remember { mutableStateOf(0) }
    var renderedAreasSignature by remember { mutableStateOf("") }
    val areasSignature = remember(areas) {
        areas.joinToString(separator = "|") { area ->
            "${area.latitude},${area.longitude},${area.bloodType},${area.availability},${area.freshnessAt}"
        }
    }
    val mapView = remember {
        MapLibre.getInstance(context.applicationContext)
        MapView(context).also { it.onCreate(null) }
    }
    // A style callback alone does not prove that the native map has rendered its
    // first complete frame. Keep loading visible until MapLibre confirms rendering.
    val mapFailedListener = remember(mapView) {
        MapView.OnDidFailLoadingMapListener {
            mapStyleLoaded = false
            mapRendered = false
            mapLoading = false
            mapError = true
        }
    }
    val mapRenderedListener = remember(mapView) {
        MapView.OnDidFinishRenderingMapListener { fully ->
            if (fully && mapStyleLoaded) {
                renderedAreasSignature = appliedAreasSignature
                mapRendered = true
                mapLoading = false
                mapError = false
            }
        }
    }
    DisposableEffect(mapView, mapFailedListener, mapRenderedListener) {
        mapView.onStart()
        mapView.onResume()
        onDispose {
            mapView.removeOnDidFailLoadingMapListener(mapFailedListener)
            mapView.removeOnDidFinishRenderingMapListener(mapRenderedListener)
            mapView.onPause()
            mapView.onStop()
            mapView.onDestroy()
        }
    }
    // Mirror the picker's guard: a stalled style load must not spin forever.
    LaunchedEffect(mapLoading) {
        if (!mapLoading) return@LaunchedEffect
        delay(MAP_LOAD_TIMEOUT_MS)
        if (mapLoading) {
            mapLoading = false
            mapRendered = false
            mapError = true
        }
    }
    val mapModifier = if (fullScreen) modifier.fillMaxSize() else modifier.fillMaxWidth().height(280.dp)
    Box(
        mapModifier
            .testTag("maplibre-map-surface")
            .semantics {
                contentDescription = if (fullScreen) "Donor map" else "Location map preview"
                stateDescription = when {
                    mapError && fullScreen -> "Map tiles unavailable; ${appliedAreasCount} approximate donor pins"
                    mapLoading && fullScreen -> "Loading map tiles; ${appliedAreasCount} approximate donor pins"
                    mapRendered && renderedAreasSignature == appliedAreasSignature && fullScreen ->
                        "Map tiles rendered; ${appliedAreasCount} approximate donor pins"
                    fullScreen -> "Updating map; ${appliedAreasCount} approximate donor pins"
                    mapError -> "Map preview unavailable"
                    mapLoading -> "Loading map preview"
                    mapRendered -> "Map preview rendered"
                    else -> "Preparing map preview"
                }
            },
    ) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = {
                mapView.apply {
                    addOnDidFailLoadingMapListener(mapFailedListener)
                    addOnDidFinishRenderingMapListener(mapRenderedListener)
                    getMapAsync { map ->
                        mapLoading = true
                        mapError = false
                        mapStyleLoaded = false
                        mapRendered = false
                        map.setStyle(OPEN_FREE_MAP_STYLE) {
                            mapStyleLoaded = true
                            mapError = false
                            if (mapRendered) mapLoading = false
                            map.cameraPosition = CameraPosition.Builder().target(center).zoom(12.0).build()
                            appliedCenter = center
                            appliedRecenterRequest = recenterRequest
                            map.clear()
                            areas.forEach { area ->
                                map.addMarker(
                                    MarkerOptions()
                                        .position(LatLng(area.latitude, area.longitude))
                                        .title("Donor pin · ${area.bloodType}")
                                        .snippet("${area.availability} · Approximate location only"),
                                )
                            }
                            appliedAreasSignature = areasSignature
                            appliedAreasCount = areas.size
                        }
                    }
                }
            },
            update = { view ->
                view.getMapAsync { map ->
                    if (retryRequest > 0) {
                        mapLoading = true
                        mapError = false
                        mapStyleLoaded = false
                        mapRendered = false
                        map.setStyle(OPEN_FREE_MAP_STYLE) {
                            mapStyleLoaded = true
                            mapError = false
                            if (mapRendered) mapLoading = false
                            map.clear()
                            areas.forEach { area ->
                                map.addMarker(
                                    MarkerOptions()
                                        .position(LatLng(area.latitude, area.longitude))
                                        .title("Donor pin · ${area.bloodType}")
                                        .snippet("${area.availability} · Approximate location only"),
                                )
                            }
                            appliedAreasSignature = areasSignature
                            appliedAreasCount = areas.size
                        }
                        retryRequest = 0
                    }
                    if (appliedAreasSignature != areasSignature) {
                        // Update annotations in place. Reloading the whole style for a
                        // blood-type/availability filter needlessly reloads map tiles.
                        map.clear()
                        areas.forEach { area ->
                            map.addMarker(
                                MarkerOptions()
                                    .position(LatLng(area.latitude, area.longitude))
                                    .title("Donor pin · ${area.bloodType}")
                                    .snippet("${area.availability} · Approximate location only"),
                            )
                        }
                        appliedAreasSignature = areasSignature
                        appliedAreasCount = areas.size
                    }
                    // Repeated Compose updates must not keep resetting the camera.
                    // Unnecessary camera moves cancel vector-tile requests and make the map
                    // feel broken while filters, loading state, or visibility controls change.
                    if (appliedCenter != center) {
                        map.cameraPosition = CameraPosition.Builder()
                            .target(center)
                            .zoom(map.cameraPosition.zoom.coerceAtLeast(10.0))
                            .build()
                        appliedCenter = center
                    }
                    if (appliedRecenterRequest != recenterRequest) {
                        map.cameraPosition = CameraPosition.Builder()
                            .target(center)
                            .zoom(map.cameraPosition.zoom.coerceAtLeast(10.0))
                            .build()
                        appliedRecenterRequest = recenterRequest
                    }
                }
            },
        )
        when {
            mapLoading -> Card(
                Modifier.align(androidx.compose.ui.Alignment.Center).padding(16.dp).semantics { liveRegion = LiveRegionMode.Polite },
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 3.dp),
            ) { RowLoading(if (fullScreen) "Loading map tiles…" else "Loading map preview…") }
            mapError -> Card(
                Modifier.align(androidx.compose.ui.Alignment.Center).padding(16.dp).semantics { liveRegion = LiveRegionMode.Assertive },
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                elevation = CardDefaults.cardElevation(defaultElevation = 3.dp),
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        if (fullScreen) "Map unavailable" else "Map preview unavailable",
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.semantics { heading() },
                    )
                    Text(
                        if (fullScreen) {
                            "Donor areas could not be rendered. Retry to reload the map."
                        } else {
                            "Donor results remain available in the list, so you can still review and select donors."
                        },
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Button(onClick = { retryRequest++ }) { Text("Retry map") }
                }
            }
        }
        if (!mapLoading && !mapError) {
            IconButton(
                onClick = { recenterRequest++ },
                modifier = Modifier.align(androidx.compose.ui.Alignment.BottomEnd).padding(12.dp),
            ) {
                Icon(Icons.Default.MyLocation, contentDescription = "Recenter map on request location")
            }
        }
    }
}
