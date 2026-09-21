package com.lifelink.app.feature.emergencyrequest

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.AlertDialog
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
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.core.content.ContextCompat

@Composable
fun LifeLinkApp(state: EmergencyRequestUiState, onAction: (EmergencyRequestAction) -> Unit) {
    EmergencyRequestScreen(state = state, onAction = onAction)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EmergencyRequestScreen(
    state: EmergencyRequestUiState,
    onAction: (EmergencyRequestAction) -> Unit,
    onExit: () -> Unit = {}
) {
    val snackbarHostState = remember { SnackbarHostState() }
    val handleBack = {
        if (state.step == RequestStep.BLOOD_NEED || state.step == RequestStep.RESULTS) onExit()
        else onAction(EmergencyRequestAction.Back)
    }
    BackHandler(onBack = handleBack)
    LaunchedEffect(state.submission, state.contactRequestSent) {
        val feedback = when (val submission = state.submission) {
            is SubmissionState.Error -> submission.message to "Retry"
            is SubmissionState.Matching -> "Request submitted. Matching donors nearby…" to null
            is SubmissionState.ManualFallback -> "No automatic matches yet. You can broadcast to the wider eligible audience." to "Broadcast"
            is SubmissionState.QueuedOffline -> "Saved offline. It will sync when connection returns." to null
            else -> null
        }
        if (feedback != null) {
            val result = snackbarHostState.showSnackbar(
                message = feedback.first,
                actionLabel = feedback.second,
                duration = if (feedback.second == null) SnackbarDuration.Short else SnackbarDuration.Indefinite
            )
            if (result == SnackbarResult.ActionPerformed) {
                if (feedback.second == "Retry") onAction(EmergencyRequestAction.Retry)
                if (feedback.second == "Broadcast") onAction(EmergencyRequestAction.SendManualBroadcast)
            }
        }
        if (state.contactRequestSent) snackbarHostState.showSnackbar("Contact request sent")
    }
    LaunchedEffect(state.draftSavedManually) {
        if (state.draftSavedManually) {
            snackbarHostState.showSnackbar("Draft saved. Returning to Home…")
            onAction(EmergencyRequestAction.DraftSaveHandled)
            onExit()
        }
    }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text(if (state.step == RequestStep.REVIEW) "Review request" else "Create request", fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = {
                        handleBack()
                    }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        },
        bottomBar = {
            if (state.step != RequestStep.RESULTS) {
                BottomBar(
                    state = state,
                    onContinue = { onAction(EmergencyRequestAction.Continue) },
                    onSubmit = { onAction(EmergencyRequestAction.Submit) },
                    onSave = { onAction(EmergencyRequestAction.SaveDraft) }
                )
            }
            },
            snackbarHost = {
                SnackbarHost(
                    hostState = snackbarHostState,
                    modifier = Modifier.navigationBarsPadding().padding(horizontal = 12.dp)
                )
            }
        ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            item { Progress(step = state.step.index, total = RequestStep.entries.size) }
            if (state.submission is SubmissionState.Error) {
                item { ErrorBanner((state.submission as SubmissionState.Error).message) { onAction(EmergencyRequestAction.Retry) } }
            }
            item {
                when (state.step) {
                    RequestStep.BLOOD_NEED -> BloodNeedStep(state.draft, onAction)
                    RequestStep.URGENCY -> UrgencyStep(state.draft, onAction)
                    RequestStep.LOCATION -> LocationStep(state.draft, onAction)
                    RequestStep.CONTACT -> ContactStep(state.draft, onAction)
                    RequestStep.REVIEW -> ReviewStep(state.draft, onAction)
                    RequestStep.RESULTS -> DonorPicker(state, onAction)
                }
            }
            if (state.step != RequestStep.RESULTS && state.discoveredDonors.isNotEmpty()) item { DonorPicker(state, onAction) }
        }
    }
    if (state.criticalConfirmationVisible) CriticalSheet(state.draft, onAction)
}

@Composable
private fun DonorPicker(state: EmergencyRequestUiState, onAction: (EmergencyRequestAction) -> Unit) {
    var showMap by rememberSaveable { mutableStateOf(false) }
    val donorsWithinFiveKm = state.discoveredDonors.count { it.distanceKm <= 5.0 }
    val donorsWithinTenKm = state.discoveredDonors.count { it.distanceKm > 5.0 && it.distanceKm <= 10.0 }
    val donorsBeyondTenKm = state.discoveredDonors.count { it.distanceKm > 10.0 }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Heading("Donor results", "Select eligible donors to contact. Their exact locations remain private.")
        if (state.contacts.isNotEmpty()) {
            Text("Donor contact", fontWeight = FontWeight.SemiBold)
            state.contacts.forEach { contact -> AcceptedContactCard(contact, onAction) }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("Results", fontWeight = FontWeight.SemiBold)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = !showMap, onClick = { showMap = false }, label = { Text("Donors") })
                FilterChip(selected = showMap, onClick = { showMap = true }, label = { Text("Map") })
            }
        }
        if (showMap) {
            val latitude = state.draft.requesterLatitude
            val longitude = state.draft.requesterLongitude
            if (latitude != null && longitude != null) {
                PrivacySafeDonorMap(latitude, longitude, donorsWithinFiveKm, donorsWithinTenKm, donorsBeyondTenKm)
            } else {
                InfoCard("Map summary unavailable", "The request location is not available. Switch to Donors to review available results.", MaterialTheme.colorScheme.secondary)
            }
            Text("Switch to Donors to select people and send contact requests.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (!showMap) {
            Text("Donor list", fontWeight = FontWeight.SemiBold)
            if (state.discoveredDonors.isEmpty()) {
                InfoCard("No eligible donors yet", "No donor cards are available for this request right now. Keep the request active and check the request status again later.", MaterialTheme.colorScheme.secondary)
            }
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

@Composable
private fun AcceptedContactCard(contact: com.lifelink.app.domain.RequesterContact, onAction: (EmergencyRequestAction) -> Unit) {
    val status = contact.status.lowercase()
    val accepted = status in setOf("accepted", "contact_shared", "meeting_arranged", "fulfilled")
    val statusLabel = status.replace('_', ' ').replaceFirstChar { it.uppercase() }
    val context = LocalContext.current
    var reportDialogVisible by remember { mutableStateOf(false) }
    var blockDialogVisible by remember { mutableStateOf(false) }
    var cancelDialogVisible by remember { mutableStateOf(false) }
    var fulfillDialogVisible by remember { mutableStateOf(false) }
    var reportReason by remember { mutableStateOf("") }
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (accepted) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface
        )
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(contact.displayName, fontWeight = FontWeight.Bold)
                Text(statusLabel, color = if (accepted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (accepted) {
                contact.acceptedAt?.let { Text("Accepted $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                contact.contactSharedAt?.let { Text("Contact shared $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                Text("Contact details are shared only after donor consent.", style = MaterialTheme.typography.bodySmall)
                contact.contactEmail?.let { email ->
                    OutlinedButton(onClick = {
                        runCatching { context.startActivity(Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:$email"))) }
                    }, modifier = Modifier.fillMaxWidth()) {
                        Text("Contact donor · $email")
                    }
                }
                when (status) {
                    "accepted" -> OutlinedButton(onClick = { onAction(EmergencyRequestAction.UpdateContactStatus(contact.donorId, "contact_shared")) }, modifier = Modifier.fillMaxWidth()) { Text("Mark contact shared") }
                    "contact_shared" -> OutlinedButton(onClick = { onAction(EmergencyRequestAction.UpdateContactStatus(contact.donorId, "meeting_arranged")) }, modifier = Modifier.fillMaxWidth()) { Text("Mark meeting arranged") }
                    "meeting_arranged" -> OutlinedButton(onClick = { fulfillDialogVisible = true }, modifier = Modifier.fillMaxWidth()) { Text("Mark fulfilled") }
                    "fulfilled" -> Text("Fulfilled", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
                }
                if (status in setOf("accepted", "contact_shared", "meeting_arranged")) TextButton(onClick = { cancelDialogVisible = true }) { Text("Cancel contact") }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { reportDialogVisible = true }) { Text("Report") }
                    TextButton(onClick = { blockDialogVisible = true }) { Text("Block") }
                }
            } else {
                Text(
                    when (status) {
                        "declined" -> "The donor declined this request. No contact details were shared."
                        "cancelled" -> "This contact request was cancelled. No further contact actions are available."
                        "expired" -> "This contact request expired. No further contact actions are available."
                        else -> "Waiting for the donor to respond. No contact details are visible yet."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }

    if (reportDialogVisible) {
        AlertDialog(
            onDismissRequest = { reportDialogVisible = false },
            title = { Text("Report donor") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Tell us what happened. Do not include medical records or unrelated personal information.")
                    OutlinedTextField(
                        value = reportReason,
                        onValueChange = { if (it.length <= 500) reportReason = it },
                        label = { Text("Reason") },
                        supportingText = { Text("${reportReason.length}/500") },
                        minLines = 3,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    onAction(EmergencyRequestAction.ReportContact(contact.donorId, reportReason.trim()))
                    reportDialogVisible = false
                    reportReason = ""
                }) { Text("Send report") }
            },
            dismissButton = { TextButton(onClick = { reportDialogVisible = false }) { Text("Cancel") } }
        )
    }
    if (blockDialogVisible) {
        AlertDialog(
            onDismissRequest = { blockDialogVisible = false },
            title = { Text("Block donor?") },
            text = { Text("Blocking stops further contact actions for this donor in this request. You can also report the interaction if it was unsafe.") },
            confirmButton = {
                TextButton(onClick = {
                    onAction(EmergencyRequestAction.BlockContact(contact.donorId))
                    blockDialogVisible = false
                }) { Text("Block donor") }
            },
            dismissButton = { TextButton(onClick = { blockDialogVisible = false }) { Text("Cancel") } }
        )
    }
    if (cancelDialogVisible) {
        AlertDialog(
            onDismissRequest = { cancelDialogVisible = false },
            title = { Text("Cancel contact request?") },
            text = { Text("This ends the contact workflow for this donor. No further contact details or lifecycle actions will be shared.") },
            confirmButton = {
                TextButton(onClick = {
                    onAction(EmergencyRequestAction.UpdateContactStatus(contact.donorId, "cancelled"))
                    cancelDialogVisible = false
                }) { Text("Cancel contact") }
            },
            dismissButton = { TextButton(onClick = { cancelDialogVisible = false }) { Text("Keep contact") } }
        )
    }
    if (fulfillDialogVisible) {
        AlertDialog(
            onDismissRequest = { fulfillDialogVisible = false },
            title = { Text("Mark request fulfilled?") },
            text = { Text("Use this after the donor interaction is complete and the blood need has been handled through the appropriate medical facility.") },
            confirmButton = {
                TextButton(onClick = {
                    onAction(EmergencyRequestAction.UpdateContactStatus(contact.donorId, "fulfilled"))
                    fulfillDialogVisible = false
                }) { Text("Mark fulfilled") }
            },
            dismissButton = { TextButton(onClick = { fulfillDialogVisible = false }) { Text("Not yet") } }
        )
    }
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
            BloodType.entries.forEach { type -> Chip(type.label, draft.bloodType == type) { onAction(EmergencyRequestAction.UpdateDraft { it.copy(bloodType = type, typeUnknown = false) }) } }
        }
        Text("Units needed", fontWeight = FontWeight.SemiBold)
        QuantityStepper(draft.units) { units -> onAction(EmergencyRequestAction.UpdateDraft { it.copy(units = units) }) }
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
    recenterRequest: Int = 0,
    modifier: Modifier = Modifier
) {
    var mapLoading by remember { mutableStateOf(true) }
    var mapError by remember { mutableStateOf<String?>(null) }
    var retryRequest by remember { mutableStateOf(0) }
    LaunchedEffect(retryRequest, mapLoading) {
        if (mapLoading) {
            delay(30_000)
            if (mapLoading) {
                mapLoading = false
                mapError = "Map preview is unavailable right now. Your location is still captured; retry the preview or use the full map."
            }
        }
    }
    if (draft.requesterLatitude != null && draft.requesterLongitude != null) {
        Box(modifier.fillMaxWidth()) {
            key(retryRequest) {
                MapLibreLocationPicker(
                    draft.requesterLatitude,
                    draft.requesterLongitude,
                    onLocationSelected,
                    recenterRequest + retryRequest,
                    onLoadingChanged = { mapLoading = it; if (it) mapError = null },
                    onMapError = { mapLoading = false; mapError = it },
                    modifier = modifier
                )
            }
            if (mapLoading) {
                Surface(Modifier.align(Alignment.TopCenter).padding(12.dp), color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f), shape = MaterialTheme.shapes.medium) {
                    Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Text("Loading map…", style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
            mapError?.let { error ->
                Card(Modifier.align(Alignment.Center).padding(16.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(error, color = MaterialTheme.colorScheme.onErrorContainer, style = MaterialTheme.typography.bodySmall)
                        OutlinedButton(onClick = { mapError = null; mapLoading = true; retryRequest++ }) { Text("Retry map") }
                    }
                }
            }
            OutlinedButton(onClick = { retryRequest++ }, modifier = Modifier.align(Alignment.BottomEnd).padding(12.dp)) { Text("Recenter") }
        }
        Text(if (retryRequest > 0) "Pin selected manually or recentered from the latest location." else "Using the current selected location. Tap or long-press to move the pin.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
        ) { Text(if (draft.requesterLatitude == null) "Show my location" else "Show my location again") }
        Text("Choose on map", fontWeight = FontWeight.SemiBold)
        LocationMapPicker(
            draft = draft,
            onLocationSelected = { latitude, longitude -> onAction(EmergencyRequestAction.SetGpsLocation(latitude, longitude, 500)) },
            recenterRequest = locationCaptureRequest,
            modifier = Modifier.height(260.dp)
        )
        if (draft.requesterLatitude != null && draft.requesterLongitude != null) {
            OutlinedButton(modifier = Modifier.fillMaxWidth(), onClick = { fullMapVisible = true }) {
                Text("Open full map")
            }
        }
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
        Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(Icons.Default.LocationOn, "Request location", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(32.dp))
                Text(if (draft.requesterLatitude == null) "Location not captured" else "Approximate location captured", fontWeight = FontWeight.SemiBold)
                if (draft.requesterLatitude != null) Text("Using a selected pin · accuracy about ${draft.locationPrecisionMeters} m", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
        CheckRow(draft.genuineRequestConfirmed, "I confirm this is a genuine blood request.") { checked -> onAction(EmergencyRequestAction.UpdateDraft { draftValue -> draftValue.copy(genuineRequestConfirmed = checked) }) }
        CheckRow(draft.sharingConsentConfirmed, "I agree to share the listed request details with eligible donors for this request.") { checked -> onAction(EmergencyRequestAction.UpdateDraft { draftValue -> draftValue.copy(sharingConsentConfirmed = checked) }) }
    }
}

@Composable private fun ReviewStep(draft: EmergencyRequestDraft, onAction: (EmergencyRequestAction) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Heading("Check everything before sending", "Notifications will go only to eligible, relevant donors.")
        Surface(Modifier.fillMaxWidth(), color = if (draft.urgency == Urgency.CRITICAL) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface, shape = MaterialTheme.shapes.large) { Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) { Text("${draft.bloodType?.label ?: "Unknown type"} · ${draft.units} unit${if (draft.units == 1) "" else "s"}", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold); Text("${draft.urgency.label.uppercase()} · ${displayDeadline(draft)}", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold); Text(locationSummary(draft)) } }
        Summary("Blood need", "${draft.bloodType?.label ?: "Unknown type"} · ${draft.units} unit${if (draft.units == 1) "" else "s"}", 0, onAction)
        Summary("Urgency", "${draft.urgency.label} · ${draft.urgency.description}", 1, onAction)
        Summary("Request location", locationSummary(draft), 2, onAction)
        Summary("Contact", draft.contactMethod.label, 3, onAction)
        CheckRow(
            checked = draft.aiMatchingEnabled,
            label = "Use AI-assisted donor ranking",
            supporting = "When enabled, LifeLink weighs distance, travel estimate, availability, verification, urgency, and response likelihood. When disabled, results are sorted by GPS distance only."
        ) { enabled -> onAction(EmergencyRequestAction.UpdateDraft { it.copy(aiMatchingEnabled = enabled) }) }
    }
}

@Composable private fun BottomBar(state: EmergencyRequestUiState, onContinue: () -> Unit, onSubmit: () -> Unit, onSave: () -> Unit) {
    val submitting = state.submission is SubmissionState.Submitting
    Surface(Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.background, shadowElevation = 8.dp) { Column(Modifier.navigationBarsPadding().imePadding().padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { if (state.step == RequestStep.REVIEW) TextButton(onClick = onSave, enabled = !submitting) { Text("Save as draft") }; Button(onClick = if (state.step == RequestStep.REVIEW) onSubmit else onContinue, modifier = Modifier.fillMaxWidth().height(54.dp), enabled = !submitting, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary), shape = MaterialTheme.shapes.medium) { if (submitting) { CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary); Spacer(Modifier.width(10.dp)); Text("Submitting…") } else Text(if (state.step == RequestStep.REVIEW) "Submit emergency request" else "Continue →", fontWeight = FontWeight.SemiBold) } } }
}

@Composable private fun Heading(title: String, subtitle: String) { Column(verticalArrangement = Arrangement.spacedBy(5.dp)) { Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold); Text(subtitle, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant) } }
@Composable private fun Chip(label: String, selected: Boolean, onClick: () -> Unit) { Surface(Modifier.height(48.dp).clickable(role = Role.RadioButton, onClick = onClick).semantics { role = Role.RadioButton }, color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface, shape = MaterialTheme.shapes.medium, border = BorderStroke(1.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline)) { Box(Modifier.padding(horizontal = 18.dp), contentAlignment = Alignment.Center) { Text(label, color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold) } } }
@Composable private fun QuantityStepper(quantity: Int, onChange: (Int) -> Unit) { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) { OutlinedButton({ if (quantity > 1) onChange(quantity - 1) }, enabled = quantity > 1, modifier = Modifier.size(52.dp), contentPadding = PaddingValues(0.dp)) { Text("−", fontSize = 24.sp) }; Text("$quantity unit${if (quantity == 1) "" else "s"}", Modifier.padding(horizontal = 24.dp), fontWeight = FontWeight.SemiBold); OutlinedButton({ if (quantity < 20) onChange(quantity + 1) }, enabled = quantity < 20, modifier = Modifier.size(52.dp), contentPadding = PaddingValues(0.dp)) { Text("+", fontSize = 24.sp) } } }
@Composable private fun CheckRow(checked: Boolean, label: String, supporting: String? = null, onChecked: (Boolean) -> Unit) { Row(Modifier.fillMaxWidth().clickable(role = Role.Checkbox) { onChecked(!checked) }.semantics { role = Role.Checkbox }, verticalAlignment = Alignment.Top) { Checkbox(checked, onChecked); Column(Modifier.padding(top = 12.dp, start = 8.dp)) { Text(label); supporting?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall) } } } }
@Composable private fun InfoCard(title: String, body: String, accent: Color = MaterialTheme.colorScheme.primary) { Surface(Modifier.fillMaxWidth(), color = if (accent == MaterialTheme.colorScheme.secondary) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.primaryContainer, shape = MaterialTheme.shapes.medium) { Row(Modifier.padding(14.dp), verticalAlignment = Alignment.Top) { Icon(if (accent == MaterialTheme.colorScheme.secondary) Icons.Default.Check else Icons.Default.Warning, title, tint = accent); Column(Modifier.padding(start = 10.dp)) { Text(title, fontWeight = FontWeight.SemiBold); Text(body, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall) } } } }
@Composable private fun FacilityRow(facility: Facility, selected: Boolean, onClick: () -> Unit) { Surface(Modifier.fillMaxWidth().clickable(role = Role.RadioButton, onClick = onClick).semantics { role = Role.RadioButton }, color = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface, shape = MaterialTheme.shapes.medium, border = BorderStroke(1.dp, if (selected) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.outline)) { Row(Modifier.padding(13.dp), verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Default.LocationOn, "Facility location", tint = MaterialTheme.colorScheme.secondary); Column(Modifier.padding(start = 10.dp).weight(1f)) { Text(facility.name, fontWeight = FontWeight.SemiBold); Text(facility.area, color = MaterialTheme.colorScheme.onSurfaceVariant) }; if (facility.verified) Icon(Icons.Default.Check, "Verified facility", tint = MaterialTheme.colorScheme.secondary) } } }
@Composable private fun SelectableRow(label: String, selected: Boolean, onClick: () -> Unit) { Surface(Modifier.fillMaxWidth().clickable(role = Role.RadioButton, onClick = onClick).semantics { role = Role.RadioButton }, color = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface, shape = MaterialTheme.shapes.medium, border = BorderStroke(1.dp, if (selected) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.outline)) { Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) { RadioButton(selected, onClick); Text(label, fontWeight = FontWeight.Medium) } } }
@Composable private fun Summary(title: String, value: String, step: Int, onAction: (EmergencyRequestAction) -> Unit) { Column { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) { Column(Modifier.weight(1f)) { Text(title, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelMedium); Text(value, fontWeight = FontWeight.Medium) }; TextButton(onClick = { onAction(EmergencyRequestAction.EditStep(RequestStep.entries[step])) }) { Text("Edit") } }; HorizontalDivider(color = MaterialTheme.colorScheme.outline) } }
@Composable private fun ErrorBanner(message: String, onRetry: () -> Unit) {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Warning, "Error", tint = MaterialTheme.colorScheme.onErrorContainer, modifier = Modifier.size(28.dp))
            Column(Modifier.weight(1f).padding(start = 10.dp)) {
                Text("Something needs attention", fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onErrorContainer)
                Text(message, color = MaterialTheme.colorScheme.onErrorContainer, style = MaterialTheme.typography.bodySmall)
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
