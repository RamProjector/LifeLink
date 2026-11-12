package com.lifelink.app.feature.privacy

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.lifelink.app.core.ui.LifeLinkEmptyState
import com.lifelink.app.domain.ChatMessage
import com.lifelink.app.domain.ContactShare
import kotlinx.coroutines.delay

/**
 * Polls while the conversation is resumed and stops automatically in the background.
 */
@Composable
private fun ConversationRefreshEffect(
    conversationId: String?,
    onAction: (PrivacyAction) -> Unit,
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(conversationId, lifecycleOwner) {
        if (conversationId.isNullOrBlank()) return@LaunchedEffect
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                delay(8_000)
                onAction(PrivacyAction.RefreshConversation)
            }
        }
    }
}

private data class ConversationListActions(
    val onContactFieldChange: (String) -> Unit,
    val onContactValueChange: (String) -> Unit,
    val onShareContact: () -> Unit,
    val onReport: () -> Unit,
    val onBlock: () -> Unit,
    val onAction: (PrivacyAction) -> Unit,
)

private data class ConversationDraftAcknowledgementState(
    val sentMessage: ChatMessage?,
    val sending: Boolean,
    val message: String?,
    val currentUserId: String,
    val draft: String,
    val draftRevision: Int,
    val submittedDraftRevision: Int?,
)

private data class ConversationDraftAcknowledgementActions(val onClearDraft: () -> Unit, val onClearSubmittedRevision: () -> Unit)

private data class ConversationMessagesListParams(
    val modifier: Modifier,
    val listState: LazyListState,
    val state: PrivacyUiState,
    val currentUserId: String,
    val chatReady: Boolean,
    val contactField: String,
    val contactValue: String,
    val actions: ConversationListActions,
)

private data class ConversationDialogState(val showReport: Boolean, val reportReason: String, val showBlockConfirm: Boolean)

private data class ConversationDialogActions(
    val onReportReasonChange: (String) -> Unit,
    val onDismissReport: () -> Unit,
    val onDismissBlock: () -> Unit,
    val onAction: (PrivacyAction) -> Unit,
    val onReportSubmitted: () -> Unit,
)

@Composable
private fun ConversationOpenEffect(
    requestId: String,
    donorId: String,
    onReset: () -> Unit,
    onAction: (PrivacyAction) -> Unit,
) {
    LaunchedEffect(requestId, donorId) {
        onReset()
        onAction(PrivacyAction.OpenConversation(requestId, donorId))
    }
}

@Composable
private fun ConversationDraftAcknowledgementEffect(
    state: ConversationDraftAcknowledgementState,
    actions: ConversationDraftAcknowledgementActions,
) {
    LaunchedEffect(state.sentMessage?.messageId, state.sending, state.message) {
        if (state.sentMessage?.senderId == state.currentUserId && state.submittedDraftRevision != null) {
            if (state.draftRevision == state.submittedDraftRevision && state.draft.trim() == state.sentMessage.body) {
                actions.onClearDraft()
            }
            actions.onClearSubmittedRevision()
        } else if (!state.sending && state.message != null) {
            actions.onClearSubmittedRevision()
        }
    }
}

@Composable
private fun ConversationContactShareEffect(
    lastSharedContact: ContactShare?,
    currentUserId: String,
    contactField: String,
    contactValue: String,
    onClearValue: () -> Unit,
) {
    LaunchedEffect(lastSharedContact?.shareId) {
        if (
            lastSharedContact?.sharedBy == currentUserId &&
            lastSharedContact.field == contactField &&
            lastSharedContact.value == contactValue
        ) {
            onClearValue()
        }
    }
}

@Composable
private fun ConversationAutoScrollEffect(messages: List<ChatMessage>, listState: LazyListState) {
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.lastIndex)
    }
}

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
    var draft by remember { mutableStateOf("") }
    var draftRevision by remember { mutableStateOf(0) }
    var submittedDraftRevision by remember { mutableStateOf<Int?>(null) }
    ConversationOpenEffect(
        requestId = requestId,
        donorId = donorId,
        onReset = {
            draft = ""
            draftRevision += 1
            submittedDraftRevision = null
        },
        onAction = onAction,
    )
    ConversationRefreshEffect(state.conversationId, onAction)
    // Clear only the unchanged draft version acknowledged by the server.
    ConversationDraftAcknowledgementEffect(
        state = ConversationDraftAcknowledgementState(
            sentMessage = state.lastSentMessage,
            sending = state.sending,
            message = state.message,
            currentUserId = currentUserId,
            draft = draft,
            draftRevision = draftRevision,
            submittedDraftRevision = submittedDraftRevision,
        ),
        actions = ConversationDraftAcknowledgementActions(
            onClearDraft = {
                draft = ""
                draftRevision += 1
            },
            onClearSubmittedRevision = { submittedDraftRevision = null },
        ),
    )
    var contactField by remember { mutableStateOf("phone") }
    var contactValue by remember { mutableStateOf("") }
    ConversationContactShareEffect(
        lastSharedContact = state.lastSharedContact,
        currentUserId = currentUserId,
        contactField = contactField,
        contactValue = contactValue,
        onClearValue = { contactValue = "" },
    )
    var showReport by remember { mutableStateOf(false) }
    var reportReason by remember { mutableStateOf("") }
    var showBlockConfirm by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val chatReady = !state.chatLoading && state.conversationId != null
    // Keep the newest message in view as the thread grows.
    ConversationAutoScrollEffect(state.messages, listState)

    Column(
        Modifier
            .fillMaxSize()
            .imePadding()
            .navigationBarsPadding()
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Conversation",
                Modifier.weight(1f).semantics { heading() },
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )
            androidx.compose.material3.IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
        }
        Text(
            "Messages are shared with the matched requester and donor. Contact details stay hidden until you choose to share them.",
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
        state.conversationRefreshError?.let { refreshError ->
            Card(
                Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        refreshError,
                        Modifier.weight(1f),
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    TextButton(onClick = { onAction(PrivacyAction.RefreshConversation) }) {
                        Text("Retry")
                    }
                }
            }
        }

        ConversationMessagesList(
            ConversationMessagesListParams(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                listState = listState,
                state = state,
                currentUserId = currentUserId,
                chatReady = chatReady,
                contactField = contactField,
                contactValue = contactValue,
                actions = ConversationListActions(
                    onContactFieldChange = { contactField = it },
                    onContactValueChange = { if (it.length <= MAX_CONTACT_VALUE_LENGTH) contactValue = it },
                    onShareContact = { onAction(PrivacyAction.ShareContact(contactField, contactValue)) },
                    onReport = { showReport = true },
                    onBlock = { showBlockConfirm = true },
                    onAction = onAction,
                ),
            ),
        )

        if (state.conversationId == null && !state.chatLoading) {
            Button(
                onClick = { onAction(PrivacyAction.OpenConversation(requestId, donorId)) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Try again")
            }
        } else {
            MessageComposer(
                draft = draft,
                sending = state.sending,
                enabled = chatReady,
                onDraftChange = {
                    if (it.length <= MAX_MESSAGE_LENGTH) {
                        draft = it
                        draftRevision += 1
                    }
                },
                onSend = {
                    if (chatReady) {
                        submittedDraftRevision = draftRevision
                        onAction(PrivacyAction.SendMessage(draft))
                    }
                },
            )
        }
    }

    ConversationSafetyDialogs(
        conversationId = state.conversationId.orEmpty(),
        dialogState = ConversationDialogState(
            showReport = showReport,
            reportReason = reportReason,
            showBlockConfirm = showBlockConfirm,
        ),
        actions = ConversationDialogActions(
            onReportReasonChange = { reportReason = it },
            onDismissReport = { showReport = false },
            onDismissBlock = { showBlockConfirm = false },
            onAction = onAction,
            onReportSubmitted = {
                reportReason = ""
                showReport = false
            },
        ),
    )
}

@Composable
private fun ConversationMessagesList(params: ConversationMessagesListParams) {
    LazyColumn(
        params.modifier,
        state = params.listState,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        conversationStatusItems(params.state)
        conversationMessageItems(params.state, params.currentUserId, params.chatReady, params.actions.onAction)
        conversationSupplementalItems(
            params.state,
            params.chatReady,
            params.contactField,
            params.contactValue,
            params.actions,
        )
    }
}

private fun LazyListScope.conversationStatusItems(state: PrivacyUiState) {
    when {
        state.chatLoading -> item {
            Column(
                Modifier.fillMaxWidth().padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                CircularProgressIndicator(Modifier.size(28.dp))
                Text("Loading conversation…", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        state.conversationId == null -> item {
            LifeLinkEmptyState(
                icon = Icons.AutoMirrored.Filled.Chat,
                title = "Conversation unavailable",
                body = "This conversation could not be opened. Go back and try again.",
            )
        }
        state.messages.isEmpty() -> item {
            LifeLinkEmptyState(
                icon = Icons.AutoMirrored.Filled.Chat,
                title = "No messages yet",
                body = "Say hello to start the conversation.",
            )
        }
    }
}

private fun LazyListScope.conversationMessageItems(
    state: PrivacyUiState,
    currentUserId: String,
    chatReady: Boolean,
    onAction: (PrivacyAction) -> Unit,
) {
    items(state.messages, key = { it.messageId }) { message ->
        val mine = message.senderId == currentUserId
        Column(Modifier.fillMaxWidth(), horizontalAlignment = if (mine) Alignment.End else Alignment.Start) {
            MessageBubble(message, mine)
            TextButton(
                onClick = { onAction(PrivacyAction.ToggleSavedMessage(message.messageId)) },
                enabled = chatReady,
            ) {
                Text(if (message.isSaved) "Remove saved message" else "Save message")
            }
        }
    }
}

private fun LazyListScope.conversationSupplementalItems(
    state: PrivacyUiState,
    chatReady: Boolean,
    contactField: String,
    contactValue: String,
    actions: ConversationListActions,
) {
    if (state.savedMessages.isNotEmpty()) {
        item(key = "saved-messages") { SavedMessagesCard(state.savedMessages) }
    }
    if (state.contactShares.isNotEmpty()) {
        item(key = "shared-contacts") { SharedContactsCard(shares = state.contactShares) }
    }
    item(key = "share-contact") {
        ShareContactCard(
            contactField = contactField,
            contactValue = contactValue,
            enabled = chatReady && !state.sharingContact,
            onFieldChange = actions.onContactFieldChange,
            onValueChange = actions.onContactValueChange,
            onShare = actions.onShareContact,
        )
    }
    item(key = "conversation-safety") {
        ConversationSafetyCard(onReport = actions.onReport, onBlock = actions.onBlock)
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

/** Displays this participant's server-owned saved messages in the open conversation. */
@Composable
private fun SavedMessagesCard(messages: List<ChatMessage>) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)) {
        Column(
            Modifier.padding(12.dp).heightIn(max = 140.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text("Saved messages", fontWeight = FontWeight.SemiBold, modifier = Modifier.semantics { heading() })
            messages.forEach { message -> Text(message.body, style = MaterialTheme.typography.bodySmall) }
        }
    }
}

/** Message input row with an IME send action and a send button that shows progress while sending. */
@Composable
private fun MessageComposer(
    draft: String,
    sending: Boolean,
    enabled: Boolean,
    onDraftChange: (String) -> Unit,
    onSend: () -> Unit,
) {
    val canSend = draft.isNotBlank() && !sending && enabled
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = draft,
            onValueChange = onDraftChange,
            enabled = enabled,
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
    enabled: Boolean,
    onFieldChange: (String) -> Unit,
    onValueChange: (String) -> Unit,
    onShare: () -> Unit,
) {
    val isPhone = contactField == "phone"
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Share contact details", fontWeight = FontWeight.SemiBold, modifier = Modifier.semantics { heading() })
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { onFieldChange("phone") }, enabled = enabled && !isPhone) { Text("Phone") }
                OutlinedButton(onClick = { onFieldChange("email") }, enabled = enabled && isPhone) { Text("Email") }
            }
            OutlinedTextField(
                value = contactValue,
                onValueChange = onValueChange,
                enabled = enabled,
                modifier = Modifier.fillMaxWidth(),
                label = { Text(if (isPhone) "Phone number" else "Email address") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = if (isPhone) KeyboardType.Phone else KeyboardType.Email),
            )
            Button(onClick = onShare, enabled = enabled && contactValue.isNotBlank()) { Text("Share $contactField") }
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
                Text(formatConversationTimestamp(message.createdAt), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** Keeps safety actions and confirmation dialogs separate from conversation layout. */
@Composable
private fun ConversationSafetyCard(
    onReport: () -> Unit,
    onBlock: () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Safety", fontWeight = FontWeight.SemiBold, modifier = Modifier.semantics { heading() })
            Text(
                "If this conversation feels unsafe, report or block the other person. Reports are reviewed by our team.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onReport, modifier = Modifier.weight(1f)) { Text("Report") }
                OutlinedButton(onClick = onBlock, modifier = Modifier.weight(1f)) { Text("Block") }
            }
        }
    }
}

/** Keeps report and block confirmation behavior isolated and easy to regression-test. */
@Composable
private fun ConversationSafetyDialogs(
    conversationId: String,
    dialogState: ConversationDialogState,
    actions: ConversationDialogActions,
) {
    if (dialogState.showReport) {
        AlertDialog(
            onDismissRequest = actions.onDismissReport,
            title = { Text("Report this person") },
            text = {
                OutlinedTextField(
                    value = dialogState.reportReason,
                    onValueChange = { if (it.length <= 500) actions.onReportReasonChange(it) },
                    label = { Text("What happened? (optional)") },
                    minLines = 2,
                    maxLines = 4,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    actions.onAction(PrivacyAction.ReportParticipant(conversationId, dialogState.reportReason))
                    actions.onReportSubmitted()
                }) { Text("Submit report") }
            },
            dismissButton = { TextButton(onClick = actions.onDismissReport) { Text("Cancel") } },
        )
    }
    if (dialogState.showBlockConfirm) {
        AlertDialog(
            onDismissRequest = actions.onDismissBlock,
            title = { Text("Block this person?") },
            text = { Text("They will no longer be able to contact you through LifeLink.") },
            confirmButton = {
                TextButton(onClick = {
                    actions.onAction(PrivacyAction.BlockParticipant(conversationId))
                    actions.onDismissBlock()
                }) { Text("Block") }
            },
            dismissButton = { TextButton(onClick = actions.onDismissBlock) { Text("Cancel") } },
        )
    }
}
