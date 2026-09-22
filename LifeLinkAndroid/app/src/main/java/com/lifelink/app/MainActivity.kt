package com.lifelink.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
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
import com.lifelink.app.data.remote.ProfileRequest
import com.lifelink.app.data.remote.PushTokenRequest
import com.lifelink.app.data.remote.RetrofitProvider
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private var recoveryUri by mutableStateOf<Uri?>(null)
    private var notificationRequestId by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        recoveryUri = intent?.data
        notificationRequestId = intent?.getStringExtra("request_id")
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
                LaunchedEffect(recoveryUri) { authViewModel.handleRecoveryCallback(recoveryUri) }
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
                var role by remember { mutableStateOf(roleStore.get()) }
                var displayName by remember { mutableStateOf("") }
                var profileSaving by remember { mutableStateOf(false) }
                var profileMessage by remember { mutableStateOf<String?>(null) }
                val signedInSession = (authState as? AuthState.SignedIn)?.session
                val accountUserId = signedInSession?.userId.orEmpty()
                LaunchedEffect(accountUserId) {
                    if (accountUserId.isNotBlank()) {
                        val api = RetrofitProvider.create(
                                tokenProvider = { app.container.authRepository.session.value?.accessToken ?: signedInSession?.accessToken },
                                onUnauthorized = app.container.authRepository::refreshAccessToken
                            )
                        runCatching { api.getProfile() }.onSuccess { response ->
                            if (response.isSuccessful) displayName = response.body()?.displayName.orEmpty()
                        }
                        runCatching { FirebaseMessaging.getInstance().token.await() }
                            .onSuccess { token -> runCatching { api.registerPushToken(PushTokenRequest(token)) } }
                    }
                }
                if (role == null) {
                    RoleSelectionScreen { selectedRole ->
                        roleStore.save(selectedRole)
                        role = selectedRole
                        roleSyncScope.launch {
                            val session = (authState as? AuthState.SignedIn)?.session
                            if (session != null) {
                                runCatching {
                                    RetrofitProvider.create(
                                        tokenProvider = { app.container.authRepository.session.value?.accessToken ?: session.accessToken },
                                        onUnauthorized = app.container.authRepository::refreshAccessToken
                                    )
                                        .upsertProfile(ProfileRequest(selectedRole.name.lowercase()))
                                }
                            }
                        }
                    }
                    return@LifeLinkTheme
                }
                val viewModel: EmergencyRequestViewModel = viewModel(
                    factory = EmergencyRequestViewModelFactory(app.container.emergencyRequestRepository)
                )
                val state by viewModel.uiState.collectAsStateWithLifecycle()
                val donorViewModel: DonorViewModel = viewModel(
                    factory = DonorViewModelFactory(app.container.donorRepository)
                )
                val donorState by donorViewModel.state.collectAsStateWithLifecycle()
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
                                ).upsertProfile(ProfileRequest(role?.name?.lowercase() ?: "requester", updatedName.trim()))
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
                    onRequestPasswordReset = { authViewModel.requestPasswordReset(signedInSession?.email.orEmpty()) },
                    onSignOut = authViewModel::signOut,
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
    }
}
