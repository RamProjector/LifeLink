package com.lifelink.app.feature.emergencyrequest

import android.app.Activity
import android.content.pm.PackageManager
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lifelink.app.domain.BloodType
import com.lifelink.app.domain.ContactMethod
import com.lifelink.app.domain.EmergencyRequestDraft
import com.lifelink.app.domain.Facility
import com.lifelink.app.domain.RequestStep
import com.lifelink.app.domain.Urgency
import com.lifelink.app.core.location.LocationProvider
import com.lifelink.app.core.location.MapLibreLocationPicker
import com.lifelink.app.core.location.MapLibrePrivacySafeDonorMap
import com.google.android.gms.common.api.ResolvableApiException
import kotlinx.coroutines.launch
import androidx.core.content.ContextCompat

@Composable
fun LifeLinkApp(state: EmergencyRequestUiState, onAction: (EmergencyRequestAction) -> Unit) {
    EmergencyRequestScreen(state = state, onAction = onAction)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EmergencyRequestScreen(state: EmergencyRequestUiState, onAction: (EmergencyRequestAction) -> Unit) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text(if (state.step == RequestStep.REVIEW) "Review request" else "Create request", fontWeight = FontWeight.SemiBold) },
                navigationIcon = { IconButton(onClick = { onAction(EmergencyRequestAction.Back) }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        },
        bottomBar = {
            BottomBar(
                state = state,
                onContinue = { onAction(EmergencyRequestAction.Continue) },
                onSubmit = { onAction(EmergencyRequestAction.Submit) },
                onSave = { onAction(EmergencyRequestAction.SaveDraft) }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            item { Progress(step = state.step.index, total = RequestStep.entries.size) }
            item {
                when (state.step) {
                    RequestStep.BLOOD_NEED -> BloodNeedStep(state.draft, onAction)
                    RequestStep.URGENCY -> UrgencyStep(state.draft, onAction)
                    RequestStep.LOCATION -> LocationStep(state.draft, onAction)
                    RequestStep.CONTACT -> ContactStep(state.draft, onAction)
                    RequestStep.REVIEW -> ReviewStep(state.draft, onAction)
                }
            }
            val submission = state.submission
            if (submission is SubmissionState.Error) item { ErrorBanner(submission.message, onRetry = { onAction(EmergencyRequestAction.Retry) }) }
            if (submission is SubmissionState.Matching) item { SuccessBanner("Request submitted. Finding eligible donors…") }
            if (state.discoveredDonors.isNotEmpty()) item { DonorPicker(state, onAction) }
            if (submission is SubmissionState.QueuedOffline) item { SuccessBanner("Saved offline. It will sync when connection returns.") }
            if (submission is SubmissionState.ManualFallback) item {
                ManualFallbackBanner(
                    reason = submission.reason,
                    onSend = { onAction(EmergencyRequestAction.SendManualBroadcast) }
                )
            }
        }
    }
    if (state.criticalConfirmationVisible) CriticalSheet(state.draft, onAction)
}

@Composable
private fun DonorPicker(state: EmergencyRequestUiState, onAction: (EmergencyRequestAction) -> Unit) {
    var showMap by remember { mutableStateOf(false) }
    val donorsWithinFiveKm = state.discoveredDonors.count { it.distanceKm <= 5.0 }
    val donorsWithinTenKm = state.discoveredDonors.count { it.distanceKm > 5.0 && it.distanceKm <= 10.0 }
    val donorsBeyondTenKm = state.discoveredDonors.count { it.distanceKm > 10.0 }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Heading("Choose donors to contact", "LifeLink only contacts donors you select. Screening and final eligibility happen outside the app.")
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("Results", fontWeight = FontWeight.SemiBold)
            FilterChip(selected = showMap, onClick = { showMap = !showMap }, label = { Text(if (showMap) "Hide map" else "Show map summary") })
        }
        if (showMap) {
            val latitude = state.draft.requesterLatitude
            val longitude = state.draft.requesterLongitude
            if (latitude != null && longitude != null) {
                PrivacySafeDonorMap(latitude, longitude, donorsWithinFiveKm, donorsWithinTenKm, donorsBeyondTenKm)
            } else {
                InfoCard("Map summary unavailable", "The request location is not available. Review the donor list below.", MaterialTheme.colorScheme.secondary)
            }
        }
        Text("Donor list", fontWeight = FontWeight.SemiBold)
        state.discoveredDonors.forEach { donor ->
            Card(
                modifier = Modifier.fillMaxWidth().clickable { onAction(EmergencyRequestAction.ToggleDonorSelection(donor.donorId)) },
                colors = CardDefaults.cardColors(containerColor = if (donor.donorId in state.selectedDonorIds) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface)
            ) {
                Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = donor.donorId in state.selectedDonorIds, onCheckedChange = { onAction(EmergencyRequestAction.ToggleDonorSelection(donor.donorId)) })
                    Column(Modifier.padding(start = 8.dp)) {
                        Text(donor.displayName, fontWeight = FontWeight.SemiBold)
                        Text("${donor.bloodType} · ${"%.1f".format(donor.distanceKm)} km · about ${donor.travelMinutes} min", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        donor.explanation.firstOrNull()?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                }
            }
        }
        if (state.contactRequestSent) {
            SuccessBanner("Contact request sent to ${state.selectedDonorIds.size} selected donor(s).")
        } else {
            Button(onClick = { onAction(EmergencyRequestAction.ContactSelectedDonors) }, enabled = state.selectedDonorIds.isNotEmpty(), modifier = Modifier.fillMaxWidth()) { Text("Contact selected donors") }
        }
        Text("This is a discovery and contact aid, not medical screening.", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun PrivacySafeDonorMap(
    latitude: Double,
    longitude: Double,
    withinFiveKm: Int,
    withinTenKm: Int,
    beyondTenKm: Int
) {
    MapLibrePrivacySafeDonorMap(latitude, longitude)
    Text("Within 5 km: $withinFiveKm · 5–10 km: $withinTenKm · Beyond 10 km: $beyondTenKm", style = MaterialTheme.typography.bodySmall)
    Text(
        "Map summary only: circles show distance bands and donor counts. No donor names or exact donor locations are shown.",
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.bodySmall
    )
}

@Composable private fun Progress(step: Int, total: Int) {
    Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
        Text("${step + 1} of $total", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelLarge)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            repeat(total) { index -> Surface(Modifier.weight(1f).height(5.dp), color = if (index <= step) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline, shape = MaterialTheme.shapes.small) {} }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable private fun BloodNeedStep(draft: EmergencyRequestDraft, onAction: (EmergencyRequestAction) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Heading("What blood is needed?", "Select the type and amount required.")
        Text("Blood type", fontWeight = FontWeight.SemiBold)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(9.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            BloodType.entries.forEach { type -> Chip(type.label, draft.bloodType == type && !draft.typeUnknown) { onAction(EmergencyRequestAction.UpdateDraft { it.copy(bloodType = type, typeUnknown = false) }) } }
        }
        Text("Units needed", fontWeight = FontWeight.SemiBold)
        QuantityStepper(draft.units) { units -> onAction(EmergencyRequestAction.UpdateDraft { it.copy(units = units) }) }
        CheckRow(draft.typeUnknown, "I’m not sure of the exact type", "A blood-bank professional must verify compatibility before a response is accepted.") { checked -> onAction(EmergencyRequestAction.UpdateDraft { it.copy(typeUnknown = checked, bloodType = if (checked) null else it.bloodType) }) }
        InfoCard("Why we ask", "Blood-type eligibility is rule-based and checked before geographic prioritization.")
    }
}

@Composable private fun UrgencyStep(draft: EmergencyRequestDraft, onAction: (EmergencyRequestAction) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Heading("How soon is help needed?", "Choose the closest accurate option.")
        Urgency.entries.forEach { urgency ->
            Card(
                modifier = Modifier.fillMaxWidth().clickable(role = Role.RadioButton) { onAction(EmergencyRequestAction.UpdateDraft { it.copy(urgency = urgency) }) },
                colors = CardDefaults.cardColors(containerColor = if (draft.urgency == urgency) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface),
                border = if (draft.urgency == urgency) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
            ) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(draft.urgency == urgency, { onAction(EmergencyRequestAction.UpdateDraft { it.copy(urgency = urgency) }) })
                    Column(Modifier.padding(start = 7.dp)) { Text(urgency.label, fontWeight = FontWeight.SemiBold); Text(urgency.description, color = MaterialTheme.colorScheme.onSurfaceVariant); if (urgency == Urgency.CRITICAL) Text("Use only for an immediate, verified need", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelSmall) }
                }
            }
        }
        TextField(draft.responseDeadline, { value -> onAction(EmergencyRequestAction.UpdateDraft { draftValue -> draftValue.copy(responseDeadline = value) }) }, "Latest acceptable response", "Today, 12:30 PM")
        TextField(draft.note, { value -> if (value.length <= 180) onAction(EmergencyRequestAction.UpdateDraft { draftValue -> draftValue.copy(note = value) }) }, "Request note (optional)", "Do not include patient names or diagnoses.", minLines = 3, supporting = "${draft.note.length}/180 characters")
    }
}

@Composable
private fun LocationMapPicker(
    draft: EmergencyRequestDraft,
    onLocationSelected: (Double, Double) -> Unit,
    modifier: Modifier = Modifier
) {
    if (draft.requesterLatitude != null && draft.requesterLongitude != null) {
        MapLibreLocationPicker(draft.requesterLatitude, draft.requesterLongitude, onLocationSelected, modifier)
    } else {
        Card(
            modifier = Modifier.fillMaxWidth().height(260.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    "Map preview appears after you capture or choose a location.",
                    modifier = Modifier.padding(24.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun LocationStep(draft: EmergencyRequestDraft, onAction: (EmergencyRequestAction) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var locationCaptureRequest by remember { mutableStateOf(0) }
    var locationMessage by remember { mutableStateOf<String?>(null) }
    var fullMapVisible by remember { mutableStateOf(false) }
    val settingsResolutionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            locationCaptureRequest++
        } else {
            locationMessage = "Location services remain off. You can choose a point on the map or enter coordinates manually."
        }
    }
    var manualLatitude by remember { mutableStateOf(draft.requesterLatitude?.toString().orEmpty()) }
    var manualLongitude by remember { mutableStateOf(draft.requesterLongitude?.toString().orEmpty()) }
    val locationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions[android.Manifest.permission.ACCESS_FINE_LOCATION] == true || permissions[android.Manifest.permission.ACCESS_COARSE_LOCATION] == true) {
            locationCaptureRequest++
        }
    }
    LaunchedEffect(Unit) {
        val hasLocationPermission = ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED || ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        if (draft.requesterLatitude == null && hasLocationPermission) locationCaptureRequest++
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
                        onAction(EmergencyRequestAction.SetGpsLocation(location.latitude, location.longitude, location.precisionMeters))
                        locationMessage = "Map centered on your current location (accuracy ±${location.precisionMeters} m)."
                    } ?: run { locationMessage = "Could not get a current fix. Try again or choose a location manually." }
                }
            },
            onNeedsResolution = { error: ResolvableApiException ->
                locationMessage = "Turn on device location to center the map automatically."
                runCatching {
                    settingsResolutionLauncher.launch(IntentSenderRequest.Builder(error.resolution).build())
                }.onFailure {
                    locationMessage = "Location settings could not be opened. Choose a location manually."
                }
            },
            onFailure = { locationMessage = "Device location is unavailable. Choose a location manually." }
        )
    }
    Column(verticalArrangement = Arrangement.spacedBy(15.dp)) {
        Heading("Where are you requesting help?", "Your approximate location is used only to find nearby eligible donors.")
        InfoCard("Privacy-first GPS", "Your precise coordinates are used for matching and are not shown to donors.", MaterialTheme.colorScheme.secondary)
        locationMessage?.let { Text(it, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall) }
        OutlinedButton(
            modifier = Modifier.fillMaxWidth(),
            onClick = {
                locationPermissionLauncher.launch(
                    arrayOf(
                        android.Manifest.permission.ACCESS_COARSE_LOCATION,
                        android.Manifest.permission.ACCESS_FINE_LOCATION
                    )
                )
            }
        ) { Text(if (draft.requesterLatitude == null) "Use my current location" else "Update current location") }
        Text("Choose on map", fontWeight = FontWeight.SemiBold)
        LocationMapPicker(
            draft = draft,
            onLocationSelected = { latitude, longitude -> onAction(EmergencyRequestAction.SetGpsLocation(latitude, longitude, 500)) }
        )
        if (draft.requesterLatitude != null && draft.requesterLongitude != null) {
            OutlinedButton(modifier = Modifier.fillMaxWidth(), onClick = { fullMapVisible = true }) {
                Text("Open full map")
            }
        }
        Text("When location access is already allowed, the map centers on your current position automatically. The pin is visible only to you; donors receive an approximate matching distance, not your coordinates.", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        Text("Or enter an approximate location manually", fontWeight = FontWeight.SemiBold)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(
                value = manualLatitude,
                onValueChange = { manualLatitude = it },
                modifier = Modifier.weight(1f),
                label = { Text("Latitude") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
            )
            OutlinedTextField(
                value = manualLongitude,
                onValueChange = { manualLongitude = it },
                modifier = Modifier.weight(1f),
                label = { Text("Longitude") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
            )
        }
        OutlinedButton(
            modifier = Modifier.fillMaxWidth(),
            onClick = {
                val latitude = manualLatitude.toDoubleOrNull()
                val longitude = manualLongitude.toDoubleOrNull()
                if (latitude != null && longitude != null && latitude in -90.0..90.0 && longitude in -180.0..180.0) {
                    onAction(EmergencyRequestAction.SetGpsLocation(latitude, longitude, 500))
                }
            }
        ) { Text("Use this approximate location") }
        Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color(0xFFECE9E2))) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(Icons.Default.LocationOn, "Request location", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(32.dp))
                Text(if (draft.requesterLatitude == null) "Location not captured" else "Approximate location captured", fontWeight = FontWeight.SemiBold)
                Text("Donors see distance and availability—not your coordinates.", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
    if (fullMapVisible && draft.requesterLatitude != null && draft.requesterLongitude != null) {
        ModalBottomSheet(onDismissRequest = { fullMapVisible = false }) {
            Column(
                Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text("Choose an approximate location", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text("Pan and zoom the map, then tap or long-press to move the private marker.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                LocationMapPicker(
                    draft = draft,
                    onLocationSelected = { latitude, longitude -> onAction(EmergencyRequestAction.SetGpsLocation(latitude, longitude, 500)) },
                    modifier = Modifier.height(520.dp)
                )
                OutlinedButton(modifier = Modifier.fillMaxWidth(), onClick = { fullMapVisible = false }) { Text("Done") }
            }
        }
    }
}

@Composable private fun ContactStep(draft: EmergencyRequestDraft, onAction: (EmergencyRequestAction) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Heading("How should responses work?", "Choose how eligible donors can contact you after they accept.")
        Text("Preferred contact", fontWeight = FontWeight.SemiBold)
        ContactMethod.entries.forEach { method -> SelectableRow(method.label, draft.contactMethod == method) { onAction(EmergencyRequestAction.UpdateDraft { it.copy(contactMethod = method) }) } }
        InfoCard("Contact privacy", "Your contact details stay private until a donor accepts your request.", MaterialTheme.colorScheme.secondary)
        CheckRow(draft.genuineRequestConfirmed, "I confirm this is a genuine blood request.") { checked -> onAction(EmergencyRequestAction.UpdateDraft { draftValue -> draftValue.copy(genuineRequestConfirmed = checked) }) }
        CheckRow(draft.sharingConsentConfirmed, "I agree to share the listed request details with eligible donors for this request.") { checked -> onAction(EmergencyRequestAction.UpdateDraft { draftValue -> draftValue.copy(sharingConsentConfirmed = checked) }) }
    }
}

@Composable private fun ReviewStep(draft: EmergencyRequestDraft, onAction: (EmergencyRequestAction) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Heading("Check everything before sending", "Notifications will go only to eligible, relevant donors.")
        Surface(Modifier.fillMaxWidth(), color = if (draft.urgency == Urgency.CRITICAL) MaterialTheme.colorScheme.primaryContainer else Color.White, shape = MaterialTheme.shapes.large) { Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) { Text("${draft.bloodType?.label ?: "Unknown type"} · ${draft.units} unit${if (draft.units == 1) "" else "s"}", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold); Text("${draft.urgency.label.uppercase()} · ${displayDeadline(draft)}", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold); Text(locationSummary(draft)) } }
        Summary("Blood need", "${draft.bloodType?.label ?: "Unknown type"} · ${draft.units} unit${if (draft.units == 1) "" else "s"}", 0, onAction)
        Summary("Urgency", "${draft.urgency.label} · ${draft.urgency.description}", 1, onAction)
        Summary("Request location", locationSummary(draft), 2, onAction)
        Summary("Contact", draft.contactMethod.label, 3, onAction)
        CheckRow(
            checked = draft.aiMatchingEnabled,
            label = "Use AI-assisted donor ranking",
            supporting = "When enabled, LifeLink weighs distance, travel estimate, availability, verification, urgency, and response likelihood. When disabled, results are sorted by GPS distance only."
        ) { enabled -> onAction(EmergencyRequestAction.UpdateDraft { it.copy(aiMatchingEnabled = enabled) }) }
        InfoCard("Matching will consider", "✓ Blood-type eligibility\n✓ Distance and estimated travel time\n✓ Availability and verification\n✓ Request urgency", MaterialTheme.colorScheme.secondary)
    }
}

@Composable private fun BottomBar(state: EmergencyRequestUiState, onContinue: () -> Unit, onSubmit: () -> Unit, onSave: () -> Unit) {
    Surface(Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.background, shadowElevation = 8.dp) { Column(Modifier.navigationBarsPadding().imePadding().padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { if (state.step == RequestStep.REVIEW) TextButton(onClick = onSave) { Text("Save as draft") }; Button(onClick = if (state.step == RequestStep.REVIEW) onSubmit else onContinue, modifier = Modifier.fillMaxWidth().height(54.dp), enabled = state.submission !is SubmissionState.Submitting, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary), shape = MaterialTheme.shapes.medium) { Text(if (state.step == RequestStep.REVIEW) "Submit emergency request" else "Continue →", fontWeight = FontWeight.SemiBold) } } }
}

@Composable private fun Heading(title: String, subtitle: String) { Column(verticalArrangement = Arrangement.spacedBy(5.dp)) { Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold); Text(subtitle, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant) } }
@Composable private fun Chip(label: String, selected: Boolean, onClick: () -> Unit) { Surface(Modifier.height(48.dp).clickable(role = Role.RadioButton, onClick = onClick).semantics { role = Role.RadioButton }, color = if (selected) MaterialTheme.colorScheme.primary else Color.White, shape = MaterialTheme.shapes.medium, border = BorderStroke(1.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline)) { Box(Modifier.padding(horizontal = 18.dp), contentAlignment = Alignment.Center) { Text(label, color = if (selected) Color.White else MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold) } } }
@Composable private fun QuantityStepper(quantity: Int, onChange: (Int) -> Unit) { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) { OutlinedButton({ if (quantity > 1) onChange(quantity - 1) }, enabled = quantity > 1, modifier = Modifier.size(52.dp), contentPadding = PaddingValues(0.dp)) { Text("−", fontSize = 24.sp) }; Text("$quantity unit${if (quantity == 1) "" else "s"}", Modifier.padding(horizontal = 24.dp), fontWeight = FontWeight.SemiBold); OutlinedButton({ if (quantity < 20) onChange(quantity + 1) }, enabled = quantity < 20, modifier = Modifier.size(52.dp), contentPadding = PaddingValues(0.dp)) { Text("+", fontSize = 24.sp) } } }
@Composable private fun CheckRow(checked: Boolean, label: String, supporting: String? = null, onChecked: (Boolean) -> Unit) { Row(Modifier.fillMaxWidth().clickable(role = Role.Checkbox) { onChecked(!checked) }.semantics { role = Role.Checkbox }, verticalAlignment = Alignment.Top) { Checkbox(checked, onChecked); Column(Modifier.padding(top = 12.dp, start = 8.dp)) { Text(label); supporting?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall) } } } }
@Composable private fun InfoCard(title: String, body: String, accent: Color = MaterialTheme.colorScheme.primary) { Surface(Modifier.fillMaxWidth(), color = if (accent == MaterialTheme.colorScheme.secondary) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.primaryContainer, shape = MaterialTheme.shapes.medium) { Row(Modifier.padding(14.dp), verticalAlignment = Alignment.Top) { Icon(if (accent == MaterialTheme.colorScheme.secondary) Icons.Default.Check else Icons.Default.Warning, null, tint = accent); Column(Modifier.padding(start = 10.dp)) { Text(title, fontWeight = FontWeight.SemiBold); Text(body, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall) } } } }
@Composable private fun FacilityRow(facility: Facility, selected: Boolean, onClick: () -> Unit) { Surface(Modifier.fillMaxWidth().clickable(role = Role.RadioButton, onClick = onClick).semantics { role = Role.RadioButton }, color = if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.White, shape = MaterialTheme.shapes.medium, border = BorderStroke(1.dp, if (selected) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.outline)) { Row(Modifier.padding(13.dp), verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Default.LocationOn, "Facility location", tint = MaterialTheme.colorScheme.secondary); Column(Modifier.padding(start = 10.dp).weight(1f)) { Text(facility.name, fontWeight = FontWeight.SemiBold); Text(facility.area, color = MaterialTheme.colorScheme.onSurfaceVariant) }; if (facility.verified) Icon(Icons.Default.Check, "Verified facility", tint = MaterialTheme.colorScheme.secondary) } } }
@Composable private fun SelectableRow(label: String, selected: Boolean, onClick: () -> Unit) { Surface(Modifier.fillMaxWidth().clickable(role = Role.RadioButton, onClick = onClick).semantics { role = Role.RadioButton }, color = if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.White, shape = MaterialTheme.shapes.medium, border = BorderStroke(1.dp, if (selected) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.outline)) { Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) { RadioButton(selected, onClick); Text(label, fontWeight = FontWeight.Medium) } } }
@Composable private fun Summary(title: String, value: String, step: Int, onAction: (EmergencyRequestAction) -> Unit) { Column { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) { Column(Modifier.weight(1f)) { Text(title, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelMedium); Text(value, fontWeight = FontWeight.Medium) }; TextButton(onClick = { onAction(EmergencyRequestAction.EditStep(RequestStep.entries[step])) }) { Text("Edit") } }; HorizontalDivider(color = MaterialTheme.colorScheme.outline) } }
@Composable private fun ErrorBanner(message: String, onRetry: () -> Unit) {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF4E5))) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Warning, "Error", tint = Color(0xFF7A4A00), modifier = Modifier.size(28.dp))
            Column(Modifier.weight(1f).padding(start = 10.dp)) {
                Text("Something needs attention", fontWeight = FontWeight.SemiBold, color = Color(0xFF7A4A00))
                Text(message, color = Color(0xFF7A4A00), style = MaterialTheme.typography.bodySmall)
            }
            TextButton(onClick = onRetry) { Text("Retry") }
        }
    }
}
@Composable private fun SuccessBanner(message: String) {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Check, "Success", tint = MaterialTheme.colorScheme.secondary, modifier = Modifier.size(28.dp))
            Column(Modifier.padding(start = 10.dp)) {
                Text("LifeLink update", fontWeight = FontWeight.SemiBold)
                Text(message, color = MaterialTheme.colorScheme.onSecondaryContainer)
            }
        }
    }
}
@Composable private fun ManualFallbackBanner(reason: String, onSend: () -> Unit) { Surface(Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.primaryContainer, shape = MaterialTheme.shapes.medium) { Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { Text("Automatic matching is unavailable", fontWeight = FontWeight.SemiBold); Text(reason, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall); Text("A manual broadcast may reach more eligible donors.", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall); Button(onClick = onSend) { Text("Send manual broadcast") } } } }
@Composable private fun TextField(value: String, onValueChange: (String) -> Unit, label: String, placeholder: String, minLines: Int = 1, supporting: String? = null) { OutlinedTextField(value, onValueChange, Modifier.fillMaxWidth(), label = { Text(label) }, placeholder = { Text(placeholder) }, minLines = minLines, singleLine = minLines == 1, supportingText = supporting?.let { { Text(it) } }, trailingIcon = if (label.contains("deadline")) ({ Icon(Icons.Default.KeyboardArrowDown, null) }) else null, shape = MaterialTheme.shapes.medium) }

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun CriticalSheet(draft: EmergencyRequestDraft, onAction: (EmergencyRequestAction) -> Unit) { ModalBottomSheet(onDismissRequest = { onAction(EmergencyRequestAction.DismissCriticalSubmit) }) { Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) { Text("Send this emergency request?", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold); Text("Eligible nearby donors will be notified. You can stop alerts from the live request screen.", color = MaterialTheme.colorScheme.onSurfaceVariant); InfoCard("Request summary", "${draft.bloodType?.label ?: "Unknown type"} · ${draft.units} unit${if (draft.units == 1) "" else "s"}\n${locationSummary(draft)}"); Button(onClick = { onAction(EmergencyRequestAction.ConfirmCriticalSubmit) }, shape = MaterialTheme.shapes.medium) { Text("Send request") }; OutlinedButton(onClick = { onAction(EmergencyRequestAction.DismissCriticalSubmit) }) { Text("Go back and edit") }; Spacer(Modifier.height(8.dp)) } } }

private fun locationSummary(draft: EmergencyRequestDraft): String =
    if (draft.requesterLatitude != null && draft.requesterLongitude != null) "Approximate GPS location captured"
    else "Approximate GPS location not captured"

private fun displayDeadline(draft: EmergencyRequestDraft): String =
    if (draft.responseDeadline.isBlank() || draft.responseDeadline.equals("hours", ignoreCase = true)) "response deadline not set"
    else "respond by ${draft.responseDeadline}"
