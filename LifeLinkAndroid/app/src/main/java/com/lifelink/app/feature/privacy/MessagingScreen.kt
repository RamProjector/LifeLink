package com.lifelink.app.feature.privacy

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.lifelink.app.core.ui.LifeLinkEmptyState
import com.lifelink.app.core.ui.LifeLinkLoadingIndicator
import com.lifelink.app.core.ui.LifeLinkPageHeader
import com.lifelink.app.domain.Conversation

/**
 * Loads conversations on entry and displays loading, retry, empty, or conversation states.
 * Selecting a conversation calls [onOpenConversation] with its request and donor IDs.
 */
@Composable
fun MessagingScreen(
    state: PrivacyUiState,
    onAction: (PrivacyAction) -> Unit,
    onOpenConversation: (String, String) -> Unit,
    targetConversationId: String? = null,
    targetConversationEventId: String? = null,
    selectedRequestId: String? = null,
    selectedDonorId: String? = null,
) {
    LaunchedEffect(Unit) { onAction(PrivacyAction.LoadConversations) }
    var handledNotificationKey by remember { mutableStateOf<String?>(null) }
    val notificationKey = targetConversationId?.let { conversationId ->
        "$conversationId|${targetConversationEventId.orEmpty()}"
    }
    LaunchedEffect(notificationKey, state.conversations, state.conversationsLoading) {
        if (notificationKey != null && notificationKey != handledNotificationKey && !state.conversationsLoading) {
            state.conversations.firstOrNull { it.conversationId == targetConversationId }?.let { conversation ->
                handledNotificationKey = notificationKey
                onOpenConversation(conversation.requestId, conversation.donorId)
            }
        }
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            LifeLinkPageHeader(
                title = "Chats",
                subtitle = "Your conversations with matched donors and requesters. Contact details remain private until shared.",
            )
        }
        when {
            state.conversationsLoading -> item { LifeLinkLoadingIndicator(label = "Loading conversations\u2026") }
            state.conversationsError != null -> item {
                Card(
                    Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Assertive },
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                ) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(state.conversationsError, color = MaterialTheme.colorScheme.onErrorContainer)
                        Button(onClick = { onAction(PrivacyAction.LoadConversations) }) { Text("Retry") }
                    }
                }
            }
            state.conversations.isEmpty() -> item {
                LifeLinkEmptyState(
                    icon = Icons.AutoMirrored.Filled.Chat,
                    title = "No conversations yet",
                    body = "Messaging becomes available after a matched donor or requester accepts a contact request.",
                )
            }
            else -> items(state.conversations, key = { it.conversationId }) { conversation ->
                ConversationRow(
                    conversation = conversation,
                    selected = conversation.requestId == selectedRequestId && conversation.donorId == selectedDonorId,
                ) { onOpenConversation(conversation.requestId, conversation.donorId) }
            }
        }
    }
}

/** Displays a conversation with an initials avatar, request reference, and message recency. */
@Composable
private fun ConversationRow(conversation: Conversation, selected: Boolean, onOpen: () -> Unit) {
    val contentColor =
        if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
    val secondaryContentColor =
        if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
    Card(
        modifier =
        Modifier
            .fillMaxWidth()
            .clickable(onClickLabel = "Open chat", onClick = onOpen),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = if (selected) 2.dp else 1.dp),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Surface(
                modifier = Modifier.size(44.dp),
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primaryContainer,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.AutoMirrored.Filled.Chat,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text("Matched conversation", style = MaterialTheme.typography.titleMedium, color = contentColor)
                Text(
                    "Linked to your request",
                    color = secondaryContentColor,
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    if (conversation.lastMessageAt != null) "Recent activity" else "No messages yet",
                    style = MaterialTheme.typography.bodySmall,
                    color = secondaryContentColor,
                )
            }
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = secondaryContentColor,
            )
        }
    }
}
