package com.lifelink.app.feature.updates

import android.text.format.DateUtils
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.NotificationsNone
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.lifelink.app.core.ui.LifeLinkPageHeader
import com.lifelink.app.domain.UpdateItem

/**
 * Displays activity and its unread count, or an empty state when [updates] is empty.
 * Delegates opening an item to [onOpen] and marking all items read to [onMarkAllRead].
 */
@Composable
fun UpdatesScreen(
    updates: List<UpdateItem>,
    onOpen: (UpdateItem) -> Unit,
    onMarkAllRead: () -> Unit,
) {
    val unread = updates.count { !it.isRead }
    LazyColumn(
        modifier = Modifier.fillMaxSize().statusBarsPadding().padding(horizontal = 20.dp),
        contentPadding = PaddingValues(top = 24.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    LifeLinkPageHeader("Activity")
                    Text(
                        if (unread == 0) "You\u2019re up to date." else "$unread item${if (unread == 1) "" else "s"} need your attention.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (unread > 0) TextButton(onClick = onMarkAllRead) { Text("Mark all read") }
            }
        }
        if (updates.isEmpty()) {
            item { EmptyUpdatesCard() }
        } else {
            items(updates, key = { it.id }) { update -> UpdateRow(update, onOpen) }
        }
    }
}

/** Empty state shown when there is no activity to review. */
@Composable
private fun EmptyUpdatesCard() {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(
            Modifier.fillMaxWidth().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Surface(
                modifier = Modifier.size(52.dp),
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.secondaryContainer,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Default.CheckCircle,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                }
            }
            Text("Nothing needs your attention", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleMedium)
            Text(
                "Request changes, donor responses, and account notices will appear here when something changes.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

/** Displays [update] with unread emphasis, a leading status icon, and a relative timestamp. */
@Composable
private fun UpdateRow(update: UpdateItem, onOpen: (UpdateItem) -> Unit) {
    Card(
        modifier =
        Modifier.fillMaxWidth().clickable { onOpen(update) }.semantics {
            contentDescription =
                if (update.requestId != null) {
                    "${update.title}. Open activity for request"
                } else {
                    "${update.title}. Open activity"
                }
        },
        colors =
        CardDefaults.cardColors(
            containerColor = if (update.isRead) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.primaryContainer,
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = if (update.isRead) 1.dp else 2.dp),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 13.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Surface(
                modifier = Modifier.size(36.dp),
                shape = MaterialTheme.shapes.medium,
                color = if (update.isRead) MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.primary,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Default.NotificationsNone,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint = if (update.isRead) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onPrimary,
                    )
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(update.title, modifier = Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                    Text(
                        DateUtils
                            .getRelativeTimeSpanString(
                                update.createdAtEpochMillis,
                                System.currentTimeMillis(),
                                DateUtils.MINUTE_IN_MILLIS,
                            ).toString(),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(update.body, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                update.requestId?.let {
                    Text("Tap to open request", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }
}
