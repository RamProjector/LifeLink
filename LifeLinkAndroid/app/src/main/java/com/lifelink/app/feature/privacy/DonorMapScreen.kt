package com.lifelink.app.feature.privacy

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
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
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.lifelink.app.core.location.MapLibrePrivacySafeDonorMap
import com.lifelink.app.core.ui.LifeLinkFeedbackCard
import com.lifelink.app.core.ui.LifeLinkFeedbackLevel
import com.lifelink.app.core.ui.LifeLinkLoadingIndicator
import com.lifelink.app.domain.DonorMapArea

private fun donorAreaKey(area: DonorMapArea): String =
    "${area.latitude}|${area.longitude}|${area.bloodType}|${area.freshnessAt}"

private fun donorAreaCountLabel(count: Int, context: String): String {
    val noun = if (count == 1) "area" else "areas"
    return if (context.isBlank()) "$count $noun" else "$count $context $noun"
}

@Composable
private fun DonorAreaDetailsScreen(area: DonorMapArea, onBack: () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        OutlinedButton(
            onClick = onBack,
            modifier = Modifier.sizeIn(minHeight = 48.dp).testTag("donor-area-back"),
        ) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
            Text("Back to map")
        }
        Text(
            "Donor area details",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.semantics { heading() },
        )
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(area.areaLabel, style = MaterialTheme.typography.titleMedium)
                Text("Blood type: ${area.bloodType}")
                Text("Availability: ${area.availability}")
                Text("Approximate coverage: at least ${area.radiusMeters} m")
                Text(
                    if (area.isStale) {
                        "Location may be out of date"
                    } else {
                        "Last updated about ${area.freshnessAgeMinutes} minutes ago"
                    },
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.secondaryContainer,
        ) {
            Text(
                "Privacy protected: this view shows an approximate area only. " +
                    "Donor identity, contact details, and exact coordinates are not disclosed here.",
                Modifier.fillMaxWidth().padding(16.dp),
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

private const val DEFAULT_MAP_LATITUDE = 14.5995
private const val DEFAULT_MAP_LONGITUDE = 120.9842

/**
 * Donor map. Shows opt-in, coarsened donor pins and a freshness timestamp only \u2014 never
 * exact coordinates or donor identities. Donors control their own
 * visibility from the same screen.
 */
@Suppress("LongMethod", "CyclomaticComplexMethod")
@Composable
fun DonorMapScreen(
    state: PrivacyUiState,
    onAction: (PrivacyAction) -> Unit,
    onBack: () -> Unit,
    centerLatitude: Double? = null,
    centerLongitude: Double? = null,
    showCloseButton: Boolean = true,
    embeddedInShell: Boolean = false,
) {
    LaunchedEffect(Unit) { onAction(PrivacyAction.LoadMap) }
    var filtersOpen by rememberSaveable { mutableStateOf(false) }
    var showList by rememberSaveable { mutableStateOf(false) }
    var privacyOpen by rememberSaveable { mutableStateOf(false) }
    var selectedBloodType by rememberSaveable { mutableStateOf("All") }
    var availableOnly by rememberSaveable { mutableStateOf(false) }
    var selectedAreaKey by rememberSaveable { mutableStateOf("") }
    val areas = state.map?.areas.orEmpty()
    val selectedArea = areas.firstOrNull { donorAreaKey(it) == selectedAreaKey }
    if (selectedArea != null) {
        BackHandler { selectedAreaKey = "" }
        DonorAreaDetailsScreen(selectedArea) { selectedAreaKey = "" }
        return
    }
    val bloodTypes = listOf("All") + areas.map { it.bloodType }.distinct().sorted()
    val activeFilterCount =
        (if (selectedBloodType == "All") 0 else 1) + (if (availableOnly) 1 else 0)
    val filteredAreas = areas.filter { area ->
        (selectedBloodType == "All" || area.bloodType == selectedBloodType) &&
            (!availableOnly || area.availability.equals("Available", ignoreCase = true))
    }
    // Keep the viewport anchored to the overall result set. Filtering should change pins,
    // not unexpectedly jump the user's map to whichever blood type happens to sort first.
    val mapArea = areas.firstOrNull()
    val mapLatitude = centerLatitude ?: mapArea?.latitude ?: DEFAULT_MAP_LATITUDE
    val mapLongitude = centerLongitude ?: mapArea?.longitude ?: DEFAULT_MAP_LONGITUDE

    Box(Modifier.fillMaxSize().testTag("donor-map-fullscreen")) {
        if (showList) {
            Column(
                Modifier.fillMaxSize().padding(top = 80.dp, bottom = 8.dp),
            ) {
                Text(
                    "Donor areas",
                    Modifier.padding(horizontal = 20.dp, vertical = 8.dp).semantics { heading() },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                LazyColumn(
                    modifier = Modifier.fillMaxSize().testTag("donor-map-area-list"),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 20.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(filteredAreas) { area ->
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .sizeIn(minHeight = 56.dp)
                                .clickable(
                                    role = Role.Button,
                                    onClickLabel = "View donor area details",
                                ) { selectedAreaKey = donorAreaKey(area) }
                                .testTag("donor-area-card"),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                            ),
                        ) {
                            Column(
                                Modifier.fillMaxWidth().padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                Text(
                                    area.areaLabel,
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold,
                                )
                                Text(
                                    "${area.bloodType} · ${area.availability}",
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                                Text(
                                    if (area.isStale) {
                                        "Location may be out of date"
                                    } else {
                                        "Approximate area · updated ${area.freshnessAgeMinutes} min ago"
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        } else {
            MapLibrePrivacySafeDonorMap(
                latitude = mapLatitude,
                longitude = mapLongitude,
                areas = filteredAreas,
                modifier = Modifier.fillMaxSize(),
                fullScreen = true,
                onAreaSelected = { area -> selectedAreaKey = donorAreaKey(area) },
            )
        }

        // Never show an empty result while a request is loading or has failed.
        // Those states need their own truthful feedback and recovery action.
        if (!state.mapLoading && state.mapError == null && filteredAreas.isEmpty()) {
            Surface(
                Modifier.align(Alignment.BottomCenter)
                    .padding(start = 24.dp, end = 24.dp, bottom = 88.dp)
                    .widthIn(max = 360.dp),
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.97f),
                shadowElevation = 4.dp,
            ) {
                Column(
                    Modifier.fillMaxWidth().padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Surface(
                        shape = RoundedCornerShape(24.dp),
                        color = MaterialTheme.colorScheme.secondaryContainer,
                    ) {
                        Icon(
                            Icons.Default.LocationOn,
                            contentDescription = null,
                            modifier = Modifier.padding(18.dp).size(30.dp),
                            tint = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                    }
                    Text(
                        if (areas.isEmpty()) "No donor areas available" else "No areas match these filters",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.semantics { heading() },
                    )
                    Text(
                        if (areas.isEmpty()) {
                            "Donor areas appear when eligible donors opt in and share a recent approximate location."
                        } else {
                            "Try a different blood type or turn off “Available now” to see more areas."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (areas.isNotEmpty()) {
                        OutlinedButton(
                            onClick = {
                                selectedBloodType = "All"
                                availableOnly = false
                            },
                        ) {
                            Text("Clear filters")
                        }
                    } else {
                        OutlinedButton(onClick = { onAction(PrivacyAction.LoadMap) }) {
                            Text("Refresh areas")
                        }
                    }
                }
            }
        }

        Row(
            Modifier.fillMaxWidth()
                .then(if (embeddedInShell) Modifier else Modifier.statusBarsPadding())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (showCloseButton) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Close donor map")
                }
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
            OutlinedButton(
                onClick = { showList = !showList },
                modifier = Modifier.testTag("toggle-donor-map-view"),
            ) {
                Text(if (showList) "Map" else "List")
            }
            BadgedBox(
                badge = {
                    if (activeFilterCount > 0) {
                        Badge { Text(activeFilterCount.toString()) }
                    }
                },
            ) {
                IconButton(onClick = { filtersOpen = !filtersOpen }) {
                    val filterAction = if (filtersOpen) "Hide map filters" else "Show map filters"
                    val filterDescription = if (activeFilterCount == 0) {
                        filterAction
                    } else {
                        "$filterAction, $activeFilterCount active filters"
                    }
                    Icon(Icons.Default.FilterList, contentDescription = filterDescription)
                }
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
                Modifier.align(Alignment.TopCenter)
                    .then(if (embeddedInShell) Modifier else Modifier.statusBarsPadding())
                    .padding(top = 72.dp, start = 16.dp, end = 16.dp)
                    .semantics { paneTitle = "Donor map filters" }.testTag("donor-map-filter-panel"),
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
                            modifier = Modifier.sizeIn(minHeight = 48.dp),
                        )
                        bloodTypes.forEach { type ->
                            FilterChip(
                                selected = selectedBloodType == type,
                                onClick = { selectedBloodType = type },
                                label = { Text(type) },
                                modifier = Modifier.sizeIn(minHeight = 48.dp),
                            )
                        }
                    }
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            donorAreaCountLabel(filteredAreas.size, "matching"),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .semantics { liveRegion = LiveRegionMode.Polite }
                                .testTag("donor-map-filter-count"),
                        )
                        if (selectedBloodType != "All" || availableOnly) {
                            OutlinedButton(
                                onClick = {
                                    selectedBloodType = "All"
                                    availableOnly = false
                                },
                            ) {
                                Text("Clear filters")
                            }
                        }
                    }
                }
            }
        }

        if (privacyOpen) {
            Card(
                Modifier.align(Alignment.BottomCenter)
                    .then(if (embeddedInShell) Modifier else Modifier.navigationBarsPadding())
                    .padding(16.dp)
                    .semantics { paneTitle = "Donor map privacy controls" }
                    .testTag("donor-map-privacy-panel"),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f)),
                elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
            ) {
                DonorVisibilityControls(state, onAction)
            }
        } else if (!state.mapLoading && state.mapError == null) {
            // Do not pair a loading/error surface with a stale "0 areas" footer.
            Card(
                Modifier.align(Alignment.BottomCenter)
                    .then(if (embeddedInShell) Modifier else Modifier.navigationBarsPadding())
                    .padding(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f)),
                elevation = CardDefaults.cardElevation(defaultElevation = 3.dp),
            ) {
                Row(
                    Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Default.Shield, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text(
                        "Approximate donor pins only",
                        Modifier.padding(start = 8.dp).weight(1f),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(donorAreaCountLabel(filteredAreas.size, ""), style = MaterialTheme.typography.labelMedium)
                }
            }
        }

        state.mapError?.let { error ->
            LifeLinkFeedbackCard(
                title = "Couldn’t load donor areas",
                message = error,
                level = LifeLinkFeedbackLevel.Error,
                actionLabel = "Retry",
                onAction = { onAction(PrivacyAction.LoadMap) },
                modifier = Modifier.align(Alignment.BottomCenter)
                    .padding(horizontal = 16.dp, vertical = 88.dp)
                    .widthIn(max = 360.dp)
                    .testTag("donor-map-error-feedback"),
            )
        }
        if (state.mapLoading) {
            LifeLinkLoadingIndicator(
                Modifier.align(Alignment.Center).testTag("donor-map-loading-indicator"),
                label = "Loading donor areas…",
            )
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
        Modifier.fillMaxWidth()
            .sizeIn(minHeight = 56.dp)
            .toggleable(
                value = checked,
                enabled = enabled,
                role = Role.Switch,
                onValueChange = onCheckedChange,
            )
            .testTag("donor-visibility-map-row"),
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
