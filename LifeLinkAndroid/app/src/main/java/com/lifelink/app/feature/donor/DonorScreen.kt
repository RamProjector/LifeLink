package com.lifelink.app.feature.donor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.lifelink.app.domain.DonorAvailability
import com.lifelink.app.domain.DonorProfile
import com.lifelink.app.domain.DonorRequest
import com.lifelink.app.domain.DonorResponse

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DonorScreen(state: DonorUiState, onAction: (DonorAction) -> Unit, onBack: () -> Unit) {
    var profileExpanded by remember { mutableStateOf(state.profile.displayName.isBlank()) }
    Scaffold(topBar = { TopAppBar(title = { Text("Donor mode") }) }) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 20.dp)
        ) {
            item {
                Text("Help when it matters", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                Text("Your availability controls which verified requests you see.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            item { AvailabilityCard(state.profile, onAction) }
            item {
                Button(onClick = { profileExpanded = !profileExpanded }, modifier = Modifier.fillMaxWidth()) {
                    Text(if (profileExpanded) "Hide donor profile" else "Edit donor profile")
                }
            }
            if (profileExpanded) item { ProfileCard(state.profile, onAction) }
            item { Text("Requests near you", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold) }
            if (state.requests.isEmpty()) item { Text("No eligible requests right now.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            items(state.requests, key = { it.requestId }) { request -> RequestCard(request, onAction) }
        }
    }
}

@Composable private fun AvailabilityCard(profile: DonorProfile, onAction: (DonorAction) -> Unit) {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Availability", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(profile.availability.label, color = MaterialTheme.colorScheme.onSecondaryContainer)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DonorAvailability.values().forEach { option ->
                    FilterChip(selected = profile.availability == option, onClick = { onAction(DonorAction.SetAvailability(option)) }, label = { Text(option.label) })
                }
            }
        }
    }
}

@Composable private fun ProfileCard(profile: DonorProfile, onAction: (DonorAction) -> Unit) {
    var name by remember(profile.displayName) { mutableStateOf(profile.displayName) }
    var area by remember(profile.area) { mutableStateOf(profile.area) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), label = { Text("Display name") }, singleLine = true)
            OutlinedTextField(area, { area = it }, Modifier.fillMaxWidth(), label = { Text("Area") }, singleLine = true)
            Button(onClick = { onAction(DonorAction.UpdateProfile(profile.copy(displayName = name, area = area))) }, Modifier.fillMaxWidth()) { Text("Save profile") }
        }
    }
}

@Composable private fun RequestCard(request: DonorRequest, onAction: (DonorAction) -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("${request.bloodType} · ${request.units} unit${if (request.units == 1) "" else "s"}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = MaterialTheme.shapes.small) {
                    Text(request.urgency.replaceFirstChar { it.uppercase() }, modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp), color = MaterialTheme.colorScheme.onPrimaryContainer, style = MaterialTheme.typography.labelMedium)
                }
            }
            Text(request.facilityName, fontWeight = FontWeight.SemiBold)
            Text("${request.area} · ${request.distanceKm} km away", color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (request.response == null) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { onAction(DonorAction.Respond(request.requestId, DonorResponse.ACCEPTED)) }, Modifier.weight(1f)) { Text("Accept") }
                    Button(onClick = { onAction(DonorAction.Respond(request.requestId, DonorResponse.DECLINED)) }, Modifier.weight(1f)) { Text("Decline") }
                }
            } else Text("Response: ${request.response.label}", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
        }
    }
}
