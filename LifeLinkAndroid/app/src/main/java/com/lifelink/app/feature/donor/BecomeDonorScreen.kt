package com.lifelink.app.feature.donor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.lifelink.app.domain.BloodType
import com.lifelink.app.domain.DonorAvailability
import com.lifelink.app.domain.DonorProfileMe

/**
 * Separate donor-profile flow: opt in to donating after account creation.
 *
 * This screen is reachable from Profile ("Become a donor") and is deliberately
 * not part of the signup form, so a user can create a normal account first and
 * decide later whether to donate. It also edits the profile and toggles
 * availability once the user has opted in.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BecomeDonorScreen(
    state: BecomeDonorUiState,
    onAction: (BecomeDonorAction) -> Unit,
    onBack: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (state.optedIn) "Donor profile" else "Become a donor") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                },
            )
        },
    ) { padding ->
        if (state.loading) {
            LoadingContent(padding)
        } else {
            BecomeDonorContent(state, onAction, padding)
        }
    }
}

@Composable
private fun LoadingContent(padding: PaddingValues) {
    Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            CircularProgressIndicator(Modifier.size(36.dp), strokeWidth = 3.dp)
            Text("Loading your donor profile\u2026", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun BecomeDonorContent(
    state: BecomeDonorUiState,
    onAction: (BecomeDonorAction) -> Unit,
    padding: PaddingValues,
) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(padding)
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            if (state.optedIn) "Manage your donor profile" else "Offer to donate",
            Modifier.semantics { heading() },
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
        )
        Text(
            "Your account is already created. Becoming a donor is optional and separate: fill in a donor " +
                "profile only when you want to help. You can pause or opt out at any time.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (state.optedIn) {
            VerificationCard(state.draft)
            AvailabilityCard(state.draft, state.saving, onAction)
        }
        DonorDetailsCard(state, onAction)
        state.message?.let { message ->
            Card(
                Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
            ) {
                Text(message, Modifier.padding(14.dp), color = MaterialTheme.colorScheme.onSecondaryContainer)
            }
        }
        if (state.optedIn) {
            OutlinedButton(
                onClick = { onAction(BecomeDonorAction.OptOut) },
                enabled = !state.saving,
                modifier = Modifier.fillMaxWidth().testTag("donor-opt-out"),
            ) { Text("Stop being a donor") }
        }
    }
}

/** Explains the verified + available gating so the donor knows why they may not be matched yet. */
@Composable
private fun VerificationCard(profile: DonorProfileMe) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Matching status", fontWeight = FontWeight.Bold, modifier = Modifier.semantics { heading() })
            Text(
                when {
                    profile.isMatchable -> "You are verified and available. You can appear in matching results."
                    !profile.verified -> "Waiting for verification. You will not appear in matching results until an admin verifies you."
                    else -> "You are verified but not available. Set availability to Available to be matched."
                },
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
    }
}

/** Availability choices; only a verified + available profile is matchable. */
@Composable
private fun AvailabilityCard(profile: DonorProfileMe, saving: Boolean, onAction: (BecomeDonorAction) -> Unit) {
    Card(Modifier.fillMaxWidth(), elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                "Availability",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.semantics { heading() },
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DonorAvailability.values().forEach { option ->
                    FilterChip(
                        selected = profile.availability == option,
                        enabled = !saving,
                        onClick = { onAction(BecomeDonorAction.SetAvailability(option)) },
                        label = { Text(option.label) },
                        modifier = Modifier.testTag("donor-availability-${option.name}"),
                    )
                }
            }
            Text(
                "Only a verified, available profile appears in matching results.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Blood type, location, radius, and notification preference for the donor profile. */
@Composable
private fun DonorDetailsCard(state: BecomeDonorUiState, onAction: (BecomeDonorAction) -> Unit) {
    val profile = state.draft
    var radius by rememberSaveable(profile.donorId) { mutableStateOf(profile.serviceRadiusKm.toString()) }
    var latitude by rememberSaveable(profile.donorId) { mutableStateOf(profile.latitude?.toString().orEmpty()) }
    var longitude by rememberSaveable(profile.donorId) { mutableStateOf(profile.longitude?.toString().orEmpty()) }
    Card(Modifier.fillMaxWidth(), elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                "Donor details",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.semantics { heading() },
            )
            OutlinedTextField(
                value = profile.displayName,
                onValueChange = { value -> onAction(BecomeDonorAction.UpdateDraft { it.copy(displayName = value) }) },
                modifier = Modifier.fillMaxWidth().testTag("donor-display-name"),
                label = { Text("Display name") },
                singleLine = true,
            )
            OutlinedTextField(
                value = profile.area,
                onValueChange = { value -> onAction(BecomeDonorAction.UpdateDraft { it.copy(area = value) }) },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Area") },
                singleLine = true,
            )
            BloodTypePicker(profile.bloodType) { option ->
                onAction(BecomeDonorAction.UpdateDraft { it.copy(bloodType = option) })
            }
            LocationFields(
                latitude = latitude,
                longitude = longitude,
                onLatitudeChange = { latitude = it },
                onLongitudeChange = { longitude = it },
                onApply = {
                    val lat = latitude.toDoubleOrNull()
                    val lng = longitude.toDoubleOrNull()
                    if (isValidCoordinate(lat, lng)) {
                        onAction(BecomeDonorAction.UpdateDraft { it.copy(latitude = lat, longitude = lng) })
                    }
                },
            )
            OutlinedTextField(
                value = radius,
                onValueChange = { value -> if (value.length <= 3 && value.all(Char::isDigit)) radius = value },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Service radius (km)") },
                singleLine = true,
            )
            NotificationToggle(profile.notificationsEnabled) { value ->
                onAction(BecomeDonorAction.UpdateDraft { it.copy(notificationsEnabled = value) })
            }
            SaveButton(state, radius, onAction) { value ->
                onAction(BecomeDonorAction.UpdateDraft { it.copy(serviceRadiusKm = value) })
            }
        }
    }
}

/** Save / become-a-donor action, applying the typed radius first. */
@Composable
private fun SaveButton(
    state: BecomeDonorUiState,
    radius: String,
    onAction: (BecomeDonorAction) -> Unit,
    onRadiusCommit: (Int) -> Unit,
) {
    Button(
        onClick = {
            radius.toIntOrNull()?.let(onRadiusCommit)
            onAction(BecomeDonorAction.Save)
        },
        enabled = !state.saving,
        modifier = Modifier.fillMaxWidth().testTag("donor-save"),
    ) {
        Text(
            when {
                state.saving -> "Saving\u2026"
                state.optedIn -> "Save changes"
                else -> "Become a donor"
            },
        )
    }
}

/** Blood-type chips, laid out four per row. */
@Composable
private fun BloodTypePicker(selected: BloodType?, onSelect: (BloodType) -> Unit) {
    Text("Blood type", fontWeight = FontWeight.SemiBold)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        BloodType.values().toList().chunked(4).forEach { rowOptions ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                rowOptions.forEach { option ->
                    FilterChip(
                        selected = selected == option,
                        onClick = { onSelect(option) },
                        label = { Text(option.label) },
                        modifier = Modifier.weight(1f).testTag("donor-blood-${option.name}"),
                    )
                }
            }
        }
    }
}

/** Manual latitude/longitude entry with an apply action. */
@Composable
private fun LocationFields(
    latitude: String,
    longitude: String,
    onLatitudeChange: (String) -> Unit,
    onLongitudeChange: (String) -> Unit,
    onApply: () -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        OutlinedTextField(
            value = latitude,
            onValueChange = onLatitudeChange,
            modifier = Modifier.weight(1f),
            label = { Text("Latitude") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        )
        OutlinedTextField(
            value = longitude,
            onValueChange = onLongitudeChange,
            modifier = Modifier.weight(1f),
            label = { Text("Longitude") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        )
    }
    OutlinedButton(onClick = onApply, modifier = Modifier.fillMaxWidth()) {
        Text("Use this approximate location")
    }
}

/** Notification preference for matching requests. */
@Composable
private fun NotificationToggle(enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Column(Modifier.weight(1f)) {
            Text("Notify me about matching requests", fontWeight = FontWeight.SemiBold)
            Text(
                "Turn off to stay a donor without push notifications.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Switch(
            checked = enabled,
            onCheckedChange = onChange,
            modifier = Modifier.testTag("donor-notifications"),
        )
    }
}

private const val MIN_LATITUDE = -90.0
private const val MAX_LATITUDE = 90.0
private const val MIN_LONGITUDE = -180.0
private const val MAX_LONGITUDE = 180.0

private fun isValidCoordinate(latitude: Double?, longitude: Double?): Boolean =
    latitude != null && longitude != null && isInRange(latitude, longitude)

private fun isInRange(latitude: Double, longitude: Double): Boolean =
    latitude in MIN_LATITUDE..MAX_LATITUDE && longitude in MIN_LONGITUDE..MAX_LONGITUDE
