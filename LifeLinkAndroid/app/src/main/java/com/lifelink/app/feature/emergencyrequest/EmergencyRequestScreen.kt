package com.lifelink.app.feature.emergencyrequest

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
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lifelink.app.domain.BloodType
import com.lifelink.app.domain.ContactMethod
import com.lifelink.app.domain.EmergencyRequestDraft
import com.lifelink.app.domain.Facility
import com.lifelink.app.domain.RequestStep
import com.lifelink.app.domain.Urgency

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
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Heading("Choose donors to contact", "LifeLink only contacts donors you select. Screening and final eligibility happen outside the app.")
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

@Composable private fun LocationStep(draft: EmergencyRequestDraft, onAction: (EmergencyRequestAction) -> Unit) {
    val locationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { }
    val facilities = listOf(Facility("st-lukes", "St. Luke’s Medical Center", "Quezon City"), Facility("pgh", "Philippine General Hospital", "Manila"), Facility("makati-med", "Makati Medical Center", "Makati"))
    Column(verticalArrangement = Arrangement.spacedBy(15.dp)) {
        Heading("Where should donors go?", "Use a hospital or approved facility.")
        InfoCard("Facility search", "Choose an approved destination to estimate donor travel time.", MaterialTheme.colorScheme.secondary)
        facilities.forEach { facility -> FacilityRow(facility, draft.facility == facility) { onAction(EmergencyRequestAction.UpdateDraft { it.copy(facility = facility) }) } }
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
        ) { Text("Use current facility location") }
        Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color(0xFFECE9E2))) { Box(Modifier.fillMaxWidth().height(130.dp), contentAlignment = Alignment.Center) { Column(horizontalAlignment = Alignment.CenterHorizontally) { Icon(Icons.Default.LocationOn, "Approximate destination", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(32.dp)); Text("Approximate destination preview", color = MaterialTheme.colorScheme.onSurfaceVariant) } } }
        Text("Exact patient location is never shown to donors.", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable private fun ContactStep(draft: EmergencyRequestDraft, onAction: (EmergencyRequestAction) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Heading("How should responses work?", "Choose how verified donors can contact the coordinator.")
        Text("Preferred contact", fontWeight = FontWeight.SemiBold)
        ContactMethod.entries.forEach { method -> SelectableRow(method.label, draft.contactMethod == method) { onAction(EmergencyRequestAction.UpdateDraft { it.copy(contactMethod = method) }) } }
        InfoCard("Coordinator contact", "+63 9••• •••• 21\nShown only after a donor confirms.", MaterialTheme.colorScheme.secondary)
        CheckRow(draft.genuineRequestConfirmed, "I confirm this is a genuine blood request for a verified facility.") { checked -> onAction(EmergencyRequestAction.UpdateDraft { draftValue -> draftValue.copy(genuineRequestConfirmed = checked) }) }
        CheckRow(draft.sharingConsentConfirmed, "I agree to share the listed request details with eligible donors for this request.") { checked -> onAction(EmergencyRequestAction.UpdateDraft { draftValue -> draftValue.copy(sharingConsentConfirmed = checked) }) }
    }
}

@Composable private fun ReviewStep(draft: EmergencyRequestDraft, onAction: (EmergencyRequestAction) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Heading("Check everything before sending", "Notifications will go only to eligible, relevant donors.")
        Surface(Modifier.fillMaxWidth(), color = if (draft.urgency == Urgency.CRITICAL) MaterialTheme.colorScheme.primaryContainer else Color.White, shape = MaterialTheme.shapes.large) { Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) { Text("${draft.bloodType?.label ?: "Unknown type"} · ${draft.units} unit${if (draft.units == 1) "" else "s"}", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold); Text("${draft.urgency.label.uppercase()} · respond by ${draft.responseDeadline}", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold); Text(draft.facility?.name ?: "Facility not selected") } }
        Summary("Blood need", "${draft.bloodType?.label ?: "Unknown type"} · ${draft.units} unit${if (draft.units == 1) "" else "s"}", 0, onAction)
        Summary("Urgency", "${draft.urgency.label} · ${draft.urgency.description}", 1, onAction)
        Summary("Destination", draft.facility?.let { "${it.name} · ${it.area}" } ?: "Not selected", 2, onAction)
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
@Composable private fun ErrorBanner(message: String, onRetry: () -> Unit) { Surface(Modifier.fillMaxWidth(), color = Color(0xFFFFF4E5), shape = MaterialTheme.shapes.medium) { Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) { Text(message, Modifier.weight(1f), color = Color(0xFF7A4A00), style = MaterialTheme.typography.bodySmall); TextButton(onClick = onRetry) { Text("Retry") } } } }
@Composable private fun SuccessBanner(message: String) { Surface(Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.medium) { Text(message, Modifier.padding(14.dp), color = MaterialTheme.colorScheme.onSecondaryContainer) } }
@Composable private fun ManualFallbackBanner(reason: String, onSend: () -> Unit) { Surface(Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.primaryContainer, shape = MaterialTheme.shapes.medium) { Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { Text("Automatic matching is unavailable", fontWeight = FontWeight.SemiBold); Text(reason, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall); Text("A manual broadcast may reach more eligible donors.", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall); Button(onClick = onSend) { Text("Send manual broadcast") } } } }
@Composable private fun TextField(value: String, onValueChange: (String) -> Unit, label: String, placeholder: String, minLines: Int = 1, supporting: String? = null) { OutlinedTextField(value, onValueChange, Modifier.fillMaxWidth(), label = { Text(label) }, placeholder = { Text(placeholder) }, minLines = minLines, singleLine = minLines == 1, supportingText = supporting?.let { { Text(it) } }, trailingIcon = if (label.contains("deadline")) ({ Icon(Icons.Default.KeyboardArrowDown, null) }) else null, shape = MaterialTheme.shapes.medium) }

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun CriticalSheet(draft: EmergencyRequestDraft, onAction: (EmergencyRequestAction) -> Unit) { ModalBottomSheet(onDismissRequest = { onAction(EmergencyRequestAction.DismissCriticalSubmit) }) { Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) { Text("Send this emergency request?", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold); Text("Eligible nearby donors will be notified. You can stop alerts from the live request screen.", color = MaterialTheme.colorScheme.onSurfaceVariant); InfoCard("Request summary", "${draft.bloodType?.label ?: "Unknown type"} · ${draft.units} unit${if (draft.units == 1) "" else "s"}\n${draft.facility?.name ?: "Facility not selected"}"); Button(onClick = { onAction(EmergencyRequestAction.ConfirmCriticalSubmit) }, shape = MaterialTheme.shapes.medium) { Text("Send request") }; OutlinedButton(onClick = { onAction(EmergencyRequestAction.DismissCriticalSubmit) }) { Text("Go back and edit") }; Spacer(Modifier.height(8.dp)) } } }
