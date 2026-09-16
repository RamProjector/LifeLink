package com.lifelink.app.feature.activeRequest

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.lifelink.app.domain.ActiveRequestSnapshot
import com.lifelink.app.domain.ActiveRequestStatus
import com.lifelink.app.feature.emergencyrequest.EmergencyRequestAction
import com.lifelink.app.feature.emergencyrequest.EmergencyRequestUiState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ActiveRequestScreen(
    state: EmergencyRequestUiState,
    onAction: (EmergencyRequestAction) -> Unit,
    onBack: () -> Unit
) {
    val active = state.activeRequest ?: return
    var showCancelConfirmation by remember { mutableStateOf(false) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Active request") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = { IconButton(onClick = { onAction(EmergencyRequestAction.RefreshStatus) }, enabled = !state.statusRefreshing) { Icon(Icons.Default.Refresh, "Refresh") } }
            )
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(active.status.label, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text("Request ${active.requestId.take(12)}", color = MaterialTheme.colorScheme.onSurfaceVariant)
            StatusCard(active)
            ProgressCard(active)
            if (active.status == ActiveRequestStatus.MANUAL_BROADCAST) {
                Button(onClick = { onAction(EmergencyRequestAction.SendManualBroadcast) }) { Text("Send manual broadcast") }
            }
            if (!active.isTerminal) {
                TextButton(onClick = { showCancelConfirmation = true }) { Text("Cancel request") }
            }
            Text("Status refreshes automatically while this request is active.", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        }
    }
    if (showCancelConfirmation) {
        AlertDialog(
            onDismissRequest = { showCancelConfirmation = false },
            title = { Text("Cancel this request?") },
            text = { Text("Donor alerts will stop and this request will be marked cancelled. This cannot be undone.") },
            confirmButton = { TextButton(onClick = { showCancelConfirmation = false; onAction(EmergencyRequestAction.CancelRequest) }) { Text("Cancel request") } },
            dismissButton = { TextButton(onClick = { showCancelConfirmation = false }) { Text("Keep active") } }
        )
    }
}

@Composable private fun StatusCard(active: ActiveRequestSnapshot) {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(active.status.label, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
            active.reason?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            Text(if (active.isTerminal) "This request is no longer accepting responses." else "Eligible donors are notified according to the matching rules.")
        }
    }
}

@Composable private fun ProgressCard(active: ActiveRequestSnapshot) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Response activity", fontWeight = FontWeight.SemiBold)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Metric("Donors notified", active.notificationsCreated.toString())
                Metric("Responses", active.matchesResponded.toString())
            }
        }
    }
}

@Composable private fun Metric(label: String, value: String) {
    Column { Text(value, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold); Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant) }
}
