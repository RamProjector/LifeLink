package com.lifelink.app.core.navigation

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Assignment
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.NotificationsNone
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
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
import com.lifelink.app.domain.UpdateItem
import com.lifelink.app.feature.updates.UpdatesScreen

private enum class ShellTab { HOME, REQUESTS, UPDATES, PROFILE }
private enum class SettingsSection { PROFILE, LEGAL, SAFETY, ABOUT, THEME, SECURITY }

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
    onRequestPasswordReset: ((String) -> Unit) -> Unit,
    onSignOut: () -> Unit,
    updates: List<UpdateItem>,
    onUpdateRead: (String) -> Unit,
    onMarkAllUpdatesRead: () -> Unit,
    notificationOpenUpdates: Boolean = false,
    notificationRequestId: String? = null
) {
    val context = LocalContext.current
    val welcomePrefs = context.getSharedPreferences("lifelink_welcome", 0)
    var showRequest by rememberSaveable { mutableStateOf(false) }
    var showActive by rememberSaveable { mutableStateOf(false) }
    var showDonor by rememberSaveable { mutableStateOf(false) }
    var showStart by rememberSaveable(accountUserId) { mutableStateOf(accountUserId.isNotBlank() && !welcomePrefs.getBoolean("seen_$accountUserId", false)) }
    var tab by rememberSaveable { mutableStateOf(ShellTab.HOME) }
    val markWelcomeSeen = {
        if (accountUserId.isNotBlank()) welcomePrefs.edit().putBoolean("seen_$accountUserId", true).apply()
        showStart = false
    }
    LaunchedEffect(notificationRequestId) {
        notificationRequestId?.takeIf { it.isNotBlank() }?.let {
            showStart = false
            showActive = true
            onAction(EmergencyRequestAction.OpenRequest(it))
        }
    }
    LaunchedEffect(notificationOpenUpdates) {
        if (notificationOpenUpdates) {
            showStart = false
            tab = ShellTab.UPDATES
        }
    }

    if (showStart) {
        StartContent(
            role = role,
            onGetStarted = markWelcomeSeen,
            onCreateRequest = { markWelcomeSeen(); showRequest = true },
            onOpenDonor = { markWelcomeSeen(); showDonor = true }
        )
        return
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
    val unreadUpdates = updates.count { !it.isRead }

    Scaffold(
        containerColor = androidx.compose.material3.MaterialTheme.colorScheme.background,
        bottomBar = {
            NavigationBar(containerColor = androidx.compose.material3.MaterialTheme.colorScheme.surface, tonalElevation = 3.dp) {
                NavigationBarItem(tab == ShellTab.HOME, { tab = ShellTab.HOME }, icon = { Icon(Icons.Default.Home, "Home") }, label = { Text("Home") })
                NavigationBarItem(tab == ShellTab.REQUESTS, { tab = ShellTab.REQUESTS }, icon = { Icon(Icons.AutoMirrored.Filled.Assignment, "Requests") }, label = { Text("Requests") })
                NavigationBarItem(
                    tab == ShellTab.UPDATES,
                    { tab = ShellTab.UPDATES },
                    icon = {
                        BadgedBox(badge = { if (unreadUpdates > 0 && tab != ShellTab.UPDATES) Badge() }) {
                            Icon(Icons.Default.NotificationsNone, if (unreadUpdates > 0) "Updates, $unreadUpdates unread" else "Updates")
                        }
                    },
                    label = { Text("Updates") }
                )
                NavigationBarItem(tab == ShellTab.PROFILE, { tab = ShellTab.PROFILE }, icon = { Icon(Icons.Default.Person, "Profile") }, label = { Text("Profile") })
            }
        }
    ) { padding ->
        Surface(Modifier.fillMaxSize().padding(padding)) {
            when (tab) {
                ShellTab.HOME -> HomeContent(state, donorState, role, onCreate = { tab = ShellTab.REQUESTS }, onActive = { showActive = true }, onDonor = { showDonor = true })
                ShellTab.REQUESTS -> RequestsContent(state, onAction = onAction, onCreate = { showRequest = true }, onOpen = { showRequest = true }, onActive = { showActive = true })
                ShellTab.UPDATES -> UpdatesScreen(
                    updates = updates,
                    onOpen = { update ->
                        onUpdateRead(update.id)
                        update.requestId?.let { onAction(EmergencyRequestAction.OpenRequest(it)); showActive = true }
                    },
                    onMarkAllRead = onMarkAllUpdatesRead
                )
                ShellTab.PROFILE -> SettingsContent(role, accountEmail, accountUserId, accountDisplayName, profileSaving, profileMessage, onSaveProfile, themeMode, onThemeModeChange, onRequestPasswordReset, { showStart = false; showDonor = true }, onSignOut)
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
        Text("Start a request when you need help finding potential donors nearby.")
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
        Text(if (role == UserRole.DONOR) "Ready to help nearby?" else "Find help when it matters", style = androidx.compose.material3.MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text(if (role == UserRole.DONOR) "Keep your availability current so requests can reach you." else "LifeLink helps you reach potential donors while keeping exact locations private.", color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant)
        if (role == UserRole.DONOR) {
            DonorDashboardSummary(profile = donorState.profile, requestCount = donorState.requests.size, onOpen = onDonor)
        }
        if (role == UserRole.REQUESTER) {
            Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = androidx.compose.material3.MaterialTheme.colorScheme.primaryContainer), elevation = CardDefaults.cardElevation(defaultElevation = 3.dp)) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Need blood? Start here.", style = androidx.compose.material3.MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text("Tell us the blood type, urgency, and approximate area. We will show potential matches before you contact anyone.")
                    Button(onClick = onCreate, Modifier.fillMaxWidth()) { Text("Open Requests") }
                }
            }
        }
        state.activeRequest?.let { active ->
            Card(Modifier.fillMaxWidth(), elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Your active request", fontWeight = FontWeight.Bold)
                    Text(active.status.label)
                    TextButton(onClick = onActive) { Text("View live status") }
                }
            }
        }
        if (role == UserRole.REQUESTER) Card(Modifier.fillMaxWidth(), elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("What happens next?", fontWeight = FontWeight.Bold)
                Text("Open Requests to create or follow a request. Donor contact details are shared only after a donor accepts.", color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun DonorDashboardSummary(
    profile: com.lifelink.app.domain.DonorProfile,
    requestCount: Int,
    onOpen: () -> Unit
) {
    val statusColor = when (profile.availability) {
        DonorAvailability.AVAILABLE -> androidx.compose.material3.MaterialTheme.colorScheme.primary
        DonorAvailability.PAUSED -> androidx.compose.material3.MaterialTheme.colorScheme.tertiary
        DonorAvailability.OFFLINE -> androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant
    }
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = androidx.compose.material3.MaterialTheme.colorScheme.secondaryContainer),
        elevation = CardDefaults.cardElevation(defaultElevation = 3.dp)
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("Donor dashboard", style = androidx.compose.material3.MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text(profile.availability.label, color = statusColor, fontWeight = FontWeight.Bold)
            }
            if (profile.isSetupComplete) {
                Text("$requestCount matching request${if (requestCount == 1) "" else "s"} in your inbox")
                Text("Your approximate location and profile are ready for matching.", color = androidx.compose.material3.MaterialTheme.colorScheme.onSecondaryContainer)
            } else {
                Text("Complete your donor profile before choosing availability or receiving matching requests.", color = androidx.compose.material3.MaterialTheme.colorScheme.onSecondaryContainer)
            }
            Button(onClick = onOpen, Modifier.fillMaxWidth()) {
                Text(if (profile.isSetupComplete) "Manage donor dashboard" else "Complete donor profile")
            }
        }
    }
}

@Composable
private fun StartContent(
    role: UserRole,
    onGetStarted: () -> Unit,
    onCreateRequest: () -> Unit,
    onOpenDonor: () -> Unit
) {
    Surface(color = androidx.compose.material3.MaterialTheme.colorScheme.background) {
        Column(
            Modifier.fillMaxSize().statusBarsPadding().padding(horizontal = 28.dp, vertical = 32.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
                Text("LifeLink", style = androidx.compose.material3.MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold)
                Text(
                    if (role == UserRole.DONOR) "A calm, private way to manage when you can help."
                    else "A clear way to find eligible blood donors nearby.",
                    style = androidx.compose.material3.MaterialTheme.typography.headlineSmall
                )
                Text(
                    "Use approximate locations, consent-based contact, and clear request status. LifeLink supports coordination; hospitals and blood banks remain responsible for screening and care.",
                    color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
                    style = androidx.compose.material3.MaterialTheme.typography.bodyLarge
                )
            }
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    onClick = if (role == UserRole.DONOR) onOpenDonor else onCreateRequest,
                    modifier = Modifier.fillMaxWidth()
                ) { Text(if (role == UserRole.DONOR) "Open donor workspace" else "Create a request") }
                androidx.compose.material3.OutlinedButton(onClick = onGetStarted, modifier = Modifier.fillMaxWidth()) {
                    Text("Go to Home")
                }
                Text(
                    if (role == UserRole.DONOR) "You control your availability and profile visibility." else "You choose when to contact a donor after they respond.",
                    modifier = Modifier.fillMaxWidth(),
                    color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
                    style = androidx.compose.material3.MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}

@Composable private fun LearnContent() {
    var selectedTopic by rememberSaveable { mutableStateOf<String?>(null) }
    val topics = listOf(
        "How LifeLink works" to null,
        "How matching works" to "Matching considers blood-type compatibility, donor availability, service radius, approximate distance, travel estimate, and urgency. A match is not medical approval; confirm compatibility with a blood-bank professional.",
        "Location privacy" to "Current or manually selected location is used for matching. Exact requester and donor coordinates are not shown to the other person. LifeLink does not track anyone in the background, and requester maps never show individual donor pins.",
        "Contact and consent" to "Contact requests remain pending until a donor responds. Contact details are disclosed only after donor acceptance and server authorization.",
        "Respond safely" to "Use a verified blood bank or hospital for screening and collection. Do not share patient names, diagnoses, medical records, passwords, or payment information in LifeLink notes or messages.",
        "What LifeLink is not" to "LifeLink is not a hospital, blood bank, emergency dispatcher, medical screening service, or guarantee that a donor can provide blood. For immediate danger, contact local emergency services."
    )
    Column(Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("LifeLink info", style = androidx.compose.material3.MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text("A direct requester-to-donor discovery and contact aid for urgent blood needs.", color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant)
        topics.forEach { (title, body) -> LearnRow(title, body) { selectedTopic = title } }
        Text("LifeLink Cloud · MVP", style = androidx.compose.material3.MaterialTheme.typography.labelMedium, color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant)
    }
    val selectedBody = topics.firstOrNull { it.first == selectedTopic }?.second
    if (selectedTopic != null && selectedBody != null) {
        AlertDialog(
            onDismissRequest = { selectedTopic = null },
            title = { Text(selectedTopic!!) },
            text = { Text(selectedBody) },
            confirmButton = { TextButton(onClick = { selectedTopic = null }) { Text("Done") } }
        )
    }
}

@Composable private fun LearnRow(title: String, body: String?, onHelp: () -> Unit) {
    Card(Modifier.fillMaxWidth(), elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)) {
        Row(Modifier.padding(start = 16.dp, end = 8.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(title, modifier = Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
            if (body != null) {
                IconButton(onClick = onHelp) { Icon(Icons.AutoMirrored.Filled.HelpOutline, contentDescription = "More about $title") }
            }
        }
    }
}

@Composable private fun LearnCard(title: String, body: String) {
    Card(Modifier.fillMaxWidth(), elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, fontWeight = FontWeight.SemiBold)
            Text(body, color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable private fun DetailRow(title: String, helpDescription: String, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth(), elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)) {
        Row(Modifier.padding(start = 16.dp, end = 8.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(title, modifier = Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
            IconButton(onClick = onClick) { Icon(Icons.AutoMirrored.Filled.HelpOutline, contentDescription = helpDescription) }
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
    onRequestPasswordReset: ((String) -> Unit) -> Unit,
    onOpenDonor: () -> Unit,
    onSignOut: () -> Unit
) {
    var section by rememberSaveable { mutableStateOf(SettingsSection.PROFILE) }
    val sections = listOf(SettingsSection.PROFILE, SettingsSection.LEGAL, SettingsSection.SAFETY, SettingsSection.ABOUT, SettingsSection.THEME, SettingsSection.SECURITY)
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 20.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Profile", style = androidx.compose.material3.MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text("Account, privacy, and LifeLink information.", color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant)
        }
        ScrollableTabRow(selectedTabIndex = sections.indexOf(section), edgePadding = 12.dp) {
            sections.forEach { item ->
                Tab(
                    selected = section == item,
                    onClick = { section = item },
                    text = { Text(item.name.lowercase().replaceFirstChar { it.uppercase() }) }
                )
            }
        }
        when (section) {
            SettingsSection.PROFILE -> ProfileContent(role, accountEmail, accountUserId, accountDisplayName, profileSaving, profileMessage, onSaveProfile, onOpenDonor, onSignOut)
            SettingsSection.LEGAL -> LegalContent()
            SettingsSection.SAFETY -> SafetyContent()
            SettingsSection.ABOUT -> LearnContent()
            SettingsSection.THEME -> ThemeContent(themeMode, onThemeModeChange)
            SettingsSection.SECURITY -> SecurityContent(onRequestPasswordReset)
        }
    }
}

@Composable private fun ThemeContent(themeMode: ThemeMode, onThemeModeChange: (ThemeMode) -> Unit) {
    Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Appearance", style = androidx.compose.material3.MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        ThemeMode.values().forEach { mode ->
            Card(
                Modifier.fillMaxWidth().clickable { onThemeModeChange(mode) },
                colors = CardDefaults.cardColors(containerColor = if (themeMode == mode) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface)
            ) {
                Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = themeMode == mode, onClick = { onThemeModeChange(mode) })
                    Text(
                        when (mode) {
                            ThemeMode.SYSTEM -> "Use device setting"
                            ThemeMode.LIGHT -> "Light"
                            ThemeMode.DARK -> "Dark"
                        },
                        modifier = Modifier.padding(start = 4.dp),
                        fontWeight = if (themeMode == mode) FontWeight.SemiBold else FontWeight.Normal
                    )
                }
            }
        }
    }
}

@Composable private fun SecurityContent(onRequestPasswordReset: ((String) -> Unit) -> Unit) {
    var resetMessage by rememberSaveable { mutableStateOf<String?>(null) }
    var showSessionHelp by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Security", style = androidx.compose.material3.MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Reset password", fontWeight = FontWeight.Bold)
                androidx.compose.material3.OutlinedButton(
                    onClick = { onRequestPasswordReset { message -> resetMessage = message } },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Send reset link") }
                resetMessage?.let { Text(it, color = androidx.compose.material3.MaterialTheme.colorScheme.primary) }
            }
        }
        DetailRow("Session safety", "About session safety") { showSessionHelp = true }
    }
    if (showSessionHelp) AlertDialog(
        onDismissRequest = { showSessionHelp = false },
        title = { Text("Session safety") },
        text = { Text("LifeLink refreshes your session when needed. Signing out clears the local session on this device. If you suspect unauthorized access, reset your password and sign out.") },
        confirmButton = { TextButton(onClick = { showSessionHelp = false }) { Text("Done") } }
    )
}

@Composable private fun LegalContent() {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Legal & Safety Center", style = androidx.compose.material3.MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text("Product guidance for a Philippines-oriented service. This is not legal or medical advice; counsel, clinicians, and licensed blood-service partners must review the final release.", color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant)
        EmergencyHelpCard()
        LegalSection("LifeLink’s role", "LifeLink is a coordination and contact service. It helps describe a need, discover potential voluntary donors, and manage consented contact. It does not confirm that a request is genuine, urgent, fulfilled, safe, or medically appropriate.")
        LegalSection("Clinical and blood-service boundary", "LifeLink is not a hospital, blood bank, collection unit, laboratory, ambulance, emergency dispatcher, or medical advice service. It does not screen or approve donors, collect or test blood, determine compatibility, store or transport blood, or guarantee supply. Licensed facilities and qualified clinicians make those decisions.")
        LegalSection("Voluntary donation", "Donation must be voluntary and free from pressure. Do not sell blood, request deposits or replacement fees, offer honoraria, demand money, or condition contact on gifts, sex, services, employment, or medical treatment.")
        LegalSection("Consent and conduct", "A match is an invitation to communicate, not consent to donate. Do not impersonate anyone, create fake emergencies, harass, threaten, stalk, doxx, share non-consensual sexual material, provide medical misinformation, forge records, or claim that a donor is tested, safe, or compatible. Use Report and Block when available.")
        LegalSection("Privacy and health information", "Blood type, emergency details, health-related messages, location, and contact details are personal information. Share only what is necessary. Do not post diagnoses, test results, patient names, IDs, passwords, one-time codes, financial credentials, exact addresses, or live location. Privacy requests and consent changes should use the support channel configured for this deployment.")
        LegalSection("User responsibilities", "Provide truthful information, post only requests you are authorized to make, respect consent, follow hospital or blood-service instructions, and verify real-world arrangements independently. LifeLink does not control off-platform transport, payment, donation, transfusion, or clinical decisions, subject to rights and responsibilities that cannot lawfully be excluded.")
        Text("Official guidance", style = androidx.compose.material3.MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        SourceLink("WHO · Blood safety and availability", "https://www.who.int/news-room/fact-sheets/detail/blood-safety-and-availability")
        SourceLink("Philippines · National Blood Services Act (RA 7719)", "https://lawphil.net/statutes/repacts/ra1994/ra_7719_1994.html")
        SourceLink("Philippines · Data Privacy Act (RA 10173)", "https://privacy.gov.ph/data-privacy-act/")
        SourceLink("Philippines · Unified emergency hotline information", "https://dilg.gov.ph/news/One-Number-for-All-Emergencies-Unified-911-to-Launch-Nationwide/NC-2025-1177")
        Text("Product guidance v1.0 · Review before production launch.", style = androidx.compose.material3.MaterialTheme.typography.labelMedium, color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable private fun EmergencyHelpCard() {
    val context = LocalContext.current
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = androidx.compose.material3.MaterialTheme.colorScheme.errorContainer), elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Emergency help", style = androidx.compose.material3.MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = androidx.compose.material3.MaterialTheme.colorScheme.onErrorContainer)
            Text("If someone is unconscious, severely bleeding, having trouble breathing, showing signs of shock, or getting worse, call 911 or go to the nearest emergency department now. Do not wait for a LifeLink match.", color = androidx.compose.material3.MaterialTheme.colorScheme.onErrorContainer)
            Button(onClick = { context.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:911"))) }) { Text("Call 911") }
        }
    }
}

@Composable private fun LegalSection(title: String, body: String) {
    Card(Modifier.fillMaxWidth(), elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Text(title, style = androidx.compose.material3.MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(body, color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable private fun SourceLink(label: String, url: String) {
    val context = LocalContext.current
    TextButton(onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }) {
        Text(label, modifier = Modifier.fillMaxWidth())
    }
}

@Composable private fun SafetyContent() {
    var selectedTopic by rememberSaveable { mutableStateOf<String?>(null) }
    val details = mapOf(
        "Location privacy" to "Your exact coordinates are never shown to the other person. Matching uses an approximate area, distance, and travel estimate instead of a public map of donor locations.",
        "Consent before contact" to "A donor must accept before contact can proceed. LifeLink asks for confirmation before opening your email app and recording contact sharing.",
        "Meet safely" to "Use a verified hospital or blood bank, tell someone you trust where you are going, and avoid exchanging money or sensitive medical information through LifeLink.",
        "If something feels unsafe" to "Stop the interaction, use Report or Block in the request flow, and contact local emergency services or a qualified medical facility when immediate danger is involved."
    )
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Safety and privacy", style = androidx.compose.material3.MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        details.keys.forEach { title -> DetailRow(title, "About $title") { selectedTopic = title } }
    }
    selectedTopic?.let { topic ->
        AlertDialog(onDismissRequest = { selectedTopic = null }, title = { Text(topic) }, text = { Text(details.getValue(topic)) }, confirmButton = { TextButton(onClick = { selectedTopic = null }) { Text("Done") } })
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
    onOpenDonor: () -> Unit,
    onSignOut: () -> Unit
) {
    var displayName by rememberSaveable(accountDisplayName) { mutableStateOf(accountDisplayName) }
    var selectedHelp by rememberSaveable { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(
            if (role == UserRole.REQUESTER) "Requester profile" else "Profile",
            style = androidx.compose.material3.MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold
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
                Text("Donor capability", fontWeight = FontWeight.Bold)
                Text(
                    if (role == UserRole.DONOR) "Manage your availability and donor profile."
                    else "You can request blood and offer to donate to other people. Your own requests will never appear as donor opportunities.",
                    color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedButton(onClick = onOpenDonor, modifier = Modifier.fillMaxWidth()) {
                    Text(if (role == UserRole.DONOR) "Open donor workspace" else "Become a donor")
                }
            }
        }
        Card(Modifier.fillMaxWidth(), elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)) {
            Row(Modifier.padding(start = 18.dp, end = 8.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Location privacy", modifier = Modifier.weight(1f), fontWeight = FontWeight.Bold)
                IconButton(onClick = { selectedHelp = "location" }) { Icon(Icons.AutoMirrored.Filled.HelpOutline, contentDescription = "About location privacy") }
            }
        }
        Card(Modifier.fillMaxWidth(), elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)) {
            Row(Modifier.padding(start = 18.dp, end = 8.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Safety and consent", modifier = Modifier.weight(1f), fontWeight = FontWeight.Bold)
                IconButton(onClick = { selectedHelp = "safety" }) { Icon(Icons.AutoMirrored.Filled.HelpOutline, contentDescription = "About safety and consent") }
            }
        }
        androidx.compose.material3.OutlinedButton(onClick = onSignOut, modifier = Modifier.fillMaxWidth()) { Text("Sign out") }
    }
    selectedHelp?.let { topic ->
        val body = if (topic == "location") {
            if (role == UserRole.DONOR) "Your approximate donor location is used for distance matching. Requesters see distance and travel estimates, not your coordinates." else "Your request location is used for matching. Donors do not see your exact coordinates."
        } else "Contact details are disclosed only after a donor accepts. LifeLink is a discovery and contact aid, not a replacement for blood-bank screening or medical care."
        AlertDialog(
            onDismissRequest = { selectedHelp = null },
            title = { Text(if (topic == "location") "Location privacy" else "Safety and consent") },
            text = { Text(body) },
            confirmButton = { TextButton(onClick = { selectedHelp = null }) { Text("Done") } }
        )
    }
}
