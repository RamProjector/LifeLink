package com.lifelink.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.material3.CircularProgressIndicator
import com.lifelink.app.core.ui.theme.LifeLinkTheme
import com.lifelink.app.core.ui.theme.ThemeMode
import com.lifelink.app.core.ui.theme.ThemeStore
import com.lifelink.app.core.navigation.LifeLinkShell
import com.lifelink.app.core.notifications.RequestNotificationPermissionIfNeeded
import com.lifelink.app.feature.emergencyrequest.EmergencyRequestViewModel
import com.lifelink.app.feature.emergencyrequest.EmergencyRequestViewModelFactory
import com.lifelink.app.feature.donor.DonorViewModel
import com.lifelink.app.feature.donor.DonorViewModelFactory
import com.lifelink.app.feature.auth.AuthScreen
import com.lifelink.app.feature.auth.AuthState
import com.lifelink.app.feature.auth.AuthViewModel
import com.lifelink.app.feature.auth.AuthViewModelFactory
import com.lifelink.app.feature.auth.RoleSelectionScreen
import com.lifelink.app.core.auth.UserRole
import com.lifelink.app.core.auth.UserRoleStore
import com.lifelink.app.domain.UpdateType
import com.lifelink.app.feature.updates.UpdatesViewModel
import com.lifelink.app.feature.updates.UpdatesViewModelFactory
import com.lifelink.app.data.remote.ProfileRequest
import com.lifelink.app.data.remote.PushTokenRequest
import com.lifelink.app.data.remote.RetrofitProvider
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private var recoveryUri by mutableStateOf<Uri?>(null)
    private var notificationRequestId by mutableStateOf<String?>(null)
    private var notificationOpenUpdates by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        recoveryUri = intent?.data
        notificationRequestId = intent?.getStringExtra("request_id")
        notificationOpenUpdates = intent?.getBooleanExtra("open_updates", false) == true
        enableEdgeToEdge()
        val app = application as LifeLinkApplication
        setContent {
            val themeStore = remember { ThemeStore(this@MainActivity) }
            var themeMode by remember { mutableStateOf(themeStore.get()) }
            LifeLinkTheme(themeMode = themeMode) {
                RequestNotificationPermissionIfNeeded()
                val authViewModel: AuthViewModel = viewModel(
                    factory = AuthViewModelFactory(app.container.authRepository)
                )
                LaunchedEffect(recoveryUri) { authViewModel.handleAuthCallback(recoveryUri) }
                val authState by authViewModel.state.collectAsStateWithLifecycle()
                if (authState !is AuthState.SignedIn) {
                    AuthScreen(
                        state = authState,
                        onSignIn = authViewModel::signIn,
                        onSignUp = authViewModel::signUp,
                        onPasswordReset = authViewModel::requestPasswordReset,
                        onResendConfirmation = authViewModel::resendConfirmation,
                        onUpdatePassword = authViewModel::updatePassword
                    )
                    return@LifeLinkTheme
                }
                val roleStore = remember { UserRoleStore(this@MainActivity) }
                val roleSyncScope = rememberCoroutineScope()
                val accountUserId = (authState as AuthState.SignedIn).session.userId
                var role by remember(accountUserId) { mutableStateOf<UserRole?>(null) }
                var profileResolved by remember(accountUserId) { mutableStateOf(false) }
                var displayName by remember { mutableStateOf("") }
                var profileSaving by remember { mutableStateOf(false) }
                var profileMessage by remember { mutableStateOf<String?>(null) }
                val signedInSession = (authState as? AuthState.SignedIn)?.session
                LaunchedEffect(accountUserId) {
                    profileResolved = false
                    role = null
                    displayName = ""
                    if (accountUserId.isNotBlank() && signedInSession != null) {
                        val api = RetrofitProvider.create(
                                tokenProvider = { app.container.authRepository.session.value?.accessToken ?: signedInSession.accessToken },
                                onUnauthorized = app.container.authRepository::refreshAccessToken
                            )
                        runCatching { api.getProfile() }.onSuccess { response ->
                            if (response.isSuccessful) {
                                val body = response.body()
                                displayName = body?.displayName.orEmpty()
                                val serverRole = when (body?.role?.lowercase()) {
                                    "donor" -> UserRole.DONOR
                                    "requester" -> UserRole.REQUESTER
                                    else -> null
                                }
                                if (serverRole != null) {
                                    roleStore.save(accountUserId, serverRole)
                                    role = serverRole
                                }
                                profileResolved = true
                            } else if (response.code() == 404) {
                                role = roleStore.get(accountUserId)
                                profileResolved = true
                            }
                        }.onFailure {
                            role = roleStore.get(accountUserId)
                            profileResolved = role != null
                        }
                        runCatching { FirebaseMessaging.getInstance().token.await() }
                            .onSuccess { token -> runCatching { api.registerPushToken(PushTokenRequest(token)) } }
                    }
                }
                if (!profileResolved) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                    return@LifeLinkTheme
                }
                if (role == null) {
                    RoleSelectionScreen { selectedRole ->
                        roleStore.save(accountUserId, selectedRole)
                        role = selectedRole
                        roleSyncScope.launch {
                            val session = (authState as? AuthState.SignedIn)?.session
                            if (session != null) {
                                runCatching {
                                    RetrofitProvider.create(
                                        tokenProvider = { app.container.authRepository.session.value?.accessToken ?: session.accessToken },
                                        onUnauthorized = app.container.authRepository::refreshAccessToken
                                    )
                                        .upsertProfile(ProfileRequest(selectedRole.name.lowercase(), canRequest = true, canDonate = selectedRole == UserRole.DONOR))
                                }
                            }
                        }
                    }
                    return@LifeLinkTheme
                }
                val viewModel: EmergencyRequestViewModel = viewModel(
                    key = "requester-$accountUserId",
                    factory = EmergencyRequestViewModelFactory(app.container.emergencyRequestRepository)
                )
                val state by viewModel.uiState.collectAsStateWithLifecycle()
                val donorViewModel: DonorViewModel = viewModel(
                    key = "donor-$accountUserId",
                    factory = DonorViewModelFactory(app.container.donorRepository)
                )
                val donorState by donorViewModel.state.collectAsStateWithLifecycle()
                val updatesViewModel: UpdatesViewModel = viewModel(
                    key = "updates-$accountUserId",
                    factory = UpdatesViewModelFactory(app.container.updatesRepository)
                )
                val updates by updatesViewModel.updates.collectAsStateWithLifecycle()
                LaunchedEffect(state.activeRequest?.requestId, state.activeRequest?.status) {
                    state.activeRequest?.let { active ->
                        app.container.updatesRepository.record(
                            id = "request:${active.requestId}:${active.status.name}",
                            type = UpdateType.REQUEST_STATUS,
                            title = "Request ${active.status.label.lowercase()}",
                            body = active.reason ?: "Your request status has changed.",
                            requestId = active.requestId,
                            actionKey = "request"
                        )
                    }
                }
                LaunchedEffect(state.contacts) {
                    state.activeRequest?.requestId?.let { requestId ->
                        state.contacts.forEach { contact ->
                            app.container.updatesRepository.record(
                                id = "contact:$requestId:${contact.donorId}:${contact.status}",
                                type = UpdateType.CONTACT_STATUS,
                                title = "Contact request ${contact.status.replace('_', ' ')}",
                                body = "A donor contact request has a new status.",
                                requestId = requestId,
                                actionKey = "request"
                            )
                        }
                    }
                }
                LaunchedEffect(donorState.requests) {
                    donorState.requests.forEach { request ->
                        request.response?.let { response ->
                            app.container.updatesRepository.record(
                                id = "donor-response:${request.requestId}",
                                type = UpdateType.DONOR_RESPONSE,
                                title = "Response sent",
                                body = "Your ${response.name.lowercase()} response was recorded.",
                                requestId = request.requestId,
                                actionKey = "request"
                            )
                        }
                    }
                }
                LifeLinkShell(
                    state = state,
                    onAction = viewModel::onAction,
                    donorState = donorState,
                    onDonorAction = donorViewModel::onAction,
                    role = role ?: UserRole.REQUESTER,
                    accountEmail = signedInSession?.email.orEmpty(),
                    accountUserId = accountUserId,
                    accountDisplayName = displayName,
                    profileSaving = profileSaving,
                    profileMessage = profileMessage,
                    onSaveProfile = { updatedName ->
                        roleSyncScope.launch {
                            profileSaving = true
                            profileMessage = null
                            runCatching {
                                RetrofitProvider.create(
                                    tokenProvider = { app.container.authRepository.session.value?.accessToken ?: signedInSession?.accessToken },
                                    onUnauthorized = app.container.authRepository::refreshAccessToken
                                        ).upsertProfile(ProfileRequest(role?.name?.lowercase() ?: "requester", updatedName.trim(), canRequest = true, canDonate = role == UserRole.DONOR))
                            }.onSuccess { response ->
                                if (response.isSuccessful) {
                                    displayName = response.body()?.displayName.orEmpty()
                                    profileMessage = "Profile saved"
                                } else profileMessage = "Profile could not be saved (${response.code()})."
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
                                app.container.authRepository.requestPasswordReset(email)
                                    .onSuccess { showMessage("Reset link sent. Check your inbox.") }
                                    .onFailure { showMessage(it.message ?: "Could not send the reset link.") }
                            }
                        }
                    },
                    onSignOut = authViewModel::signOut,
                    updates = updates,
                    onUpdateRead = updatesViewModel::markRead,
                    onMarkAllUpdatesRead = updatesViewModel::markAllRead,
                    notificationOpenUpdates = notificationOpenUpdates,
                    notificationRequestId = notificationRequestId
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        recoveryUri = intent.data
        notificationRequestId = intent.getStringExtra("request_id")
        notificationOpenUpdates = intent.getBooleanExtra("open_updates", false)
    }
}
