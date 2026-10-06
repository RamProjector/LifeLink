package com.lifelink.app.feature.privacy

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
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
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.lifelink.app.core.ui.LifeLinkEmptyState
import com.lifelink.app.domain.ChatMessage
import com.lifelink.app.domain.ContactShare

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
    val listState = rememberLazyListState()
    // Chat convention: keep the newest message in view as the thread grows.
    LaunchedEffect(state.messages.size) {
        if (state.messages.isNotEmpty()) listState.animateScrollToItem(state.messages.lastIndex)
    }

    Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Conversation",
                Modifier.weight(1f).semantics { heading() },
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )
            TextButton(onClick = onBack) { Text("Back") }
        }
        Text(
            "Only you and the other person can read these messages. Contact details stay hidden until you share them.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        state.message?.let {
            Text(
                it,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
        }

        LazyColumn(
            Modifier.weight(1f).fillMaxWidth(),
            state = listState,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (state.messages.isEmpty()) {
                item {
                    LifeLinkEmptyState(
                        icon = Icons.AutoMirrored.Filled.Chat,
                        title = "No messages yet",
                        body = "Say hello to start the conversation.",
                    )
                }
            }
            items(state.messages, key = { it.messageId }) { message -> MessageBubble(message, message.senderId == currentUserId) }
        }

        if (state.contactShares.isNotEmpty()) {
            SharedContactsCard(shares = state.contactShares)
        }

        MessageComposer(
            draft = draft,
            sending = state.sending,
            onDraftChange = { if (it.length <= MAX_MESSAGE_LENGTH) draft = it },
            onSend = {
                onAction(PrivacyAction.SendMessage(draft))
                draft = ""
            },
        )

        ShareContactCard(
            contactField = contactField,
            contactValue = contactValue,
            onFieldChange = { contactField = it },
            onValueChange = { if (it.length <= MAX_CONTACT_VALUE_LENGTH) contactValue = it },
            onShare = {
                onAction(PrivacyAction.ShareContact(contactField, contactValue))
                contactValue = ""
            },
        )

        // Report and Block are surfaced here so a participant can act on an unsafe
        // interaction without leaving the conversation.
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Safety", fontWeight = FontWeight.SemiBold, modifier = Modifier.semantics { heading() })
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

// Field length limits for the message composer and the contact-share input.
private const val MAX_MESSAGE_LENGTH = 2000
private const val MAX_CONTACT_VALUE_LENGTH = 320

/** Card listing the contact details each participant has chosen to share. Bounded and scrollable. */
@Composable
private fun SharedContactsCard(shares: List<ContactShare>) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(
            Modifier.padding(12.dp).heightIn(max = 180.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text("Shared contact details", fontWeight = FontWeight.SemiBold, modifier = Modifier.semantics { heading() })
            shares.forEach { share ->
                Text(
                    "${share.field.replaceFirstChar { it.uppercase() }}: ${share.value}",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

/** Message input row with an IME send action and a send button that shows progress while sending. */
@Composable
private fun MessageComposer(draft: String, sending: Boolean, onDraftChange: (String) -> Unit, onSend: () -> Unit) {
    val canSend = draft.isNotBlank() && !sending
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = draft,
            onValueChange = onDraftChange,
            modifier = Modifier.weight(1f),
            label = { Text("Message") },
            maxLines = 4,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { if (canSend) onSend() }),
        )
        Button(
            onClick = onSend,
            enabled = canSend,
            contentPadding =
            androidx.compose.foundation.layout
                .PaddingValues(horizontal = 16.dp),
        ) {
            if (sending) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            } else {
                Icon(
                    Icons.AutoMirrored.Filled.Send,
                    contentDescription = "Send message",
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

/** Card letting a participant share one contact field (phone or email) with the other. */
@Composable
private fun ShareContactCard(
    contactField: String,
    contactValue: String,
    onFieldChange: (String) -> Unit,
    onValueChange: (String) -> Unit,
    onShare: () -> Unit,
) {
    val isPhone = contactField == "phone"
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Share contact details", fontWeight = FontWeight.SemiBold, modifier = Modifier.semantics { heading() })
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { onFieldChange("phone") }, enabled = !isPhone) { Text("Phone") }
                OutlinedButton(onClick = { onFieldChange("email") }, enabled = isPhone) { Text("Email") }
            }
            OutlinedTextField(
                value = contactValue,
                onValueChange = onValueChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text(if (isPhone) "Phone number" else "Email address") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = if (isPhone) KeyboardType.Phone else KeyboardType.Email),
            )
            Button(onClick = onShare, enabled = contactValue.isNotBlank()) { Text("Share $contactField") }
            Text(
                "Sharing is recorded in an audit log. You can share each field separately.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun MessageBubble(message: ChatMessage, mine: Boolean) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start) {
        Card(
            modifier = Modifier.widthIn(max = 300.dp),
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
