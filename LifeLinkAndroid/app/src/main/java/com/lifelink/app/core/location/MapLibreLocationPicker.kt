@file:Suppress("DEPRECATION")

package com.lifelink.app.core.location

import android.view.MotionEvent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.lifelink.app.R
import kotlinx.coroutines.delay
import org.maplibre.android.MapLibre
import org.maplibre.android.annotations.Marker
import org.maplibre.android.annotations.MarkerOptions
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapView

// OpenFreeMap's documented Liberty vector-tile style. It is a free, key-less
// style; the URL is used verbatim by MapLibre's setStyle(String).
private const val OPEN_FREE_MAP_STYLE = "https://tiles.openfreemap.org/styles/liberty"

// If MapLibre never reports success or failure, the retry affordance must still
// appear so the map is never silently stuck on a blank surface.
private const val MAP_LOAD_TIMEOUT_MS = 30_000L

// Neutral, Philippines-oriented center used only when the caller has no
// coordinates yet, so the map always renders a real surface instead of a
// placeholder or a (0, 0) point in the Gulf of Guinea.
private val DEFAULT_CENTER = LatLng(14.5995, 120.9842)

/** How the currently shown point was chosen, surfaced to the user as a label. */
enum class MapLocationSource { CURRENT, MANUAL }

/**
 * Real native MapLibre map, rendered full-screen and already loaded: the map
 * surface is shown immediately and the style loads in the background, so there
 * is no intermediate placeholder and no separate "load map" action.
 *
 * The only controls on the map surface are a single three-dot overflow icon in
 * the top-right corner and a raised, circular recenter button centered at the
 * bottom. All text-based options live inside the overflow menu. A style or tile
 * load failure surfaces a compact retry control instead of a blocking overlay.
 */
@Suppress("LongMethod", "CyclomaticComplexMethod", "LongParameterList")
@Composable
fun MapLibreLocationPicker(
    latitude: Double?,
    longitude: Double?,
    onLocationSelected: (Double, Double) -> Unit,
    recenterRequest: Int = 0,
    onLoadingChanged: (Boolean) -> Unit = {},
    onMapError: (String) -> Unit = {},
    initialSource: MapLocationSource = MapLocationSource.MANUAL,
    accuracyMeters: Int? = null,
    showControls: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val latestLatitude by rememberUpdatedState(latitude)
    val latestLongitude by rememberUpdatedState(longitude)
    var marker by remember { mutableStateOf<Marker?>(null) }
    var appliedLatitude by remember { mutableStateOf<Double?>(null) }
    var appliedLongitude by remember { mutableStateOf<Double?>(null) }
    var appliedRecenterRequest by remember { mutableStateOf(-1) }
    var mapError by remember { mutableStateOf<String?>(null) }
    var retryRequest by remember { mutableStateOf(0) }
    var source by remember { mutableStateOf(initialSource) }
    var moveConfirmed by remember { mutableStateOf(false) }
    var optionsOpen by remember { mutableStateOf(false) }
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
    // leave the map blank with no way to recover. Surface the retry control.
    LaunchedEffect(mapError) {
        if (mapError != null) return@LaunchedEffect
        delay(MAP_LOAD_TIMEOUT_MS)
        if (mapError == null) {
            mapError = "The map is taking too long to load."
            onMapError("Map tiles could not be loaded.")
        }
    }

    // The move confirmation is transient: it confirms the pin moved, then clears.
    LaunchedEffect(moveConfirmed) {
        if (!moveConfirmed) return@LaunchedEffect
        delay(2_000)
        moveConfirmed = false
    }

    Box(modifier.fillMaxWidth().heightIn(min = 260.dp)) {
        AndroidView(
            modifier = Modifier.fillMaxWidth().heightIn(min = 260.dp),
            factory = {
                mapView.apply {
                    addOnDidFailLoadingMapListener {
                        mapError = "Map tiles could not be loaded."
                        onMapError("Map tiles could not be loaded.")
                    }
                    setOnTouchListener { view, event ->
                        when (event.actionMasked) {
                            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> view.parent?.requestDisallowInterceptTouchEvent(true)
                            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> view.parent?.requestDisallowInterceptTouchEvent(false)
                        }
                        false
                    }
                    getMapAsync { map ->
                        mapError = null
                        onLoadingChanged(true)
                        map.setStyle(OPEN_FREE_MAP_STYLE) {
                            mapError = null
                            onLoadingChanged(false)
                            val currentPosition = latestLatitude?.let { lat -> latestLongitude?.let { lon -> LatLng(lat, lon) } }
                            val target = currentPosition ?: DEFAULT_CENTER
                            map.cameraPosition = CameraPosition.Builder()
                                .target(target)
                                .zoom(if (currentPosition != null) 15.0 else 11.0)
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
                                source = MapLocationSource.MANUAL
                                moveConfirmed = true
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
                        mapError = null
                        map.setStyle(OPEN_FREE_MAP_STYLE) { mapError = null }
                        retryRequest = 0
                    }
                    if (target != null &&
                        (appliedLatitude != latitude || appliedLongitude != longitude || appliedRecenterRequest != recenterRequest)
                    ) {
                        appliedLatitude = latitude
                        appliedLongitude = longitude
                        appliedRecenterRequest = recenterRequest
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
            },
        )

        // Single option control: a three-dot overflow icon in the top-right.
        if (showControls) {
        Box(Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(12.dp)) {
            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surface, shadowElevation = 3.dp) {
                IconButton(onClick = { optionsOpen = true }) {
                    Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.donor_map_overflow))
                }
            }
            DropdownMenu(expanded = optionsOpen, onDismissRequest = { optionsOpen = false }) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.donor_map_recenter)) },
                    onClick = {
                        optionsOpen = false
                        source = MapLocationSource.CURRENT
                        onLocationSelected(latitude ?: DEFAULT_CENTER.latitude, longitude ?: DEFAULT_CENTER.longitude)
                    },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.donor_map_retry)) },
                    onClick = {
                        optionsOpen = false
                        retryRequest++
                    },
                )
            }
        }
        }

        // Raised, circular recenter button centered at the bottom, matching the
        // reference's prominent centered control.
        if (showControls) {
        FilledIconButton(
            onClick = {
                source = MapLocationSource.CURRENT
                onLocationSelected(latitude ?: DEFAULT_CENTER.latitude, longitude ?: DEFAULT_CENTER.longitude)
            },
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 20.dp).size(56.dp),
            shape = CircleShape,
            colors = IconButtonDefaults.filledIconButtonColors(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            ),
        ) {
            Icon(Icons.Default.MyLocation, contentDescription = stringResource(R.string.donor_map_recenter))
        }

        // Location-source and accuracy feedback, kept as a compact non-interactive chip.
        Surface(
            modifier = Modifier.align(Alignment.BottomStart).padding(start = 12.dp, bottom = 20.dp),
            shape = MaterialTheme.shapes.small,
            color = MaterialTheme.colorScheme.surface,
            shadowElevation = 2.dp,
        ) {
            Column(Modifier.padding(horizontal = 10.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                val sourceLabel =
                    if (source == MapLocationSource.CURRENT) {
                        R.string.donor_map_source_current
                    } else {
                        R.string.donor_map_source_manual
                    }
                Text(
                    stringResource(sourceLabel),
                    style = MaterialTheme.typography.labelMedium,
                )
                accuracyMeters?.let {
                    Text(
                        stringResource(R.string.donor_map_accuracy, it),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        }

        // Compact, icon-only retry control shown only when the style/tiles fail.
        if (mapError != null) {
            Surface(
                modifier = Modifier.align(Alignment.TopStart).statusBarsPadding().padding(12.dp),
                shape = CircleShape,
                color = MaterialTheme.colorScheme.errorContainer,
                shadowElevation = 3.dp,
            ) {
                IconButton(onClick = { retryRequest++ }) {
                    Icon(
                        Icons.Default.Refresh,
                        contentDescription = stringResource(R.string.donor_map_retry),
                        tint = MaterialTheme.colorScheme.onErrorContainer,
                    )
                }
            }
        }

        if (showControls && moveConfirmed) {
            Surface(
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 88.dp),
                shape = MaterialTheme.shapes.small,
                color = MaterialTheme.colorScheme.inverseSurface,
            ) {
                Text(
                    stringResource(R.string.donor_map_move_confirmed),
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.inverseOnSurface,
                )
            }
        }
    }
}

/**
 * Read-only privacy-safe donor map used by the requester results summary. It
 * shows a single approximate center and never exposes donor pins or exact
 * coordinates. It renders full-size and loads its style in the background.
 */
@Suppress("LongMethod")
@Composable
fun MapLibrePrivacySafeDonorMap(
    latitude: Double,
    longitude: Double,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val center = remember(latitude, longitude) { LatLng(latitude, longitude) }
    var mapError by remember { mutableStateOf(false) }
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
    Box(modifier.fillMaxWidth().height(280.dp)) {
        AndroidView(
            modifier = Modifier.fillMaxWidth().height(280.dp),
            factory = {
                mapView.apply {
                    addOnDidFailLoadingMapListener { mapError = true }
                    getMapAsync { map ->
                        mapError = false
                        map.setStyle(OPEN_FREE_MAP_STYLE) {
                            mapError = false
                            map.cameraPosition = CameraPosition.Builder().target(center).zoom(12.0).build()
                            map.addMarker(MarkerOptions().position(center).title("Your request location"))
                        }
                    }
                }
            },
            update = { view ->
                view.getMapAsync { map ->
                    if (retryRequest > 0) {
                        mapError = false
                        map.setStyle(OPEN_FREE_MAP_STYLE) { mapError = false }
                        retryRequest = 0
                    }
                    map.cameraPosition = CameraPosition.Builder()
                        .target(LatLng(latitude, longitude))
                        .zoom(map.cameraPosition.zoom.coerceAtLeast(10.0))
                        .build()
                }
            },
        )
        if (mapError) {
            Surface(
                modifier = Modifier.align(Alignment.TopStart).padding(12.dp),
                shape = CircleShape,
                color = MaterialTheme.colorScheme.errorContainer,
                shadowElevation = 3.dp,
            ) {
                IconButton(onClick = { retryRequest++ }) {
                    Icon(
                        Icons.Default.Refresh,
                        contentDescription = stringResource(R.string.donor_map_retry),
                        tint = MaterialTheme.colorScheme.onErrorContainer,
                    )
                }
            }
        }
    }
}
