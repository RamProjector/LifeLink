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
import androidx.compose.material.icons.filled.Assignment
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
import com.lifelink.app.domain.ActiveRequestSnapshot
import com.lifelink.app.core.auth.UserRole
import com.lifelink.app.feature.activeRequest.ActiveRequestScreen
import com.lifelink.app.feature.donor.DonorAction
import com.lifelink.app.feature.donor.DonorScreen
import com.lifelink.app.feature.donor.DonorUiState
import com.lifelink.app.feature.emergencyrequest.EmergencyRequestAction
import com.lifelink.app.feature.emergencyrequest.EmergencyRequestScreen
import com.lifelink.app.feature.emergencyrequest.EmergencyRequestUiState

private enum class ShellTab { HOME, REQUESTS, LEARN, PROFILE }

@Composable
fun LifeLinkShell(
    state: EmergencyRequestUiState,
    onAction: (EmergencyRequestAction) -> Unit,
    donorState: DonorUiState,
    onDonorAction: (DonorAction) -> Unit,
    role: UserRole
) {
    var showRequest by rememberSaveable { mutableStateOf(false) }
    var showActive by rememberSaveable { mutableStateOf(false) }
    var showDonor by rememberSaveable { mutableStateOf(false) }
    var tab by rememberSaveable { mutableStateOf(ShellTab.HOME) }

    if (showRequest) {
        EmergencyRequestScreen(state = state, onAction = onAction, onExit = { showRequest = false; tab = ShellTab.REQUESTS })
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
                NavigationBarItem(tab == ShellTab.REQUESTS, { tab = ShellTab.REQUESTS }, icon = { Icon(Icons.Default.Assignment, "Requests") }, label = { Text("Requests") })
                NavigationBarItem(tab == ShellTab.LEARN, { tab = ShellTab.LEARN }, icon = { Icon(Icons.Default.School, "Learn") }, label = { Text("Learn") })
                NavigationBarItem(tab == ShellTab.PROFILE, { tab = ShellTab.PROFILE }, icon = { Icon(Icons.Default.Person, "Profile") }, label = { Text("Profile") })
            }
        }
    ) { padding ->
        Surface(Modifier.fillMaxSize().padding(padding)) {
            when (tab) {
                ShellTab.HOME -> HomeContent(state, donorState, role, onCreate = { showRequest = true }, onActive = { showActive = true }, onDonor = { showDonor = true })
                ShellTab.REQUESTS -> RequestsContent(state, onCreate = { showRequest = true }, onOpen = { showRequest = true }, onActive = { showActive = true })
                ShellTab.LEARN -> LearnContent()
                ShellTab.PROFILE -> ProfileContent(role)
            }
        }
    }
}

@Composable private fun RequestsContent(
    state: EmergencyRequestUiState,
    onCreate: () -> Unit,
    onOpen: () -> Unit,
    onActive: () -> Unit
) {
    Column(Modifier.fillMaxSize().statusBarsPadding().padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Requests", style = androidx.compose.material3.MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text("Create a request, review matching donors, and follow responses in one place.", color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant)
        if (state.step == com.lifelink.app.domain.RequestStep.RESULTS && state.submission is com.lifelink.app.feature.emergencyrequest.SubmissionState.Matching) {
            Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = androidx.compose.material3.MaterialTheme.colorScheme.primaryContainer)) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Matching results ready", fontWeight = FontWeight.Bold)
                    Text("Review nearby donors and manage contact requests.")
                    Button(onClick = onOpen, Modifier.fillMaxWidth()) { Text("Open matching results") }
                }
            }
        } else if (state.activeRequest != null) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Active request", fontWeight = FontWeight.Bold)
                    Text(state.activeRequest.status.label)
                    TextButton(onClick = onActive) { Text("View live status") }
                }
            }
        } else {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("No active request", fontWeight = FontWeight.Bold)
                    Text("Start a request when you need help finding eligible donors nearby.")
                }
            }
        }
        if (state.requestHistory.isNotEmpty()) {
            Text("Request history", style = androidx.compose.material3.MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            state.requestHistory.forEach { request -> RequestHistoryCard(request) }
        }
        Button(onClick = onCreate, Modifier.fillMaxWidth()) { Text("Create emergency request") }
    }
}

@Composable
private fun RequestHistoryCard(request: ActiveRequestSnapshot) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(request.status.label, fontWeight = FontWeight.SemiBold)
                Text(if (request.isTerminal) "Past" else "Active", color = androidx.compose.material3.MaterialTheme.colorScheme.primary)
            }
            Text("Request ${request.requestId.take(12)}", color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant)
            Text("${request.matchesResponded} donor response${if (request.matchesResponded == 1) "" else "s"} · ${request.notificationsCreated} notified", style = androidx.compose.material3.MaterialTheme.typography.bodySmall, color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant)
            request.reason?.let { Text(it, style = androidx.compose.material3.MaterialTheme.typography.bodySmall) }
        }
    }
}

@Composable private fun HomeContent(
    state: EmergencyRequestUiState,
    donorState: DonorUiState,
    role: UserRole,
    onCreate: () -> Unit,
    onActive: () -> Unit,
    onDonor: () -> Unit
) {
    Column(
        Modifier.fillMaxSize().statusBarsPadding().padding(horizontal = 20.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(if (role == UserRole.DONOR) "Ready to help nearby" else "Find eligible donors nearby", style = androidx.compose.material3.MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text(if (role == UserRole.DONOR) "Manage your donor profile and availability." else "Create a request and connect with donors who choose to respond.", color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant)
        if (role == UserRole.DONOR) {
            Button(onClick = onDonor, Modifier.fillMaxWidth()) { Text("Open donor dashboard") }
        }
        if (role == UserRole.REQUESTER) {
            Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = androidx.compose.material3.MaterialTheme.colorScheme.primaryContainer)) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(Icons.Default.Favorite, "LifeLink support", tint = androidx.compose.material3.MaterialTheme.colorScheme.primary)
                    Text("Need blood urgently?", style = androidx.compose.material3.MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text("Create a request and notify only eligible donors in range.")
                    Button(onClick = onCreate, Modifier.fillMaxWidth()) { Text("Create emergency request") }
                }
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
        if (role == UserRole.REQUESTER) Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Want to help someone nearby?", fontWeight = FontWeight.Bold)
                Text("Choose the donor role from your profile to manage availability and respond to requests.", color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable private fun LearnContent() {
    Column(Modifier.fillMaxSize().statusBarsPadding().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Learn", style = androidx.compose.material3.MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text("LifeLink helps requesters connect with eligible, available donors nearby.", color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant)
        LearnCard("How matching works", "LifeLink checks blood-type eligibility, donor availability, service radius, approximate distance, travel time, and urgency. A match is not medical approval; confirm compatibility with a blood-bank professional.")
        LearnCard("Location privacy", "Use current location or choose a point on the map. Exact requester and donor coordinates are used for matching but are not shown to the other person. LifeLink does not track anyone in the background.")
        LearnCard("Respond safely", "Contact requests stay pending until a donor responds. Share contact details only after acceptance, confirm the meeting place through the app, and use a verified blood bank or hospital for screening.")
    }
}

@Composable private fun LearnCard(title: String, body: String) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, fontWeight = FontWeight.Bold)
            Text(body, color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable private fun ProfileContent(role: UserRole) {
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Profile", style = androidx.compose.material3.MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text(if (role == UserRole.DONOR) "Donor account" else "Requester account", color = androidx.compose.material3.MaterialTheme.colorScheme.primary)
        Text(if (role == UserRole.DONOR) "Your approximate location is used only for proximity matching." else "Your exact request location is used only for matching and is not shown to donors.")
        Text("Privacy and consent settings")
        Text("Use the role-specific dashboard to update your profile, availability, or request details.", color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
