package com.lifelink.app.feature.privacy

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.lifelink.app.core.ui.LifeLinkLoadingIndicator
import com.lifelink.app.core.ui.LifeLinkPageHeader
import com.lifelink.app.domain.Conversation

@Composable
fun MessagingScreen(
    state: PrivacyUiState,
    onAction: (PrivacyAction) -> Unit,
    onOpenConversation: (String, String) -> Unit,
) {
    LaunchedEffect(Unit) { onAction(PrivacyAction.LoadConversations) }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                LifeLinkPageHeader("Messaging")
                Text(
                    "Chat with matched donors and requesters. Contact details remain private until shared.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (state.conversationsLoading) {
            item { LifeLinkLoadingIndicator(label = "Loading conversations…") }
        } else if (state.conversationsError != null) {
            item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(state.conversationsError, color = MaterialTheme.colorScheme.onErrorContainer)
                        Button(onClick = { onAction(PrivacyAction.LoadConversations) }) { Text("Retry") }
                    }
                }
            }
        } else if (state.conversations.isEmpty()) {
            item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("No conversations yet", fontWeight = FontWeight.SemiBold)
                        Text(
                            "Messaging becomes available after a matched donor or requester accepts a contact request.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        } else {
            items(state.conversations, key = { it.conversationId }) { conversation ->
                ConversationRow(conversation) { onOpenConversation(conversation.requestId, conversation.donorId) }
            }
        }
    }
}

@Composable
private fun ConversationRow(conversation: Conversation, onOpen: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen)
            .semantics { contentDescription = "Conversation ${conversation.conversationId.take(8)}, open chat" },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text("Conversation ${conversation.conversationId.take(8)}", fontWeight = FontWeight.SemiBold)
            Text("Request ${conversation.requestId.take(12)}", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                conversation.lastMessageAt?.let { "Last message: $it" } ?: "No messages yet",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
