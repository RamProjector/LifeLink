package com.lifelink.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.google.firebase.messaging.FirebaseMessaging
import com.lifelink.app.R
import com.lifelink.app.core.auth.RoleSwitcher
import com.lifelink.app.core.auth.UserRole
import com.lifelink.app.core.auth.UserRoleStore
import com.lifelink.app.core.navigation.LifeLinkShell
import com.lifelink.app.core.notifications.RequestNotificationPermissionIfNeeded
import com.lifelink.app.core.ui.theme.LifeLinkTheme
import com.lifelink.app.core.ui.theme.ThemeStore
import com.lifelink.app.data.remote.ProfileRequest
import com.lifelink.app.domain.UpdateType
import com.lifelink.app.feature.admin.AdminConsoleScreen
import com.lifelink.app.feature.auth.AuthScreen
import com.lifelink.app.feature.auth.AuthState
import com.lifelink.app.feature.auth.AuthViewModel
import com.lifelink.app.feature.auth.AuthViewModelFactory
import com.lifelink.app.feature.donor.BecomeDonorViewModel
import com.lifelink.app.feature.donor.BecomeDonorViewModelFactory
import com.lifelink.app.feature.donor.DonorViewModel
import com.lifelink.app.feature.donor.DonorViewModelFactory
import com.lifelink.app.feature.emergencyrequest.EmergencyRequestViewModel
import com.lifelink.app.feature.emergencyrequest.EmergencyRequestViewModelFactory
import com.lifelink.app.feature.privacy.PrivacyViewModel
import com.lifelink.app.feature.privacy.PrivacyViewModelFactory
import com.lifelink.app.feature.updates.UpdatesViewModel
import com.lifelink.app.feature.updates.UpdatesViewModelFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await

class MainActivity : ComponentActivity() {
    private fun notificationDestination(intent: Intent?): String? {
        val conversationId = intent?.getStringExtra("conversation_id")?.takeIf { it.isNotBlank() }
        val requestId = intent?.getStringExtra("request_id")?.takeIf { it.isNotBlank() }
        val eventId = intent?.getStringExtra("notification_event_id").orEmpty()
        return when {
            conversationId != null -> "conversation:$conversationId|$eventId"
            requestId != null -> "request:$requestId|$eventId"
            else -> null
        }
    }

    private var recoveryUri by mutableStateOf<Uri?>(null)
    private var notificationRequestId by mutableStateOf<String?>(null)
    private var notificationOpenUpdates by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        recoveryUri = intent?.data
        notificationRequestId = notificationDestination(intent)
        notificationOpenUpdates = intent?.getBooleanExtra("open_updates", false) == true
        enableEdgeToEdge()
        val app = application as LifeLinkApplication
        setContent {
            val themeStore = remember { ThemeStore(this@MainActivity) }
            var themeMode by remember { mutableStateOf(themeStore.get()) }
            LifeLinkTheme(themeMode = themeMode) {
                var showFakeBackendNotice by remember { mutableStateOf(BuildConfig.LIFELINK_FAKE_BACKEND) }
                if (showFakeBackendNotice) {
                    AlertDialog(
                        onDismissRequest = { showFakeBackendNotice = false },
                        title = { Text("UI test build — fake backend") },
                        text = {
                            Text(
                                "All accounts, requests, map pins, and messages are synthetic. " +
                                    "Changes do not reach LifeLink's real backend or Supabase.",
                            )
                        },
                        confirmButton = {
                            TextButton(onClick = { showFakeBackendNotice = false }) {
                                Text("Continue demo")
                            }
                        },
                    )
                }
                if (!BuildConfig.LIFELINK_FAKE_BACKEND) RequestNotificationPermissionIfNeeded()
                val authViewModel: AuthViewModel =
                    viewModel(
                        factory = AuthViewModelFactory(app.authRepository),
                    )
                LaunchedEffect(recoveryUri) { authViewModel.handleAuthCallback(recoveryUri) }
                val authState by authViewModel.state.collectAsStateWithLifecycle()
                val roleStore = remember { UserRoleStore(this@MainActivity) }
                if (authState !is AuthState.SignedIn) {
                    AuthScreen(
                        state = authState,
                        onSignIn = authViewModel::signIn,
                        onSignUp = authViewModel::signUp,
                        onPasswordReset = authViewModel::requestPasswordReset,
                        onResendConfirmation = authViewModel::resendConfirmation,
                        onUpdatePassword = authViewModel::updatePassword,
                    )
                    return@LifeLinkTheme
                }
                val roleSyncScope = rememberCoroutineScope()
                val signedInSession = (authState as AuthState.SignedIn).session
                val accountUserId = signedInSession.userId
                var roleLoadError by remember(accountUserId) { mutableStateOf<String?>(null) }
                var roleAttempt by remember(accountUserId) { mutableStateOf(0) }
                var role by remember(accountUserId) { mutableStateOf<UserRole?>(null) }
                var displayName by remember { mutableStateOf("") }
                var canRequest by remember { mutableStateOf(true) }
                var canDonate by remember { mutableStateOf(false) }
                // Whether a donor profile actually exists, kept separate from the
                // canDonate permission flag: a role switch can set canDonate without
                // the user ever having created a donor profile.
                var hasDonorProfile by remember { mutableStateOf(false) }
                var profileSaving by remember { mutableStateOf(false) }
                var profileMessage by remember { mutableStateOf<String?>(null) }
                // Guards the optimistic role transition: a second tap while the first
                // write is still in flight could otherwise roll back to a stale role
                // and desync the UI from the server.
                var roleSwitchInFlight by remember(accountUserId) { mutableStateOf(false) }
                val account = remember(accountUserId) { app.accountContainer(accountUserId) }
                var adminAccess by remember(accountUserId) { mutableStateOf<Boolean?>(null) }
                LaunchedEffect(accountUserId) {
                    adminAccess = runCatching {
                        val response = account.api.adminMe()
                        response.isSuccessful && response.body()?.isAdmin == true
                    }.getOrDefault(false)
                }
                if (adminAccess == true) {
                    AdminConsoleScreen(
                        api = account.api,
                        onSignOut = authViewModel::signOut,
                    )
                    return@LifeLinkTheme
                }
                // Serializes profile writes so a role switch cannot be overtaken by an
                // in-flight write and leave the server on the wrong role.
                val profileWriteMutex = remember(accountUserId) { Mutex() }
                LaunchedEffect(accountUserId, roleAttempt) {
                    roleLoadError = null
                    role = null
                    displayName = ""
                    canRequest = true
                    canDonate = false
                    hasDonorProfile = false
                    if (accountUserId.isNotBlank()) {
                        val api = account.api
                        runCatching { api.getProfile() }
                            .onSuccess { response ->
                                if (response.isSuccessful) {
                                    response.body()?.let { profile ->
                                        displayName = profile.displayName.orEmpty()
                                        canRequest = profile.canRequest
                                        canDonate = profile.canDonate
                                        // canDonate is a capability flag, not proof that a donor
                                        // profile exists. Resolve profile existence from /v1/donor-profile
                                        // when switching back to requester mode.
                                        role = if (profile.role.equals("donor", ignoreCase = true)) UserRole.DONOR else UserRole.REQUESTER
                                        roleStore.save(accountUserId, role!!)
                                    }
                                } else if (response.code() == 404) {
                                    // A newly authenticated account starts in requester mode,
                                    // with donor mode available later from Profile. Do not show
                                    // the old mandatory role-choice screen.
                                    role = UserRole.REQUESTER
                                    canRequest = true
                                    canDonate = false
                                    roleStore.save(accountUserId, UserRole.REQUESTER)
                                    roleSyncScope.launch {
                                        profileWriteMutex.withLock {
                                            runCatching {
                                                api.upsertProfile(
                                                    ProfileRequest("requester", canRequest = true, canDonate = false),
                                                )
                                            }
                                        }
                                    }
                                }
                                if (role == null) roleLoadError = "Your profile could not be loaded (${response.code()}). Please retry."
                            }.onFailure { error ->
                                if (error is CancellationException) throw error
                                roleLoadError = "Your profile could not be loaded. Check your connection and retry."
                            }
                        if (!BuildConfig.LIFELINK_FAKE_BACKEND) {
                            runCatching { fetchFirebaseToken() }
                                .onSuccess { token -> app.enqueuePushTokenRegistration(accountUserId, token) }
                        }
                    }
                }
                if (role == null) {
                    Column(
                        modifier = Modifier.fillMaxSize().padding(24.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        if (roleLoadError == null) {
                            AnimatedLifeLinkIcon()
                            Text("Loading your profile\u2026")
                        } else {
                            Text(roleLoadError.orEmpty())
                            Button(onClick = { roleAttempt++ }) { Text("Retry") }
                            Button(onClick = authViewModel::signOut) { Text("Sign out") }
                        }
                    }
                    return@LifeLinkTheme
                }
                val accountModels: AccountViewModelStore = viewModel(key = "account-models-$accountUserId")
                DisposableEffect(accountModels) {
                    onDispose {
                        if (!this@MainActivity.isChangingConfigurations) accountModels.viewModelStore.clear()
                    }
                }
                val viewModel: EmergencyRequestViewModel =
                    viewModel(
                        viewModelStoreOwner = accountModels,
                        key = "emergency-request-$accountUserId",
                        factory = EmergencyRequestViewModelFactory(account.emergencyRequestRepository),
                    )
                val state by viewModel.uiState.collectAsStateWithLifecycle()
                val donorViewModel: DonorViewModel =
                    viewModel(
                        viewModelStoreOwner = accountModels,
                        key = "donor-$accountUserId",
                        factory =
                        DonorViewModelFactory(
                            account.donorRepository,
                            launchDurableWrite = { block -> app.launchAccountWrite(accountUserId, block) },
                        ),
                    )
                val donorState by donorViewModel.state.collectAsStateWithLifecycle()
                val becomeDonorViewModel: BecomeDonorViewModel =
                    viewModel(
                        viewModelStoreOwner = accountModels,
                        key = "become-donor-$accountUserId",
                        factory = BecomeDonorViewModelFactory(account.donorProfileRepository),
                    )
                val becomeDonorState by becomeDonorViewModel.state.collectAsStateWithLifecycle()
                val privacyViewModel: PrivacyViewModel =
                    viewModel(
                        viewModelStoreOwner = accountModels,
                        key = "privacy-$accountUserId",
                        factory = PrivacyViewModelFactory(account.privacyRepository),
                    )
                val privacyState by privacyViewModel.state.collectAsStateWithLifecycle()
                val updatesViewModel: UpdatesViewModel =
                    viewModel(
                        viewModelStoreOwner = accountModels,
                        key = "updates-$accountUserId",
                        factory = UpdatesViewModelFactory(account.updatesRepository),
                    )
                val updates by updatesViewModel.updates.collectAsStateWithLifecycle()
                LaunchedEffect(state.activeRequest?.requestId, state.activeRequest?.status) {
                    state.activeRequest?.let { active ->
                        account.updatesRepository.record(
                            id = "request:${active.requestId}:${active.status.name}",
                            type = UpdateType.REQUEST_STATUS,
                            title = "Request ${active.status.label.lowercase()}",
                            body = active.reason ?: "Your request status has changed.",
                            requestId = active.requestId,
                            actionKey = "request",
                        )
                    }
                }
                LaunchedEffect(state.contacts) {
                    state.activeRequest?.requestId?.let { requestId ->
                        state.contacts.forEach { contact ->
                            account.updatesRepository.record(
                                id = "contact:$requestId:${contact.donorId}:${contact.status}",
                                type = UpdateType.CONTACT_STATUS,
                                title = "Contact request ${contact.status.replace('_', ' ')}",
                                body = "A donor contact request has a new status.",
                                requestId = requestId,
                                actionKey = "request",
                            )
                        }
                    }
                }
                LaunchedEffect(donorState.requests) {
                    donorState.requests.forEach { request ->
                        request.response?.let { response ->
                            account.updatesRepository.record(
                                id = "donor-response:${request.requestId}",
                                type = UpdateType.DONOR_RESPONSE,
                                title = "Response sent",
                                body = "Your ${response.name.lowercase()} response was recorded.",
                                requestId = request.requestId,
                                actionKey = "request",
                            )
                        }
                    }
                }
                LifeLinkShell(
                    state = state,
                    onAction = viewModel::onAction,
                    donorState = donorState,
                    onDonorAction = donorViewModel::onAction,
                    becomeDonorState = becomeDonorState,
                    onBecomeDonorAction = becomeDonorViewModel::onAction,
                    privacyState = privacyState,
                    onPrivacyAction = privacyViewModel::onAction,
                    role = role ?: UserRole.REQUESTER,
                    onSwitchRole = {
                        if (!roleSwitchInFlight) {
                            roleSwitchInFlight = true
                            val previousRole = role ?: UserRole.REQUESTER
                            val next = RoleSwitcher.toggled(previousRole)
                            roleSyncScope.launch {
                                try {
                                    // A profile's can_donate flag can be true merely because the
                                    // user opened donor mode. Before switching back to requester,
                                    // verify actual donor-profile existence from the server.
                                    val donorProfileExists =
                                        if (next == UserRole.REQUESTER) {
                                            val profileResult = runCatching { account.donorProfileRepository.load() }
                                            val error = profileResult.exceptionOrNull()
                                            if (error is CancellationException) throw error
                                            if (error != null) {
                                                profileMessage =
                                                    "Your donor profile could not be checked. Your role was not changed; please retry."
                                                return@launch
                                            }
                                            profileResult.getOrNull() != null
                                        } else {
                                            hasDonorProfile
                                        }
                                    if (next == UserRole.REQUESTER) {
                                        hasDonorProfile = donorProfileExists
                                    }
                                    val caps = RoleSwitcher.capabilities(
                                        next,
                                        hasDonorProfile = donorProfileExists,
                                    )
                                    val saved =
                                        profileWriteMutex.withLock {
                                            runCatching {
                                                account.api.upsertProfile(
                                                    ProfileRequest(
                                                        RoleSwitcher.profileRole(next),
                                                        displayName.trim().ifBlank { null },
                                                        canRequest = caps.canRequest,
                                                        canDonate = caps.canDonate,
                                                    ),
                                                )
                                            }
                                        }
                                    val error = saved.exceptionOrNull()
                                    if (error is CancellationException) throw error
                                    if (saved.getOrNull()?.isSuccessful == true) {
                                        role = next
                                        canRequest = caps.canRequest
                                        canDonate = caps.canDonate
                                        roleStore.save(accountUserId, next)
                                        profileMessage = null
                                    } else {
                                        profileMessage = "Role could not be switched. Please try again."
                                    }
                                } finally {
                                    roleSwitchInFlight = false
                                }
                            }
                        }
                    },
                    accountEmail = signedInSession?.email.orEmpty(),
                    accountUserId = accountUserId,
                    accountDisplayName = displayName,
                    profileSaving = profileSaving,
                    profileMessage = profileMessage,
                    onSaveProfile = { updatedName ->
                        roleSyncScope.launch {
                            profileSaving = true
                            profileMessage = null
                            val response =
                                profileWriteMutex.withLock {
                                    runCatching {
                                        account.api.upsertProfile(
                                            ProfileRequest(
                                                role?.name?.lowercase() ?: "requester",
                                                updatedName.trim(),
                                                canRequest = canRequest,
                                                canDonate = canDonate,
                                            ),
                                        )
                                    }
                                }
                            response
                                .onSuccess { saved ->
                                    if (saved.isSuccessful) {
                                        displayName = saved.body()?.displayName.orEmpty()
                                        profileMessage = "Profile saved"
                                    } else {
                                        profileMessage = "Profile could not be saved (${saved.code()})."
                                    }
                                }.onFailure { error -> profileMessage = error.message ?: "Profile could not be saved." }
                            profileSaving = false
                        }
                    },
                    themeMode = themeMode,
                    onThemeModeChange = { selectedMode ->
                        themeStore.save(selectedMode)
                        themeMode = selectedMode
                    },
                    onRequestPasswordReset = { showMessage ->
                        roleSyncScope.launch {
                            val email = signedInSession?.email.orEmpty()
                            if (email.isBlank()) {
                                showMessage("No account email is available for password recovery.")
                            } else {
                                app.authRepository
                                    .requestPasswordReset(email)
                                    .onSuccess { showMessage("Reset link sent. Check your inbox.") }
                                    .onFailure { showMessage(it.message ?: "Could not send the reset link.") }
                            }
                        }
                    },
                    onSignOut = {
                        roleStore.clear(accountUserId)
                        authViewModel.signOut()
                    },
                    updates = updates,
                    onUpdateRead = updatesViewModel::markRead,
                    onMarkAllUpdatesRead = updatesViewModel::markAllRead,
                    notificationOpenUpdates = notificationOpenUpdates,
                    notificationRequestId = notificationRequestId,
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        recoveryUri = intent.data
        notificationRequestId = notificationDestination(intent)
        notificationOpenUpdates = intent.getBooleanExtra("open_updates", false)
    }
}

@Suppress("DEPRECATION")
private suspend fun fetchFirebaseToken(): String = FirebaseMessaging.getInstance().getToken().await()

/**
 * Animated LifeLink icon shown while the app loads. It pulses and slowly rotates;
 * in Inspect Mode (previews) it renders a static frame so the animation never
 * blocks rendering.
 */
@Composable
private fun AnimatedLifeLinkIcon() {
    val inspection = LocalInspectionMode.current
    val transition = rememberInfiniteTransition(label = "lifelink_load")
    val pulse by transition.animateFloat(
        initialValue = 0.9f,
        targetValue = 1.06f,
        animationSpec = infiniteRepeatable(tween(durationMillis = 700), RepeatMode.Reverse),
        label = "lifelink_pulse",
    )
    val spin by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(durationMillis = 2400)),
        label = "lifelink_spin",
    )
    Image(
        painter = painterResource(R.drawable.lifelink_icon),
        contentDescription = "LifeLink",
        modifier =
        Modifier
            .size((88 * pulse).dp)
            .rotate(if (inspection) 0f else spin)
            .clip(CircleShape),
    )
}

/** Keeps account work across rotation and cancels it when the account leaves the UI. */
class AccountViewModelStore :
    ViewModel(),
    ViewModelStoreOwner {
    override val viewModelStore = ViewModelStore()

    override fun onCleared() = viewModelStore.clear()
}
