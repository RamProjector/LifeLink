package com.lifelink.app.core.navigation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddAlert
import androidx.compose.material.icons.filled.Assignment
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.lifelink.app.domain.DonorAvailability
import com.lifelink.app.domain.RequestHistoryItem
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
    role: UserRole,
    accountEmail: String,
    accountUserId: String,
    accountDisplayName: String,
    profileSaving: Boolean,
    profileMessage: String?,
    onSaveProfile: (String) -> Unit,
    onSignOut: () -> Unit,
    notificationRequestId: String? = null
) {
    var showRequest by rememberSaveable { mutableStateOf(false) }
    var showActive by rememberSaveable { mutableStateOf(false) }
    var showDonor by rememberSaveable { mutableStateOf(false) }
    var tab by rememberSaveable { mutableStateOf(ShellTab.HOME) }
    LaunchedEffect(notificationRequestId) {
        notificationRequestId?.takeIf { it.isNotBlank() }?.let {
            showActive = true
            onAction(EmergencyRequestAction.OpenRequest(it))
        }
    }

    if (showRequest) {
        EmergencyRequestScreen(state = state, onAction = onAction, onExit = { showRequest = false; tab = ShellTab.HOME })
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
        containerColor = androidx.compose.material3.MaterialTheme.colorScheme.background,
        bottomBar = {
            NavigationBar(containerColor = androidx.compose.material3.MaterialTheme.colorScheme.surface, tonalElevation = 3.dp) {
                NavigationBarItem(tab == ShellTab.HOME, { tab = ShellTab.HOME }, icon = { Icon(Icons.Default.Home, "Home") }, label = { Text("Home") })
                NavigationBarItem(tab == ShellTab.REQUESTS, { tab = ShellTab.REQUESTS }, icon = { Icon(Icons.Default.Assignment, "Requests") }, label = { Text("Requests") })
                NavigationBarItem(tab == ShellTab.LEARN, { tab = ShellTab.LEARN }, icon = { Icon(Icons.Default.Info, "Info") }, label = { Text("Info") })
                NavigationBarItem(tab == ShellTab.PROFILE, { tab = ShellTab.PROFILE }, icon = { Icon(Icons.Default.Person, "Profile") }, label = { Text("Profile") })
            }
        }
    ) { padding ->
        Surface(Modifier.fillMaxSize().padding(padding)) {
            when (tab) {
                ShellTab.HOME -> HomeContent(state, donorState, role, onCreate = { showRequest = true }, onActive = { showActive = true }, onDonor = { showDonor = true })
                ShellTab.REQUESTS -> RequestsContent(state, onAction = onAction, onCreate = { showRequest = true }, onOpen = { showRequest = true }, onActive = { showActive = true })
                ShellTab.LEARN -> LearnContent()
                ShellTab.PROFILE -> ProfileContent(role, accountEmail, accountUserId, accountDisplayName, profileSaving, profileMessage, onSaveProfile, onSignOut)
            }
        }
    }
}

@Composable private fun RequestsContent(
    state: EmergencyRequestUiState,
    onAction: (EmergencyRequestAction) -> Unit,
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
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text("Request history", style = androidx.compose.material3.MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                TextButton(onClick = { onAction(EmergencyRequestAction.RefreshHistory) }, enabled = !state.historyRefreshing) { Text(if (state.historyRefreshing) "Refreshing…" else "Refresh") }
            }
            state.requestHistory.forEach { request -> RequestHistoryCard(request) }
        }
        Button(onClick = onCreate, Modifier.fillMaxWidth()) { Text("Create emergency request") }
    }
}

@Composable
private fun RequestHistoryCard(request: RequestHistoryItem) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(request.status.label, fontWeight = FontWeight.SemiBold)
                Text(if (request.status in setOf(com.lifelink.app.domain.ActiveRequestStatus.FULFILLED, com.lifelink.app.domain.ActiveRequestStatus.EXPIRED, com.lifelink.app.domain.ActiveRequestStatus.CANCELLED)) "Past" else "Active", color = androidx.compose.material3.MaterialTheme.colorScheme.primary)
            }
            Text("Request ${request.requestId.take(12)}", color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant)
            Text("${request.bloodType ?: "Request"} · ${request.units?.let { "$it unit${if (it == 1) "" else "s"}" } ?: "Details unavailable"}", style = androidx.compose.material3.MaterialTheme.typography.bodySmall)
            Text("${request.matchesResponded} donor response${if (request.matchesResponded == 1) "" else "s"} · ${request.notificationsCreated} notified", style = androidx.compose.material3.MaterialTheme.typography.bodySmall, color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant)
            request.facilityName?.let { Text("$it${request.area?.let { area -> " · $area" } ?: ""}", style = androidx.compose.material3.MaterialTheme.typography.bodySmall) }
            if (request.contactStatuses.isNotEmpty()) Text("Contacts: ${request.contactStatuses.joinToString()}", style = androidx.compose.material3.MaterialTheme.typography.bodySmall, color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant)
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
    Column(Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("LifeLink info", style = androidx.compose.material3.MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text("A direct requester-to-donor discovery and contact aid for urgent blood needs.", color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant)
        LearnCard("How LifeLink works", "Requesters share the blood type, units, urgency, and an approximate location. LifeLink finds eligible, available donors nearby. Donors choose whether to accept or decline a contact request.")
        LearnCard("How matching works", "Matching considers blood-type compatibility, donor availability, service radius, approximate distance, travel estimate, and urgency. A match is not medical approval; confirm compatibility with a blood-bank professional.")
        LearnCard("Location privacy", "Current or manually selected location is used for matching. Exact requester and donor coordinates are not shown to the other person. LifeLink does not track anyone in the background, and requester maps never show individual donor pins.")
        LearnCard("Contact and consent", "Contact requests remain pending until a donor responds. Contact details are disclosed only after donor acceptance and server authorization. You can mark contact shared, meeting arranged, fulfilled, or cancelled.")
        LearnCard("Respond safely", "Use a verified blood bank or hospital for screening and collection. Do not share patient names, diagnoses, medical records, passwords, or payment information in LifeLink notes or messages. Report or block unsafe interactions.")
        LearnCard("What LifeLink is not", "LifeLink is not a hospital, blood bank, emergency dispatcher, medical screening service, or guarantee that a donor can provide blood. For immediate danger, contact local emergency services and a qualified medical facility.")
        Text("LifeLink Cloud · MVP", style = androidx.compose.material3.MaterialTheme.typography.labelMedium, color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant)
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

@Composable private fun ProfileContent(
    role: UserRole,
    accountEmail: String,
    accountUserId: String,
    accountDisplayName: String,
    profileSaving: Boolean,
    profileMessage: String?,
    onSaveProfile: (String) -> Unit,
    onSignOut: () -> Unit
) {
    var displayName by rememberSaveable(accountDisplayName) { mutableStateOf(accountDisplayName) }
    Column(Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text(
            if (role == UserRole.REQUESTER) "Requester profile" else "Profile",
            style = androidx.compose.material3.MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold
        )
        Text(
            if (role == UserRole.REQUESTER) "Update the name shown on your requester account."
            else "Account and privacy controls",
            color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant
        )
        Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = androidx.compose.material3.MaterialTheme.colorScheme.primaryContainer)) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(accountEmail.ifBlank { "Authenticated LifeLink account" }, fontWeight = FontWeight.Bold)
                Text(if (role == UserRole.DONOR) "Donor account" else "Requester account", color = androidx.compose.material3.MaterialTheme.colorScheme.primary)
                if (accountUserId.isNotBlank()) Text("Account ID · ${accountUserId.take(8)}…", style = androidx.compose.material3.MaterialTheme.typography.bodySmall, color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(if (role == UserRole.REQUESTER) "Requester details" else "Profile details", fontWeight = FontWeight.Bold)
                OutlinedTextField(
                    value = displayName,
                    onValueChange = { if (it.length <= 160) displayName = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Display name") },
                    supportingText = {
                        Text(
                            if (role == UserRole.REQUESTER) "This name identifies you when coordinating a blood request."
                            else "Keep medical details out of this field."
                        )
                    },
                    singleLine = true
                )
                Button(
                    onClick = { onSaveProfile(displayName) },
                    enabled = !profileSaving && displayName.trim().length >= 2,
                    modifier = Modifier.fillMaxWidth()
                ) { Text(if (profileSaving) "Saving…" else "Save profile") }
                profileMessage?.let { Text(it, color = androidx.compose.material3.MaterialTheme.colorScheme.primary) }
            }
        }
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Location privacy", fontWeight = FontWeight.Bold)
                Text(
                    if (role == UserRole.DONOR) "Your approximate donor location is used for distance matching. Requesters see distance and travel estimates, not your coordinates."
                    else "Your request location is used for matching. Donors do not see your exact coordinates.",
                    color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Safety and consent", fontWeight = FontWeight.Bold)
                Text("Contact details are disclosed only after a donor accepts. LifeLink is a discovery and contact aid, not a replacement for blood-bank screening or medical care.", color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Text(
            if (role == UserRole.REQUESTER) "Your requester profile is used with your emergency requests. Update it here whenever your display name changes."
            else "Use the role-specific dashboard to update availability or location.",
            color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant
        )
        androidx.compose.material3.OutlinedButton(onClick = onSignOut, modifier = Modifier.fillMaxWidth()) { Text("Sign out") }
    }
}
