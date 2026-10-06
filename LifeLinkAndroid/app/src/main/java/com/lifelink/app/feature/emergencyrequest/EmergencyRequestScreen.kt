package com.lifelink.app.feature.emergencyrequest

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.google.android.gms.common.api.ResolvableApiException
import com.lifelink.app.core.location.LocationProvider
import com.lifelink.app.core.location.MapLibreLocationPicker
import com.lifelink.app.core.location.MapLibrePrivacySafeDonorMap
import com.lifelink.app.domain.BloodType
import com.lifelink.app.domain.ContactMethod
import com.lifelink.app.domain.EmergencyRequestDraft
import com.lifelink.app.domain.Facility
import com.lifelink.app.domain.RequestStep
import com.lifelink.app.domain.Urgency
import kotlinx.coroutines.launch

/** Renders the emergency request flow using the supplied state and action handler. */
@Composable
fun LifeLinkApp(state: EmergencyRequestUiState, onAction: (EmergencyRequestAction) -> Unit) {
    EmergencyRequestScreen(state = state, onAction = onAction)
}

/**
 * Renders the request wizard and donor results, dispatching edits through [onAction].
 *
 * [onExit] leaves the flow; [onOpenConversation] opens a contacted donor conversation
 * using the request ID and donor ID supplied by the contact card.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EmergencyRequestScreen(
    state: EmergencyRequestUiState,
    onAction: (EmergencyRequestAction) -> Unit,
    onExit: () -> Unit = {},
    onOpenConversation: (String, String) -> Unit = { _, _ -> },
) {
    val snackbarHostState = remember { SnackbarHostState() }
    val handleBack = {
        if (state.step == RequestStep.BLOOD_NEED || state.step == RequestStep.RESULTS) {
            onExit()
        } else {
            onAction(EmergencyRequestAction.Back)
        }
    }
    BackHandler(onBack = handleBack)
    LaunchedEffect(state.submission, state.contactRequestSent) {
        val feedback =
            when (val submission = state.submission) {
                is SubmissionState.Error -> submission.message to "Retry"
                is SubmissionState.Matching -> "Request submitted. Matching donors nearby…" to null
                is SubmissionState.ManualFallback -> "No automatic matches yet. You can broadcast to more potential donors." to "Broadcast"
                is SubmissionState.QueuedOffline -> "Saved offline. It will sync when connection returns." to null
                else -> null
            }
        if (feedback != null) {
            val result =
                snackbarHostState.showSnackbar(
                    message = feedback.first,
                    actionLabel = feedback.second,
                    duration = if (feedback.second == null) SnackbarDuration.Short else SnackbarDuration.Indefinite,
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
                title = {
                    Text(
                        if (state.step ==
                            RequestStep.REVIEW
                        ) {
                            "Review request"
                        } else {
                            "Create request"
                        },
                        fontWeight = FontWeight.SemiBold,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = {
                        handleBack()
                    }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        bottomBar = {
            if (state.step != RequestStep.RESULTS) {
                BottomBar(
                    state = state,
                    onContinue = { onAction(EmergencyRequestAction.Continue) },
                    onSubmit = { onAction(EmergencyRequestAction.Submit) },
                    onSave = { onAction(EmergencyRequestAction.SaveDraft) },
                )
            }
        },
        snackbarHost = {
            SnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier.navigationBarsPadding().padding(horizontal = 12.dp),
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
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
                    RequestStep.RESULTS -> DonorPicker(state, onAction, onOpenConversation)
                }
            }
            if (state.step == RequestStep.RESULTS) {
                item {
                    TextButton(onClick = onExit, modifier = Modifier.fillMaxWidth()) {
                        Text("Back to Home")
                    }
                }
            }
            if (state.step != RequestStep.RESULTS && state.discoveredDonors.isNotEmpty()) {
                item { DonorPicker(state, onAction, onOpenConversation) }
            }
        }
    }
    if (state.criticalConfirmationVisible) CriticalSheet(state.draft, onAction)
}

/**
 * Shows donor matches, selection controls, and contact activity for the request.
 *
 * Resolves the request ID from saved results or the current matching submission and
 * forwards conversation actions through [onOpenConversation].
 */
@Composable
private fun DonorPicker(
    state: EmergencyRequestUiState,
    onAction: (EmergencyRequestAction) -> Unit,
    onOpenConversation: (String, String) -> Unit,
) {
    var showMap by rememberSaveable { mutableStateOf(false) }
    val donorsWithinFiveKm = state.discoveredDonors.count { it.distanceKm <= 5.0 }
    val donorsWithinTenKm = state.discoveredDonors.count { it.distanceKm > 5.0 && it.distanceKm <= 10.0 }
    val donorsBeyondTenKm = state.discoveredDonors.count { it.distanceKm > 10.0 }
    val requestId = state.resultsRequestId ?: (state.submission as? SubmissionState.Matching)?.requestId
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Heading("Potential donors", "Review matches and choose who to contact.")
        if (state.matchesRefreshing) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                Text("Loading saved matches…", Modifier.padding(start = 10.dp))
            }
        }
        if (state.matchesError != null && requestId != null) {
            ErrorBanner(state.matchesError) { onAction(EmergencyRequestAction.ShowContactResults(requestId)) }
        }
        if (state.contactsError != null && state.resultsRequestId != null) {
            ErrorBanner(state.contactsError) { onAction(EmergencyRequestAction.RefreshContacts(state.resultsRequestId)) }
        }
        Surface(
            Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.primaryContainer,
            shape = MaterialTheme.shapes.medium,
        ) {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "${state.discoveredDonors.size} potential match${if (state.discoveredDonors.size == 1) "" else "es"}",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        if (state.selectedDonorIds.isEmpty()) {
                            "Select one or more donors to continue."
                        } else {
                            "${state.selectedDonorIds.size} selected"
                        },
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
                if (state.contacts.isNotEmpty()) {
                    Text(
                        "${state.contacts.size} contacted",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
            }
        }
        if (state.contacts.isNotEmpty()) {
            Text("Contact activity", fontWeight = FontWeight.SemiBold)
            state.contacts.forEach { contact ->
                AcceptedContactCard(
                    contact = contact,
                    actionInFlight = state.contactActionInFlightDonorId == contact.donorId,
                    requestId = requestId,
                    onOpenConversation = onOpenConversation,
                    onAction = onAction,
                )
            }
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
                InfoCard(
                    "Map summary unavailable",
                    "The request location is not available. Switch to Donors to review available results.",
                    MaterialTheme.colorScheme.secondary,
                )
            }
            Text(
                "Switch to Donors to select people and send contact requests.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (!showMap) {
            if (state.discoveredDonors.isEmpty() && !state.matchesRefreshing && state.matchesError == null) {
                InfoCard(
                    "No potential donors yet",
                    "No donor cards are available for this request right now. Keep the request active and check the request status again later.",
                    MaterialTheme.colorScheme.secondary,
                )
            }
            state.discoveredDonors.forEach { donor ->
                Card(
                    modifier = Modifier.fillMaxWidth().clickable { onAction(EmergencyRequestAction.ToggleDonorSelection(donor.donorId)) },
                    colors =
                    CardDefaults.cardColors(
                        containerColor =
                        if (donor.donorId in
                            state.selectedDonorIds
                        ) {
                            MaterialTheme.colorScheme.primaryContainer
                        } else {
                            MaterialTheme.colorScheme.surface
                        },
                    ),
                ) {
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = donor.donorId in state.selectedDonorIds, onCheckedChange = {
                            onAction(EmergencyRequestAction.ToggleDonorSelection(donor.donorId))
                        })
                        Column(Modifier.weight(1f).padding(start = 8.dp)) {
                            Text(donor.displayName, fontWeight = FontWeight.SemiBold)
                            Text(
                                "${donor.bloodType} · ${"%.1f".format(donor.distanceKm)} km · about ${donor.travelMinutes} min",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            donor.explanation.firstOrNull()?.let {
                                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
            if (state.contactRequestSent) {
                SuccessBanner("Contact request sent. You can select more matches to contact additional donors.")
            }
            val alreadyContactedIds = state.contacts.mapTo(mutableSetOf()) { it.donorId }
            val newSelectionCount = state.selectedDonorIds.count { it !in alreadyContactedIds }
            Button(
                onClick = { onAction(EmergencyRequestAction.ContactSelectedDonors) },
                enabled = newSelectionCount > 0,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    if (newSelectionCount > 0) {
                        "Contact $newSelectionCount new donor${if (newSelectionCount == 1) "" else "s"}"
                    } else if (state.selectedDonorIds.isNotEmpty()) {
                        "Selected donors already contacted"
                    } else {
                        "Select donors to contact"
                    },
                )
            }
        }
        Text(
            "LifeLink supports discovery and contact only. Screening remains with a licensed facility.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

/** Shows distance-band donor counts around the request location without displaying individual donor pins. */
@Composable
private fun PrivacySafeDonorMap(
    latitude: Double,
    longitude: Double,
    withinFiveKm: Int,
    withinTenKm: Int,
    beyondTenKm: Int,
) {
    MapLibrePrivacySafeDonorMap(latitude, longitude)
    Text("Within 5 km: $withinFiveKm · 5–10 km: $withinTenKm · Beyond 10 km: $beyondTenKm", style = MaterialTheme.typography.bodySmall)
    Text(
        "Map summary only: circles show distance bands and donor counts. No donor names or exact donor locations are shown.",
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.bodySmall,
    )
}

/**
 * Shows contact progress, consent controls, and report or block actions.
 *
 * For accepted contacts, messaging requires a non-null [requestId] and is disabled
 * while [actionInFlight]. [onOpenConversation] receives that ID and the donor ID.
 */
@Composable
private fun AcceptedContactCard(
    contact: com.lifelink.app.domain.RequesterContact,
    actionInFlight: Boolean,
    requestId: String?,
    onOpenConversation: (String, String) -> Unit,
    onAction: (EmergencyRequestAction) -> Unit,
) {
    val status = contact.status.lowercase()
    val accepted = status in setOf("accepted", "arrived", "contact_shared", "meeting_arranged", "fulfilled")
    val statusLabel = status.replace('_', ' ').replaceFirstChar { it.uppercase() }
    val context = LocalContext.current
    var reportDialogVisible by remember { mutableStateOf(false) }
    var blockDialogVisible by remember { mutableStateOf(false) }
    var cancelDialogVisible by remember { mutableStateOf(false) }
    var fulfillDialogVisible by remember { mutableStateOf(false) }
    var contactConsentVisible by remember { mutableStateOf(false) }
    var reportReason by remember { mutableStateOf("") }
    Card(
        Modifier.fillMaxWidth(),
        colors =
        CardDefaults.cardColors(
            containerColor = if (accepted) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
        ),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(contact.displayName, fontWeight = FontWeight.Bold)
                Text(statusLabel, color = if (accepted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (accepted) {
                contact.acceptedAt?.let {
                    Text(
                        "Accepted ${formatContactTimestamp(it)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                contact.contactSharedAt?.let {
                    Text(
                        "Contact shared ${formatContactTimestamp(it)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                contact.updatedAt?.let {
                    Text(
                        "Last updated ${formatContactTimestamp(it)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text("Contact details are shared only after donor consent.", style = MaterialTheme.typography.bodySmall)
                if (status in setOf("accepted", "arrived") && contact.contactEmail == null) {
                    OutlinedButton(
                        onClick = { contactConsentVisible = true },
                        enabled = !actionInFlight,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        if (actionInFlight) {
                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        } else {
                            Text("Request contact details")
                        }
                    }
                } else {
                    contact.contactEmail?.let { email ->
                        OutlinedButton(
                            onClick = { contactConsentVisible = true },
                            enabled = !actionInFlight,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            if (actionInFlight) {
                                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            } else {
                                Text("Contact donor · $email")
                            }
                        }
                    }
                }
                when (status) {
                    "accepted", "arrived" ->
                        if (contact.contactEmail ==
                            null
                        ) {
                            Text(
                                "Continue above to request the authorized contact details.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    "contact_shared" ->
                        OutlinedButton(enabled = !actionInFlight, onClick = {
                            onAction(EmergencyRequestAction.UpdateContactStatus(contact.donorId, "meeting_arranged"))
                        }, modifier = Modifier.fillMaxWidth()) { Text("Mark meeting arranged") }
                    "meeting_arranged" ->
                        OutlinedButton(enabled = !actionInFlight, onClick = {
                            fulfillDialogVisible = true
                        }, modifier = Modifier.fillMaxWidth()) { Text("Mark fulfilled") }
                    "fulfilled" -> Text("Fulfilled", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
                }
                if (status in
                    setOf("accepted", "arrived", "contact_shared", "meeting_arranged")
                ) {
                    TextButton(enabled = !actionInFlight, onClick = {
                        cancelDialogVisible =
                            true
                    }) { Text("Cancel contact") }
                }
                // Open the in-app conversation with this matched donor. The chat was
                // previously unreachable: ConversationScreen existed but nothing
                // navigated to it, so an accepted contact had no way to message.
                if (requestId != null) {
                    OutlinedButton(
                        onClick = { onOpenConversation(requestId, contact.donorId) },
                        enabled = !actionInFlight,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        if (actionInFlight) {
                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        } else {
                            Text("Message ${contact.displayName}")
                        }
                    }
                }
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
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    if (contactConsentVisible) {
        AlertDialog(
            onDismissRequest = { contactConsentVisible = false },
            title = { Text("Contact donor?") },
            text = {
                Text(
                    "The donor accepted this request. LifeLink will share the authorized contact address and open your email app. Only continue if you consent to this contact interaction.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    contact.contactEmail?.let { email ->
                        runCatching { context.startActivity(Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:$email"))) }
                    }
                    onAction(EmergencyRequestAction.UpdateContactStatus(contact.donorId, "contact_shared"))
                    contactConsentVisible = false
                }) { Text("Continue") }
            },
            dismissButton = { TextButton(onClick = { contactConsentVisible = false }) { Text("Not now") } },
        )
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
                        modifier = Modifier.fillMaxWidth(),
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
            dismissButton = { TextButton(onClick = { reportDialogVisible = false }) { Text("Cancel") } },
        )
    }
    if (blockDialogVisible) {
        AlertDialog(
            onDismissRequest = { blockDialogVisible = false },
            title = { Text("Block donor?") },
            text = {
                Text(
                    "Blocking stops further contact actions for this donor in this request. You can also report the interaction if it was unsafe.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    onAction(EmergencyRequestAction.BlockContact(contact.donorId))
                    blockDialogVisible = false
                }) { Text("Block donor") }
            },
            dismissButton = { TextButton(onClick = { blockDialogVisible = false }) { Text("Cancel") } },
        )
    }
    if (cancelDialogVisible) {
        AlertDialog(
            onDismissRequest = { cancelDialogVisible = false },
            title = { Text("Cancel contact request?") },
            text = {
                Text(
                    "This ends the contact workflow for this donor. No further contact details or lifecycle actions will be shared.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    onAction(EmergencyRequestAction.UpdateContactStatus(contact.donorId, "cancelled"))
                    cancelDialogVisible = false
                }) { Text("Cancel contact") }
            },
            dismissButton = { TextButton(onClick = { cancelDialogVisible = false }) { Text("Keep contact") } },
        )
    }
    if (fulfillDialogVisible) {
        AlertDialog(
            onDismissRequest = { fulfillDialogVisible = false },
            title = { Text("Mark request fulfilled?") },
            text = {
                Text(
                    "Use this after the donor interaction is complete and the blood need has been handled through the appropriate medical facility.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    onAction(EmergencyRequestAction.UpdateContactStatus(contact.donorId, "fulfilled"))
                    fulfillDialogVisible = false
                }) { Text("Mark fulfilled") }
            },
            dismissButton = { TextButton(onClick = { fulfillDialogVisible = false }) { Text("Not yet") } },
        )
    }
}

/** Displays up to the first 16 characters with 'T' replaced by a space, without parsing or converting time zones. */
private fun formatContactTimestamp(value: String): String =
    runCatching {
        value.take(16).replace('T', ' ')
    }.getOrDefault(value)

/** Displays the current wizard step label and highlights progress through the supplied number of steps. */
@Composable private fun Progress(step: Int, total: Int) {
    val labels = listOf("Blood need", "Timing", "Location", "Contact", "Review", "Results")
    Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
        Text(
            "Step ${step + 1} of $total · ${labels.getOrElse(step) { "Request" }}",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelLarge,
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            repeat(total) { index ->
                Surface(
                    Modifier.weight(1f).height(6.dp),
                    color =
                    if (index <=
                        step
                    ) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.surfaceContainerHighest
                    },
                    shape = MaterialTheme.shapes.small,
                ) {}
            }
        }
    }
}

/** Edits the requested blood type and unit count through draft-update actions. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BloodNeedStep(draft: EmergencyRequestDraft, onAction: (EmergencyRequestAction) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Heading("Blood need", "Select the type and amount required.")
        Text("Blood type", fontWeight = FontWeight.SemiBold)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(9.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            BloodType.entries.forEach { type ->
                Chip(type.label, draft.bloodType == type) {
                    onAction(EmergencyRequestAction.UpdateDraft { it.copy(bloodType = type, typeUnknown = false) })
                }
            }
        }
        Text("Units needed", fontWeight = FontWeight.SemiBold)
        QuantityStepper(draft.units) { units -> onAction(EmergencyRequestAction.UpdateDraft { it.copy(units = units) }) }
    }
}

/** Edits urgency, response timing, and the optional request note through draft-update actions. */
@Composable private fun UrgencyStep(draft: EmergencyRequestDraft, onAction: (EmergencyRequestAction) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Heading("Timing", "Choose the closest accurate response window.")
        Urgency.entries.forEach { urgency ->
            Card(
                modifier =
                Modifier.fillMaxWidth().clickable(role = Role.RadioButton) {
                    onAction(EmergencyRequestAction.UpdateDraft { it.copy(urgency = urgency) })
                },
                colors =
                CardDefaults.cardColors(
                    containerColor =
                    if (draft.urgency ==
                        urgency
                    ) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.surface
                    },
                ),
                border =
                if (draft.urgency ==
                    urgency
                ) {
                    BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
                } else {
                    BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
                },
            ) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(draft.urgency == urgency, { onAction(EmergencyRequestAction.UpdateDraft { it.copy(urgency = urgency) }) })
                    Column(Modifier.padding(start = 7.dp)) {
                        Text(urgency.label, fontWeight = FontWeight.SemiBold)
                        Text(urgency.description, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (urgency ==
                            Urgency.CRITICAL
                        ) {
                            Text(
                                "Use only for an immediate, verified need",
                                color = MaterialTheme.colorScheme.primary,
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                    }
                }
            }
        }
        TextField(draft.responseDeadline, { value ->
            onAction(
                EmergencyRequestAction.UpdateDraft { draftValue ->
                    draftValue.copy(responseDeadline = value)
                },
            )
        }, "Latest acceptable response", "Today, 12:30 PM")
        TextField(draft.note, { value ->
            if (value.length <=
                180
            ) {
                onAction(EmergencyRequestAction.UpdateDraft { draftValue -> draftValue.copy(note = value) })
            }
        }, "Request note (optional)", "Avoid names or medical records.", minLines = 3, supporting = "${draft.note.length}/180 characters")
    }
}

/** Displays the request location picker and supports recentering and retrying the map. */
@Composable
private fun LocationMapPicker(
    draft: EmergencyRequestDraft,
    onLocationSelected: (Double, Double) -> Unit,
    recenterRequest: Int = 0,
    modifier: Modifier = Modifier,
) {
    var retryRequest by remember { mutableStateOf(0) }
    // MapLibreLocationPicker renders its own loading and error UI, so this screen
    // must not add a second spinner on top of it.
    Box(modifier.fillMaxWidth()) {
        key(retryRequest) {
            MapLibreLocationPicker(
                draft.requesterLatitude,
                draft.requesterLongitude,
                onLocationSelected,
                recenterRequest + retryRequest,
                modifier = modifier,
            )
        }
        OutlinedButton(onClick = { retryRequest++ }, modifier = Modifier.align(Alignment.BottomEnd).padding(12.dp)) { Text("Reload map") }
    }
    Text(
        if (draft.requesterLatitude != null && draft.requesterLongitude != null) {
            "Tap or long-press to move the selected approximate pin."
        } else {
            "Pan and zoom to an area, then tap or long-press to choose your approximate location. No location is selected until you choose a point."
        },
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** Captures or edits request coordinates using device location, map selection, or manual input. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LocationStep(draft: EmergencyRequestDraft, onAction: (EmergencyRequestAction) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var locationCaptureRequest by remember { mutableStateOf(0) }
    var locationMessage by remember { mutableStateOf<String?>(null) }
    var fullMapVisible by remember { mutableStateOf(false) }
    val settingsResolutionLauncher =
        rememberLauncherForActivityResult(
            ActivityResultContracts.StartIntentSenderForResult(),
        ) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                locationCaptureRequest++
            } else {
                locationMessage = "Location services remain off. You can choose a point on the map or enter coordinates manually."
            }
        }
    var manualLatitude by remember { mutableStateOf(draft.requesterLatitude?.toString().orEmpty()) }
    var manualLongitude by remember { mutableStateOf(draft.requesterLongitude?.toString().orEmpty()) }
    val locationPermissionLauncher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.RequestMultiplePermissions(),
        ) { permissions ->
            if (permissions[android.Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                permissions[android.Manifest.permission.ACCESS_COARSE_LOCATION] == true
            ) {
                locationCaptureRequest++
            }
        }
    LaunchedEffect(Unit) {
        val hasLocationPermission =
            ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.ACCESS_COARSE_LOCATION,
            ) == PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(
                    context,
                    android.Manifest.permission.ACCESS_FINE_LOCATION,
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
            onFailure = { locationMessage = "Device location is unavailable. Choose a location manually." },
        )
    }
    Column(verticalArrangement = Arrangement.spacedBy(15.dp)) {
        Heading("Approximate location", "Used to find nearby potential donors. Your exact location stays private.")
        locationMessage?.let { Text(it, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall) }
        OutlinedButton(
            modifier = Modifier.fillMaxWidth(),
            onClick = {
                locationPermissionLauncher.launch(
                    arrayOf(
                        android.Manifest.permission.ACCESS_COARSE_LOCATION,
                        android.Manifest.permission.ACCESS_FINE_LOCATION,
                    ),
                )
            },
        ) { Text(if (draft.requesterLatitude == null) "Show my location" else "Show my location again") }
        Text("Choose a point", fontWeight = FontWeight.SemiBold)
        LocationMapPicker(
            draft = draft,
            onLocationSelected = { latitude, longitude -> onAction(EmergencyRequestAction.SetGpsLocation(latitude, longitude, 500)) },
            recenterRequest = locationCaptureRequest,
            modifier = Modifier.height(260.dp),
        )
        OutlinedButton(modifier = Modifier.fillMaxWidth(), onClick = { fullMapVisible = true }) {
            Text("Open full map")
        }
        Text("Or enter coordinates", fontWeight = FontWeight.SemiBold)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(
                value = manualLatitude,
                onValueChange = { manualLatitude = it },
                modifier = Modifier.weight(1f),
                label = { Text("Latitude") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
            OutlinedTextField(
                value = manualLongitude,
                onValueChange = { manualLongitude = it },
                modifier = Modifier.weight(1f),
                label = { Text("Longitude") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
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
            },
        ) { Text("Use this approximate location") }
        Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    if (draft.requesterLatitude ==
                        null
                    ) {
                        "Location not captured"
                    } else {
                        "Approximate location captured"
                    },
                    fontWeight = FontWeight.SemiBold,
                )
                if (draft.requesterLatitude !=
                    null
                ) {
                    Text(
                        "Using a selected pin · accuracy about ${draft.locationPrecisionMeters} m",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
    if (fullMapVisible) {
        ModalBottomSheet(onDismissRequest = { fullMapVisible = false }) {
            Column(
                Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("Choose an approximate location", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text(
                    "Pan and zoom the map, then tap or long-press to move the private marker.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                LocationMapPicker(
                    draft = draft,
                    onLocationSelected = {
                            latitude,
                            longitude,
                        ->
                        onAction(EmergencyRequestAction.SetGpsLocation(latitude, longitude, 500))
                    },
                    modifier = Modifier.height(520.dp),
                )
                OutlinedButton(modifier = Modifier.fillMaxWidth(), onClick = { fullMapVisible = false }) { Text("Done") }
            }
        }
    }
}

/** Edits contact preferences and the genuine-request and sharing-consent confirmations. */
@Composable private fun ContactStep(draft: EmergencyRequestDraft, onAction: (EmergencyRequestAction) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Heading("Contact preferences", "Choose how an accepting donor can reach you.")
        Text("Preferred contact", fontWeight = FontWeight.SemiBold)
        ContactMethod.entries.forEach { method ->
            SelectableRow(method.label, draft.contactMethod == method) {
                onAction(EmergencyRequestAction.UpdateDraft { it.copy(contactMethod = method) })
            }
        }
        CheckRow(draft.genuineRequestConfirmed, "I confirm this is a genuine blood request.") { checked ->
            onAction(
                EmergencyRequestAction.UpdateDraft { draftValue ->
                    draftValue.copy(genuineRequestConfirmed = checked)
                },
            )
        }
        CheckRow(
            draft.sharingConsentConfirmed,
            "I agree to share the listed request details with potential donors for this request.",
        ) { checked ->
            onAction(
                EmergencyRequestAction.UpdateDraft { draftValue ->
                    draftValue.copy(sharingConsentConfirmed = checked)
                },
            )
        }
        Text(
            "A licensed facility remains responsible for eligibility, screening, and care.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

/** Summarizes the request draft with edit actions and an optional matching preference. */
@Composable private fun ReviewStep(draft: EmergencyRequestDraft, onAction: (EmergencyRequestAction) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Heading("Review request", "Check the essentials before notifying potential donors.")
        Surface(
            Modifier.fillMaxWidth(),
            color =
            if (draft.urgency ==
                Urgency.CRITICAL
            ) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surface
            },
            shape = MaterialTheme.shapes.large,
        ) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    "${draft.bloodType?.label ?: "Unknown type"} · ${draft.units} unit${if (draft.units == 1) "" else "s"}",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    "${draft.urgency.label.uppercase()} · ${displayDeadline(draft)}",
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold,
                )
                Text(locationSummary(draft))
            }
        }
        Summary(
            "Blood need",
            "${draft.bloodType?.label ?: "Unknown type"} · ${draft.units} unit${if (draft.units == 1) "" else "s"}",
            0,
            onAction,
        )
        Summary("Urgency", "${draft.urgency.label} · ${draft.urgency.description}", 1, onAction)
        Summary("Request location", locationSummary(draft), 2, onAction)
        Summary("Contact", draft.contactMethod.label, 3, onAction)
        CheckRow(
            checked = draft.aiMatchingEnabled,
            label = "Use assisted donor ranking",
            supporting = "Ranks matches using distance, availability, urgency, and other matching signals.",
        ) { enabled -> onAction(EmergencyRequestAction.UpdateDraft { it.copy(aiMatchingEnabled = enabled) }) }
    }
}

/** Offers step continuation or submission and draft saving, disabling actions during submission. */
@Composable private fun BottomBar(state: EmergencyRequestUiState, onContinue: () -> Unit, onSubmit: () -> Unit, onSave: () -> Unit) {
    val submitting = state.submission is SubmissionState.Submitting
    Surface(Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.background, shadowElevation = 8.dp) {
        Column(Modifier.navigationBarsPadding().imePadding().padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (state.step ==
                RequestStep.REVIEW
            ) {
                TextButton(onClick = onSave, enabled = !submitting) { Text("Save as draft") }
            }
            Button(
                onClick =
                if (state.step ==
                    RequestStep.REVIEW
                ) {
                    onSubmit
                } else {
                    onContinue
                },
                modifier = Modifier.fillMaxWidth().height(54.dp),
                enabled = !submitting,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                shape = MaterialTheme.shapes.medium,
            ) {
                if (submitting) {
                    CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                    Spacer(Modifier.width(10.dp))
                    Text("Submitting…")
                } else {
                    Text(
                        if (state.step ==
                            RequestStep.REVIEW
                        ) {
                            "Submit emergency request"
                        } else {
                            "Continue →"
                        },
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }
    }
}

/** Displays a title and subtitle with heading semantics for accessibility. */
@Composable private fun Heading(title: String, subtitle: String) {
    Column(Modifier.semantics { heading() }, verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text(subtitle, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Displays a selectable option with radio-button semantics and forwards clicks to [onClick]. */
@Composable private fun Chip(label: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        Modifier.height(48.dp).clickable(role = Role.RadioButton, onClick = onClick).semantics {
            role =
                Role.RadioButton
        },
        color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface,
        shape = MaterialTheme.shapes.medium,
        border = BorderStroke(1.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline),
    ) {
        Box(Modifier.padding(horizontal = 18.dp), contentAlignment = Alignment.Center) {
            Text(
                label,
                color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

/** Displays the unit count and invokes [onChange] for permitted increments and decrements. */
@Composable private fun QuantityStepper(quantity: Int, onChange: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
        OutlinedButton(
            {
                if (quantity >
                    1
                ) {
                    onChange(quantity - 1)
                }
            },
            enabled = quantity > 1,
            modifier =
            Modifier.size(
                52.dp,
            ),
            contentPadding = PaddingValues(0.dp),
        ) { Text("−", fontSize = 24.sp) }
        Text("$quantity unit${if (quantity == 1) "" else "s"}", Modifier.padding(horizontal = 24.dp), fontWeight = FontWeight.SemiBold)
        OutlinedButton(
            {
                if (quantity <
                    20
                ) {
                    onChange(quantity + 1)
                }
            },
            enabled = quantity < 20,
            modifier =
            Modifier.size(
                52.dp,
            ),
            contentPadding = PaddingValues(0.dp),
        ) { Text("+", fontSize = 24.sp) }
    }
}

/** Displays a checkbox, label, and optional supporting text; clicking the row toggles the value. */
@Composable private fun CheckRow(checked: Boolean, label: String, supporting: String? = null, onChecked: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(role = Role.Checkbox) { onChecked(!checked) }.semantics {
            role =
                Role.Checkbox
        },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked, onChecked)
        Column(Modifier.padding(start = 8.dp)) {
            Text(label)
            supporting?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall) }
        }
    }
}

/** Displays a titled message using a container color selected by [accent]. */
@Composable private fun InfoCard(title: String, body: String, accent: Color = MaterialTheme.colorScheme.primary) {
    Surface(
        Modifier.fillMaxWidth(),
        color =
        if (accent ==
            MaterialTheme.colorScheme.secondary
        ) {
            MaterialTheme.colorScheme.secondaryContainer
        } else {
            MaterialTheme.colorScheme.primaryContainer
        },
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, fontWeight = FontWeight.SemiBold)
            Text(body, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        }
    }
}

/** Displays a selectable facility with its area and an indicator for verified facilities. */
@Composable private fun FacilityRow(facility: Facility, selected: Boolean, onClick: () -> Unit) {
    Surface(
        Modifier.fillMaxWidth().clickable(role = Role.RadioButton, onClick = onClick).semantics {
            role =
                Role.RadioButton
        },
        color = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface,
        shape = MaterialTheme.shapes.medium,
        border = BorderStroke(1.dp, if (selected) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.outline),
    ) {
        Row(Modifier.padding(13.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.LocationOn, "Facility location", tint = MaterialTheme.colorScheme.secondary)
            Column(Modifier.padding(start = 10.dp).weight(1f)) {
                Text(facility.name, fontWeight = FontWeight.SemiBold)
                Text(facility.area, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (facility.verified) Icon(Icons.Default.Check, "Verified facility", tint = MaterialTheme.colorScheme.secondary)
        }
    }
}

/** Displays a labeled radio option and delegates selection to [onClick]. */
@Composable private fun SelectableRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        Modifier.fillMaxWidth().clickable(role = Role.RadioButton, onClick = onClick).semantics {
            role =
                Role.RadioButton
        },
        color = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface,
        shape = MaterialTheme.shapes.medium,
        border = BorderStroke(1.dp, if (selected) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.outline),
    ) {
        Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected, onClick)
            Text(label, fontWeight = FontWeight.Medium)
        }
    }
}

/** Displays a review value with an edit action for the supplied request-step index. */
@Composable private fun Summary(title: String, value: String, step: Int, onAction: (EmergencyRequestAction) -> Unit) {
    Column {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                Text(title, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelMedium)
                Text(value, fontWeight = FontWeight.Medium)
            }
            TextButton(onClick = { onAction(EmergencyRequestAction.EditStep(RequestStep.entries[step])) }) { Text("Edit") }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outline)
    }
}

/** Displays an error message and delegates retry requests to [onRetry]. */
@Composable private fun ErrorBanner(message: String, onRetry: () -> Unit) {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Something needs attention", fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onErrorContainer)
                Text(message, color = MaterialTheme.colorScheme.onErrorContainer, style = MaterialTheme.typography.bodySmall)
            }
            TextButton(onClick = onRetry) { Text("Retry") }
        }
    }
}

/** Displays a successful request update in a status card. */
@Composable private fun SuccessBanner(message: String) {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("LifeLink update", fontWeight = FontWeight.SemiBold)
            Text(message, color = MaterialTheme.colorScheme.onSecondaryContainer)
        }
    }
}

/** Explains why automatic matching is unavailable and offers the manual broadcast action. */
@Composable private fun ManualFallbackBanner(reason: String, onSend: () -> Unit) {
    Surface(Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.primaryContainer, shape = MaterialTheme.shapes.medium) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Automatic matching is unavailable", fontWeight = FontWeight.SemiBold)
            Text(reason, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            Text(
                "A manual broadcast may reach more eligible donors.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
            Button(onClick = onSend) { Text("Send manual broadcast") }
        }
    }
}

/** Displays an outlined request field with optional supporting text and configurable line count. */
@Composable
private fun TextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    placeholder: String,
    minLines: Int = 1,
    supporting: String? = null,
) {
    OutlinedTextField(
        value,
        onValueChange,
        Modifier.fillMaxWidth(),
        label = { Text(label) },
        placeholder = { Text(placeholder) },
        minLines = minLines,
        singleLine =
        minLines == 1,
        supportingText =
        supporting?.let {
            {
                Text(it)
            }
        },
        shape = MaterialTheme.shapes.medium,
    )
}

/** Shows the critical-request summary and dispatches confirmation or dismissal actions. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CriticalSheet(
    draft: EmergencyRequestDraft,
    onAction: (EmergencyRequestAction) -> Unit,
) {
    ModalBottomSheet(onDismissRequest = {
        onAction(EmergencyRequestAction.DismissCriticalSubmit)
    }) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("Send this emergency request?", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text(
                "Eligible nearby donors will be notified. You can stop alerts from the live request screen.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            InfoCard(
                "Request summary",
                "${draft.bloodType?.label ?: "Unknown type"} · ${draft.units} unit${if (draft.units == 1) "" else "s"}\n${locationSummary(
                    draft,
                )}",
            )
            Button(
                onClick = { onAction(EmergencyRequestAction.ConfirmCriticalSubmit) },
                shape = MaterialTheme.shapes.medium,
            ) { Text("Send request") }
            OutlinedButton(onClick = { onAction(EmergencyRequestAction.DismissCriticalSubmit) }) { Text("Go back and edit") }
            Spacer(Modifier.height(8.dp))
        }
    }
}

/** Reports whether both request coordinates are present without exposing their values. */
private fun locationSummary(draft: EmergencyRequestDraft): String =
    if (draft.requesterLatitude != null && draft.requesterLongitude != null) {
        "Approximate GPS location captured"
    } else {
        "Approximate GPS location not captured"
    }

/** Describes the response deadline, treating blank text and the hours placeholder as unset. */
private fun displayDeadline(draft: EmergencyRequestDraft): String =
    if (draft.responseDeadline.isBlank() || draft.responseDeadline.equals("hours", ignoreCase = true)) {
        "response deadline not set"
    } else {
        "respond by ${draft.responseDeadline}"
    }
