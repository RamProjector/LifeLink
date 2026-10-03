package com.lifelink.app.feature.privacy

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.lifelink.app.core.location.MapLibrePrivacySafeDonorMap
import com.lifelink.app.core.ui.LifeLinkLoadingIndicator
import com.lifelink.app.domain.DonorMapArea

/**
 * Donor map. Shows approximate areas and a freshness timestamp only — never
 * individual donor pins or exact coordinates. Donors control their own
 * visibility from the same screen.
 */
@Composable
fun DonorMapScreen(
    state: PrivacyUiState,
    onAction: (PrivacyAction) -> Unit,
    onBack: () -> Unit,
) {
    LaunchedEffect(Unit) { onAction(PrivacyAction.LoadMap) }
    LazyColumn(
        Modifier.fillMaxSize().padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Donor map", Modifier.weight(1f), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                TextButton(onClick = onBack) { Text("Back") }
            }
        }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Approximate areas only", fontWeight = FontWeight.SemiBold)
                    Text(
                        "The map shows where donors are available as approximate areas with a freshness timestamp. Exact donor locations are never shown here — they are shared only with a matched requester, and only while the donor allows it.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        item { DonorVisibilityControls(state, onAction) }
        state.mapError?.let { error ->
            item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(error, color = MaterialTheme.colorScheme.onErrorContainer)
                        Button(onClick = { onAction(PrivacyAction.LoadMap) }) { Text("Retry") }
                    }
                }
            }
        }
        if (state.mapLoading) {
            item { LifeLinkLoadingIndicator(label = "Loading donor areas…") }
        }
        val areas = state.map?.areas.orEmpty()
        if (!state.mapLoading && state.mapError == null && areas.isEmpty()) {
            item {
                Text(
                    "No donors are currently visible on the map. Donors appear here only after they opt in.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (areas.isNotEmpty()) {
            item {
                MapLibrePrivacySafeDonorMap(
                    latitude = areas.first().latitude,
                    longitude = areas.first().longitude,
                )
            }
        }
        items(areas) { area -> DonorAreaCard(area) }
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
                        Text("Checking location sharing…", style = MaterialTheme.typography.bodySmall)
                    }
                location?.shared == true -> {
                    Text(
                        "Exact location shared for this request",
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text("Latitude ${location.latitude}, longitude ${location.longitude}")
                    location.precisionMeters?.let { Text("Precision ±$it m", style = MaterialTheme.typography.bodySmall) }
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
