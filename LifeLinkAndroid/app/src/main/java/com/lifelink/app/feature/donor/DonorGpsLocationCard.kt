package com.lifelink.app.feature.donor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.lifelink.app.core.ui.LifeLinkFeedbackLevel

internal enum class GpsSettingsAction(val testTag: String, val label: String) {
    APP_PERMISSION("donor-gps-settings", "Open app settings"),
    DEVICE_LOCATION("donor-gps-location-settings", "Open device location settings"),
}

internal data class GpsLocationCardState(
    val latitude: Double? = null,
    val longitude: Double? = null,
    val capturedThisSession: Boolean = false,
    val loading: Boolean = false,
    val message: String? = null,
    val messageLevel: LifeLinkFeedbackLevel = LifeLinkFeedbackLevel.Info,
    val settingsAction: GpsSettingsAction? = null,
)

@Composable
internal fun GpsLocationCard(
    state: GpsLocationCardState,
    onRequestCurrentGps: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    Card(
        Modifier.fillMaxWidth().testTag("donor-gps-location"),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            GpsLocationSummary(state)
            Button(
                onClick = onRequestCurrentGps,
                enabled = !state.loading,
                modifier = Modifier.fillMaxWidth().testTag("capture-donor-gps"),
            ) {
                Text(
                    when {
                        state.loading -> "Getting GPS location…"
                        state.capturedThisSession -> "Refresh current GPS location"
                        else -> "Use current GPS location"
                    },
                )
            }
            state.message?.let { GpsCaptureMessage(it, state.messageLevel) }
            state.settingsAction?.let { GpsSettingsButton(it, onOpenSettings) }
        }
    }
}

@Composable
private fun GpsLocationSummary(state: GpsLocationCardState) {
    Text("Required GPS location", fontWeight = FontWeight.SemiBold)
    Text(
        when {
            state.capturedThisSession -> "A current device location has been captured for this session."
            state.latitude != null && state.longitude != null ->
                "A saved location exists, but it must be refreshed from device GPS before saving."
            else -> "Capture your device's current GPS location before saving your donor profile."
        },
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.bodySmall,
    )
}

@Composable
private fun GpsCaptureMessage(message: String, level: LifeLinkFeedbackLevel) {
    Text(
        message,
        color = when (level) {
            LifeLinkFeedbackLevel.Success -> MaterialTheme.colorScheme.primary
            LifeLinkFeedbackLevel.Info -> MaterialTheme.colorScheme.onSurfaceVariant
            LifeLinkFeedbackLevel.Warning -> MaterialTheme.colorScheme.tertiary
            LifeLinkFeedbackLevel.Error -> MaterialTheme.colorScheme.error
        },
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.testTag("donor-gps-message").semantics {
            liveRegion = if (level == LifeLinkFeedbackLevel.Error) LiveRegionMode.Assertive else LiveRegionMode.Polite
            if (level == LifeLinkFeedbackLevel.Error) error(message)
        },
    )
}

@Composable
private fun GpsSettingsButton(action: GpsSettingsAction, onClick: () -> Unit) {
    TextButton(onClick = onClick, modifier = Modifier.testTag(action.testTag)) {
        Text(action.label)
    }
}
