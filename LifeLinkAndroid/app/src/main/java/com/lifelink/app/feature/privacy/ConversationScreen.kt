package com.lifelink.app.feature.privacy

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.lifelink.app.domain.ChatMessage

/**
 * In-app conversation between the requester and donor of one request. Messages
 * are stored server-side and readable only by those two users. Phone and email
 * stay hidden until either participant explicitly shares them.
 */
@Composable
fun ConversationScreen(
    state: PrivacyUiState,
    requestId: String,
    donorId: String,
    currentUserId: String,
    onAction: (PrivacyAction) -> Unit,
    onBack: () -> Unit,
) {
    LaunchedEffect(requestId, donorId) { onAction(PrivacyAction.OpenConversation(requestId, donorId)) }
    var draft by remember { mutableStateOf("") }
    var contactField by remember { mutableStateOf("phone") }
    var contactValue by remember { mutableStateOf("") }
    var showReport by remember { mutableStateOf(false) }
    var reportReason by remember { mutableStateOf("") }
    var showBlockConfirm by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Conversation", Modifier.weight(1f), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            TextButton(onClick = onBack) { Text("Back") }
        }
        Text(
            "Only you and the other person can read these messages. Contact details stay hidden until you share them.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        state.message?.let { Text(it, color = MaterialTheme.colorScheme.primary) }

        LazyColumn(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(state.messages) { message -> MessageBubble(message, message.senderId == currentUserId) }
        }

        if (state.contactShares.isNotEmpty()) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
                // Bounded and independently scrollable: an unbounded list of shares
                // would otherwise push the Safety card below the viewport, where the
                // message LazyColumn cannot scroll to it.
                Column(
                    Modifier.padding(12.dp).heightIn(max = 180.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text("Shared contact details", fontWeight = FontWeight.SemiBold)
                    state.contactShares.forEach { share ->
                        Text(
                            "${share.field.replaceFirstChar { it.uppercase() }}: ${share.value}",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }

        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = draft,
                onValueChange = { if (it.length <= 2000) draft = it },
                modifier = Modifier.weight(1f),
                label = { Text("Message") },
                maxLines = 4,
            )
            Button(
                onClick = {
                    onAction(PrivacyAction.SendMessage(draft))
                    draft = ""
                },
                enabled = draft.isNotBlank() && !state.sending,
            ) { Text(if (state.sending) "Sending\u2026" else "Send") }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Share contact details", fontWeight = FontWeight.SemiBold)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { contactField = "phone" }, enabled = contactField != "phone") { Text("Phone") }
                    OutlinedButton(onClick = { contactField = "email" }, enabled = contactField != "email") { Text("Email") }
                }
                OutlinedTextField(
                    value = contactValue,
                    onValueChange = { if (it.length <= 320) contactValue = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(if (contactField == "phone") "Phone number" else "Email address") },
                    singleLine = true,
                )
                Button(
                    onClick = {
                        onAction(PrivacyAction.ShareContact(contactField, contactValue))
                        contactValue = ""
                    },
                    enabled = contactValue.isNotBlank(),
                ) { Text("Share $contactField") }
                Text(
                    "Sharing is recorded in an audit log. You can share each field separately.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // Report and Block are surfaced here so a participant can act on an unsafe
        // interaction without leaving the conversation.
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Safety", fontWeight = FontWeight.SemiBold)
                Text(
                    "If this conversation feels unsafe, report or block the other person. Reports are reviewed by our team.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { showReport = true }, modifier = Modifier.weight(1f)) { Text("Report") }
                    OutlinedButton(onClick = { showBlockConfirm = true }, modifier = Modifier.weight(1f)) { Text("Block") }
                }
            }
        }
    }

    if (showReport) {
        AlertDialog(
            onDismissRequest = { showReport = false },
            title = { Text("Report this person") },
            text = {
                OutlinedTextField(
                    value = reportReason,
                    onValueChange = { if (it.length <= 500) reportReason = it },
                    label = { Text("What happened? (optional)") },
                    minLines = 2,
                    maxLines = 4,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    onAction(PrivacyAction.ReportParticipant(state.conversationId.orEmpty(), reportReason))
                    reportReason = ""
                    showReport = false
                }) { Text("Submit report") }
            },
            dismissButton = { TextButton(onClick = { showReport = false }) { Text("Cancel") } },
        )
    }

    if (showBlockConfirm) {
        AlertDialog(
            onDismissRequest = { showBlockConfirm = false },
            title = { Text("Block this person?") },
            text = { Text("They will no longer be able to contact you through LifeLink.") },
            confirmButton = {
                TextButton(onClick = {
                    onAction(PrivacyAction.BlockParticipant(state.conversationId.orEmpty()))
                    showBlockConfirm = false
                }) { Text("Block") }
            },
            dismissButton = { TextButton(onClick = { showBlockConfirm = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun MessageBubble(message: ChatMessage, mine: Boolean) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start) {
        Card(
            colors =
            CardDefaults.cardColors(
                containerColor =
                if (mine) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceContainerLow
                },
            ),
        ) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(message.body)
                Text(message.createdAt, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
