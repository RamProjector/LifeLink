package com.lifelink.app

import android.app.Application
import android.net.ConnectivityManager
import androidx.work.WorkManager
import com.lifelink.app.data.local.LifeLinkDatabase
import com.lifelink.app.data.repository.EmergencyRequestRepositoryImpl
import com.lifelink.app.data.repository.LifeLinkAppContainer
import com.lifelink.app.data.repository.DonorRepositoryImpl
import com.lifelink.app.data.remote.RetrofitProvider
import com.lifelink.app.core.auth.AuthSessionStore
import com.lifelink.app.core.auth.SupabaseAuthRepository
import com.lifelink.app.core.notifications.LifeLinkNotifications

class LifeLinkApplication : Application() {
    lateinit var container: LifeLinkAppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        LifeLinkNotifications.createChannels(this)
        val authSessionStore = AuthSessionStore(this)
        val authRepository = SupabaseAuthRepository(authSessionStore)
        val database = LifeLinkDatabase.getInstance(this)
        container = LifeLinkAppContainer(
            authRepository = authRepository,
            emergencyRequestRepository = EmergencyRequestRepositoryImpl(
                draftDao = database.emergencyRequestDraftDao(),
                pendingSubmissionDao = database.pendingSubmissionDao(),
                activeRequestDao = database.activeRequestDao(),
                api = RetrofitProvider.create(tokenProvider = authSessionStore::accessToken, onUnauthorized = authRepository::refreshAccessToken),
                workManager = WorkManager.getInstance(this),
                requesterIdProvider = authSessionStore::userId,
                networkAvailable = {
                    val connectivity = getSystemService(ConnectivityManager::class.java)
                    val network = connectivity.activeNetwork
                    val capabilities = network?.let(connectivity::getNetworkCapabilities)
                    capabilities?.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
                }
            ),
            donorRepository = DonorRepositoryImpl(
                dao = database.donorDao(),
                api = RetrofitProvider.create(tokenProvider = authSessionStore::accessToken, onUnauthorized = authRepository::refreshAccessToken),
                donorIdProvider = authSessionStore::userId
            )
        )
    }
}
