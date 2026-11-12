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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.lifelink.app.core.ui.LifeLinkEmptyState
import com.lifelink.app.core.ui.LifeLinkLoadingIndicator
import com.lifelink.app.core.ui.LifeLinkPageHeader
import com.lifelink.app.domain.Conversation
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Locale

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
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                LifeLinkPageHeader("Chats")
                Text(
                    "Chat with matched donors and requesters. Contact details remain private until shared.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
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
                    body = "Chats appear when you request chat with a matched participant or receive a contact request.",
                )
            }
            else -> items(state.conversations, key = { it.conversationId }) { conversation ->
                ConversationRow(conversation) { onOpenConversation(conversation.requestId, conversation.donorId) }
            }
        }
    }
}

/**
 * Converts API timestamps to concise device-local labels. Invalid or legacy values
 * fall back to neutral copy rather than exposing raw timestamp strings in the UI.
 */
internal fun formatConversationTimestamp(timestamp: String, now: OffsetDateTime = OffsetDateTime.now()): String {
    val conversationTime = try {
        OffsetDateTime.parse(timestamp).atZoneSameInstant(ZoneId.systemDefault())
    } catch (_: DateTimeParseException) {
        return "Recent message"
    }
    val localNow = now.atZoneSameInstant(ZoneId.systemDefault())
    val timeLabel = conversationTime.format(DateTimeFormatter.ofPattern("h:mm a", Locale.getDefault()))
    return when (conversationTime.toLocalDate()) {
        localNow.toLocalDate() -> "Today · $timeLabel"
        localNow.toLocalDate().minusDays(1) -> "Yesterday · $timeLabel"
        else -> {
            val pattern = if (conversationTime.year == localNow.year) "MMM d · h:mm a" else "MMM d, yyyy"
            conversationTime.format(DateTimeFormatter.ofPattern(pattern, Locale.getDefault()))
        }
    }
}

/** Displays a conversation with an initials avatar, request reference, and message recency. */
@Composable
private fun ConversationRow(conversation: Conversation, onOpen: () -> Unit) {
    Card(
        modifier =
        Modifier
            .fillMaxWidth()
            .clickable(onClickLabel = "Open chat", onClick = onOpen),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
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
                Text("LifeLink participant", fontWeight = FontWeight.SemiBold)
                Text(
                    "Request conversation",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    conversation.lastMessageAt?.let { "Last message · ${formatConversationTimestamp(it)}" } ?: "No messages yet",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
