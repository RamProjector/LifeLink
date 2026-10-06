package com.lifelink.app.core.navigation

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
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
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.Assignment
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ChatBubbleOutline
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.NotificationsNone
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import com.lifelink.app.core.auth.UserRole
import com.lifelink.app.core.ui.LifeLinkEmptyState
import com.lifelink.app.core.ui.LifeLinkPageHeader
import com.lifelink.app.core.ui.theme.LifeLinkTheme
import com.lifelink.app.core.ui.theme.ThemeMode
import com.lifelink.app.domain.DonorAvailability
import com.lifelink.app.domain.RequestHistoryItem
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

private enum class SettingsSection { PROFILE, LEGAL, THEME, SECURITY }

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
        DonorMapScreen(state = privacyState, onAction = onPrivacyAction, onBack = { showDonorMap = false })
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
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        FilledTonalButton(
                            onClick = { showDonorMap = true },
                            modifier = Modifier.padding(bottom = 4.dp).testTag("open-fullscreen-donor-map"),
                        ) {
                            Icon(Icons.Default.Map, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("Find donors on full-screen map")
                        }
                        NavigationBar(containerColor = MaterialTheme.colorScheme.surface, tonalElevation = 0.dp) {
                            ShellTab.entries.forEach { destination ->
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
                                HomeContent(state, donorState, role, onSwitchRole, onCreate = { showRequest = true }, onActive = {
                                    showActive =
                                        true
                                }, onDonor = {
                                    showDonor = true
                                }, updates = updates, onRequests = {
                                    tab =
                                        ShellTab.REQUESTS
                                }, onUpdates = { showNotifications = true })
                            ShellTab.REQUESTS ->
                                RequestsContent(state, onAction = onAction, onCreate = { showRequest = true }, onOpen = {
                                    showRequest =
                                        true
                                }, onActive = { showActive = true })
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
@Composable private fun RequestsContent(
    state: EmergencyRequestUiState,
    onAction: (EmergencyRequestAction) -> Unit,
    onCreate: () -> Unit,
    onOpen: () -> Unit,
    onActive: () -> Unit,
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
                                "Loading your requests…"
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
                Icon(Icons.Default.Add, null, Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text("Create emergency request", fontWeight = FontWeight.SemiBold)
            }
        }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Request history",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f).semantics { heading() },
                )
                TextButton(onClick = { onAction(EmergencyRequestAction.RefreshHistory) }, enabled = !state.historyRefreshing) {
                    Text(
                        if (state.historyRefreshing) {
                            "Refreshing…"
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
            item {
                Card(
                    Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                ) {
                    Row(
                        Modifier.padding(16.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Default.Error,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.size(20.dp),
                        )
                        Text(
                            error,
                            modifier = Modifier.weight(1f),
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        TextButton(
                            onClick = { onAction(EmergencyRequestAction.RefreshHistory) },
                            enabled = !state.historyRefreshing,
                        ) { Text("Retry") }
                    }
                }
            }
        }
        if (state.requestHistory.isEmpty() && !state.historyRefreshing && state.historyError == null) {
            item {
                LifeLinkEmptyState(
                    icon = Icons.AutoMirrored.Filled.Assignment,
                    title = "No requests yet",
                    body = "Your request history will appear here after you create your first request.",
                )
            }
        }
        items(state.requestHistory, key = { it.requestId }) { RequestHistoryCard(it) }
    }
}

/** Summarizes a saved request and labels terminal requests as past requests. */
@Composable
private fun RequestHistoryCard(request: RequestHistoryItem) {
    Card(Modifier.fillMaxWidth(), elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)) {
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
                "${request.bloodType ?: "Request"} · ${request.units?.let {
                    "$it unit${if (it == 1) "" else "s"}"
                } ?: "Details unavailable"}",
                style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
            )
            Text(
                "${request.matchesResponded} donor response${if (request.matchesResponded == 1) "" else "s"} · ${request.notificationsCreated} notified",
                style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
            )
            request.facilityName?.let {
                Text(
                    "$it${request.area?.let { area ->
                        " · $area"
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
    onSwitchRole: () -> Unit,
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
            LifeLinkPageHeader("LifeLink")
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onSwitchRole, modifier = Modifier.testTag("role-switcher").widthIn(max = 200.dp)) {
                Text(
                    if (role == UserRole.DONOR) "Switch to requester" else "Switch to donor",
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
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
                    Text(
                        "Your requests",
                        Modifier.weight(1f).semantics { heading() },
                        style = MaterialTheme.typography.titleMedium,
                    )
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
                                    "Loading your requests…"
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
                    Text("Create emergency request", fontWeight = FontWeight.SemiBold)
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Recent activity",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.semantics { heading() },
                )
                if (updates.isEmpty()) {
                    LifeLinkEmptyState(
                        icon = Icons.Default.NotificationsNone,
                        title = "No updates yet",
                        body = "Donor responses and request updates will appear here.",
                    )
                } else {
                    updates.take(3).forEach { update ->
                        ListItem(
                            headlineContent = {
                                Text(
                                    update.title,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                    fontWeight = if (update.isRead) FontWeight.Normal else FontWeight.SemiBold,
                                )
                            },
                            supportingContent = { Text(update.body, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                            leadingContent = {
                                Surface(
                                    modifier = Modifier.size(8.dp),
                                    shape = CircleShape,
                                    color = if (update.isRead) Color.Transparent else MaterialTheme.colorScheme.primary,
                                ) {}
                            },
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
            Text(
                active.status.label,
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.semantics { heading() },
            )
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
@Preview(name = "Home · light", widthDp = 360, heightDp = 820, showBackground = true)
@Preview(name = "Home · dark", widthDp = 360, heightDp = 820, uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun HomePreview() {
    LifeLinkTheme { HomeContent(EmergencyRequestUiState(), DonorUiState(), UserRole.REQUESTER, {}, {}, {}, {}, emptyList(), {}, {}) }
}

/** Summarizes donor availability and request count with an action to open donor mode. */
@Composable
private fun DonorDashboardSummary(profile: com.lifelink.app.domain.DonorProfile, requestCount: Int, onOpen: () -> Unit) {
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
                    modifier = Modifier.weight(1f).padding(end = 12.dp).semantics { heading() },
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

/** Shows introductory guidance and delegates request creation or donor navigation to the caller. */
@Composable
private fun StartContent(role: UserRole, onGetStarted: () -> Unit, onCreateRequest: () -> Unit, onOpenDonor: () -> Unit) {
    Surface(color = androidx.compose.material3.MaterialTheme.colorScheme.background) {
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 28.dp, vertical = 32.dp),
            verticalArrangement = Arrangement.spacedBy(32.dp),
        ) {
            StartHeader(role)
            StartActions(role = role, onGetStarted = onGetStarted, onCreateRequest = onCreateRequest, onOpenDonor = onOpenDonor)
        }
    }
}

/** Brand mark, headline, and supporting copy at the top of the start screen. */
@Composable
private fun StartHeader(role: UserRole) {
    Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(
                modifier =
                    Modifier
                        .size(36.dp)
                        .background(
                            brush =
                                Brush.linearGradient(
                                    listOf(
                                        MaterialTheme.colorScheme.primary,
                                        MaterialTheme.colorScheme.tertiary,
                                    ),
                                ),
                            shape = CircleShape,
                        ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Default.Favorite,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(20.dp),
                )
            }
            Text(
                "LifeLink",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
        }
        Text(
            if (role == UserRole.DONOR) {
                "Set up your donor profile"
            } else {
                "Find nearby blood donors"
            },
            style = androidx.compose.material3.MaterialTheme.typography.headlineSmall,
            modifier = Modifier.semantics { heading() },
        )
        Text(
            "Use approximate locations, consent-based contact, and clear request status. LifeLink supports coordination; hospitals and blood banks remain responsible for screening and care.",
            color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
            style = androidx.compose.material3.MaterialTheme.typography.bodyLarge,
        )
    }
}

/** Primary/secondary actions and the role-specific reassurance line. */
@Composable
private fun StartActions(role: UserRole, onGetStarted: () -> Unit, onCreateRequest: () -> Unit, onOpenDonor: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Button(
            onClick = if (role == UserRole.DONOR) onOpenDonor else onCreateRequest,
            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
            shape = MaterialTheme.shapes.medium,
        ) {
            Icon(Icons.Default.Favorite, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text(if (role == UserRole.DONOR) "Open donor workspace" else "Create a request")
        }
        androidx.compose.material3.OutlinedButton(onClick = onGetStarted, modifier = Modifier.fillMaxWidth()) {
            Text("Go to Home")
        }
        Text(
            if (role == UserRole.DONOR) {
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

/** Routes the selected settings section to profile, appearance, security, or guidance content. */
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
    onBecomeDonor: () -> Unit,
    onSignOut: () -> Unit,
) {
    var section by rememberSaveable { mutableStateOf(SettingsSection.PROFILE) }
    val sections =
        listOf(
            SettingsSection.PROFILE,
            SettingsSection.LEGAL,
            SettingsSection.THEME,
            SettingsSection.SECURITY,
        )
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 20.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Profile", style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
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
            SettingsSection.LEGAL -> LegalContent()
            SettingsSection.THEME -> ThemeContent(themeMode, onThemeModeChange)
            SettingsSection.SECURITY -> SecurityContent(onRequestPasswordReset)
        }
    }
}

/** Displays accessible theme choices and reports the selected mode through [onThemeModeChange]. */
@Composable private fun ThemeContent(themeMode: ThemeMode, onThemeModeChange: (ThemeMode) -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .selectableGroup()
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            "Appearance",
            style = androidx.compose.material3.MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.semantics { heading() },
        )
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
        Text(
            "Security",
            style = androidx.compose.material3.MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.semantics { heading() },
        )
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
            Modifier
                .fillMaxWidth()
                .clickable(role = Role.Button) { showSessionHelp = true },
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

/** Displays product limitations, emergency guidance, and legal and safety information. */
@Composable private fun LegalContent() {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            "Legal & Safety Center",
            style = androidx.compose.material3.MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.semantics { heading() },
        )
        Text(
            "Product guidance for a Philippines-oriented service. This is not legal or medical advice; counsel, clinicians, and licensed blood-service partners must review the final release.",
            color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
        )
        EmergencyHelpCard()
        LegalSection(
            "How matching works",
            "Matching considers blood-type compatibility, donor availability, service radius, approximate distance, travel estimate, and urgency. A match is not medical approval; confirm compatibility with a blood-bank professional.",
        )
        LegalSection(
            "LifeLink’s role",
            "LifeLink is a coordination and contact service. It helps describe a need, discover potential voluntary donors, and manage consented contact. It does not confirm that a request is genuine, urgent, fulfilled, safe, or medically appropriate.",
        )
        LegalSection(
            "Clinical and blood-service boundary",
            "LifeLink is not a hospital, blood bank, collection unit, laboratory, ambulance, emergency dispatcher, or medical advice service. It does not screen or approve donors, collect or test blood, determine compatibility, store or transport blood, or guarantee supply. Licensed facilities and qualified clinicians make those decisions.",
        )
        LegalSection(
            "Voluntary donation",
            "Donation must be voluntary and free from pressure. Do not sell blood, request deposits or replacement fees, offer honoraria, demand money, or condition contact on gifts, sex, services, employment, or medical treatment.",
        )
        LegalSection(
            "Consent and conduct",
            "A match is an invitation to communicate, not consent to donate. Do not impersonate anyone, create fake emergencies, harass, threaten, stalk, doxx, share non-consensual sexual material, provide medical misinformation, forge records, or claim that a donor is tested, safe, or compatible. Use Report and Block when available.",
        )
        LegalSection(
            "Privacy and health information",
            "Blood type, emergency details, health-related messages, location, and contact details are personal information. Share only what is necessary. Do not post diagnoses, test results, patient names, IDs, passwords, one-time codes, financial credentials, exact addresses, or live location. Privacy requests and consent changes should use the support channel configured for this deployment.",
        )
        LegalSection(
            "User responsibilities",
            "Provide truthful information, post only requests you are authorized to make, respect consent, follow hospital or blood-service instructions, and verify real-world arrangements independently. LifeLink does not control off-platform transport, payment, donation, transfusion, or clinical decisions, subject to rights and responsibilities that cannot lawfully be excluded.",
        )
        LegalSourcesSection()
        Text(
            "Product guidance v1.0 · Review before production launch.",
            style = androidx.compose.material3.MaterialTheme.typography.labelMedium,
            color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Heading plus the official-guidance source links. */
@Composable private fun LegalSourcesSection() {
    Text(
        "Official guidance",
        style = androidx.compose.material3.MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.semantics { heading() },
    )
    SourceLink("WHO · Blood safety and availability", "https://www.who.int/news-room/fact-sheets/detail/blood-safety-and-availability")
    SourceLink("Philippines · National Blood Services Act (RA 7719)", "https://lawphil.net/statutes/repacts/ra1994/ra_7719_1994.html")
    SourceLink("Philippines · Data Privacy Act (RA 10173)", "https://privacy.gov.ph/data-privacy-act/")
    SourceLink(
        "Philippines · Unified emergency hotline information",
        "https://dilg.gov.ph/news/One-Number-for-All-Emergencies-Unified-911-to-Launch-Nationwide/NC-2025-1177",
    )
}

/** Displays urgent-care guidance and opens the system dialer with 911 when requested. */
@Composable private fun EmergencyHelpCard() {
    val context = LocalContext.current
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = androidx.compose.material3.MaterialTheme.colorScheme.errorContainer),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                "Emergency help",
                style = androidx.compose.material3.MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = androidx.compose.material3.MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.semantics { heading() },
            )
            Text(
                "If someone is unconscious, severely bleeding, having trouble breathing, showing signs of shock, or getting worse, call 911 or go to the nearest emergency department now. Do not wait for a LifeLink match.",
                color = androidx.compose.material3.MaterialTheme.colorScheme.onErrorContainer,
            )
            Button(
                onClick = { context.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:911"))) },
                modifier = Modifier.heightIn(min = 48.dp),
            ) {
                Icon(Icons.Default.Phone, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Call 911")
            }
        }
    }
}

@Composable private fun LegalSection(title: String, body: String) {
    Card(Modifier.fillMaxWidth(), elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Text(
                title,
                style = androidx.compose.material3.MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.semantics { heading() },
            )
            Text(body, color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable private fun SourceLink(label: String, url: String) {
    val context = LocalContext.current
    TextButton(
        onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) },
        modifier = Modifier.fillMaxWidth(),
    ) {
        Icon(Icons.Default.OpenInNew, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(label, modifier = Modifier.weight(1f))
    }
}

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
                            "Account ID · ${accountUserId.take(8)}…",
                            style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                            color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    if (role == UserRole.REQUESTER) "Requester details" else "Profile details",
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.semantics { heading() },
                )
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
                ) { Text(if (profileSaving) "Saving…" else "Save profile") }
                profileMessage?.let { Text(it, color = androidx.compose.material3.MaterialTheme.colorScheme.primary) }
            }
        }
        Card(Modifier.fillMaxWidth(), elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Donor capability", fontWeight = FontWeight.Bold, modifier = Modifier.semantics { heading() })
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
