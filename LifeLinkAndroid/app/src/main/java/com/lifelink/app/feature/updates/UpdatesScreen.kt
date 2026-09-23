package com.lifelink.app.feature.updates

import android.text.format.DateUtils
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.lifelink.app.domain.UpdateItem

@Composable
fun UpdatesScreen(
    updates: List<UpdateItem>,
    onOpen: (UpdateItem) -> Unit,
    onMarkAllRead: () -> Unit
) {
    val unread = updates.count { !it.isRead }
    LazyColumn(
        modifier = Modifier.fillMaxSize().statusBarsPadding().padding(horizontal = 20.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(top = 24.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Updates", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                    Text(
                        if (unread == 0) "You’re up to date." else "$unread update${if (unread == 1) "" else "s"} need your attention.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (unread > 0) TextButton(onClick = onMarkAllRead) { Text("Mark all read") }
            }
        }
        if (updates.isEmpty()) {
            item {
                Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Nothing new", fontWeight = FontWeight.SemiBold)
                        Text("Request changes, donor responses, and account notices will appear here.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        } else {
            items(updates, key = { it.id }) { update -> UpdateRow(update, onOpen) }
        }
    }
}

@Composable
private fun UpdateRow(update: UpdateItem, onOpen: (UpdateItem) -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable { onOpen(update) },
        colors = CardDefaults.cardColors(
            containerColor = if (update.isRead) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.primaryContainer
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = if (update.isRead) 1.dp else 2.dp)
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(update.title, modifier = Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                Text(
                    DateUtils.getRelativeTimeSpanString(update.createdAtEpochMillis, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(update.body, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (update.requestId != null) {
                OutlinedButton(onClick = { onOpen(update) }) { Text("Open request") }
            }
        }
    }
}
