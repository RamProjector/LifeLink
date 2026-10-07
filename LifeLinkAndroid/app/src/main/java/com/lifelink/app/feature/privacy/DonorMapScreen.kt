package com.lifelink.app.feature.privacy

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
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

/**
 * Donor map. Shows approximate areas and a freshness timestamp only \u2014 never
 * individual donor pins or exact coordinates. Donors control their own
 * visibility from the same screen.
 */
@Composable
fun DonorMapScreen(state: PrivacyUiState, onAction: (PrivacyAction) -> Unit, onBack: () -> Unit) {
    LaunchedEffect(Unit) { onAction(PrivacyAction.LoadMap) }
    var filtersOpen by rememberSaveable { mutableStateOf(false) }
    var privacyOpen by rememberSaveable { mutableStateOf(false) }
    var selectedBloodType by rememberSaveable { mutableStateOf("All") }
    var availableOnly by rememberSaveable { mutableStateOf(false) }
    val areas = state.map?.areas.orEmpty()
    val bloodTypes = listOf("All") + areas.map { it.bloodType }.distinct().sorted()
    val filteredAreas = areas.filter { area ->
        (selectedBloodType == "All" || area.bloodType == selectedBloodType) &&
            (!availableOnly || area.availability.equals("Available", ignoreCase = true))
    }
    val mapArea = filteredAreas.firstOrNull()

    Box(Modifier.fillMaxSize().testTag("donor-map-fullscreen")) {
        if (mapArea != null) {
            MapLibrePrivacySafeDonorMap(
                latitude = mapArea.latitude,
                longitude = mapArea.longitude,
                modifier = Modifier.fillMaxSize(),
                fullScreen = true,
            )
        } else {
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                LifeLinkEmptyState(
                    icon = Icons.Default.LocationOn,
                    title = if (areas.isEmpty()) "No donors on the map yet" else "No donors match these filters",
                    body = "Donor areas appear here only after donors opt in. Exact donor locations are never shown.",
                )
            }
        }

        Row(
            Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Close donor map")
            }
            Surface(
                Modifier.weight(1f),
                shape = RoundedCornerShape(18.dp),
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
                shadowElevation = 3.dp,
            ) {
                Text(
                    "Find donors",
                    Modifier.padding(horizontal = 16.dp, vertical = 10.dp).semantics { heading() },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            IconButton(onClick = { filtersOpen = !filtersOpen }) {
                Icon(Icons.Default.FilterList, contentDescription = if (filtersOpen) "Hide map filters" else "Show map filters")
            }
            IconButton(onClick = { privacyOpen = !privacyOpen }) {
                Icon(Icons.Default.Shield, contentDescription = "Map privacy controls")
            }
            IconButton(onClick = { onAction(PrivacyAction.LoadMap) }) {
                Icon(Icons.Default.Refresh, contentDescription = "Refresh donor map")
            }
        }

        if (filtersOpen) {
            Card(
                Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 72.dp, start = 16.dp, end = 16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f)),
                elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
            ) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Tune, contentDescription = null, modifier = Modifier.size(18.dp))
                        Text("Filter donor areas", Modifier.padding(start = 8.dp), style = MaterialTheme.typography.titleSmall)
                    }
                    Row(
                        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        FilterChip(
                            selected = availableOnly,
                            onClick = { availableOnly = !availableOnly },
                            label = { Text("Available now") },
                        )
                        bloodTypes.forEach { type ->
                            FilterChip(
                                selected = selectedBloodType == type,
                                onClick = { selectedBloodType = type },
                                label = { Text(type) },
                            )
                        }
                    }
                }
            }
        }

        if (privacyOpen) {
            Card(
                Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f)),
                elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
            ) {
                DonorVisibilityControls(state, onAction)
            }
        } else {
            Card(
                Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f)),
                elevation = CardDefaults.cardElevation(defaultElevation = 3.dp),
            ) {
                Row(
                    Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Default.Shield, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text(
                        "Approximate areas only",
                        Modifier.padding(start = 8.dp).weight(1f),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text("${filteredAreas.size} areas", style = MaterialTheme.typography.labelMedium)
                }
            }
        }

        state.mapError?.let { error ->
            DonorMapErrorCard(
                error = error,
                onRetry = { onAction(PrivacyAction.LoadMap) },
                modifier = Modifier.align(Alignment.Center).padding(16.dp),
            )
        }
        if (state.mapLoading) {
            LifeLinkLoadingIndicator(Modifier.align(Alignment.Center), label = "Loading donor areas…")
        }
    }
}

/** Error surface with a retry action, announced assertively to screen readers. */
@Composable
private fun DonorMapErrorCard(error: String, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    Card(
        modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Assertive },
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
private fun VisibilitySwitchRow(title: String, subtitle: String, checked: Boolean, enabled: Boolean, onCheckedChange: (Boolean) -> Unit) {
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

/**
 * Matched-requester view of a donor's exact location. Renders a pin only when
 * the server confirms a live share; otherwise it explains why it is hidden.
 */
@Composable
fun MatchedDonorLocationCard(state: PrivacyUiState, requestId: String, donorId: String, onAction: (PrivacyAction) -> Unit) {
    LaunchedEffect(requestId, donorId) { onAction(PrivacyAction.LoadMatchedLocation(requestId, donorId)) }
    val location = state.matchedLocation
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Donor location", fontWeight = FontWeight.Bold, modifier = Modifier.semantics { heading() })
            when {
                state.locationLoading ->
                    Row(
                        Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
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
