package com.lifelink.app.core.navigation

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.text.format.DateUtils
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.NotificationsNone
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
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
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.WindowSizeClass
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.paneTitle
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
import com.lifelink.app.feature.auth.LegalDocument
import com.lifelink.app.feature.auth.LegalDocumentDialog
import com.lifelink.app.feature.auth.PrivacyPolicyPhilippines
import com.lifelink.app.feature.auth.TermsAndConditionsPhilippines
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

private enum class ShellTab(val label: String) {
    HOME("Home"),
    REQUESTS("Requests"),
    MAP("Donors"),
    UPDATES("Chats"),
    SETTINGS("Settings"),
}

private enum class SettingsSection(val label: String) {
    ACCOUNT("Account"),
    NOTIFICATIONS("Notifications"),
    PRIVACY("Privacy, Safety & Legal"),
    THEME("Theme"),
    HELP("Help & FAQ"),
}

/**
 * Renders account navigation and routes requester and donor actions to their screens.
 *
 * Opening a conversation records the request and donor IDs and shows [ConversationScreen],
 * which dispatches [PrivacyAction.OpenConversation] itself on composition (and after
 * process-death restoration); closing it returns to the screen that opened it.
 */
// This root composable intentionally coordinates shared navigation and saveable shell state.
// Keep feature UI in destination composables; extract routing/state only when ownership can move safely.
@Suppress("LongMethod", "CyclomaticComplexMethod", "ReturnCount")
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3WindowSizeClassApi::class)
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
    val screenStateHolder = rememberSaveableStateHolder()
    var showRequest by rememberSaveable { mutableStateOf(false) }
    var showActive by rememberSaveable { mutableStateOf(false) }
    var showDonor by rememberSaveable { mutableStateOf(false) }
    // Separate donor-profile flow: opt in to donating after account creation.
    var showBecomeDonor by rememberSaveable { mutableStateOf(false) }
    var showNotifications by rememberSaveable { mutableStateOf(false) }
    // The in-app conversation was previously unreachable: ConversationScreen and
    // PrivacyViewModel.openConversation existed, but nothing navigated to them.
    // This flag is the single entry point for both the requester (from a contact
    // card) and the donor (from an accepted request).
    var showConversation by rememberSaveable { mutableStateOf(false) }
    var conversationRequestId by rememberSaveable { mutableStateOf("") }
    var conversationDonorId by rememberSaveable { mutableStateOf("") }
    var conversationOpenedFromDonor by rememberSaveable { mutableStateOf(false) }
    val closeConversation = {
        // Restore the exact workspace that opened this conversation. Assign both
        // states explicitly so a stale donor flag cannot leak into requester flows
        // (or leave a donor returning to the shell after system Back).
        showDonor = conversationOpenedFromDonor
        showConversation = false
        conversationOpenedFromDonor = false
    }
    val openConversationFrom: (String, String, Boolean) -> Unit = { requestId, donorId, fromDonor ->
        conversationRequestId = requestId
        conversationDonorId = donorId
        conversationOpenedFromDonor = fromDonor
        if (fromDonor) showDonor = true
        showConversation = true
    }
    val openConversation: (String, String) -> Unit = { requestId, donorId ->
        openConversationFrom(requestId, donorId, false)
    }
    val openDonorConversation: (String, String) -> Unit = { requestId, donorId ->
        openConversationFrom(requestId, donorId, true)
    }
    var showStart by rememberSaveable(accountUserId) {
        mutableStateOf(
            accountUserId.isNotBlank() && !welcomePrefs.getBoolean("seen_$accountUserId", false),
        )
    }
    var tab by rememberSaveable { mutableStateOf(ShellTab.HOME) }
    var donorSelectedTab by rememberSaveable(accountUserId) { mutableStateOf("HOME") }
    val markWelcomeSeen = {
        if (accountUserId.isNotBlank()) welcomePrefs.edit().putBoolean("seen_$accountUserId", true).apply()
        showStart = false
    }
    // Route changes must close any competing overlay. This keeps a stale request,
    // notification, or conversation flag from masking the donor workspace.
    val openDonorWorkspace = {
        markWelcomeSeen()
        showRequest = false
        showActive = false
        showBecomeDonor = false
        showNotifications = false
        showConversation = false
        conversationOpenedFromDonor = false
        conversationRequestId = ""
        conversationDonorId = ""
        showDonor = true
    }
    LaunchedEffect(notificationRequestId) {
        notificationRequestId?.takeIf { it.isNotBlank() }?.let { destination ->
            showStart = false
            when {
                destination.startsWith("conversation:") -> {
                    showActive = false
                    showNotifications = false
                    tab = ShellTab.UPDATES
                }
                destination.startsWith("request:") -> {
                    showActive = true
                    onAction(EmergencyRequestAction.OpenRequest(destination.substringAfter("request:").substringBefore("|")))
                }
                else -> {
                    showActive = true
                    onAction(EmergencyRequestAction.OpenRequest(destination))
                }
            }
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
            hasActiveRequest = state.hasActiveRequest,
            canCreateRequest = state.historyLoaded && !state.historyRefreshing &&
                state.historyError == null && !state.hasActiveRequest,
            onGetStarted = markWelcomeSeen,
            onCreateRequest = {
                markWelcomeSeen()
                showRequest = true
            },
            onOpenActiveRequest = {
                markWelcomeSeen()
                showActive = true
            },
            onOpenDonor = openDonorWorkspace,
        )
        return
    }
    if (showConversation && conversationRequestId.isNotBlank() && conversationDonorId.isNotBlank()) {
        BackHandler { closeConversation() }
        ConversationScreen(
            state = privacyState,
            requestId = conversationRequestId,
            donorId = conversationDonorId,
            currentUserId = accountUserId,
            onAction = onPrivacyAction,
            onBack = { closeConversation() },
        )
        return
    }
    if (showNotifications) {
        BackHandler { showNotifications = false }
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background,
        ) {
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
                onBack = { showNotifications = false },
            )
        }
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
            requestDetails = state.requestHistory.firstOrNull { it.requestId == activeRequestId },
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
        screenStateHolder.SaveableStateProvider("donor-workspace") {
            DonorScreen(
                state = donorState,
                onAction = onDonorAction,
                onBack = { showDonor = false },
                onOpenConversation = openDonorConversation,
                selectedTabKey = donorSelectedTab,
                onSelectedTabKeyChange = { donorSelectedTab = it },
            )
        }
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
                if (tab != ShellTab.MAP) {
                    Row(
                        Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 12.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Surface(
                            modifier = Modifier.size(36.dp),
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.primaryContainer,
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    Icons.Default.Shield,
                                    contentDescription = "LifeLink app",
                                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                    modifier = Modifier.size(20.dp),
                                )
                            }
                        }
                        Text(
                            tab.label,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(start = 8.dp),
                        )
                        Spacer(Modifier.weight(1f))
                        IconButton(
                            onClick = { showNotifications = true },
                            modifier = Modifier.testTag("open-notifications"),
                        ) {
                            BadgedBox(badge = {
                                if (unreadUpdates > 0) {
                                    Badge { Text(if (unreadUpdates > 99) "99+" else unreadUpdates.toString()) }
                                }
                            }) {
                                Icon(
                                    Icons.Default.NotificationsNone,
                                    contentDescription = if (unreadUpdates > 0) "$unreadUpdates unread notifications" else "Notifications",
                                )
                            }
                        }
                        IconButton(
                            onClick = { tab = ShellTab.SETTINGS },
                            modifier = Modifier.testTag("open-profile"),
                        ) {
                            Icon(Icons.Default.Person, contentDescription = "Open profile")
                        }
                    }
                }
            },
            bottomBar = {
                if (!useRail) {
                    NavigationBar(containerColor = MaterialTheme.colorScheme.surface, tonalElevation = 0.dp) {
                        ShellTab.entries.forEach { destination ->
                            ShellNavigationBarItem(destination, tab == destination) {
                                tab = destination
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
                                HomeContent(state, donorState, role, onCreate = { showRequest = true }, onActive = {
                                    showActive =
                                        true
                                }, onDonor = openDonorWorkspace, updates = updates, onRequests = {
                                    tab =
                                        ShellTab.REQUESTS
                                }, onOpenUpdate = { update ->
                                    onUpdateRead(update.id)
                                    update.requestId?.let { requestId ->
                                        onAction(EmergencyRequestAction.OpenRequest(requestId))
                                        showActive = true
                                    } ?: run { showNotifications = true }
                                }, onRefreshHistory = {
                                    onAction(EmergencyRequestAction.RefreshHistory)
                                })
                            ShellTab.REQUESTS ->
                                RequestsContent(state, onAction = onAction, onCreate = { showRequest = true }, onOpen = {
                                    showRequest =
                                        true
                                }, onActive = { showActive = true })
                            ShellTab.MAP ->
                                DonorMapScreen(
                                    state = privacyState,
                                    onAction = onPrivacyAction,
                                    onBack = { tab = ShellTab.HOME },
                                    eligibleDonors = state.discoveredDonors,
                                    onRequestChat = state.activeRequest?.requestId?.let { requestId ->
                                        { donor -> openConversation(requestId, donor.donorId) }
                                    },
                                    centerLatitude = state.draft.requesterLatitude,
                                    centerLongitude = state.draft.requesterLongitude,
                                    showCloseButton = false,
                                    embeddedInShell = true,
                                )
                            ShellTab.UPDATES ->
                                MessagingScreen(
                                    state = privacyState,
                                    onAction = onPrivacyAction,
                                    onOpenConversation = openConversation,
                                    targetConversationId = notificationRequestId
                                        ?.takeIf { it.startsWith("conversation:") }
                                        ?.substringAfter("conversation:")
                                        ?.substringBefore("|"),
                                    targetConversationEventId = notificationRequestId
                                        ?.takeIf { it.startsWith("conversation:") }
                                        ?.substringAfter("|"),
                                )
                            ShellTab.SETTINGS ->
                                SettingsContent(
                                    role,
                                    accountEmail,
                                    accountDisplayName,
                                    profileSaving,
                                    profileMessage,
                                    onSaveProfile,
                                    themeMode,
                                    onThemeModeChange,
                                    onRequestPasswordReset,
                                    onSwitchRole,
                                    onOpenDonor = openDonorWorkspace,
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

/** Renders one labelled top-level destination in the app navigation. */
@Composable
private fun RowScope.ShellNavigationBarItem(tab: ShellTab, selected: Boolean, onClick: () -> Unit) {
    val isPrimaryMap = tab == ShellTab.MAP
    NavigationBarItem(
        selected = selected,
        onClick = onClick,
        icon = {
            if (isPrimaryMap) {
                // The map is LifeLink's core matching surface, so give the centered
                // destination a clear visual anchor without changing navigation order.
                Surface(
                    modifier = Modifier.size(48.dp).testTag("primary-map-destination"),
                    shape = CircleShape,
                    color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.primaryContainer,
                    contentColor = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onPrimaryContainer,
                    tonalElevation = if (selected) 3.dp else 1.dp,
                    shadowElevation = if (selected) 3.dp else 0.dp,
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        ShellNavigationIcon(tab, size = 25.dp)
                    }
                }
            } else {
                ShellNavigationIcon(tab)
            }
        },
        label = {
            Text(
                tab.label,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontWeight = if (isPrimaryMap) FontWeight.Bold else FontWeight.Medium,
            )
        },
        modifier = Modifier.weight(1f).testTag("nav-${tab.name}"),
    )
}

/** Renders a consistent navigation icon with optional emphasis for the primary map destination. */
@Composable
private fun ShellNavigationIcon(tab: ShellTab, size: androidx.compose.ui.unit.Dp = 24.dp) {
    val icon =
        when (tab) {
            ShellTab.HOME -> Icons.Default.Home
            ShellTab.REQUESTS -> Icons.AutoMirrored.Filled.Assignment
            ShellTab.MAP -> Icons.Default.Map
            ShellTab.UPDATES -> Icons.AutoMirrored.Filled.Chat
            ShellTab.SETTINGS -> Icons.Default.Settings
        }
    Icon(icon, contentDescription = null, modifier = Modifier.size(size))
}

/** Shows request history with actions to create, resume, or view the current request. */
@Suppress("CyclomaticComplexMethod", "LongMethod")
@Composable
private fun RequestsContent(
    state: EmergencyRequestUiState,
    onAction: (EmergencyRequestAction) -> Unit,
    onCreate: () -> Unit,
    onOpen: () -> Unit,
    onActive: () -> Unit,
) {
    var selectedRequest by remember { mutableStateOf<RequestHistoryItem?>(null) }
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var historyFilter by rememberSaveable { mutableStateOf("All") }
    val terminalStatuses = remember {
        setOf(
            com.lifelink.app.domain.ActiveRequestStatus.FULFILLED,
            com.lifelink.app.domain.ActiveRequestStatus.EXPIRED,
            com.lifelink.app.domain.ActiveRequestStatus.CANCELLED,
        )
    }
    val visibleHistory = state.requestHistory.filter { request ->
        val query = searchQuery.trim().lowercase()
        val matchesQuery = query.isBlank() ||
            listOfNotNull(
                request.requestId,
                request.bloodType,
                request.urgency,
                request.facilityName,
                request.area,
                request.status.label,
            ).any { it.lowercase().contains(query) }
        val matchesFilter = when (historyFilter) {
            "Active" -> request.status !in terminalStatuses
            "Past" -> request.status in terminalStatuses
            else -> true
        }
        matchesQuery && matchesFilter
    }
    selectedRequest?.let { request ->
        BackHandler { selectedRequest = null }
        RequestHistoryDetailScreen(
            request = request,
            onBack = { selectedRequest = null },
            onOpenStatus = {
                selectedRequest = null
                onAction(EmergencyRequestAction.OpenRequest(request.requestId))
            },
        )
        return
    }
    val showEmptyHistory = state.historyLoaded &&
        visibleHistory.isEmpty() &&
        !state.historyRefreshing &&
        state.historyError == null
    Box(Modifier.fillMaxSize()) {
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
                            Text(
                                "Matching results ready",
                                style = MaterialTheme.typography.titleLarge,
                                modifier = Modifier.semantics { heading() },
                            )
                            Text("Review nearby donors and choose who to contact.")
                            Button(
                                onClick = onOpen,
                                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                            ) { Text("Open matching results") }
                        }
                    }
                } else if (state.activeRequest != null) {
                    ActiveRequestSummary(state, onActive)
                } else {
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
                        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                if (!state.historyLoaded || state.historyRefreshing) {
                                    "Loading your requests…"
                                } else if (state.historyError !=
                                    null
                                ) {
                                    "Requests unavailable"
                                } else {
                                    "No active request"
                                },
                                style = MaterialTheme.typography.titleMedium,
                                modifier = Modifier.semantics { heading() },
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
                if (state.hasActiveRequest) {
                    OutlinedButton(
                        onClick = onActive,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                    ) {
                        Text("Manage active request", fontWeight = FontWeight.SemiBold)
                    }
                } else {
                    Button(
                        onClick = onCreate,
                        enabled = state.historyLoaded && !state.historyRefreshing && state.historyError == null,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                        shape = MaterialTheme.shapes.medium,
                    ) {
                        Icon(Icons.Default.Add, null, Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Create emergency request", fontWeight = FontWeight.SemiBold)
                    }
                    if (!state.historyLoaded || state.historyRefreshing || state.historyError != null) {
                        Text(
                            if (!state.historyLoaded || state.historyRefreshing) {
                                "Checking request status before allowing a new request."
                            } else {
                                "Refresh your request history before starting another request."
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            item {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "Request history",
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.weight(1f).semantics { heading() },
                    )
                    // The header action is always a refresh; the error card below owns the single
                    // "Retry" affordance so the screen never shows two identically-labelled actions.
                    TextButton(onClick = { onAction(EmergencyRequestAction.RefreshHistory) }, enabled = !state.historyRefreshing) {
                        Text(if (state.historyRefreshing) "Refreshing…" else "Refresh")
                    }
                }
            }
            item {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    modifier = Modifier.fillMaxWidth().testTag("request-history-search"),
                    label = { Text("Search requests") },
                    placeholder = { Text("Blood type, facility, status, or ID") },
                    singleLine = true,
                )
            }
            item {
                Row(
                    Modifier
                        .testTag("request-history-filters")
                        .semantics { paneTitle = "Request history filters" },
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    listOf("All", "Active", "Past").forEach { filter ->
                        FilterChip(
                            selected = historyFilter == filter,
                            onClick = { historyFilter = filter },
                            label = { Text(filter) },
                            modifier = Modifier.testTag("request-filter-${filter.lowercase()}"),
                        )
                    }
                }
            }
            val activeFilterCount =
                (if (historyFilter != "All") 1 else 0) +
                    (if (searchQuery.isNotBlank()) 1 else 0)
            if (activeFilterCount > 0) {
                item {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "$activeFilterCount active ${if (activeFilterCount == 1) "filter" else "filters"}",
                            modifier = Modifier
                                .weight(1f)
                                .testTag("request-history-filter-summary")
                                .semantics { liveRegion = LiveRegionMode.Polite },
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        TextButton(
                            onClick = {
                                historyFilter = "All"
                                searchQuery = ""
                            },
                        ) { Text("Clear filters") }
                    }
                }
            }
            item {
                Text(
                    "${visibleHistory.size} ${if (visibleHistory.size == 1) "request" else "requests"} found",
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("request-history-result-count")
                        .semantics { liveRegion = LiveRegionMode.Polite },
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
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
            if (showEmptyHistory) {
                item {
                    LifeLinkEmptyState(
                        icon = Icons.AutoMirrored.Filled.Assignment,
                        title = if (state.requestHistory.isEmpty() && activeFilterCount == 0) {
                            "No requests yet"
                        } else {
                            "No requests match these filters"
                        },
                        body = if (state.requestHistory.isEmpty() && activeFilterCount == 0) {
                            "Your request history will appear here after you create your first request."
                        } else {
                            "Clear the active filters or try a different search term."
                        },
                    )
                }
            }
            items(visibleHistory, key = { it.requestId }) { request ->
                RequestHistoryCard(request, onOpen = { selectedRequest = request })
            }
        }
    }
}

/** Summarizes a saved request and labels terminal requests as past requests. */
@Composable
@Suppress("LongMethod")
private fun RequestHistoryCard(request: RequestHistoryItem, onOpen: () -> Unit) {
    Card(
        Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClickLabel = "View request details", onClick = onOpen)
            .testTag("request-history-${request.requestId}"),
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
            request.createdAt?.takeIf { it.isNotBlank() }?.let {
                Text("Created $it", style = MaterialTheme.typography.bodySmall)
            }
            request.expiresAt?.takeIf { it.isNotBlank() }?.let {
                Text("Response deadline $it", style = MaterialTheme.typography.bodySmall)
            }
            Text(
                "${request.bloodType ?: "Request"} · ${request.units?.let {
                    "$it unit${if (it == 1) "" else "s"}"
                } ?: "Details unavailable"}",
                style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
            )
            Text(
                "${request.matchesResponded} donor response${if (request.matchesResponded == 1) "" else "s"} · " +
                    "${request.notificationsCreated} notified",
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
            Text(
                "View request details",
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold,
                style = MaterialTheme.typography.labelLarge,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RequestHistoryDetailScreen(
    request: RequestHistoryItem,
    onBack: () -> Unit,
    onOpenStatus: () -> Unit,
) {
    Scaffold(
        topBar = {
            androidx.compose.material3.TopAppBar(
                title = { Text("Request details") },
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
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Card(
                Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
            ) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(request.status.label, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text(
                        if (
                            request.status in setOf(
                                com.lifelink.app.domain.ActiveRequestStatus.FULFILLED,
                                com.lifelink.app.domain.ActiveRequestStatus.EXPIRED,
                                com.lifelink.app.domain.ActiveRequestStatus.CANCELLED,
                            )
                        ) {
                            "Past request"
                        } else {
                            "Active request"
                        },
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
            }
            RequestDetailsContent(request)
            Button(onClick = onOpenStatus, modifier = Modifier.fillMaxWidth()) {
                Text("Open request status")
            }
            OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
                Text("Back to requests")
            }
        }
    }
}

@Composable
private fun RequestDetailsContent(request: RequestHistoryItem) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(request.status.label, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
        DetailRow("Request ID", request.requestId.take(16))
        request.createdAt?.takeIf { it.isNotBlank() }?.let { DetailRow("Created", it) }
        request.expiresAt?.takeIf { it.isNotBlank() }?.let { DetailRow("Response deadline", it) }
        request.bloodType?.let { DetailRow("Blood type", it) }
        request.units?.let { DetailRow("Units", "$it") }
        request.urgency?.let { DetailRow("Urgency", it) }
        request.facilityName?.let { DetailRow("Facility", it) }
        request.area?.let { DetailRow("Area", it) }
        DetailRow("Donors notified", request.notificationsCreated.toString())
        DetailRow("Responses", request.matchesResponded.toString())
        if (request.contactStatuses.isNotEmpty()) {
            DetailRow("Contact status", request.contactStatuses.joinToString())
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

/** Displays the account dashboard with request, donor, and activity navigation actions. */
@Suppress("LongParameterList")
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
    onOpenUpdate: (UpdateItem) -> Unit,
    onRefreshHistory: () -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LifeLinkPageHeader("Home")
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            if (role == UserRole.DONOR) {
                DonorDashboardSummary(donorState.profile, donorState.requests.size, onDonor)
            }
            if (role == UserRole.REQUESTER) {
                RequesterHomeSection(state, onCreate, onActive, onRequests, onRefreshHistory)
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            HomeRecentActivitySection(updates, onOpenUpdate)
        }
    }
}

/** Groups the request summary and the single permitted next action for requesters. */
@Composable
private fun RequesterHomeSection(
    state: EmergencyRequestUiState,
    onCreate: () -> Unit,
    onActive: () -> Unit,
    onRequests: () -> Unit,
    onRefreshHistory: () -> Unit,
) {
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
            RequestHistoryStatusCard(state, onRefreshHistory)
        }
        if (state.hasActiveRequest) {
            OutlinedButton(
                onClick = onActive,
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
            ) {
                Text("Manage active request", fontWeight = FontWeight.SemiBold)
            }
            Text(
                "You can start another request after this one is completed or cancelled.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Button(
                onClick = onCreate,
                enabled = state.historyLoaded && !state.historyRefreshing && state.historyError == null,
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                shape = MaterialTheme.shapes.medium,
            ) {
                Icon(Icons.Default.Add, null, Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text("Create emergency request", fontWeight = FontWeight.SemiBold)
            }
            if (!state.historyLoaded || state.historyRefreshing || state.historyError != null) {
                Text(
                    if (!state.historyLoaded || state.historyRefreshing) {
                        "Checking request status before allowing a new request."
                    } else {
                        "Refresh your request history before starting another request."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** Shows a loading, error, or empty message while request history is restored. */
@Composable
private fun RequestHistoryStatusCard(state: EmergencyRequestUiState, onRefreshHistory: () -> Unit) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                if (!state.historyLoaded || state.historyRefreshing) {
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
                    ?: if (!state.historyLoaded || state.historyRefreshing) {
                        "Checking your account for saved requests."
                    } else {
                        "Create a request to find nearby blood donors."
                    },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (state.historyError != null) {
                TextButton(onClick = onRefreshHistory, enabled = !state.historyRefreshing) { Text("Retry") }
            }
        }
    }
}

/** Shows the most recent activity with concise previews and clear unread status. */
@Composable
private fun HomeRecentActivitySection(updates: List<UpdateItem>, onOpenUpdate: (UpdateItem) -> Unit) {
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
                    supportingContent = {
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(update.body, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(
                                DateUtils.getRelativeTimeSpanString(
                                    update.createdAtEpochMillis,
                                    System.currentTimeMillis(),
                                    DateUtils.MINUTE_IN_MILLIS,
                                ).toString(),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    },
                    trailingContent = {
                        if (!update.isRead) {
                            Text(
                                "Unread",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    },
                    leadingContent = {
                        Surface(
                            modifier = Modifier.size(8.dp),
                            shape = CircleShape,
                            color = if (update.isRead) Color.Transparent else MaterialTheme.colorScheme.primary,
                        ) {}
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(
                            onClickLabel = "Open update: ${update.title}",
                            role = Role.Button,
                            onClick = { onOpenUpdate(update) },
                        )
                        .testTag("home-update-${update.id}"),
                )
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
    LifeLinkTheme { HomeContent(EmergencyRequestUiState(), DonorUiState(), UserRole.REQUESTER, {}, {}, {}, emptyList(), {}, {}, {}) }
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
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
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
private fun StartContent(
    role: UserRole,
    hasActiveRequest: Boolean,
    canCreateRequest: Boolean,
    onGetStarted: () -> Unit,
    onCreateRequest: () -> Unit,
    onOpenActiveRequest: () -> Unit,
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
            StartHeader(role)
            StartActions(
                role = role,
                hasActiveRequest = hasActiveRequest,
                canCreateRequest = canCreateRequest,
                onGetStarted = onGetStarted,
                onCreateRequest = onCreateRequest,
                onOpenActiveRequest = onOpenActiveRequest,
                onOpenDonor = onOpenDonor,
            )
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
                    Icons.Default.Shield,
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
            "Use approximate locations, consent-based contact, and clear request status. " +
                "LifeLink supports coordination; hospitals and blood banks remain responsible for screening and care.",
            color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
            style = androidx.compose.material3.MaterialTheme.typography.bodyLarge,
        )
    }
}

/** Primary/secondary actions and the role-specific reassurance line. */
@Composable
private fun StartActions(
    role: UserRole,
    hasActiveRequest: Boolean,
    canCreateRequest: Boolean,
    onGetStarted: () -> Unit,
    onCreateRequest: () -> Unit,
    onOpenActiveRequest: () -> Unit,
    onOpenDonor: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (role == UserRole.REQUESTER && hasActiveRequest) {
            OutlinedButton(
                onClick = onOpenActiveRequest,
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
            ) { Text("View active request") }
        } else {
            Button(
                onClick = if (role == UserRole.DONOR) onOpenDonor else onCreateRequest,
                enabled = role == UserRole.DONOR || canCreateRequest,
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                shape = MaterialTheme.shapes.medium,
            ) {
                Icon(
                    if (role == UserRole.DONOR) Icons.Default.Person else Icons.Default.Add,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(if (role == UserRole.DONOR) "Open donor workspace" else "Create a request")
            }
            if (role == UserRole.REQUESTER && !canCreateRequest) {
                Text(
                    "Checking your request status. You can create a request after your history loads.",
                    modifier = Modifier.fillMaxWidth(),
                    color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
                    style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                )
            }
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

/** Routes the selected settings section to account, notifications, privacy, appearance, or guidance. */
@Suppress("LongParameterList", "LongMethod")
@Composable
private fun SettingsContent(
    role: UserRole,
    accountEmail: String,
    accountDisplayName: String,
    profileSaving: Boolean,
    profileMessage: String?,
    onSaveProfile: (String) -> Unit,
    themeMode: ThemeMode,
    onThemeModeChange: (ThemeMode) -> Unit,
    onRequestPasswordReset: ((String) -> Unit) -> Unit,
    onSwitchRole: () -> Unit,
    onOpenDonor: () -> Unit,
    onBecomeDonor: () -> Unit,
    onSignOut: () -> Unit,
) {
    var section by rememberSaveable { mutableStateOf(SettingsSection.ACCOUNT) }
    val sections =
        listOf(
            SettingsSection.ACCOUNT,
            SettingsSection.NOTIFICATIONS,
            SettingsSection.PRIVACY,
            SettingsSection.THEME,
            SettingsSection.HELP,
        )
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 20.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                "Profile",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.testTag("settings-screen-title").semantics { heading() },
            )
            Text(
                "Profile, notifications, privacy, safety, and app preferences.",
                color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .testTag("settings-sections"),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            sections.forEach { item ->
                FilterChip(
                    selected = section == item,
                    onClick = { section = item },
                    label = { Text(item.label, maxLines = 1) },
                    modifier = Modifier.testTag("settings-${item.name}"),
                )
            }
        }
        when (section) {
            SettingsSection.ACCOUNT ->
                ProfileContent(
                    role,
                    accountEmail,
                    accountDisplayName,
                    profileSaving,
                    profileMessage,
                    onSaveProfile,
                    onSwitchRole,
                    onOpenDonor,
                    onBecomeDonor,
                    onSignOut,
                )
            SettingsSection.NOTIFICATIONS -> NotificationSettingsContent()
            SettingsSection.PRIVACY -> PrivacySafetyContent(onRequestPasswordReset)
            SettingsSection.THEME -> ThemeContent(themeMode, onThemeModeChange)
            SettingsSection.HELP -> HelpFaqContent()
        }
    }
}

/** Answers common safety, matching, notification, and account-recovery questions. */
@Composable
private fun HelpFaqContent() {
    val faqs =
        listOf(
            "Is LifeLink an emergency service?" to
                "No. Call your local emergency number for immediate medical help. " +
                "LifeLink helps coordinate requests and donor connections; it does not replace " +
                "clinicians or emergency services.",
            "How does donor location privacy work?" to
                "The donor map uses approximate areas. Exact location is shared only in an authorized, " +
                "consent-based request flow and should never be treated as a public pin.",
            "When can I message another person?" to
                "Messaging is tied to a request and its connection state. Share phone or email details only " +
                "when you are comfortable and only through the available consent action.",
            "Why did I not receive a notification?" to
                "Check Android notification permission and device notification settings. Open Updates in " +
                "LifeLink to review durable activity even when a system notification was dismissed.",
            "How do I recover my account?" to
                "Use the password-reset action in Privacy, Safety & Legal. If you still cannot access " +
                "the account, contact the support channel provided by your deployment administrator.",
        )
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            "Help & FAQ",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.semantics { heading() },
        )
        Text(
            "Quick guidance for safer use of LifeLink.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        faqs.forEach { (question, answer) ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(question, fontWeight = FontWeight.SemiBold)
                    Text(answer, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

/** Opens Android's app-notification controls rather than storing a duplicate permission preference. */
@Composable
private fun NotificationSettingsContent() {
    val context = LocalContext.current
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            "Notifications",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.semantics { heading() },
        )
        Text(
            "Choose whether LifeLink can send notifications from Android app settings. " +
                "Emergency request notifications use the device's notification permission and channel controls.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(
            onClick = {
                val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                } else {
                    Intent(
                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.fromParts("package", context.packageName, null),
                    )
                }
                context.startActivity(intent)
            },
            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
        ) {
            Text("Manage device notifications")
        }
        Text(
            "In-app activity remains available from the bell at the top of Home.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Summarizes existing privacy boundaries without implying controls the app does not yet expose here. */
@Composable
@Suppress("UnusedPrivateMember")
private fun PrivacySettingsContent() {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            "Privacy",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.semantics { heading() },
        )
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Donor locations", fontWeight = FontWeight.SemiBold)
                Text(
                    "The donor map shows approximate areas, not exact coordinates. " +
                        "Exact location sharing is request-scoped and requires donor consent.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Chat history", fontWeight = FontWeight.SemiBold)
                Text(
                    "Messages are stored on LifeLink servers and are readable by the two participants in that conversation. " +
                        "Offline message history and saved-message bookmarks are not currently available.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
@Suppress("LongMethod")
private fun PrivacySafetyContent(onRequestPasswordReset: ((String) -> Unit) -> Unit) {
    var legalDocument by remember { mutableStateOf<LegalDocument?>(null) }
    var resetMessage by rememberSaveable { mutableStateOf<String?>(null) }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            "Privacy, Safety & Legal",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.semantics { heading() },
        )
        Text(
            "Review what LifeLink shares, what you control, and which privacy requests still need support processing.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Location and matching", fontWeight = FontWeight.SemiBold)
                Text(
                    "The map uses approximate areas. Exact location and identity details are shared only " +
                        "in a consent-based request flow. " +
                        "Availability does not guarantee eligibility or a successful connection.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Card(Modifier.fillMaxWidth()) {
            Column(
                Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text("Messages and data", fontWeight = FontWeight.SemiBold)
                Text(
                    "Messages are visible to the participants in the conversation. " +
                        "Do not share passwords, payment details, or medical records in chat. " +
                        "Use the report and block actions when a connection is unsafe.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        PrivacyCapabilityCard(
            title = "Contact sharing",
            status = "Consent required",
            body =
            "Phone numbers and email addresses are not public donor-profile fields. Share contact details " +
                "only after you choose the available consent action with a matched participant.",
        )
        PrivacyCapabilityCard(
            title = "Notifications",
            status = "Controlled by Android",
            body =
            "LifeLink may send request and conversation updates when Android notification permission and " +
                "device settings allow it. In-app Updates remains the source of durable activity history.",
        )
        PrivacyCapabilityCard(
            title = "Retention summary",
            status = "Server policy required",
            body =
            "This build does not claim a user-configurable retention schedule. Request, profile, and message " +
                "retention enforcement must be defined and operated on the server before we describe a deletion date.",
        )
        PrivacyCapabilityCard(
            title = "Privacy requests",
            status = "Not available in-app yet",
            body =
            "Consent history, data export, account deletion requests, request status, and DPO processing are " +
                "not implemented in this build. Contact the support or privacy address supplied by your deployment administrator.",
        )
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Account security", fontWeight = FontWeight.SemiBold)
                Text(
                    "Signing out clears the local session on this device. " +
                        "If you suspect unauthorized access, reset your password and sign out.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedButton(
                    onClick = { onRequestPasswordReset { message -> resetMessage = message } },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Send password reset link") }
                resetMessage?.let {
                    Text(
                        it,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    )
                }
            }
        }
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Legal documents", fontWeight = FontWeight.SemiBold)
                LegalDocumentPicker(onSelect = { legalDocument = it })
            }
        }
        Text(
            "Need help? Contact the LifeLink support or privacy contact listed by your deployment administrator. " +
                "LifeLink is not a substitute for emergency medical services.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    legalDocument?.let { document ->
        LegalDocumentDialog(document = document, onDismiss = { legalDocument = null })
    }
}

@Composable
private fun PrivacyCapabilityCard(title: String, status: String, body: String) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(title, Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                Text(status, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium)
            }
            Text(body, color = MaterialTheme.colorScheme.onSurfaceVariant)
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

/** Buttons that open the full privacy policy and terms documents. */
@Composable
private fun LegalDocumentPicker(onSelect: (LegalDocument) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        OutlinedButton(onClick = { onSelect(PrivacyPolicyPhilippines) }, modifier = Modifier.weight(1f)) {
            Text("Privacy Policy")
        }
        OutlinedButton(onClick = { onSelect(TermsAndConditionsPhilippines) }, modifier = Modifier.weight(1f)) {
            Text("Terms and Conditions")
        }
    }
}

/**
 * Edits the account display name and exposes donor navigation and sign-out actions.
 * Donors invoke [onOpenDonor] to open their workspace; other roles invoke [onBecomeDonor] to start setup.
 */
@Suppress("LongParameterList", "LongMethod")
@Composable
private fun ProfileContent(
    role: UserRole,
    accountEmail: String,
    accountDisplayName: String,
    profileSaving: Boolean,
    profileMessage: String?,
    onSaveProfile: (String) -> Unit,
    onSwitchRole: () -> Unit,
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
                    Text(
                        accountDisplayName.ifBlank {
                            accountEmail.ifBlank { "LifeLink account" }
                        },
                        fontWeight = FontWeight.Bold,
                    )
                    if (accountDisplayName.isNotBlank() && accountEmail.isNotBlank()) {
                        Text(
                            accountEmail,
                            style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                            color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(
                        if (role == UserRole.DONOR) "Donor account" else "Requester account",
                        color = androidx.compose.material3.MaterialTheme.colorScheme.primary,
                    )
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
                profileMessage?.let {
                    Text(
                        it,
                        color = androidx.compose.material3.MaterialTheme.colorScheme.primary,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    )
                }
            }
        }
        Card(Modifier.fillMaxWidth(), elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Donor capability", fontWeight = FontWeight.Bold, modifier = Modifier.semantics { heading() })
                Text(
                    if (role == UserRole.DONOR) {
                        "Manage your availability and donor profile."
                    } else {
                        "You can request blood and offer to donate to other people. " +
                            "Your own requests will never appear as donor opportunities."
                    },
                    color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (role == UserRole.DONOR) {
                    OutlinedButton(
                        onClick = onOpenDonor,
                        modifier = Modifier.fillMaxWidth().testTag("open-donor-workspace"),
                    ) {
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
        OutlinedButton(
            onClick = onSwitchRole,
            modifier = Modifier.fillMaxWidth().testTag("profile-role-switcher"),
        ) {
            Text(if (role == UserRole.DONOR) "Switch to requester mode" else "Switch to donor mode")
        }
        androidx.compose.material3.OutlinedButton(onClick = onSignOut, modifier = Modifier.fillMaxWidth()) { Text("Sign out") }
    }
}
