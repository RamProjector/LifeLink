package com.lifelink.app.feature.privacy

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.lifelink.app.core.location.MapLibrePrivacySafeDonorMap
import com.lifelink.app.core.ui.LifeLinkEmptyState
import com.lifelink.app.core.ui.LifeLinkLoadingIndicator
import com.lifelink.app.domain.DonorMapArea

/**
 * Donor map. Shows approximate areas and a freshness timestamp only \u2014 never
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
                Text(
                    "Donor map",
                    Modifier.weight(1f).semantics { heading() },
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                )
                TextButton(onClick = onBack) { Text("Back") }
            }
        }
        item { PrivacyBanner() }
        item { DonorVisibilityControls(state, onAction) }
        state.mapError?.let { error ->
            item { DonorMapErrorCard(error = error, onRetry = { onAction(PrivacyAction.LoadMap) }) }
        }
        if (state.mapLoading) {
            item { LifeLinkLoadingIndicator(label = "Loading donor areas\u2026") }
        }
        val areas = state.map?.areas.orEmpty()
        if (!state.mapLoading && state.mapError == null && areas.isEmpty()) {
            item {
                LifeLinkEmptyState(
                    icon = Icons.Default.LocationOn,
                    title = "No donors on the map yet",
                    body = "Donors appear here only after they opt in to map visibility.",
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
        items(areas, key = { "${it.areaLabel}-${it.bloodType}-${it.latitude}-${it.longitude}" }) { area -> DonorAreaCard(area) }
    }
}

/** Error surface with a retry action, announced assertively to screen readers. */
@Composable
private fun DonorMapErrorCard(error: String, onRetry: () -> Unit) {
    Card(
        Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Assertive },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
    ) {
        Row(
            Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Default.Error,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.size(20.dp),
            )
            Text(
                error,
                modifier = Modifier.weight(1f),
                color = MaterialTheme.colorScheme.onErrorContainer,
                style = MaterialTheme.typography.bodySmall,
            )
            Button(onClick = onRetry) { Text("Retry") }
        }
    }
}

/** Explains the approximate-only privacy guarantee at the top of the map. */
@Composable
private fun PrivacyBanner() {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Row(
            Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Icon(
                Icons.Default.Shield,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Approximate areas only", fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSecondaryContainer)
                Text(
                    "The map shows where donors are available as approximate areas with a freshness timestamp. " +
                        "Exact donor locations are never shown here \u2014 they are shared only with a matched requester, " +
                        "and only while the donor allows it.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
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
    Card(Modifier.fillMaxWidth(), elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Your visibility", fontWeight = FontWeight.Bold, modifier = Modifier.semantics { heading() })
            VisibilitySwitchRow(
                title = "Show me on the donor map",
                subtitle = "Your approximate area and a freshness timestamp are shown. You can hide at any time.",
                checked = visibility?.mapVisible == true,
                enabled = !state.visibilitySaving,
                onCheckedChange = { checked ->
                    onAction(PrivacyAction.SetMapVisibility(checked, visibility?.exactLocationSharingEnabled == true))
                },
            )
            VisibilitySwitchRow(
                title = "Show my exact location to matched requesters",
                subtitle =
                    "Only a requester whose active request has matched you can see your exact location, " +
                        "and it expires when the request ends.",
                checked = visibility?.exactLocationSharingEnabled == true,
                enabled = !state.visibilitySaving,
                onCheckedChange = { checked ->
                    onAction(PrivacyAction.SetMapVisibility(visibility?.mapVisible == true, checked))
                },
            )
            visibility?.freshnessAt?.let {
                Text(
                    "Location last updated: $it",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            state.message?.let {
                Text(
                    it,
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
        }
    }
}

/**
 * A full-width switch row with a title and explanatory subtitle. The whole row is the hit target
 * via [toggleable]; the inner [Switch] is non-interactive so the two never double-toggle.
 */
@Composable
private fun VisibilitySwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().toggleable(
            value = checked,
            enabled = enabled,
            role = Role.Switch,
            onValueChange = onCheckedChange,
        ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(
            checked = checked,
            enabled = enabled,
            onCheckedChange = null,
        )
    }
}

/** Summarizes [area] with its blood type, approximate radius, and location freshness. */
@Composable
private fun DonorAreaCard(area: DonorMapArea) {
    Card(Modifier.fillMaxWidth(), elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = MaterialTheme.shapes.small) {
                    Text(
                        area.bloodType,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        fontWeight = FontWeight.Bold,
                    )
                }
                Text(
                    if (area.isStale) "Location may be outdated" else "Updated ${area.freshnessAgeMinutes} min ago",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (area.isStale) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                area.areaLabel,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.semantics { heading() },
            )
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
            Text("Donor location", fontWeight = FontWeight.Bold, modifier = Modifier.semantics { heading() })
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
