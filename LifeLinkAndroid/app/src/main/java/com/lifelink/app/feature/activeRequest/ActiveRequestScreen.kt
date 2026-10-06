package com.lifelink.app.feature.activeRequest

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.HourglassEmpty
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.VolunteerActivism
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.lifelink.app.core.ui.LifeLinkEmptyState
import com.lifelink.app.core.ui.LifeLinkLoadingIndicator
import com.lifelink.app.domain.ActiveRequestSnapshot
import com.lifelink.app.domain.ActiveRequestStatus
import com.lifelink.app.feature.emergencyrequest.EmergencyRequestAction
import com.lifelink.app.feature.emergencyrequest.EmergencyRequestUiState

/**
 * Live status of the requester's active request: current status, response metrics,
 * donor contact activity, and the fulfil/cancel lifecycle actions.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ActiveRequestScreen(
    state: EmergencyRequestUiState,
    onAction: (EmergencyRequestAction) -> Unit,
    onBack: () -> Unit,
    onReviewContacts: () -> Unit,
) {
    val active = state.activeRequest ?: return
    LaunchedEffect(active.requestId) {
        onAction(EmergencyRequestAction.RefreshContacts(active.requestId))
    }
    // After a successful cancel the request is terminal: leave the active-request
    // screen so the user lands back on Home with the request reset to idle.
    LaunchedEffect(state.cancelCompleted) {
        if (state.cancelCompleted) {
            onAction(EmergencyRequestAction.CancelHandled)
            onBack()
        }
    }
    var showCancelConfirmation by remember { mutableStateOf(false) }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("Active request", fontWeight = FontWeight.SemiBold) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = {
                    IconButton(onClick = {
                        onAction(EmergencyRequestAction.RefreshStatus)
                    }, enabled = !state.statusRefreshing) { Icon(Icons.Default.Refresh, "Refresh status") }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            StatusCard(active)
            ProgressCard(active)
            ContactActivityCard(state, active, onAction, onReviewContacts)
            if (active.status == ActiveRequestStatus.MANUAL_BROADCAST) {
                Button(onClick = {
                    onAction(EmergencyRequestAction.SendManualBroadcast)
                }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.Campaign, contentDescription = null, Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Send manual broadcast")
                }
            }
            if (!active.isTerminal) {
                Button(onClick = {
                    onAction(EmergencyRequestAction.FulfillRequest)
                }, enabled = !state.statusRefreshing, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.CheckCircle, contentDescription = null, Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Mark request fulfilled")
                }
                OutlinedButton(onClick = { showCancelConfirmation = true }, modifier = Modifier.fillMaxWidth()) {
                    Text("Cancel request")
                }
            }
            Text(
                "Request ${active.requestId.take(12)} \u00b7 Updates automatically while active.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
    if (showCancelConfirmation) {
        AlertDialog(
            onDismissRequest = { showCancelConfirmation = false },
            title = { Text("Cancel this request?") },
            text = { Text("Donor alerts will stop and this request will be marked cancelled. This cannot be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    showCancelConfirmation = false
                    onAction(EmergencyRequestAction.CancelRequest)
                }) { Text("Cancel request") }
            },
            dismissButton = { TextButton(onClick = { showCancelConfirmation = false }) { Text("Keep active") } },
        )
    }
}

/** Hero card: the current status, its reason, and what happens next. */
@Composable
private fun StatusCard(active: ActiveRequestSnapshot) {
    Card(
        Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Surface(
                    modifier = Modifier.size(40.dp),
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.primary,
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = if (active.isTerminal) Icons.Default.CheckCircle else Icons.Default.HourglassEmpty,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(22.dp),
                        )
                    }
                }
                Text(
                    "Current status",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
            Text(
                active.status.label,
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.semantics { heading() },
            )
            active.reason?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            Text(
                if (active.isTerminal) {
                    "This request is no longer accepting responses."
                } else {
                    "Eligible donors are notified according to the matching rules."
                },
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
    }
}

/** Two metric tiles summarizing how many donors were notified and how many responded. */
@Composable
private fun ProgressCard(active: ActiveRequestSnapshot) {
    Card(Modifier.fillMaxWidth(), elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("Response activity", fontWeight = FontWeight.SemiBold, modifier = Modifier.semantics { heading() })
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                MetricTile("Donors notified", active.notificationsCreated.toString(), Modifier.weight(1f))
                MetricTile("Responses", active.matchesResponded.toString(), Modifier.weight(1f))
            }
            val responseRatio =
                if (active.notificationsCreated > 0) {
                    (active.matchesResponded.toFloat() / active.notificationsCreated.toFloat()).coerceIn(0f, 1f)
                } else {
                    0f
                }
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                LinearProgressIndicator(
                    progress = { responseRatio },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    if (active.notificationsCreated == 0) {
                        "No donors have been notified yet."
                    } else {
                        "${active.matchesResponded} of ${active.notificationsCreated} notified donors responded " +
                            "(${(responseRatio * 100).toInt()}%)."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** A single metric rendered as a tonal tile so the two counts read as a pair. */
@Composable
private fun MetricTile(label: String, value: String, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.semantics { contentDescription = "$label: $value" },
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(value, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        }
    }
}

/** Donor contact activity with loading, empty, error, and populated states. */
@Composable
private fun ContactActivityCard(
    state: EmergencyRequestUiState,
    active: ActiveRequestSnapshot,
    onAction: (EmergencyRequestAction) -> Unit,
    onReviewContacts: () -> Unit,
) {
    Card(Modifier.fillMaxWidth(), elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)) {
        Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Donor contact activity", fontWeight = FontWeight.SemiBold, modifier = Modifier.semantics { heading() })
            when {
                state.contactsRefreshing && state.contacts.isEmpty() ->
                    LifeLinkLoadingIndicator(label = "Refreshing contact activity\u2026")
                state.contacts.isEmpty() ->
                    LifeLinkEmptyState(
                        icon = Icons.Default.VolunteerActivism,
                        title = "No contact activity yet",
                        body = "Selected donors and their responses will appear here.",
                    )
                else -> {
                    state.contacts.forEachIndexed { index, contact ->
                        if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 6.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(contact.displayName, Modifier.weight(1f), fontWeight = FontWeight.Medium)
                            Text(
                                contact.status.replace('_', ' ').replaceFirstChar { it.uppercase() },
                                color = MaterialTheme.colorScheme.primary,
                                style = MaterialTheme.typography.labelLarge,
                            )
                        }
                    }
                }
            }
            state.contactsError?.let { error ->
                Surface(
                    Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.errorContainer,
                    shape = MaterialTheme.shapes.medium,
                ) {
                    Row(
                        Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            error,
                            modifier = Modifier.weight(1f),
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            style = MaterialTheme.typography.bodySmall,
                        )
                        TextButton(onClick = { onAction(EmergencyRequestAction.RefreshContacts(active.requestId)) }) {
                            Text("Retry")
                        }
                    }
                }
            }
            Button(onClick = onReviewContacts, modifier = Modifier.fillMaxWidth()) {
                Text("Review matches and contacts")
            }
        }
    }
}
