package com.lifelink.app

import android.app.Application
import android.net.ConnectivityManager
import androidx.work.WorkManager
import androidx.work.await
import com.lifelink.app.data.local.LifeLinkDatabase
import com.lifelink.app.data.repository.EmergencyRequestRepositoryImpl
import com.lifelink.app.data.repository.LifeLinkAccountContainer
import com.lifelink.app.data.repository.DonorRepositoryImpl
import com.lifelink.app.data.repository.UpdatesRepository
import com.lifelink.app.data.remote.RetrofitProvider
import com.lifelink.app.core.auth.AccountDataCoordinator
import com.lifelink.app.core.auth.AuthSessionStore
import com.lifelink.app.core.auth.SupabaseAuthRepository
import com.lifelink.app.core.notifications.LifeLinkNotifications
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class LifeLinkApplication : Application() {
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    lateinit var authRepository: SupabaseAuthRepository
        private set

    private val accountData = AccountDataCoordinator { authRepository.session.value?.userId }

    internal fun launchAccountWrite(ownerId: String, block: suspend () -> Unit) =
        applicationScope.launch { accountData.runForOwner(ownerId, block) }

    private suspend fun clearLocalAccountData(ownerId: String) = accountData.cleanup {
        WorkManager.getInstance(this).cancelAllWorkByTag("lifelink-account-$ownerId").await()
        LifeLinkDatabase.getInstance(this).clearLocalAccountData(ownerId)
    }

    override fun onCreate() {
        super.onCreate()
        LifeLinkNotifications.createChannels(this)
        authRepository = SupabaseAuthRepository(AuthSessionStore(this) { ownerId ->
            // Session removal triggers cleanup even when no Activity is composed.
            applicationScope.launch { clearLocalAccountData(ownerId) }
        })
        applicationScope.launch {
            // Warm the public health endpoint without binding startup work to an account.
            runCatching { RetrofitProvider.create(tokenProvider = { null }).health() }
        }
    }

    fun accountContainer(ownerId: String): LifeLinkAccountContainer {
        require(ownerId.isNotBlank())
        val database = LifeLinkDatabase.getInstance(this)
        // A retained ViewModel or worker must never borrow another account's token.
        val api = RetrofitProvider.create(
            tokenProvider = {
                authRepository.session.value?.takeIf { it.userId == ownerId }?.accessToken
                    ?: throw IOException("The signed-in account has changed.")
            },
            onUnauthorized = {
                if (authRepository.session.value?.userId == ownerId) {
                    authRepository.refreshAccessToken()
                        ?.takeIf { authRepository.session.value?.userId == ownerId }
                } else null
            }
        )
        return LifeLinkAccountContainer(
            api = api,
            emergencyRequestRepository = EmergencyRequestRepositoryImpl(
                draftDao = database.emergencyRequestDraftDao(),
                pendingSubmissionDao = database.pendingSubmissionDao(),
                activeRequestDao = database.activeRequestDao(),
                api = api,
                workManager = WorkManager.getInstance(this),
                requesterIdProvider = { ownerId },
                networkAvailable = {
                    val connectivity = getSystemService(ConnectivityManager::class.java)
                    connectivity.activeNetwork?.let(connectivity::getNetworkCapabilities)
                        ?.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
                }
            ),
            donorRepository = DonorRepositoryImpl(database.donorDao(), api, { ownerId }),
            updatesRepository = UpdatesRepository(database.updateDao(), ownerId)
        )
    }
}
