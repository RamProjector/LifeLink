package com.lifelink.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.lifelink.app.core.ui.theme.LifeLinkTheme
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
import com.lifelink.app.data.remote.RetrofitProvider
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val app = application as LifeLinkApplication
        setContent {
            LifeLinkTheme {
                RequestNotificationPermissionIfNeeded()
                val authViewModel: AuthViewModel = viewModel(
                    factory = AuthViewModelFactory(app.container.authRepository)
                )
                val authState by authViewModel.state.collectAsStateWithLifecycle()
                if (authState !is AuthState.SignedIn) {
                    AuthScreen(authState, authViewModel::signIn, authViewModel::signUp)
                    return@LifeLinkTheme
                }
                val roleStore = remember { UserRoleStore(this@MainActivity) }
                val roleSyncScope = rememberCoroutineScope()
                var role by remember { mutableStateOf(roleStore.get()) }
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
                    role = role ?: UserRole.REQUESTER
                )
            }
        }
    }
}
