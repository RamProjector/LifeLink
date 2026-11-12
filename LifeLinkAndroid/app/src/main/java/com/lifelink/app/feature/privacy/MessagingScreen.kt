package com.lifelink.app.feature.privacy

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.lifelink.app.core.ui.LifeLinkEmptyState
import com.lifelink.app.core.ui.LifeLinkLoadingIndicator
import com.lifelink.app.core.ui.LifeLinkPageHeader
import com.lifelink.app.domain.Conversation
import java.text.ParsePosition
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Loads conversations on entry and chooses compact navigation or expanded list-detail presentation.
 */
@Composable
fun MessagingScreen(
    state: PrivacyUiState,
    onAction: (PrivacyAction) -> Unit,
    onOpenConversation: (String, String) -> Unit,
    currentUserId: String = "",
    useListDetailLayout: Boolean = false,
    targetConversationId: String? = null,
    targetConversationEventId: String? = null,
) {
    LaunchedEffect(Unit) { onAction(PrivacyAction.LoadConversations) }
    var handledNotificationKey by rememberSaveable(currentUserId) { mutableStateOf<String?>(null) }
    var selectedConversationId by rememberSaveable(currentUserId) { mutableStateOf<String?>(null) }
    MessagingNotificationEffect(
        target = targetConversationId?.let { ConversationNotificationTarget(it, targetConversationEventId) },
        handledNotificationKey = handledNotificationKey,
        onHandledNotification = { handledNotificationKey = it },
        state = state,
        useListDetailLayout = useListDetailLayout,
        onSelectConversation = { selectedConversationId = it.conversationId },
        onOpenConversation = onOpenConversation,
    )
    if (!useListDetailLayout) {
        ConversationListPane(
            state = state,
            selectedConversationId = null,
            onAction = onAction,
            onSelectConversation = { conversation ->
                onOpenConversation(conversation.requestId, conversation.donorId)
            },
            modifier = Modifier.fillMaxSize(),
        )
    } else {
        Row(Modifier.fillMaxSize()) {
            ConversationListPane(
                state = state,
                selectedConversationId = selectedConversationId,
                onAction = onAction,
                onSelectConversation = { selectedConversationId = it.conversationId },
                modifier = Modifier
                    .width(360.dp)
                    .fillMaxHeight()
                    .semantics { paneTitle = "Conversations" }
                    .testTag("conversation-list-pane"),
            )
            androidx.compose.material3.VerticalDivider()
            ConversationDetailPane(
                state = state,
                selectedConversation = state.conversations.firstOrNull {
                    it.conversationId == selectedConversationId
                },
                currentUserId = currentUserId,
                onAction = onAction,
                onClearSelection = { selectedConversationId = null },
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .semantics { paneTitle = "Conversation details" }
                    .testTag("conversation-detail-pane"),
            )
        }
    }
}

private data class ConversationNotificationTarget(
    val conversationId: String,
    val eventId: String?,
)

@Composable
private fun MessagingNotificationEffect(
    target: ConversationNotificationTarget?,
    handledNotificationKey: String?,
    onHandledNotification: (String) -> Unit,
    state: PrivacyUiState,
    useListDetailLayout: Boolean,
    onSelectConversation: (Conversation) -> Unit,
    onOpenConversation: (String, String) -> Unit,
) {
    val notificationKey = target?.let { "\${it.conversationId}|\${it.eventId.orEmpty()}" }
    LaunchedEffect(notificationKey, state.conversations, state.conversationsLoading, useListDetailLayout) {
        if (notificationKey == null || notificationKey == handledNotificationKey || state.conversationsLoading) {
            return@LaunchedEffect
        }
        state.conversations.firstOrNull { it.conversationId == target?.conversationId }?.let { conversation ->
            onHandledNotification(notificationKey)
            if (useListDetailLayout) {
                onSelectConversation(conversation)
            } else {
                onOpenConversation(conversation.requestId, conversation.donorId)
            }
        }
    }
}
@Composable
private fun ConversationListPane(
    state: PrivacyUiState,
    selectedConversationId: String?,
    onAction: (PrivacyAction) -> Unit,
    onSelectConversation: (Conversation) -> Unit,
    modifier: Modifier,
) {
    LazyColumn(
        modifier = modifier,
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
            state.conversationsLoading -> item { LifeLinkLoadingIndicator(label = "Loading conversations…") }
            state.conversationsError != null -> item {
                ConversationLoadError(state.conversationsError, onRetry = {
                    onAction(PrivacyAction.LoadConversations)
                })
            }
            state.conversations.isEmpty() -> item {
                LifeLinkEmptyState(
                    icon = Icons.AutoMirrored.Filled.Chat,
                    title = "No conversations yet",
                    body = "Chats appear when you request chat with a matched participant or receive a contact request.",
                )
            }
            else -> items(state.conversations, key = { it.conversationId }) { conversation ->
                ConversationRow(
                    conversation = conversation,
                    selected = selectedConversationId == conversation.conversationId,
                    onOpen = { onSelectConversation(conversation) },
                )
            }
        }
    }
}

@Composable
private fun ConversationLoadError(error: String, onRetry: () -> Unit) {
    Card(
        Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Assertive },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(error, color = MaterialTheme.colorScheme.onErrorContainer)
            Button(onClick = onRetry) { Text("Retry") }
        }
    }
}

@Composable
private fun ConversationDetailPane(
    state: PrivacyUiState,
    selectedConversation: Conversation?,
    currentUserId: String,
    onAction: (PrivacyAction) -> Unit,
    onClearSelection: () -> Unit,
    modifier: Modifier,
) {
    Box(modifier, contentAlignment = Alignment.Center) {
        if (selectedConversation == null) {
            LifeLinkEmptyState(
                icon = Icons.AutoMirrored.Filled.Chat,
                title = "Choose a conversation",
                body = "Select a chat to view messages.",
            )
        } else {
            ConversationScreen(
                state = state,
                requestId = selectedConversation.requestId,
                donorId = selectedConversation.donorId,
                currentUserId = currentUserId,
                onAction = onAction,
                onBack = onClearSelection,
            )
        }
    }
}

/**
 * Converts API timestamps to concise device-local labels. Uses platform date APIs
 * supported by the app's API 24 minimum; invalid or legacy values get neutral copy.
 */
internal fun formatConversationTimestamp(timestamp: String, now: Date = Date()): String {
    val normalized = timestamp.replace(
        Regex("(\\.\\d{3})\\d+(?=(Z|[+-]\\d{2}:?\\d{2})$)"),
        "$1",
    )
    val patterns = listOf(
        "yyyy-MM-dd'T'HH:mm:ss.SSSXXX",
        "yyyy-MM-dd'T'HH:mm:ssXXX",
    )
    val parsed = patterns.firstNotNullOfOrNull { pattern ->
        val parser = SimpleDateFormat(pattern, Locale.US).apply { isLenient = false }
        val position = ParsePosition(0)
        parser.parse(normalized, position)?.takeIf { position.index == normalized.length }
    } ?: return "Recent message"

    val localTime = Calendar.getInstance().apply { time = parsed }
    val localNow = Calendar.getInstance().apply { time = now }
    val timeLabel = SimpleDateFormat("h:mm a", Locale.getDefault()).format(parsed)
    val sameDay = localTime.get(Calendar.ERA) == localNow.get(Calendar.ERA) &&
        localTime.get(Calendar.YEAR) == localNow.get(Calendar.YEAR) &&
        localTime.get(Calendar.DAY_OF_YEAR) == localNow.get(Calendar.DAY_OF_YEAR)
    if (sameDay) return "Today · $timeLabel"

    val yesterday = (localNow.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, -1) }
    val isYesterday = localTime.get(Calendar.ERA) == yesterday.get(Calendar.ERA) &&
        localTime.get(Calendar.YEAR) == yesterday.get(Calendar.YEAR) &&
        localTime.get(Calendar.DAY_OF_YEAR) == yesterday.get(Calendar.DAY_OF_YEAR)
    if (isYesterday) return "Yesterday · $timeLabel"

    val pattern = if (localTime.get(Calendar.YEAR) == localNow.get(Calendar.YEAR)) "MMM d · h:mm a" else "MMM d, yyyy"
    return SimpleDateFormat(pattern, Locale.getDefault()).format(parsed)
}

/** Displays a conversation with an initials avatar, request reference, and message recency. */
@Composable
private fun ConversationRow(conversation: Conversation, selected: Boolean, onOpen: () -> Unit) {
    Card(
        modifier =
        Modifier
            .fillMaxWidth()
            .clickable(onClickLabel = "Open chat", onClick = onOpen),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface,
        ),
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
