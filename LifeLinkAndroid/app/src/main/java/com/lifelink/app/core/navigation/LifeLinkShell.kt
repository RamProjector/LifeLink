package com.lifelink.app.core.navigation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Assignment
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
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
import com.lifelink.app.core.ui.theme.ThemeMode

private enum class ShellTab { HOME, REQUESTS, INFO, SETTINGS }
private enum class SettingsSection { PROFILE, LEGAL, SAFETY, THEME, SECURITY }

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
    themeMode: ThemeMode,
    onThemeModeChange: (ThemeMode) -> Unit,
    onRequestPasswordReset: () -> Unit,
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
                NavigationBarItem(tab == ShellTab.INFO, { tab = ShellTab.INFO }, icon = { Icon(Icons.Default.Info, "Info") }, label = { Text("Info") })
                NavigationBarItem(tab == ShellTab.SETTINGS, { tab = ShellTab.SETTINGS }, icon = { Icon(Icons.Default.Settings, "Settings") }, label = { Text("Settings") })
            }
        }
    ) { padding ->
        Surface(Modifier.fillMaxSize().padding(padding)) {
            when (tab) {
                ShellTab.HOME -> HomeContent(state, donorState, role, onCreate = { showRequest = true }, onActive = { showActive = true }, onDonor = { showDonor = true })
                ShellTab.REQUESTS -> RequestsContent(state, onAction = onAction, onCreate = { showRequest = true }, onOpen = { showRequest = true }, onActive = { showActive = true })
                ShellTab.INFO -> LearnContent()
                ShellTab.SETTINGS -> SettingsContent(role, accountEmail, accountUserId, accountDisplayName, profileSaving, profileMessage, onSaveProfile, themeMode, onThemeModeChange, onRequestPasswordReset, onSignOut)
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
            Card(Modifier.fillMaxWidth(), elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Active request", fontWeight = FontWeight.Bold)
                    Text(state.activeRequest.status.label)
                    TextButton(onClick = onActive) { Text("View live status") }
                }
            }
        } else {
            Card(Modifier.fillMaxWidth(), elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)) {
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
    Card(Modifier.fillMaxWidth(), elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)) {
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
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Surface(
                modifier = Modifier.size(44.dp),
                shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp),
                color = androidx.compose.material3.MaterialTheme.colorScheme.primaryContainer
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.Favorite, contentDescription = null, tint = androidx.compose.material3.MaterialTheme.colorScheme.primary)
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Text("LifeLink", style = androidx.compose.material3.MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text(if (role == UserRole.DONOR) "Donor workspace" else "Requester workspace", style = androidx.compose.material3.MaterialTheme.typography.bodySmall, color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Text(if (role == UserRole.DONOR) "Ready to help nearby" else "Find eligible donors nearby", style = androidx.compose.material3.MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text(if (role == UserRole.DONOR) "Manage your donor profile and availability." else "Create a request and connect with donors who choose to respond.", color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant)
        if (role == UserRole.DONOR) {
            Button(onClick = onDonor, Modifier.fillMaxWidth()) { Text("Open donor dashboard") }
        }
        if (role == UserRole.REQUESTER) {
            Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = androidx.compose.material3.MaterialTheme.colorScheme.primaryContainer), elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(Icons.Default.Favorite, "LifeLink support", tint = androidx.compose.material3.MaterialTheme.colorScheme.primary)
                    Text("Need blood urgently?", style = androidx.compose.material3.MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text("Create a request and notify only eligible donors in range.")
                    Button(onClick = onCreate, Modifier.fillMaxWidth()) { Text("Create emergency request") }
                }
            }
        }
        state.activeRequest?.let { active ->
            Card(Modifier.fillMaxWidth(), elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Active request", fontWeight = FontWeight.Bold)
                    Text(active.status.label)
                    TextButton(onClick = onActive) { Text("View live status") }
                }
            }
        }
        if (role == UserRole.REQUESTER) Card(Modifier.fillMaxWidth(), elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)) {
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
    Card(Modifier.fillMaxWidth(), elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, fontWeight = FontWeight.Bold)
            Text(body, color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable private fun SettingsContent(
    role: UserRole,
    accountEmail: String,
    accountUserId: String,
    accountDisplayName: String,
    profileSaving: Boolean,
    profileMessage: String?,
    onSaveProfile: (String) -> Unit,
    themeMode: ThemeMode,
    onThemeModeChange: (ThemeMode) -> Unit,
    onRequestPasswordReset: () -> Unit,
    onSignOut: () -> Unit
) {
    var section by rememberSaveable { mutableStateOf(SettingsSection.PROFILE) }
    val sections = listOf(SettingsSection.PROFILE, SettingsSection.LEGAL, SettingsSection.SAFETY, SettingsSection.THEME, SettingsSection.SECURITY)
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 20.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Settings", style = androidx.compose.material3.MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text("Manage your account, privacy choices, and LifeLink information.", color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant)
        }
        TabRow(selectedTabIndex = sections.indexOf(section)) {
            sections.forEach { item ->
                Tab(
                    selected = section == item,
                    onClick = { section = item },
                    text = { Text(item.name.lowercase().replaceFirstChar { it.uppercase() }) }
                )
            }
        }
        when (section) {
            SettingsSection.PROFILE -> ProfileContent(role, accountEmail, accountUserId, accountDisplayName, profileSaving, profileMessage, onSaveProfile, onSignOut)
            SettingsSection.LEGAL -> LegalContent()
            SettingsSection.SAFETY -> SafetyContent()
            SettingsSection.THEME -> ThemeContent(themeMode, onThemeModeChange)
            SettingsSection.SECURITY -> SecurityContent(onRequestPasswordReset)
        }
    }
}

@Composable private fun ThemeContent(themeMode: ThemeMode, onThemeModeChange: (ThemeMode) -> Unit) {
    Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("Theme", style = androidx.compose.material3.MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text("Choose how LifeLink should look on this device.", color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant)
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp)) {
                ThemeMode.values().forEach { mode ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = themeMode == mode, onClick = { onThemeModeChange(mode) })
                        Text(
                            when (mode) {
                                ThemeMode.SYSTEM -> "Use device setting"
                                ThemeMode.LIGHT -> "Light"
                                ThemeMode.DARK -> "Dark"
                            },
                            modifier = Modifier.padding(start = 4.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable private fun SecurityContent(onRequestPasswordReset: () -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Security", style = androidx.compose.material3.MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        LearnCard("Account protection", "Keep your email account secure and never share your LifeLink password, reset link, or session details with another person.")
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Reset password", fontWeight = FontWeight.Bold)
                Text("Send a password-reset link to the email address on this account.", color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant)
                androidx.compose.material3.OutlinedButton(onClick = onRequestPasswordReset, modifier = Modifier.fillMaxWidth()) { Text("Send reset link") }
            }
        }
        LearnCard("Session safety", "LifeLink refreshes authenticated sessions when needed. Signing out clears the local session on this device. If you suspect unauthorized access, reset your password and sign out.")
    }
}

@Composable private fun LegalContent() {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Legal information", style = androidx.compose.material3.MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text("Please read how LifeLink is intended to be used before creating or responding to a request.", color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant)
        LearnCard("Service scope", "LifeLink is a direct requester-to-donor discovery and contact aid. It is not a hospital, blood bank, emergency dispatcher, medical screening service, or guarantee that a donor can provide blood.")
        LearnCard("Privacy", "LifeLink uses approximate location for matching and does not show exact coordinates between users. Donor contact details are disclosed only after the donor accepts and the requester explicitly chooses to continue.")
        LearnCard("User responsibility", "Use a qualified hospital or blood bank for screening, collection, and urgent medical care. Do not use LifeLink to share patient records, passwords, payment details, or other sensitive information.")
        LearnCard("Contact and reports", "Interactions are user-controlled. You can cancel a request, report unsafe behavior, or block a contact. Safety reports may be recorded for abuse prevention and service auditing.")
        Text("LifeLink Cloud · MVP", style = androidx.compose.material3.MaterialTheme.typography.labelMedium, color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable private fun SafetyContent() {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Safety and privacy", style = androidx.compose.material3.MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        LearnCard("Location privacy", "Your exact coordinates are never shown to the other person. Matching uses an approximate area, distance, and travel estimate instead of a public map of donor locations.")
        LearnCard("Consent before contact", "A donor must accept before contact can proceed. When contact details become available, LifeLink asks for your confirmation before opening your email app and recording contact sharing.")
        LearnCard("Meet safely", "Use a verified hospital or blood bank, tell someone you trust where you are going, and avoid exchanging money or sensitive medical information through LifeLink.")
        LearnCard("If something feels unsafe", "Stop the interaction, use Report or Block in the request flow, and contact local emergency services or a qualified medical facility when immediate danger is involved.")
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
    Column(Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
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
        Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = androidx.compose.material3.MaterialTheme.colorScheme.primaryContainer), elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)) {
            Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Surface(modifier = Modifier.size(48.dp), shape = androidx.compose.foundation.shape.CircleShape, color = androidx.compose.material3.MaterialTheme.colorScheme.primary) {
                    Box(contentAlignment = Alignment.Center) { Icon(Icons.Default.Person, contentDescription = null, tint = androidx.compose.material3.MaterialTheme.colorScheme.onPrimary) }
                }
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(accountEmail.ifBlank { "Authenticated LifeLink account" }, fontWeight = FontWeight.Bold)
                    Text(if (role == UserRole.DONOR) "Donor account" else "Requester account", color = androidx.compose.material3.MaterialTheme.colorScheme.primary)
                    if (accountUserId.isNotBlank()) Text("Account ID · ${accountUserId.take(8)}…", style = androidx.compose.material3.MaterialTheme.typography.bodySmall, color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant)
                }
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
        Card(Modifier.fillMaxWidth(), elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Location privacy", fontWeight = FontWeight.Bold)
                Text(
                    if (role == UserRole.DONOR) "Your approximate donor location is used for distance matching. Requesters see distance and travel estimates, not your coordinates."
                    else "Your request location is used for matching. Donors do not see your exact coordinates.",
                    color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Card(Modifier.fillMaxWidth(), elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)) {
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
