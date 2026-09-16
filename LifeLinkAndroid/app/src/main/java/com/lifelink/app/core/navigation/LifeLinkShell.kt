package com.lifelink.app.core.navigation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddAlert
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.School
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.lifelink.app.domain.DonorAvailability
import com.lifelink.app.feature.activeRequest.ActiveRequestScreen
import com.lifelink.app.feature.donor.DonorAction
import com.lifelink.app.feature.donor.DonorScreen
import com.lifelink.app.feature.donor.DonorUiState
import com.lifelink.app.feature.emergencyrequest.EmergencyRequestAction
import com.lifelink.app.feature.emergencyrequest.EmergencyRequestScreen
import com.lifelink.app.feature.emergencyrequest.EmergencyRequestUiState

private enum class ShellTab { HOME, LEARN, PROFILE }

@Composable
fun LifeLinkShell(
    state: EmergencyRequestUiState,
    onAction: (EmergencyRequestAction) -> Unit,
    donorState: DonorUiState,
    onDonorAction: (DonorAction) -> Unit
) {
    var showRequest by rememberSaveable { mutableStateOf(false) }
    var showActive by rememberSaveable { mutableStateOf(false) }
    var showDonor by rememberSaveable { mutableStateOf(false) }
    var tab by rememberSaveable { mutableStateOf(ShellTab.HOME) }

    if (showRequest) {
        EmergencyRequestScreen(state = state, onAction = onAction)
        return
    }
    if (showActive && state.activeRequest != null) {
        ActiveRequestScreen(state = state, onAction = onAction, onBack = { showActive = false })
        return
    }
    if (showDonor) {
        DonorScreen(state = donorState, onAction = onDonorAction, onBack = { showDonor = false })
        return
    }

    Scaffold(
        bottomBar = {
            NavigationBar {
                NavigationBarItem(tab == ShellTab.HOME, { tab = ShellTab.HOME }, icon = { Icon(Icons.Default.AddAlert, "Home") }, label = { Text("Home") })
                NavigationBarItem(tab == ShellTab.LEARN, { tab = ShellTab.LEARN }, icon = { Icon(Icons.Default.School, "Learn") }, label = { Text("Learn") })
                NavigationBarItem(tab == ShellTab.PROFILE, { tab = ShellTab.PROFILE }, icon = { Icon(Icons.Default.Person, "Profile") }, label = { Text("Profile") })
            }
        }
    ) { padding ->
        Surface(Modifier.fillMaxSize().padding(padding)) {
            when (tab) {
                ShellTab.HOME -> HomeContent(state, donorState, onCreate = { showRequest = true }, onActive = { showActive = true }, onDonor = { showDonor = true })
                ShellTab.LEARN -> LearnContent()
                ShellTab.PROFILE -> ProfileContent()
            }
        }
    }
}

@Composable private fun HomeContent(
    state: EmergencyRequestUiState,
    donorState: DonorUiState,
    onCreate: () -> Unit,
    onActive: () -> Unit,
    onDonor: () -> Unit
) {
    Column(
        Modifier.fillMaxSize().statusBarsPadding().padding(horizontal = 20.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("Good morning, Mr. Reyes", style = androidx.compose.material3.MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text("Verified coordinator", color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant)
        Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = androidx.compose.material3.MaterialTheme.colorScheme.primaryContainer)) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(Icons.Default.Favorite, "LifeLink support", tint = androidx.compose.material3.MaterialTheme.colorScheme.primary)
                Text("Need blood urgently?", style = androidx.compose.material3.MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text("Create a verified request and notify only eligible donors in range.")
                Button(onClick = onCreate, Modifier.fillMaxWidth()) { Text("Create emergency request") }
            }
        }
        state.activeRequest?.let { active ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Active request", fontWeight = FontWeight.Bold)
                    Text(active.status.label)
                    TextButton(onClick = onActive) { Text("View live status") }
                }
            }
        }
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Want to help someone nearby?", fontWeight = FontWeight.Bold)
                Text("Switch to donor mode to manage availability and respond to requests.", color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = onDonor) { Text("Open donor mode") }
            }
        }
    }
}

@Composable private fun LearnContent() {
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Learn", style = androidx.compose.material3.MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text("LifeLink helps verified coordinators connect urgent blood requests with eligible, available donors.")
        Text("Never share patient-identifying information in request notes. Confirm details with the blood bank.")
    }
}

@Composable private fun ProfileContent() {
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Profile", style = androidx.compose.material3.MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text("Verified coordinator", color = androidx.compose.material3.MaterialTheme.colorScheme.primary)
        Text("Privacy and consent settings")
        Text("Authentication and identity verification are required before production release.", color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
