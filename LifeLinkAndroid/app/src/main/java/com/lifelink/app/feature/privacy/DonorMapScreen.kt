package com.lifelink.app.feature.privacy

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.lifelink.app.R
import com.lifelink.app.core.location.MapLibreLocationPicker
import com.lifelink.app.core.location.MapLocationSource
import com.lifelink.app.domain.DonorMapArea

// Neutral, Philippines-oriented center used only when no donor area is loaded,
// so the map always renders a real surface instead of a placeholder.
private const val DEFAULT_MAP_LATITUDE = 14.5995
private const val DEFAULT_MAP_LONGITUDE = 120.9842

/**
 * Donor map. The map is the primary, full-screen surface and is already loaded
 * when the screen opens \u2014 there is no intermediate placeholder and no separate
 * "load map" action. The only control on the map is a single three-dot overflow
 * icon in the top-right corner; every text-based option (visibility controls,
 * donor areas, refresh) lives inside that menu. Approximate areas and a
 * freshness timestamp are shown only \u2014 never individual donor pins or exact
 * coordinates.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DonorMapScreen(
    state: PrivacyUiState,
    onAction: (PrivacyAction) -> Unit,
    onBack: () -> Unit,
) {
    LaunchedEffect(Unit) { onAction(PrivacyAction.LoadMap) }
    val areas = state.map?.areas.orEmpty()
    val center = areas.firstOrNull()
    var optionsOpen by remember { mutableStateOf(false) }
    var showVisibility by remember { mutableStateOf(false) }
    var showAreas by remember { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    Box(Modifier.fillMaxSize()) {
        MapLibreLocationPicker(
            latitude = center?.latitude ?: DEFAULT_MAP_LATITUDE,
            longitude = center?.longitude ?: DEFAULT_MAP_LONGITUDE,
            onLocationSelected = { _, _ -> },
            initialSource = MapLocationSource.MANUAL,
            // The donor map owns its own single overflow control, so the picker's
            // built-in controls are suppressed to avoid a duplicate icon.
            showControls = false,
            modifier = Modifier.fillMaxSize(),
        )

        // Single option control: a three-dot overflow icon in the top-right.
        Box(Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(12.dp)) {
            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surface, shadowElevation = 3.dp) {
                IconButton(onClick = { optionsOpen = true }) {
                    Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.donor_map_overflow))
                }
            }
            DropdownMenu(expanded = optionsOpen, onDismissRequest = { optionsOpen = false }) {
                DropdownMenuItem(
                    text = { Text("Refresh donor areas") },
                    onClick = {
                        optionsOpen = false
                        onAction(PrivacyAction.LoadMap)
                    },
                )
                DropdownMenuItem(
                    text = { Text("Your visibility") },
                    onClick = {
                        optionsOpen = false
                        showVisibility = true
                    },
                )
                DropdownMenuItem(
                    text = { Text("Donor areas (${areas.size})") },
                    onClick = {
                        optionsOpen = false
                        showAreas = true
                    },
                )
            }
        }

        // Compact, icon-only retry shown only when the donor-map request fails.
        if (state.mapError != null) {
            Surface(
                modifier = Modifier.align(Alignment.TopStart).statusBarsPadding().padding(12.dp),
                shape = CircleShape,
                color = MaterialTheme.colorScheme.errorContainer,
                shadowElevation = 3.dp,
            ) {
                IconButton(onClick = { onAction(PrivacyAction.LoadMap) }) {
                    Icon(
                        Icons.Default.Refresh,
                        contentDescription = stringResource(R.string.donor_map_retry),
                        tint = MaterialTheme.colorScheme.onErrorContainer,
                    )
                }
            }
        }
    }

    if (showVisibility) {
        ModalBottomSheet(onDismissRequest = { showVisibility = false }, sheetState = sheetState) {
            DonorVisibilityControls(state, onAction)
        }
    }
    if (showAreas) {
        ModalBottomSheet(onDismissRequest = { showAreas = false }, sheetState = sheetState) {
            LazyColumn(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item {
                    Text("Donor areas", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                }
                if (areas.isEmpty()) {
                    item {
                        Text(
                            "No donors are currently visible on the map. Donors appear here only after they opt in.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    items(areas) { area -> DonorAreaCard(area) }
                }
            }
        }
    }
}

/**
 * Displays map visibility and exact-location sharing toggles from [state].
 * Sends changes through [onAction] and disables the toggles while a save is pending.
 */
@Composable
private fun DonorVisibilityControls(state: PrivacyUiState, onAction: (PrivacyAction) -> Unit) {
    val visibility = state.visibility
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Your visibility", fontWeight = FontWeight.Bold)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Show me on the donor map")
                    Text(
                        "Your approximate area and a freshness timestamp are shown. You can hide at any time.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = visibility?.mapVisible == true,
                    enabled = !state.visibilitySaving,
                    onCheckedChange = { checked ->
                        onAction(PrivacyAction.SetMapVisibility(checked, visibility?.exactLocationSharingEnabled == true))
                    },
                )
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Show my exact location to matched requesters")
                    Text(
                        "Only a requester whose active request has matched you can see your exact location, and it expires when the request ends.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = visibility?.exactLocationSharingEnabled == true,
                    enabled = !state.visibilitySaving,
                    onCheckedChange = { checked ->
                        onAction(PrivacyAction.SetMapVisibility(visibility?.mapVisible == true, checked))
                    },
                )
            }
            visibility?.freshnessAt?.let {
                Text(
                    "Location last updated: $it",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            state.message?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
        }
    }
}

/** Summarizes [area] with its blood type, approximate radius, and location freshness. */
@Composable
private fun DonorAreaCard(area: DonorMapArea) {
    Card(Modifier.fillMaxWidth(), elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(area.bloodType, fontWeight = FontWeight.Bold)
                Text(
                    if (area.isStale) "Location may be outdated" else "Updated ${area.freshnessAgeMinutes} min ago",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (area.isStale) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(area.areaLabel, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                "Approx. radius ${area.radiusMeters} m",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Matched-requester view of a donor's exact location. Renders a pin only when
 * the server confirms a live share; otherwise it explains why it is hidden.
 */
@Composable
fun MatchedDonorLocationCard(
    state: PrivacyUiState,
    requestId: String,
    donorId: String,
    onAction: (PrivacyAction) -> Unit,
) {
    LaunchedEffect(requestId, donorId) { onAction(PrivacyAction.LoadMatchedLocation(requestId, donorId)) }
    val location = state.matchedLocation
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Donor location", fontWeight = FontWeight.Bold)
            when {
                state.locationLoading ->
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.heightIn(max = 20.dp), strokeWidth = 2.dp)
                        Text("Checking location sharing\u2026", style = MaterialTheme.typography.bodySmall)
                    }
                location?.shared == true -> {
                    Text(
                        "Exact location shared for this request",
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text("Latitude ${location.latitude}, longitude ${location.longitude}")
                    location.precisionMeters?.let { Text("Precision \u00b1$it m", style = MaterialTheme.typography.bodySmall) }
                    location.expiresAt?.let {
                        Text("Expires: $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                else -> {
                    Text(
                        location?.reason ?: "Exact location is not available for this donor.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedButton(onClick = { onAction(PrivacyAction.ActivateLocationShare(requestId, donorId)) }) {
                        Text("Request exact location")
                    }
                }
            }
        }
    }
}
