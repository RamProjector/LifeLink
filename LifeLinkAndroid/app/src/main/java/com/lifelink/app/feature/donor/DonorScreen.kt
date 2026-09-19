package com.lifelink.app.feature.donor

import android.app.Activity
import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.google.android.gms.common.api.ResolvableApiException
import com.lifelink.app.core.location.LocationProvider
import com.lifelink.app.core.location.MapLibreLocationPicker
import com.lifelink.app.domain.DonorAvailability
import com.lifelink.app.domain.DonorProfile
import com.lifelink.app.domain.DonorRequest
import com.lifelink.app.domain.DonorResponse
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DonorScreen(state: DonorUiState, onAction: (DonorAction) -> Unit, onBack: () -> Unit) {
    var profileExpanded by remember { mutableStateOf(true) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var locationCaptureRequest by remember { mutableStateOf(0) }
    var locationMessage by remember { mutableStateOf<String?>(null) }
    val settingsResolutionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) locationCaptureRequest++
        else locationMessage = "Location services remain off. Use the map or manual coordinates instead."
    }
    val locationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
        if (permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true || permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true) {
            locationCaptureRequest++
        } else locationMessage = "Location permission was not granted. Use the map or manual coordinates instead."
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
            onFailure = { locationMessage = "Device location is unavailable. Use the map or manual coordinates instead." }
        )
    }
    Scaffold(topBar = {
        TopAppBar(
            title = { Text("Donor mode") },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } }
        )
    }) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 20.dp)
        ) {
            item {
                Text("Help when it matters", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                Text("Your availability controls which verified requests you see.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            item { AvailabilityCard(state.profile, onAction) }
            state.message?.let { message -> item { Text(message, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold) } }
            locationMessage?.let { message -> item { Text(message, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall) } }
            item {
                Button(onClick = { profileExpanded = !profileExpanded }, modifier = Modifier.fillMaxWidth()) {
                    Text(if (profileExpanded) "Hide donor profile and map" else "Show donor profile and map")
                }
            }
            if (profileExpanded) item {
                ProfileCard(
                    profile = state.profile,
                    onAction = onAction,
                    onCaptureLocation = {
                        val hasPermission = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                        if (hasPermission) locationCaptureRequest++
                        else locationLauncher.launch(arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION))
                    },
                    onLocationSelected = { latitude, longitude -> onAction(DonorAction.SetLocation(latitude, longitude, 500)) }
                )
            }
            item { Text("Requests near you", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold) }
            if (state.requests.isEmpty()) item { Text("No eligible requests right now.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            items(state.requests, key = { it.requestId }) { request -> RequestCard(request, onAction) }
        }
    }
}

@Composable private fun AvailabilityCard(profile: DonorProfile, onAction: (DonorAction) -> Unit) {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Availability", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(profile.availability.label, color = MaterialTheme.colorScheme.onSecondaryContainer)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DonorAvailability.values().forEach { option ->
                    FilterChip(selected = profile.availability == option, onClick = { onAction(DonorAction.SetAvailability(option)) }, label = { Text(option.label) })
                }
            }
        }
    }
}

@Composable
private fun ProfileCard(
    profile: DonorProfile,
    onAction: (DonorAction) -> Unit,
    onCaptureLocation: () -> Unit,
    onLocationSelected: (Double, Double) -> Unit
) {
    var name by remember(profile.displayName) { mutableStateOf(profile.displayName) }
    var area by remember(profile.area) { mutableStateOf(profile.area) }
    var manualLatitude by remember(profile.latitude) { mutableStateOf(profile.latitude?.toString().orEmpty()) }
    var manualLongitude by remember(profile.longitude) { mutableStateOf(profile.longitude?.toString().orEmpty()) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), label = { Text("Display name") }, singleLine = true)
            OutlinedTextField(area, { area = it }, Modifier.fillMaxWidth(), label = { Text("Area") }, singleLine = true)
            Text(
                if (profile.latitude == null) "Location not captured" else "Approximate location saved for matching (±${profile.locationPrecisionMeters} m)",
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Button(onClick = onCaptureLocation, modifier = Modifier.fillMaxWidth()) { Text(if (profile.latitude == null) "Use my current location" else "Update current location") }
            Text("Choose or adjust your approximate donor location", fontWeight = FontWeight.SemiBold)
            DonorLocationMap(profile.latitude, profile.longitude, onLocationSelected)
            Text("Only you can see this pin. Requesters receive distance and travel estimates, not your coordinates.", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(manualLatitude, { manualLatitude = it }, Modifier.weight(1f), label = { Text("Latitude") }, singleLine = true)
                OutlinedTextField(manualLongitude, { manualLongitude = it }, Modifier.weight(1f), label = { Text("Longitude") }, singleLine = true)
            }
            OutlinedButton(
                onClick = {
                    val latitude = manualLatitude.toDoubleOrNull()
                    val longitude = manualLongitude.toDoubleOrNull()
                    if (latitude != null && longitude != null && latitude in -90.0..90.0 && longitude in -180.0..180.0) onLocationSelected(latitude, longitude)
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Use this approximate location") }
            Button(onClick = { onAction(DonorAction.UpdateProfile(profile.copy(displayName = name, area = area))) }, Modifier.fillMaxWidth()) { Text("Save profile") }
        }
    }
}

@Composable
private fun DonorLocationMap(latitude: Double?, longitude: Double?, onLocationSelected: (Double, Double) -> Unit) {
    val selectedLatitude = latitude ?: 14.5995
    val selectedLongitude = longitude ?: 120.9842
    MapLibreLocationPicker(selectedLatitude, selectedLongitude, onLocationSelected)
}

@Composable private fun RequestCard(request: DonorRequest, onAction: (DonorAction) -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("${request.bloodType} · ${request.units} unit${if (request.units == 1) "" else "s"}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = MaterialTheme.shapes.small) {
                    Text(request.urgency.replaceFirstChar { it.uppercase() }, modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp), color = MaterialTheme.colorScheme.onPrimaryContainer, style = MaterialTheme.typography.labelMedium)
                }
            }
            Text(request.facilityName, fontWeight = FontWeight.SemiBold)
            Text("${request.area} · ${request.distanceKm} km away", color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (request.response == null) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { onAction(DonorAction.Respond(request.requestId, DonorResponse.ACCEPTED)) }, Modifier.weight(1f)) { Text("Accept") }
                    Button(onClick = { onAction(DonorAction.Respond(request.requestId, DonorResponse.DECLINED)) }, Modifier.weight(1f)) { Text("Decline") }
                }
            } else Text("Response: ${request.response.label}", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
        }
    }
}
