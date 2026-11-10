package com.lifelink.app.feature.privacy

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Row
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.lifelink.app.domain.DiscoveredDonor

/**
 * Authorized detail view for a matched donor. It deliberately contains no exact
 * coordinates or private contact fields; those remain request-scoped server actions.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MatchedDonorProfileScreen(
    donor: DiscoveredDonor,
    onBack: () -> Unit,
    onRequestChat: (() -> Unit)? = null,
) {
    BackHandler(onBack = onBack)
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text("Donor profile") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Card(
                Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
            ) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(donor.displayName, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text("Matched donor", color = MaterialTheme.colorScheme.onPrimaryContainer)
                    Text(
                        "Eligible for this request based on the current authenticated match result.",
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
            }
            Text("Match details", style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    DetailRow("Blood type", donor.bloodType)
                    DetailRow("Approximate distance", "${"%.1f".format(donor.distanceKm)} km")
                    DetailRow("Estimated travel", "About ${donor.travelMinutes} minutes")
                    donor.explanation.forEach { explanation ->
                        Text("• $explanation", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            Card(
                Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
            ) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.LocationOn, contentDescription = null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
                        Text("Approximate location only", Modifier.padding(start = 8.dp), fontWeight = FontWeight.SemiBold)
                    }
                    Text(
                        "Exact donor coordinates, personal contact details, and map-pin identity are hidden. " +
                            "Use Request chat to start the authorized request-scoped conversation.",
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Shield, contentDescription = null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
                        Text("Privacy protected", Modifier.padding(start = 8.dp), color = MaterialTheme.colorScheme.onSecondaryContainer)
                    }
                }
            }
            onRequestChat?.let { requestChat ->
                Button(
                    onClick = requestChat,
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(vertical = 14.dp),
                ) {
                    Icon(Icons.Default.Chat, contentDescription = null)
                    Text("Request chat", Modifier.padding(start = 8.dp))
                }
            }
            OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
                Text("Back to donors")
            }
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyLarge)
    }
}
