package com.lifelink.app.core.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * Consistent, non-modal feedback for an operation or content panel.
 *
 * Pass only safe, user-facing copy in [message] (never raw exceptions or server payloads).
 * The optional action is rendered only when both its label and callback are supplied.
 * Urgent errors are announced assertively; routine status updates use a polite live region.
 */
@Composable
fun LifeLinkFeedbackCard(
    title: String,
    message: String,
    level: LifeLinkFeedbackLevel = LifeLinkFeedbackLevel.Info,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    val containerColor = when (level) {
        LifeLinkFeedbackLevel.Success -> MaterialTheme.colorScheme.secondaryContainer
        LifeLinkFeedbackLevel.Info -> MaterialTheme.colorScheme.surfaceContainerHigh
        LifeLinkFeedbackLevel.Warning -> MaterialTheme.colorScheme.tertiaryContainer
        LifeLinkFeedbackLevel.Error -> MaterialTheme.colorScheme.errorContainer
    }
    val contentColor = when (level) {
        LifeLinkFeedbackLevel.Success -> MaterialTheme.colorScheme.onSecondaryContainer
        LifeLinkFeedbackLevel.Info -> MaterialTheme.colorScheme.onSurface
        LifeLinkFeedbackLevel.Warning -> MaterialTheme.colorScheme.onTertiaryContainer
        LifeLinkFeedbackLevel.Error -> MaterialTheme.colorScheme.onErrorContainer
    }

    Card(
        modifier = modifier.fillMaxWidth().semantics {
            liveRegion = if (level == LifeLinkFeedbackLevel.Error) {
                LiveRegionMode.Assertive
            } else {
                LiveRegionMode.Polite
            }
        },
        colors = CardDefaults.cardColors(containerColor = containerColor),
    ) {
        Column(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                title,
                color = contentColor,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                message,
                modifier = Modifier.semantics {
                    if (level == LifeLinkFeedbackLevel.Error) error(message)
                },
                color = contentColor,
                style = MaterialTheme.typography.bodyMedium,
            )
            if (actionLabel != null && onAction != null) {
                Button(onClick = onAction) {
                    Text(actionLabel)
                }
            }
        }
    }
}
