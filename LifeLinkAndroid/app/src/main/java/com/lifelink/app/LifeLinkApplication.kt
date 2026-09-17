package com.lifelink.app

import android.app.Application
import androidx.work.WorkManager
import com.lifelink.app.data.local.LifeLinkDatabase
import com.lifelink.app.data.repository.EmergencyRequestRepositoryImpl
import com.lifelink.app.data.repository.LifeLinkAppContainer
import com.lifelink.app.data.repository.DonorRepositoryImpl
import com.lifelink.app.data.remote.RetrofitProvider
import com.lifelink.app.core.auth.AuthSessionStore
import com.lifelink.app.core.auth.SupabaseAuthRepository
import com.lifelink.app.core.notifications.LifeLinkNotifications
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class LifeLinkApplication : Application() {
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    lateinit var container: LifeLinkAppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        LifeLinkNotifications.createChannels(this)
        val authSessionStore = AuthSessionStore(this)
        val database = LifeLinkDatabase.getInstance(this)
        container = LifeLinkAppContainer(
            authRepository = SupabaseAuthRepository(authSessionStore),
            emergencyRequestRepository = EmergencyRequestRepositoryImpl(
                draftDao = database.emergencyRequestDraftDao(),
                pendingSubmissionDao = database.pendingSubmissionDao(),
                activeRequestDao = database.activeRequestDao(),
                api = RetrofitProvider.create(tokenProvider = authSessionStore::accessToken),
                workManager = WorkManager.getInstance(this),
                requesterIdProvider = authSessionStore::userId
            ),
            donorRepository = DonorRepositoryImpl(database.donorDao(), RetrofitProvider.create(tokenProvider = authSessionStore::accessToken)).also { repository ->
                applicationScope.launch { repository.seedDemoRequests() }
            }
        )
    }
}
