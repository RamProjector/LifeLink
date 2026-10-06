package com.lifelink.app.feature.donor

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.google.android.gms.common.api.ResolvableApiException
import com.lifelink.app.core.location.LocationProvider
import com.lifelink.app.core.location.MapLibreLocationPicker
import com.lifelink.app.core.ui.LifeLinkEmptyState
import com.lifelink.app.domain.BloodType
import com.lifelink.app.domain.DonorAvailability
import com.lifelink.app.domain.DonorProfile
import com.lifelink.app.domain.DonorRequest
import com.lifelink.app.domain.DonorResponse
import kotlinx.coroutines.launch

/**
 * Displays donor setup, availability, and the request inbox from [state].
 *
 * [onOpenConversation] receives the request ID and donor ID when the donor messages
 * a requester from an accepted or arrived request.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DonorScreen(
    state: DonorUiState,
    onAction: (DonorAction) -> Unit,
    onBack: () -> Unit,
    onOpenConversation: (String, String) -> Unit = { _, _ -> },
) {
    var profileExpanded by rememberSaveable { mutableStateOf(false) }
    var fullScreenProfile by rememberSaveable { mutableStateOf(false) }
    var selectedTab by rememberSaveable { mutableStateOf(DonorTab.HOME) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var locationCaptureRequest by remember { mutableStateOf(0) }
    var locationMessage by remember { mutableStateOf<String?>(null) }
    val settingsResolutionLauncher =
        rememberLauncherForActivityResult(
            ActivityResultContracts.StartIntentSenderForResult(),
        ) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                locationCaptureRequest++
            } else {
                locationMessage = "Location services remain off. Use the map or manual coordinates instead."
            }
        }
    val locationLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            if (permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true
            ) {
                locationCaptureRequest++
            } else {
                locationMessage = "Location permission was not granted. Use the map or manual coordinates instead."
            }
        }
    LaunchedEffect(locationCaptureRequest) {
        if (locationCaptureRequest == 0) return@LaunchedEffect
        val provider = LocationProvider(context)
        locationMessage = "Checking device location settings…"
        provider.checkLocationSettings(
            onReady = {
                scope.launch {
                    locationMessage = "Finding your current location…"
                    provider.currentLocation()?.let { location ->
                        onAction(DonorAction.SetLocation(location.latitude, location.longitude, location.precisionMeters))
                        locationMessage = "Location captured. Save your profile to update matching."
                    } ?: run { locationMessage = "Could not get a current fix. Try again or choose a location manually." }
                }
            },
            onNeedsResolution = { error: ResolvableApiException ->
                locationMessage = "Turn on device location to capture your current position."
                runCatching {
                    settingsResolutionLauncher.launch(IntentSenderRequest.Builder(error.resolution).build())
                }.onFailure { locationMessage = "Location settings could not be opened. Use the map or manual coordinates instead." }
            },
            onFailure = { locationMessage = "Device location is unavailable. Use the map or manual coordinates instead." },
        )
    }
    // Explicitly typed as () -> Unit: the branches below return Int (the post-increment)
    // and Unit (the launcher call), so an inferred type would be () -> Any and would not
    // satisfy the () -> Unit parameter of ProfileCard.
    val captureLocation: () -> Unit = {
        val hasPermission =
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (hasPermission) {
            locationCaptureRequest++
        } else {
            locationLauncher.launch(arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION))
        }
    }
    val selectLocation: (Double, Double) -> Unit = { latitude, longitude -> onAction(DonorAction.SetLocation(latitude, longitude, 500)) }
    // Unsaved editor input lives here, not inside ProfileCard, so switching between the
    // tabbed editor and the full-screen editor does not reset what the donor typed.
    var serviceRadius by remember(state.profile.donorId) { mutableStateOf(state.profile.serviceRadiusKm.toString()) }
    var manualLatitude by remember(state.profile.donorId) {
        mutableStateOf(
            state.profile.latitude
                ?.toString()
                .orEmpty(),
        )
    }
    var manualLongitude by remember(state.profile.donorId) {
        mutableStateOf(
            state.profile.longitude
                ?.toString()
                .orEmpty(),
        )
    }
    Scaffold(topBar = {
        TopAppBar(
            title = { Text("Donor workspace") },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            actions = {
                IconButton(
                    onClick = { fullScreenProfile = !fullScreenProfile },
                    enabled = profileExpanded || fullScreenProfile,
                ) {
                    Icon(
                        if (fullScreenProfile) Icons.Default.FullscreenExit else Icons.Default.Fullscreen,
                        if (fullScreenProfile) "Exit full screen profile" else "Open profile in full screen",
                    )
                }
                IconButton(
                    onClick = { onAction(DonorAction.RefreshRequests) },
                    enabled = !state.requestsRefreshing && state.profile.isSetupComplete,
                ) { Icon(Icons.Default.Refresh, "Refresh requests") }
            },
        )
    }) { padding ->
        if (fullScreenProfile) {
            // Full-screen profile editor: the whole surface is the form, so a long
            // donor profile is not cramped inside the tabbed workspace.
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                Column(Modifier.fillMaxSize().padding(padding)) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                    ) {
                        Text(
                            "Donor profile",
                            Modifier.weight(1f),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                        )
                        TextButton(onClick = { fullScreenProfile = false }) { Text("Exit full screen") }
                    }
                    LazyColumn(
                        Modifier.fillMaxSize().padding(horizontal = 20.dp),
                        contentPadding =
                        androidx.compose.foundation.layout
                            .PaddingValues(vertical = 12.dp),
                    ) {
                        item {
                            ProfileCard(
                                profile = state.profile,
                                saving = state.saving,
                                onAction = onAction,
                                onCaptureLocation = captureLocation,
                                onLocationSelected = selectLocation,
                                serviceRadius = serviceRadius,
                                onServiceRadiusChange = { value ->
                                    if (value.length <= 3 &&
                                        value.all(Char::isDigit)
                                    ) {
                                        serviceRadius = value
                                    }
                                },
                                manualLatitude = manualLatitude,
                                onManualLatitudeChange = { manualLatitude = it },
                                manualLongitude = manualLongitude,
                                onManualLongitudeChange = { manualLongitude = it },
                            )
                        }
                        // Keep the same status feedback the tabbed editor shows, so a
                        // failed save or a denied location permission is still visible
                        // while the donor is in full-screen mode.
                        (locationMessage ?: state.message)?.let { message -> item { StatusMessage(message) } }
                    }
                }
            }
            return@Scaffold
        }
        Column(Modifier.fillMaxSize().padding(padding)) {
            TabRow(selectedTabIndex = selectedTab.ordinal) {
                DonorTab.values().forEach { tab ->
                    Tab(
                        selected = selectedTab == tab,
                        onClick = { selectedTab = tab },
                        text = { Text(tab.label) },
                        modifier = Modifier.testTag("donor-tab-${tab.name}"),
                    )
                }
            }
            when (selectedTab) {
                DonorTab.HOME ->
                    DonorHomeContent(
                        state = state,
                        locationMessage = locationMessage,
                        profileExpanded = profileExpanded,
                        onToggleProfile = { profileExpanded = !profileExpanded },
                        onAction = onAction,
                        onCaptureLocation = captureLocation,
                        onLocationSelected = selectLocation,
                        serviceRadius = serviceRadius,
                        onServiceRadiusChange = { value -> if (value.length <= 3 && value.all(Char::isDigit)) serviceRadius = value },
                        manualLatitude = manualLatitude,
                        onManualLatitudeChange = { manualLatitude = it },
                        manualLongitude = manualLongitude,
                        onManualLongitudeChange = { manualLongitude = it },
                    )
                DonorTab.REQUESTS -> DonorRequestsContent(state, onAction, onOpenConversation)
            }
        }.imePadding()
    }
}

private enum class DonorTab(val label: String) {
    HOME("Home"),
    REQUESTS("Requests"),
}

/** Shows donor availability or setup guidance, status messages, and the expandable profile editor. */
@Composable
private fun DonorHomeContent(
    state: DonorUiState,
    locationMessage: String?,
    profileExpanded: Boolean,
    onToggleProfile: () -> Unit,
    onAction: (DonorAction) -> Unit,
    onCaptureLocation: () -> Unit,
    onLocationSelected: (Double, Double) -> Unit,
    serviceRadius: String,
    onServiceRadiusChange: (String) -> Unit,
    manualLatitude: String,
    onManualLatitudeChange: (String) -> Unit,
    manualLongitude: String,
    onManualLongitudeChange: (String) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        contentPadding =
        androidx.compose.foundation.layout
            .PaddingValues(vertical = 20.dp),
    ) {
        item {
            Text(
                "Ready to help",
                Modifier.semantics { heading() },
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
            )
            Text("Manage your availability, profile, and private location settings.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (!state.profile.isSetupComplete) {
            item { SetupRequiredCard(state.profile) }
        } else {
            item { AvailabilityCard(state.profile, onAction, state.saving) }
        }
        (locationMessage ?: state.message)?.let { message -> item { StatusMessage(message) } }
        item {
            OutlinedButton(onClick = onToggleProfile, modifier = Modifier.fillMaxWidth()) {
                Text(if (profileExpanded) "Hide profile editor" else "Edit donor profile")
            }
        }
        if (profileExpanded) {
            item {
                ProfileCard(
                    profile = state.profile,
                    saving = state.saving,
                    onAction = onAction,
                    onCaptureLocation = onCaptureLocation,
                    onLocationSelected = onLocationSelected,
                    serviceRadius = serviceRadius,
                    onServiceRadiusChange = onServiceRadiusChange,
                    manualLatitude = manualLatitude,
                    onManualLatitudeChange = onManualLatitudeChange,
                    manualLongitude = manualLongitude,
                    onManualLongitudeChange = onManualLongitudeChange,
                )
            }
        }
    }
}

/**
 * Shows setup guidance until the donor profile is complete, then renders the inbox.
 *
 * Passes the profile donor ID and [onOpenConversation] to each request card.
 */
@Composable
private fun DonorRequestsContent(
    state: DonorUiState,
    onAction: (DonorAction) -> Unit,
    onOpenConversation: (String, String) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding =
        androidx.compose.foundation.layout
            .PaddingValues(vertical = 20.dp),
    ) {
        item {
            Text(
                "Requests near you",
                Modifier.semantics { heading() },
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
            )
            Text(
                "Only requests matching your saved profile and availability appear here.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (state.requestsRefreshing) item { StatusMessage("Refreshing eligible requests…", compact = true, loading = true) }
        state.message?.let { message -> if (!state.requestsRefreshing) item { StatusMessage(message, compact = true) } }
        if (!state.profile.isSetupComplete) {
            item { SetupRequiredCard(state.profile) }
        } else if (state.requests.isEmpty()) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    LifeLinkEmptyState(
                        icon = Icons.Default.Refresh,
                        title = "No matching requests",
                        body = "New requests will appear here when available. Refreshing does not change your availability.",
                    )
                    OutlinedButton(
                        onClick = { onAction(DonorAction.RefreshRequests) },
                        enabled = !state.requestsRefreshing,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Refresh requests") }
                }
            }
        } else {
            items(state.requests, key = { it.requestId }) { request ->
                RequestCard(request, onAction, state.saving, state.profile.donorId, onOpenConversation)
            }
        }
    }
}

/** Displays a donor status message with optional compact styling and a loading indicator. */
@Composable
private fun StatusMessage(message: String, compact: Boolean = false, loading: Boolean = false) {
    Card(
        Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Row(
            Modifier.padding(if (compact) 12.dp else 14.dp),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (loading) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
            Text(
                message,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                style = if (compact) MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

/** Displays availability choices and dispatches changes while no save is in progress. */
@Composable private fun AvailabilityCard(profile: DonorProfile, onAction: (DonorAction) -> Unit, saving: Boolean) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                "Availability",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.semantics { heading() },
            )
            Text(profile.availability.label, color = MaterialTheme.colorScheme.onSecondaryContainer)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DonorAvailability.entries.forEach { option ->
                    FilterChip(
                        selected = profile.availability == option,
                        enabled = !saving,
                        onClick = { onAction(DonorAction.SetAvailability(option)) },
                        label = { Text(option.label) },
                        modifier = Modifier.testTag("donor-availability-${option.name}"),
                    )
                }
            }
        }
    }
}

/** Lists missing donor profile fields and explains why requests remain hidden until setup is complete. */
@Composable
private fun SetupRequiredCard(profile: DonorProfile) {
    val missing =
        buildList {
            if (profile.displayName.trim().length < 2) add("display name")
            if (profile.bloodType == null) add("blood type")
            if (profile.latitude == null || profile.longitude == null) add("approximate location")
            if (profile.serviceRadiusKm !in 1..100) add("service radius")
        }
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                "Finish donor setup",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.semantics { heading() },
            )
            Text(
                "Complete ${missing.joinToString()}. Requests stay hidden until your profile is ready.",
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Text(
                "Choose availability after setup is saved.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
    }
}

/**
 * Edits donor profile fields through [onAction] and the supplied location callbacks.
 *
 * The caller owns unsaved radius and manual coordinate text so it survives switching
 * between the tabbed and full-screen profile editors.
 */
@Composable
private fun ProfileCard(
    profile: DonorProfile,
    saving: Boolean,
    onAction: (DonorAction) -> Unit,
    onCaptureLocation: () -> Unit,
    onLocationSelected: (Double, Double) -> Unit,
    serviceRadius: String,
    onServiceRadiusChange: (String) -> Unit,
    manualLatitude: String,
    onManualLatitudeChange: (String) -> Unit,
    manualLongitude: String,
    onManualLongitudeChange: (String) -> Unit,
) {
    // The editable profile lives in the ViewModel, like the emergency-request draft.
    // GPS can therefore update only coordinates without recreating this form. The
    // unsaved service radius and manual coordinates are owned by DonorScreen so they
    // survive switching between the tabbed and full-screen editors.
    var showMoreSettings by rememberSaveable(profile.donorId) { mutableStateOf(false) }
    var coordinateError by remember { mutableStateOf<String?>(null) }
    Card(Modifier.fillMaxWidth(), elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                "Donor profile",
                Modifier.semantics { heading() },
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            OutlinedTextField(profile.displayName, { value ->
                onAction(
                    DonorAction.UpdateDraft {
                        it.copy(displayName = value)
                    },
                )
            }, Modifier.fillMaxWidth(), label = { Text("Display name") }, singleLine = true)
            OutlinedTextField(profile.area, { value ->
                onAction(
                    DonorAction.UpdateDraft {
                        it.copy(area = value)
                    },
                )
            }, Modifier.fillMaxWidth(), label = { Text("Area") }, singleLine = true)
            OutlinedButton(onClick = { showMoreSettings = !showMoreSettings }, modifier = Modifier.fillMaxWidth()) {
                Text(if (showMoreSettings) "Hide optional settings" else "Show optional settings")
            }
            if (showMoreSettings) {
                OutlinedTextField(
                    value = profile.donorNote,
                    onValueChange = { if (it.length <= 500) onAction(DonorAction.UpdateDraft { draft -> draft.copy(donorNote = it) }) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Optional donor note") },
                    minLines = 2,
                    maxLines = 4,
                )
                Text("Preferred contact method", fontWeight = FontWeight.SemiBold)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("in_app" to "In-app", "phone" to "Phone").forEach { (value, label) ->
                        FilterChip(
                            selected = profile.preferredContactMethod == value,
                            onClick = { onAction(DonorAction.UpdateDraft { it.copy(preferredContactMethod = value) }) },
                            label = { Text(label) },
                        )
                    }
                }
                if (profile.availability == DonorAvailability.PAUSED) {
                    OutlinedTextField(
                        value = profile.pauseReason.orEmpty(),
                        onValueChange = {
                            if (it.length <=
                                240
                            ) {
                                onAction(DonorAction.UpdateDraft { draft -> draft.copy(pauseReason = it) })
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Why are you paused? (optional)") },
                        singleLine = true,
                    )
                }
                Row(
                    Modifier
                        .fillMaxWidth()
                        .toggleable(
                            value = profile.profileVisible,
                            onValueChange = { value ->
                                onAction(DonorAction.UpdateDraft { it.copy(profileVisible = value) })
                            },
                        ),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Profile visible to requesters", fontWeight = FontWeight.SemiBold)
                        Text(
                            if (profile.profileVisible) {
                                "You can appear in matching results when available."
                            } else {
                                "You will not appear in new matching results."
                            },
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Switch(checked = profile.profileVisible, onCheckedChange = null)
                }
            }
            Text("Blood type", fontWeight = FontWeight.SemiBold)
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                BloodType.entries.chunked(4).forEach { rowOptions ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        rowOptions.forEach { option ->
                            FilterChip(
                                selected = profile.bloodType == option,
                                onClick = { onAction(DonorAction.UpdateDraft { it.copy(bloodType = option) }) },
                                label = { Text(option.label) },
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
            }
            OutlinedTextField(
                value = serviceRadius,
                onValueChange = onServiceRadiusChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Service radius (km)") },
                singleLine = true,
            )
            Text(
                text =
                if (profile.latitude == null) {
                    "Location not captured"
                } else {
                    "Approximate location saved for matching " +
                        "(±${profile.locationPrecisionMeters} m)"
                },
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(onClick = onCaptureLocation, enabled = !saving, modifier = Modifier.fillMaxWidth()) {
                Text(
                    if (profile.latitude ==
                        null
                    ) {
                        "Use my current location"
                    } else {
                        "Update current location"
                    },
                )
            }
            Text("Choose or adjust your approximate donor location", fontWeight = FontWeight.SemiBold)
            DonorLocationMap(profile.latitude, profile.longitude, onLocationSelected)
            Text(
                "Only you can see this pin. Requesters receive distance and travel estimates, not your coordinates.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    manualLatitude,
                    onManualLatitudeChange,
                    Modifier.weight(1f),
                    label = { Text("Latitude") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
                OutlinedTextField(
                    manualLongitude,
                    onManualLongitudeChange,
                    Modifier.weight(1f),
                    label = { Text("Longitude") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
            }
            coordinateError?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            OutlinedButton(
                onClick = {
                    val latitude = manualLatitude.toDoubleOrNull()
                    val longitude = manualLongitude.toDoubleOrNull()
                    when {
                        manualLatitude.isBlank() || manualLongitude.isBlank() ->
                            coordinateError = "Enter both a latitude and a longitude before applying."
                        latitude == null || longitude == null ->
                            coordinateError = "Use decimal numbers, for example 14.5995 and 120.9842."
                        latitude !in -90.0..90.0 || longitude !in -180.0..180.0 ->
                            coordinateError = "Latitude must be between -90 and 90, and longitude between -180 and 180."
                        else -> {
                            coordinateError = null
                            onLocationSelected(latitude, longitude)
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Use this approximate location") }
            Button(
                onClick = {
                    serviceRadius.toIntOrNull()?.let { radius ->
                        onAction(DonorAction.UpdateDraft { it.copy(serviceRadiusKm = radius) })
                        onAction(DonorAction.SaveProfile)
                    }
                },
                enabled =
                !saving &&
                    profile.displayName.trim().length >= 2 &&
                    profile.bloodType != null &&
                    profile.latitude != null &&
                    profile.longitude != null &&
                    serviceRadius.toIntOrNull() in 1..100,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    if (saving) {
                        "Saving…"
                    } else if (profile.isSetupComplete) {
                        "Save changes"
                    } else {
                        "Complete donor setup"
                    },
                )
            }
        }
    }
}

/** Lets the donor select an approximate location without inventing a default city. */
@Composable
private fun DonorLocationMap(latitude: Double?, longitude: Double?, onLocationSelected: (Double, Double) -> Unit) {
    var retryRequest by remember { mutableStateOf(0) }
    // MapLibreLocationPicker renders its own loading and error UI, so this screen
    // must not add a second spinner on top of it.
    Box(Modifier.fillMaxWidth()) {
        MapLibreLocationPicker(
            latitude,
            longitude,
            onLocationSelected,
            retryRequest,
            modifier = Modifier.fillMaxWidth().height(260.dp),
        )
        OutlinedButton(onClick = {
            retryRequest++
        }, modifier = Modifier.align(androidx.compose.ui.Alignment.BottomEnd).padding(12.dp)) { Text("Recenter") }
    }
    Text(
        if (latitude != null && longitude != null) {
            "Tap or long-press to move the approximate donor location."
        } else {
            "No location selected yet. Tap or long-press the map to choose an approximate donor area."
        },
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/**
 * Displays a donor request with response actions disabled while [saving].
 *
 * Accepted or arrived requests can open a conversation using the request ID and
 * [donorId]; [onOpenConversation] delegates navigation to the caller.
 */
@Composable private fun RequestCard(
    request: DonorRequest,
    onAction: (DonorAction) -> Unit,
    saving: Boolean,
    donorId: String,
    onOpenConversation: (String, String) -> Unit,
) {
    Card(Modifier.fillMaxWidth(), elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            val urgencyColors =
                when (request.urgency.lowercase()) {
                    "critical" -> MaterialTheme.colorScheme.errorContainer to MaterialTheme.colorScheme.onErrorContainer
                    "urgent" -> MaterialTheme.colorScheme.tertiaryContainer to MaterialTheme.colorScheme.onTertiaryContainer
                    else -> MaterialTheme.colorScheme.secondaryContainer to MaterialTheme.colorScheme.onSecondaryContainer
                }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "${request.bloodType} · ${request.units} unit${if (request.units == 1) "" else "s"}",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                Surface(color = urgencyColors.first, shape = MaterialTheme.shapes.small) {
                    Text(
                        request.urgency.replaceFirstChar { it.uppercase() },
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        color = urgencyColors.second,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
            Text(request.facilityName, fontWeight = FontWeight.SemiBold)
            Text("${request.area} · ${request.distanceKm} km away", color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (request.response == null) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(enabled = !saving, onClick = {
                        onAction(DonorAction.Respond(request.requestId, DonorResponse.ACCEPTED))
                    }, modifier = Modifier.weight(1f)) { Text("Accept") }
                    OutlinedButton(enabled = !saving, onClick = {
                        onAction(DonorAction.Respond(request.requestId, DonorResponse.DECLINED))
                    }, modifier = Modifier.weight(1f)) { Text("Decline") }
                }
            } else {
                Text("Response: ${request.response.label}", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                // A donor who accepted can open the in-app conversation with the
                // requester directly from their accepted request.
                if (request.response == DonorResponse.ACCEPTED || request.response == DonorResponse.ARRIVED) {
                    OutlinedButton(
                        onClick = { onOpenConversation(request.requestId, donorId) },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Message requester") }
                }
            }
        }
    }
}
