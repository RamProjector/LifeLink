package com.lifelink.app.core.navigation

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.Assignment
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ChatBubbleOutline
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.NotificationsNone
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.WindowSizeClass
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import com.lifelink.app.R
import com.lifelink.app.core.auth.UserRole
import com.lifelink.app.core.ui.LifeLinkBrand
import com.lifelink.app.core.ui.LifeLinkPageHeader
import com.lifelink.app.core.ui.theme.LifeLinkTheme
import com.lifelink.app.core.ui.theme.ThemeMode
import com.lifelink.app.domain.DonorAvailability
import com.lifelink.app.domain.RequestHistoryItem
import com.lifelink.app.domain.RequesterContact
import com.lifelink.app.domain.UpdateItem
import com.lifelink.app.feature.activeRequest.ActiveRequestScreen
import com.lifelink.app.feature.donor.BecomeDonorAction
import com.lifelink.app.feature.donor.BecomeDonorScreen
import com.lifelink.app.feature.donor.BecomeDonorUiState
import com.lifelink.app.feature.donor.DonorAction
import com.lifelink.app.feature.donor.DonorScreen
import com.lifelink.app.feature.donor.DonorUiState
import com.lifelink.app.feature.emergencyrequest.EmergencyRequestAction
import com.lifelink.app.feature.emergencyrequest.EmergencyRequestScreen
import com.lifelink.app.feature.emergencyrequest.EmergencyRequestUiState
import com.lifelink.app.feature.privacy.ConversationScreen
import com.lifelink.app.feature.privacy.DonorMapScreen
import com.lifelink.app.feature.privacy.MessagingScreen
import com.lifelink.app.feature.privacy.PrivacyAction
import com.lifelink.app.feature.privacy.PrivacyUiState
import com.lifelink.app.feature.updates.UpdatesScreen

private enum class ShellTab(
    val label: String,
) {
    HOME("Home"),
    REQUESTS("Requests"),
    MESSAGING("Messaging"),
    PROFILE("Profile"),
}

private enum class SettingsSection { PROFILE, APPEARANCE, ACCESSIBILITY, SECURITY }

/**
 * Renders account navigation and routes requester and donor actions to their screens.
 *
 * Opening a conversation records the request and donor IDs and shows [ConversationScreen],
 * which dispatches [PrivacyAction.OpenConversation] itself on composition (and after
 * process-death restoration); closing it returns to the screen that opened it.
 */
@OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
@Composable
fun LifeLinkShell(
    state: EmergencyRequestUiState,
    onAction: (EmergencyRequestAction) -> Unit,
    donorState: DonorUiState,
    onDonorAction: (DonorAction) -> Unit,
    becomeDonorState: BecomeDonorUiState,
    onBecomeDonorAction: (BecomeDonorAction) -> Unit,
    privacyState: PrivacyUiState,
    onPrivacyAction: (PrivacyAction) -> Unit,
    role: UserRole,
    onSwitchRole: () -> Unit,
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
    notificationRequestId: String? = null,
) {
    val context = LocalContext.current
    val welcomePrefs = context.getSharedPreferences("lifelink_welcome", 0)
    var showRequest by rememberSaveable { mutableStateOf(false) }
    var showActive by rememberSaveable { mutableStateOf(false) }
    var showDonor by rememberSaveable { mutableStateOf(false) }
    // Separate donor-profile flow: opt in to donating after account creation.
    var showBecomeDonor by rememberSaveable { mutableStateOf(false) }
    var showDonorMap by rememberSaveable { mutableStateOf(false) }
    var showNotifications by rememberSaveable { mutableStateOf(false) }
    // Accepted-donor contact screen: opened from a request-history card so the
    // requester can see accepted timestamps, contact-shared and meeting-arranged
    // status, and the Fulfilled / Cancelled lifecycle actions in one place.
    var showAcceptedContacts by rememberSaveable { mutableStateOf(false) }
    var acceptedContactsRequestId by rememberSaveable { mutableStateOf("") }
    // The in-app conversation was previously unreachable: ConversationScreen and
    // PrivacyViewModel.openConversation existed, but nothing navigated to them.
    // This flag is the single entry point for both the requester (from a contact
    // card) and the donor (from an accepted request).
    var showConversation by rememberSaveable { mutableStateOf(false) }
    var conversationRequestId by rememberSaveable { mutableStateOf("") }
    var conversationDonorId by rememberSaveable { mutableStateOf("") }
    val openConversation: (String, String) -> Unit = { requestId, donorId ->
        conversationRequestId = requestId
        conversationDonorId = donorId
        showConversation = true
    }
    var showStart by rememberSaveable(accountUserId) {
        mutableStateOf(
            accountUserId.isNotBlank() && !welcomePrefs.getBoolean("seen_$accountUserId", false),
        )
    }
    var tab by rememberSaveable { mutableStateOf(ShellTab.HOME) }
    val markWelcomeSeen = {
        if (accountUserId.isNotBlank()) welcomePrefs.edit().putBoolean("seen_$accountUserId", true).apply()
        showStart = false
    }
    LaunchedEffect(accountUserId, role) {
        if (accountUserId.isNotBlank() && role == UserRole.DONOR) {
            showStart = false
            showDonor = true
        }
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
            showNotifications = true
        }
    }

    if (showStart) {
        StartContent(
            role = role,
            onGetStarted = markWelcomeSeen,
            onCreateRequest = {
                markWelcomeSeen()
                showRequest = true
            },
            onOpenDonor = {
                markWelcomeSeen()
                showDonor = true
            },
        )
        return
    }
    if (showConversation && conversationRequestId.isNotBlank() && conversationDonorId.isNotBlank()) {
        BackHandler { showConversation = false }
        ConversationScreen(
            state = privacyState,
            requestId = conversationRequestId,
            donorId = conversationDonorId,
            currentUserId = accountUserId,
            onAction = onPrivacyAction,
            onBack = { showConversation = false },
        )
        return
    }
    if (showNotifications) {
        BackHandler { showNotifications = false }
        UpdatesScreen(
            updates = updates,
            onOpen = { update ->
                onUpdateRead(update.id)
                showNotifications = false
                update.requestId?.let {
                    onAction(EmergencyRequestAction.OpenRequest(it))
                    showActive = true
                }
            },
            onMarkAllRead = onMarkAllUpdatesRead,
        )
        return
    }
    if (showRequest) {
        EmergencyRequestScreen(
            state = state,
            onAction = onAction,
            onExit = {
                showRequest = false
                tab = ShellTab.HOME
            },
            onOpenConversation = openConversation,
        )
        return
    }
    if (showActive && state.activeRequest != null) {
        val activeRequestId = state.activeRequest.requestId
        BackHandler { showActive = false }
        ActiveRequestScreen(
            state = state,
            onAction = onAction,
            onBack = { showActive = false },
            onReviewContacts = {
                onAction(EmergencyRequestAction.ShowContactResults(activeRequestId))
                showActive = false
                showRequest = true
            },
        )
        return
    }
    if (showBecomeDonor) {
        BackHandler { showBecomeDonor = false }
        BecomeDonorScreen(
            state = becomeDonorState,
            onAction = onBecomeDonorAction,
            onBack = { showBecomeDonor = false },
        )
        return
    }
    if (showDonor) {
        BackHandler { showDonor = false }
        DonorScreen(
            state = donorState,
            onAction = onDonorAction,
            onBack = { showDonor = false },
            onOpenConversation = openConversation,
        )
        return
    }
    if (showDonorMap) {
        BackHandler { showDonorMap = false }
        DonorMapScreen(state = privacyState, onAction = onPrivacyAction)
        return
    }
    if (showAcceptedContacts) {
        BackHandler { showAcceptedContacts = false }
        AcceptedDonorContactsScreen(
            state = state,
            requestId = acceptedContactsRequestId,
            onAction = onAction,
            onBack = { showAcceptedContacts = false },
        )
        return
    }
    BackHandler(enabled = tab != ShellTab.HOME) { tab = ShellTab.HOME }
    val unreadUpdates = updates.count { !it.isRead }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val windowSize = WindowSizeClass.calculateFromSize(DpSize(maxWidth, maxHeight))
        val useRail = windowSize.widthSizeClass != WindowWidthSizeClass.Compact
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            topBar = {
                Row(
                    Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 12.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.End,
                ) {
                    IconButton(
                        onClick = { showNotifications = true },
                        modifier = Modifier.testTag("open-notifications"),
                    ) {
                        BadgedBox(badge = {
                            if (unreadUpdates >
                                0
                            ) {
                                Badge { Text(if (unreadUpdates > 99) "99+" else unreadUpdates.toString()) }
                            }
                        }) {
                            Icon(
                                Icons.Default.NotificationsNone,
                                contentDescription = if (unreadUpdates > 0) "$unreadUpdates unread notifications" else "Notifications",
                            )
                        }
                    }
                }
            },
            bottomBar = {
                if (!useRail) {
                    NavigationBar(containerColor = MaterialTheme.colorScheme.surface, tonalElevation = 0.dp) {
                        val tabs = ShellTab.entries
                        tabs.forEachIndexed { index, destination ->
                            // A single centered map control splits the tab row: the first
                            // half of the tabs on the left, the second half on the right.
                            if (index == tabs.size / 2) {
                                ShellMapAction(onClick = { showDonorMap = true })
                            }
                            NavigationBarItem(
                                selected = tab == destination,
                                onClick = { tab = destination },
                                icon = { ShellNavigationIcon(destination) },
                                label = { Text(destination.label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                modifier = Modifier.testTag("nav-${destination.name}"),
                            )
                        }
                    }
                }
            },
        ) { padding ->
            Row(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) {
                if (useRail) {
                    NavigationRail(
                        modifier = Modifier.fillMaxHeight().testTag("navigation-rail"),
                        containerColor = MaterialTheme.colorScheme.surface,
                    ) {
                        NavigationRailItem(
                            selected = false,
                            onClick = { showDonorMap = true },
                            icon = { Icon(Icons.Default.Map, contentDescription = null) },
                            label = { Text("Map") },
                            modifier = Modifier.testTag("open-fullscreen-donor-map-rail"),
                        )
                        HorizontalDivider()
                        ShellTab.entries.forEach { destination ->
                            NavigationRailItem(
                                selected = tab == destination,
                                onClick = { tab = destination },
                                icon = { ShellNavigationIcon(destination) },
                                label = { Text(destination.label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                modifier = Modifier.testTag("nav-${destination.name}"),
                            )
                        }
                    }
                }
                Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.TopCenter) {
                    Surface(Modifier.widthIn(max = 840.dp).fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                        when (tab) {
                            ShellTab.HOME ->
                                HomeContent(
                                    state = state,
                                    donorState = donorState,
                                    role = role,
                                    onCreate = { showRequest = true },
                                    onActive = { showActive = true },
                                    onDonor = { showDonor = true },
                                    updates = updates,
                                    onRequests = { tab = ShellTab.REQUESTS },
                                    onUpdates = { showNotifications = true },
                                )
                            ShellTab.REQUESTS ->
                                RequestsContent(
                                    state = state,
                                    onAction = onAction,
                                    onCreate = { showRequest = true },
                                    onOpen = { showRequest = true },
                                    onActive = { showActive = true },
                                    onOpenContacts = { requestId ->
                                        acceptedContactsRequestId = requestId
                                        showAcceptedContacts = true
                                    },
                                )
                            ShellTab.MESSAGING ->
                                MessagingScreen(
                                    state = privacyState,
                                    onAction = onPrivacyAction,
                                    onOpenConversation = openConversation,
                                )
                            ShellTab.PROFILE ->
                                SettingsContent(
                                    role,
                                    accountEmail,
                                    accountUserId,
                                    accountDisplayName,
                                    profileSaving,
                                    profileMessage,
                                    onSaveProfile,
                                    themeMode,
                                    onThemeModeChange,
                                    onRequestPasswordReset,
                                    onOpenDonor = {
                                        showStart =
                                            false
                                        ; showDonor = true
                                    },
                                    onBecomeDonor = {
                                        showStart = false
                                        showBecomeDonor = true
                                    },
                                    onSwitchRole = onSwitchRole,
                                    onSignOut = onSignOut,
                                )
                        }
                    }
                }
            }
        }
    }
}

/** Renders the tab icon and caps the visible unread-update badge at 99+. */
@Composable
private fun ShellNavigationIcon(tab: ShellTab) {
    val icon =
        when (tab) {
            ShellTab.HOME -> Icons.Default.Home
            ShellTab.REQUESTS -> Icons.AutoMirrored.Filled.Assignment
            ShellTab.MESSAGING -> Icons.Default.ChatBubbleOutline
            ShellTab.PROFILE -> Icons.Default.Person
        }
    Icon(icon, contentDescription = null)
}

/** Shows request history with actions to create, resume, or view the current request. */
@Suppress("LongMethod")
@Composable
private fun RequestsContent(
    state: EmergencyRequestUiState,
    onAction: (EmergencyRequestAction) -> Unit,
    onCreate: () -> Unit,
    onOpen: () -> Unit,
    onActive: () -> Unit,
    onOpenContacts: (String) -> Unit,
) {
    LazyColumn(
        Modifier.fillMaxSize().testTag("request-history"),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item { LifeLinkPageHeader("Requests") }
        item {
            if (state.step == com.lifelink.app.domain.RequestStep.RESULTS &&
                state.submission is com.lifelink.app.feature.emergencyrequest.SubmissionState.Matching
            ) {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                    Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("Matching results ready", style = MaterialTheme.typography.titleLarge)
                        Text("Review nearby donors and choose who to contact.")
                        Button(onClick = onOpen, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text("Open matching results") }
                    }
                }
            } else if (state.activeRequest != null) {
                ActiveRequestSummary(state, onActive)
            } else {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
                    Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            if (state.historyRefreshing) {
                                "Loading your requests\u2026"
                            } else if (state.historyError !=
                                null
                            ) {
                                "Requests unavailable"
                            } else {
                                "No active request"
                            },
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            if (state.historyRefreshing ||
                                state.historyError != null
                            ) {
                                "Your saved requests will appear after they load."
                            } else {
                                "When you need blood, start with the type, amount, and location."
                            },
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
        item {
            Button(onClick = onCreate, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp), shape = MaterialTheme.shapes.medium) {
                Icon(Icons.Default.Add, null)
                Spacer(Modifier.width(8.dp))
                Text("Create emergency request")
            }
        }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("Request history", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                TextButton(onClick = { onAction(EmergencyRequestAction.RefreshHistory) }, enabled = !state.historyRefreshing) {
                    Text(
                        if (state.historyRefreshing) {
                            "Refreshing\u2026"
                        } else if (state.historyError != null) {
                            "Retry"
                        } else {
                            "Refresh"
                        },
                    )
                }
            }
        }
        state.historyError?.let { error ->
            item { Text(error, color = MaterialTheme.colorScheme.error) }
        }
        items(state.requestHistory, key = { it.requestId }) { request ->
            RequestHistoryCard(request) { onOpenContacts(request.requestId) }
        }
    }
}

/** Summarizes a saved request and labels terminal requests as past requests. */
@Composable
private fun RequestHistoryCard(request: RequestHistoryItem, onOpenContacts: () -> Unit) {
    Card(
        Modifier.fillMaxWidth().clickable(onClick = onOpenContacts),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(request.status.label, modifier = Modifier.weight(1f).padding(end = 12.dp), fontWeight = FontWeight.SemiBold)
                Text(
                    if (request.status in
                        setOf(
                            com.lifelink.app.domain.ActiveRequestStatus.FULFILLED,
                            com.lifelink.app.domain.ActiveRequestStatus.EXPIRED,
                            com.lifelink.app.domain.ActiveRequestStatus.CANCELLED,
                        )
                    ) {
                        "Past"
                    } else {
                        "Active"
                    },
                    color = androidx.compose.material3.MaterialTheme.colorScheme.primary,
                )
            }
            Text("Request ${request.requestId.take(12)}", color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                "${request.bloodType ?: "Request"} \u00b7 ${request.units?.let {
                    "$it unit${if (it == 1) "" else "s"}"
                } ?: "Details unavailable"}",
                style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
            )
            val responseWord = if (request.matchesResponded == 1) "" else "s"
            Text(
                "${request.matchesResponded} donor response$responseWord \u00b7 ${request.notificationsCreated} notified",
                style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
            )
            request.facilityName?.let {
                Text(
                    "$it${request.area?.let { area ->
                        " \u00b7 $area"
                    } ?: ""}",
                    style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                )
            }
            if (request.contactStatuses.isNotEmpty()) {
                Text(
                    "Contacts: ${request.contactStatuses.joinToString()}",
                    style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                    color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** Displays the account dashboard with request, donor, and activity navigation actions. */
@Suppress("LongParameterList", "LongMethod")
@Composable
private fun HomeContent(
    state: EmergencyRequestUiState,
    donorState: DonorUiState,
    role: UserRole,
    onCreate: () -> Unit,
    onActive: () -> Unit,
    onDonor: () -> Unit,
    updates: List<UpdateItem>,
    onRequests: () -> Unit,
    onUpdates: () -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LifeLinkBrand()
            Spacer(Modifier.weight(1f))
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            if (role == UserRole.DONOR) {
                DonorDashboardSummary(donorState.profile, donorState.requests.size, onDonor)
            }
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Your requests", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                    TextButton(onClick = onRequests) { Text("View all") }
                }
                if (state.activeRequest != null) {
                    ActiveRequestSummary(state, onActive)
                } else {
                    Card(
                        Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                    ) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                if (state.historyRefreshing) {
                                    "Loading your requests\u2026"
                                } else if (state.historyError != null) {
                                    "Requests unavailable"
                                } else {
                                    "No active request"
                                },
                                style = MaterialTheme.typography.titleSmall,
                            )
                            Text(
                                state.historyError
                                    ?: if (state.historyRefreshing) {
                                        "Checking your account for saved requests."
                                    } else {
                                        "Create a request to find nearby blood donors."
                                    },
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                Button(onClick = onCreate, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp), shape = MaterialTheme.shapes.medium) {
                    Icon(Icons.Default.Add, null, Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Create emergency request")
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Recent activity", style = MaterialTheme.typography.titleMedium)
                if (updates.isEmpty()) {
                    Text("No updates yet", modifier = Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "Donor responses and request updates will appear here.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    updates.take(3).forEach { update ->
                        ListItem(
                            headlineContent = { Text(update.title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                            supportingContent = { Text(update.body, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                            modifier = Modifier.fillMaxWidth().clickable(onClick = onUpdates),
                        )
                    }
                }
            }
        }
    }
}

/** Shows the current request status and response count, or nothing when no request is loaded. */
@Composable
private fun ActiveRequestSummary(state: EmergencyRequestUiState, onOpen: () -> Unit) {
    val active = state.activeRequest ?: return
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                if (active.isTerminal) "Latest request" else "Active request",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(active.status.label, style = MaterialTheme.typography.titleLarge)
            Text(
                "${active.matchesResponded} donor responses",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(onClick = onOpen) {
                Text(if (active.isTerminal) "View request" else "View live status")
                Spacer(Modifier.width(8.dp))
                Icon(Icons.AutoMirrored.Filled.ArrowForward, null)
            }
        }
    }
}

/** Previews the requester dashboard with empty request and donor state in light and dark themes. */
@Preview(name = "Home \u00b7 light", widthDp = 360, heightDp = 820, showBackground = true)
@Preview(name = "Home \u00b7 dark", widthDp = 360, heightDp = 820, uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun HomePreview() {
    LifeLinkTheme {
        HomeContent(EmergencyRequestUiState(), DonorUiState(), UserRole.REQUESTER, {}, {}, {}, emptyList(), {}, {})
    }
}

/** Summarizes donor availability and request count with an action to open donor mode. */
@Composable
private fun DonorDashboardSummary(
    profile: com.lifelink.app.domain.DonorProfile,
    requestCount: Int,
    onOpen: () -> Unit,
) {
    val statusColor =
        when (profile.availability) {
            DonorAvailability.AVAILABLE -> androidx.compose.material3.MaterialTheme.colorScheme.primary
            DonorAvailability.PAUSED -> androidx.compose.material3.MaterialTheme.colorScheme.tertiary
            DonorAvailability.OFFLINE -> androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant
        }
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = androidx.compose.material3.MaterialTheme.colorScheme.surfaceContainerLow),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Donor dashboard",
                    modifier = Modifier.weight(1f).padding(end = 12.dp),
                    style = androidx.compose.material3.MaterialTheme.typography.titleMedium,
                )
                Text(profile.availability.label, color = statusColor, fontWeight = FontWeight.Bold)
            }
            if (profile.isSetupComplete) {
                Text("$requestCount matching request${if (requestCount == 1) "" else "s"} in your inbox")
                Text(
                    "Your approximate location and profile are ready for matching.",
                    color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Text(
                    "Complete your donor profile before choosing availability or receiving matching requests.",
                    color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Button(onClick = onOpen, Modifier.fillMaxWidth()) {
                Text(if (profile.isSetupComplete) "Manage donor dashboard" else "Complete donor profile")
            }
        }
    }
}

/** Raised, centered map action that splits the bottom tab row, matching the reference. */
@Composable
private fun ShellMapAction(onClick: () -> Unit) {
    Box(Modifier.width(76.dp).fillMaxHeight(), contentAlignment = Alignment.Center) {
        Surface(
            onClick = onClick,
            modifier = Modifier.size(52.dp).offset(y = (-6).dp).testTag("open-fullscreen-donor-map"),
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
            shadowElevation = 6.dp,
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(Icons.Default.Map, contentDescription = stringResource(R.string.lifelink_map_action))
            }
        }
    }
}

/**
 * Accessibility and role settings. The requester/donor switch used to sit in the
 * home header; it lives here now so the switch is grouped with the other
 * account-level accessibility controls instead of competing with the home title.
 */
@Composable
private fun AccessibilityContent(
    role: UserRole,
    accountUserId: String,
    onSwitchRole: () -> Unit,
) {
    val context = LocalContext.current
    val prefs = context.getSharedPreferences("lifelink_accessibility", 0)
    var reduceMotion by rememberSaveable(accountUserId) {
        mutableStateOf(prefs.getBoolean("reduce_motion_$accountUserId", false))
    }
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Accessibility", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Mode", fontWeight = FontWeight.Bold)
                Text(
                    if (role == UserRole.DONOR) {
                        "You are using the donor workspace. Switch to request blood or track your requests."
                    } else {
                        "You are using the requester workspace. Switch to manage your donor availability."
                    },
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                androidx.compose.material3.OutlinedButton(
                    onClick = onSwitchRole,
                    modifier = Modifier.fillMaxWidth().testTag("role-switcher"),
                ) {
                    Text(if (role == UserRole.DONOR) "Switch to requester" else "Switch to donor")
                }
            }
        }
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Reduce motion", fontWeight = FontWeight.Bold)
                        Text(
                            "Play the loading animation as a static LifeLink icon instead of a pulsing one.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    androidx.compose.material3.Switch(
                        checked = reduceMotion,
                        onCheckedChange = { checked ->
                            reduceMotion = checked
                            if (accountUserId.isNotBlank()) {
                                prefs.edit().putBoolean("reduce_motion_$accountUserId", checked).apply()
                            }
                        },
                    )
                }
            }
        }
    }
}

/** Shows introductory guidance and delegates request creation or donor navigation to the caller. */
@Composable
private fun StartContent(
    role: UserRole,
    onGetStarted: () -> Unit,
    onCreateRequest: () -> Unit,
    onOpenDonor: () -> Unit,
) {
    Surface(color = androidx.compose.material3.MaterialTheme.colorScheme.background) {
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 28.dp, vertical = 32.dp),
            verticalArrangement = Arrangement.spacedBy(32.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
                LifeLinkBrand()
                Text(
                    if (role == UserRole.DONOR) {
                        "Set up your donor profile"
                    } else {
                        "Find nearby blood donors"
                    },
                    style = androidx.compose.material3.MaterialTheme.typography.headlineSmall,
                )
                Text(
                    "Use approximate locations, consent-based contact, and clear request status. LifeLink supports coordination; hospitals and blood banks remain responsible for screening and care.",
                    color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
                    style = androidx.compose.material3.MaterialTheme.typography.bodyLarge,
                )
            }
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    onClick = if (role == UserRole.DONOR) onOpenDonor else onCreateRequest,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(if (role == UserRole.DONOR) "Open donor workspace" else "Create a request") }
                androidx.compose.material3.OutlinedButton(onClick = onGetStarted, modifier = Modifier.fillMaxWidth()) {
                    Text("Go to Home")
                }
                Text(
                    if (role ==
                        UserRole.DONOR
                    ) {
                        "You control your availability and profile visibility."
                    } else {
                        "You choose when to contact a donor after they respond."
                    },
                    modifier = Modifier.fillMaxWidth(),
                    color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
                    style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

/** Routes the selected settings section to profile, appearance, security, or guidance content. */
@Suppress("LongParameterList")
@Composable
private fun SettingsContent(
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
    onBecomeDonor: () -> Unit,
    onSwitchRole: () -> Unit,
    onSignOut: () -> Unit,
) {
    var section by rememberSaveable { mutableStateOf(SettingsSection.PROFILE) }
    val sections =
        listOf(
            SettingsSection.PROFILE,
            SettingsSection.APPEARANCE,
            SettingsSection.ACCESSIBILITY,
            SettingsSection.SECURITY,
        )
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 20.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Profile", style = MaterialTheme.typography.titleLarge)
            Text(
                "Account, privacy, and LifeLink information.",
                color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        ScrollableTabRow(selectedTabIndex = sections.indexOf(section), edgePadding = 12.dp) {
            sections.forEach { item ->
                Tab(
                    modifier = Modifier.testTag("settings-${item.name}"),
                    selected = section == item,
                    onClick = { section = item },
                    text = { Text(item.name.lowercase().replaceFirstChar { it.uppercase() }) },
                )
            }
        }
        when (section) {
            SettingsSection.PROFILE ->
                ProfileContent(
                    role,
                    accountEmail,
                    accountUserId,
                    accountDisplayName,
                    profileSaving,
                    profileMessage,
                    onSaveProfile,
                    onOpenDonor,
                    onBecomeDonor,
                    onSignOut,
                )
            SettingsSection.APPEARANCE -> AppearanceContent(themeMode, onThemeModeChange)
            SettingsSection.ACCESSIBILITY -> AccessibilityContent(role, accountUserId, onSwitchRole)
            SettingsSection.SECURITY -> SecurityContent(onRequestPasswordReset)
        }
    }
}

/** Displays accessible theme choices and reports the selected mode through [onThemeModeChange]. */
@Composable private fun AppearanceContent(themeMode: ThemeMode, onThemeModeChange: (ThemeMode) -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .selectableGroup()
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Appearance", style = androidx.compose.material3.MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        ThemeMode.values().forEach { mode ->
            Card(
                Modifier.fillMaxWidth().heightIn(min = 56.dp).selectable(selected = themeMode == mode, role = Role.RadioButton, onClick = {
                    onThemeModeChange(mode)
                }),
                colors =
                    CardDefaults.cardColors(
                        containerColor =
                            if (themeMode ==
                                mode
                            ) {
                                MaterialTheme.colorScheme.primaryContainer
                            } else {
                                MaterialTheme.colorScheme.surface
                            },
                    ),
            ) {
                Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = themeMode == mode, onClick = null)
                    Text(
                        when (mode) {
                            ThemeMode.SYSTEM -> "Use device setting"
                            ThemeMode.LIGHT -> "Light"
                            ThemeMode.DARK -> "Dark"
                            ThemeMode.DYNAMIC -> "Wallpaper colors (Android 12+)"
                        },
                        modifier = Modifier.padding(start = 4.dp),
                        fontWeight = if (themeMode == mode) FontWeight.SemiBold else FontWeight.Normal,
                    )
                }
            }
        }
    }
}

/** Requests a password-reset link and shows the callback result alongside session-safety guidance. */
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
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Send reset link") }
                resetMessage?.let { Text(it, color = androidx.compose.material3.MaterialTheme.colorScheme.primary) }
            }
        }
        Card(
            Modifier.fillMaxWidth().clickable { showSessionHelp = true },
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Session safety", style = MaterialTheme.typography.titleMedium)
                Text("About session safety", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    if (showSessionHelp) {
        AlertDialog(
            onDismissRequest = { showSessionHelp = false },
            title = { Text("Session safety") },
            text = {
                Text(
                    "LifeLink refreshes your session when needed. Signing out clears the local session on this device. If you suspect unauthorized access, reset your password and sign out.",
                )
            },
            confirmButton = { TextButton(onClick = { showSessionHelp = false }) { Text("Done") } },
        )
    }
}

/**
 * Accepted-donor contact screen. Shows the accepted timestamp, contact-shared
 * and meeting-arranged status, and the Fulfilled / Cancelled lifecycle actions
 * for every contact on a request. The legal-consent flow from PR #80 lives in
 * the auth screen and is unaffected by this screen.
 */
@Composable
private fun AcceptedDonorContactsScreen(
    state: EmergencyRequestUiState,
    requestId: String,
    onAction: (EmergencyRequestAction) -> Unit,
    onBack: () -> Unit,
) {
    LaunchedEffect(requestId) { onAction(EmergencyRequestAction.RefreshContacts(requestId)) }
    val contacts = state.contacts
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
            Text("Accepted donors", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            if (state.contactsRefreshing) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            } else {
                IconButton(onClick = { onAction(EmergencyRequestAction.RefreshContacts(requestId)) }) {
                    Icon(Icons.Default.Refresh, contentDescription = "Refresh contacts")
                }
            }
        }
        state.contactsError?.let { error ->
            Text(error, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 20.dp))
        }
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (contacts.isEmpty() && !state.contactsRefreshing) {
                item {
                    Text(
                        "No donor has accepted this request yet. Accepted donors appear here with their contact and meeting status.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            items(contacts, key = { it.donorId }) { contact -> AcceptedDonorContactCard(contact, onAction) }
        }
    }
}

/** Shows one accepted donor's timestamps, status, and Fulfilled / Cancelled actions. */
@Suppress("LongMethod")
@Composable
private fun AcceptedDonorContactCard(contact: RequesterContact, onAction: (EmergencyRequestAction) -> Unit) {
    val status = contact.status.lowercase()
    val accepted = status in setOf("accepted", "arrived", "contact_shared", "meeting_arranged", "fulfilled")
    val statusLabel = status.replace('_', ' ').replaceFirstChar { it.uppercase() }
    Card(
        Modifier.fillMaxWidth(),
        colors =
            CardDefaults.cardColors(
                containerColor = if (accepted) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
            ),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(contact.displayName, fontWeight = FontWeight.Bold)
                Text(statusLabel, color = if (accepted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
            }
            contact.acceptedAt?.let {
                Text(
                    "Accepted ${formatShellTimestamp(it)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            val contactSharedLabel =
                if (contact.contactSharedAt != null) {
                    "Contact shared ${formatShellTimestamp(contact.contactSharedAt)}"
                } else {
                    "Contact not shared yet"
                }
            Text(
                contactSharedLabel,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                if (status == "meeting_arranged" || status == "fulfilled") "Meeting arranged" else "Meeting not arranged yet",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (accepted) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { onAction(EmergencyRequestAction.UpdateContactStatus(contact.donorId, "fulfilled")) },
                        enabled = status != "fulfilled",
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Fulfilled")
                    }
                    OutlinedButton(
                        onClick = { onAction(EmergencyRequestAction.UpdateContactStatus(contact.donorId, "cancelled")) },
                        enabled = status != "cancelled",
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(Icons.Default.Error, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Cancelled")
                    }
                }
            }
        }
    }
}

/** Number of leading characters kept when rendering an ISO-8601 timestamp as text. */
private const val SHELL_TIMESTAMP_LENGTH = 16

/** Displays up to the first 16 characters with 'T' replaced by a space, without parsing or converting time zones. */
private fun formatShellTimestamp(value: String): String = value.take(SHELL_TIMESTAMP_LENGTH).replace('T', ' ')

/**
 * Edits the account display name and exposes donor navigation and sign-out actions.
 * Donors invoke [onOpenDonor] to open their workspace; other roles invoke [onBecomeDonor] to start setup.
 */
@Composable private fun ProfileContent(
    role: UserRole,
    accountEmail: String,
    accountUserId: String,
    accountDisplayName: String,
    profileSaving: Boolean,
    profileMessage: String?,
    onSaveProfile: (String) -> Unit,
    onOpenDonor: () -> Unit,
    onBecomeDonor: () -> Unit,
    onSignOut: () -> Unit,
) {
    var displayName by rememberSaveable(accountDisplayName) { mutableStateOf(accountDisplayName) }
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Card(
            Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = androidx.compose.material3.MaterialTheme.colorScheme.primaryContainer),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        ) {
            Row(
                Modifier.padding(18.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Surface(
                    modifier = Modifier.size(48.dp),
                    shape = androidx.compose.foundation.shape.CircleShape,
                    color = androidx.compose.material3.MaterialTheme.colorScheme.primary,
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.Default.Person,
                            contentDescription = null,
                            tint = androidx.compose.material3.MaterialTheme.colorScheme.onPrimary,
                        )
                    }
                }
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(accountEmail.ifBlank { "Authenticated LifeLink account" }, fontWeight = FontWeight.Bold)
                    Text(
                        if (role ==
                            UserRole.DONOR
                        ) {
                            "Donor account"
                        } else {
                            "Requester account"
                        },
                        color = androidx.compose.material3.MaterialTheme.colorScheme.primary,
                    )
                    if (accountUserId.isNotBlank()) {
                        Text(
                            "Account ID \u00b7 ${accountUserId.take(8)}\u2026",
                            style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                            color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
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
                    singleLine = true,
                )
                Button(
                    onClick = { onSaveProfile(displayName) },
                    enabled = !profileSaving && displayName.trim().length >= 2,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(if (profileSaving) "Saving\u2026" else "Save profile") }
                profileMessage?.let { Text(it, color = androidx.compose.material3.MaterialTheme.colorScheme.primary) }
            }
        }
        Card(Modifier.fillMaxWidth(), elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Donor capability", fontWeight = FontWeight.Bold)
                Text(
                    if (role == UserRole.DONOR) {
                        "Manage your availability and donor profile."
                    } else {
                        "You can request blood and offer to donate to other people. Your own requests will never appear as donor opportunities."
                    },
                    color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (role == UserRole.DONOR) {
                    OutlinedButton(onClick = onOpenDonor, modifier = Modifier.fillMaxWidth()) {
                        Text("Open donor workspace")
                    }
                } else {
                    // Separate donor-profile flow: opt in to donating after account creation.
                    Button(
                        onClick = onBecomeDonor,
                        modifier = Modifier.fillMaxWidth().testTag("open-become-donor"),
                    ) { Text("Become a donor") }
                }
            }
        }
        androidx.compose.material3.OutlinedButton(onClick = onSignOut, modifier = Modifier.fillMaxWidth()) { Text("Sign out") }
    }
}
