package com.lifelink.app

import android.Manifest
import android.app.Activity
import android.app.Application
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.os.Bundle
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.await
import androidx.work.workDataOf
import com.google.android.gms.common.api.ApiException
import com.lifelink.app.core.auth.AccountDataCoordinator
import com.lifelink.app.core.auth.AuthSessionStore
import com.lifelink.app.core.auth.SupabaseAuthRepository
import com.lifelink.app.core.location.LocationProvider
import com.lifelink.app.core.location.shouldRefreshDonorPresence
import com.lifelink.app.core.notifications.LifeLinkNotifications
import com.lifelink.app.core.notifications.PushTokenRegistrationWorker
import com.lifelink.app.data.local.LifeLinkDatabase
import com.lifelink.app.data.remote.DonorPresenceRequest
import com.lifelink.app.data.remote.RetrofitProvider
import com.lifelink.app.data.repository.DonorProfileRepositoryImpl
import com.lifelink.app.data.repository.DonorRepositoryImpl
import com.lifelink.app.data.repository.EmergencyRequestRepositoryImpl
import com.lifelink.app.data.repository.LifeLinkAccountContainer
import com.lifelink.app.data.repository.PrivacyRepositoryImpl
import com.lifelink.app.data.repository.UpdatesRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import retrofit2.HttpException
import java.io.IOException
import java.net.HttpURLConnection
import java.util.concurrent.TimeUnit

private const val DONOR_PRESENCE_INTERVAL_MILLIS = 5 * 60 * 1000L
private const val MIN_DONOR_LOCATION_PRECISION_METERS = 1
private const val MAX_DONOR_LOCATION_PRECISION_METERS = 10_000

class LifeLinkApplication : Application() {
    private val applicationScope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, throwable ->
            DebugFileLogger.error("application-coroutine", "Unhandled application coroutine exception", throwable)
        },
    )
    lateinit var authRepository: SupabaseAuthRepository
        private set

    private val accountData = AccountDataCoordinator { authRepository.session.value?.userId }
    private var startedActivityCount = 0
    private var donorPresenceJob: Job? = null

    internal fun launchAccountWrite(ownerId: String, block: suspend () -> Unit) =
        applicationScope.launch { accountData.runForOwner(ownerId, block) }

    internal fun enqueuePushTokenRegistration(ownerId: String, token: String) {
        if (ownerId.isBlank() || token.isBlank()) return
        val work = OneTimeWorkRequestBuilder<PushTokenRegistrationWorker>()
            .setInputData(
                workDataOf(
                    PushTokenRegistrationWorker.OWNER_ID to ownerId,
                    PushTokenRegistrationWorker.TOKEN to token,
                ),
            )
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build(),
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .addTag("lifelink-account-$ownerId")
            .build()
        launchAccountWrite(ownerId) {
            // Finish scheduling under the cleanup lock before sign-out can cancel this work.
            WorkManager.getInstance(this@LifeLinkApplication).enqueueUniqueWork(
                "lifelink-push-token-$ownerId",
                ExistingWorkPolicy.REPLACE,
                work,
            ).await()
        }
    }

    // Catch unexpected JSON/provider runtime failures at this background boundary.
    // Cancellation is rethrown below; other failures must not terminate the app process.
    @Suppress("TooGenericExceptionCaught")
    private fun startDonorPresenceHeartbeat() {
        if (donorPresenceJob?.isActive == true) return
        donorPresenceJob = applicationScope.launch {
            while (isActive) {
                try {
                    refreshDonorPresenceIfEligible()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: IOException) {
                    Log.w("LifeLinkPresence", "Donor presence refresh failed; it will retry.", error)
                    DebugFileLogger.warning("donor-presence", "Donor presence refresh failed; it will retry.", error)
                } catch (error: HttpException) {
                    Log.w("LifeLinkPresence", "Donor presence refresh failed; it will retry.", error)
                    DebugFileLogger.warning("donor-presence", "Donor presence refresh failed; it will retry.", error)
                } catch (error: SecurityException) {
                    Log.w("LifeLinkPresence", "Donor presence refresh failed; it will retry.", error)
                    DebugFileLogger.warning("donor-presence", "Donor presence refresh failed; it will retry.", error)
                } catch (error: IllegalStateException) {
                    Log.w("LifeLinkPresence", "Donor presence refresh failed; it will retry.", error)
                    DebugFileLogger.warning("donor-presence", "Donor presence refresh failed; it will retry.", error)
                } catch (error: ApiException) {
                    Log.w("LifeLinkPresence", "Donor presence refresh failed; it will retry.", error)
                    DebugFileLogger.warning("donor-presence", "Donor presence refresh failed; it will retry.", error)
                } catch (error: Exception) {
                    // Malformed API responses and unexpected provider exceptions must not crash the app.
                    // Log the exception type only; never include donor coordinates or response payloads.
                    val message = "Donor presence refresh failed (${error.javaClass.simpleName}); it will retry."
                    Log.w("LifeLinkPresence", message)
                    DebugFileLogger.warning("donor-presence", message)
                }
                delay(DONOR_PRESENCE_INTERVAL_MILLIS)
            }
        }
    }

    private fun stopDonorPresenceHeartbeat() {
        donorPresenceJob?.cancel()
        donorPresenceJob = null
    }

    private suspend fun refreshDonorPresenceIfEligible() {
        val session = authRepository.session.value ?: return
        if (
            checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        refreshDonorPresenceForAccount(session.userId)
    }

    private suspend fun refreshDonorPresenceForAccount(ownerId: String) {
        val api = accountContainer(ownerId).api
        val profileResponse = api.getDonorProfileMe()
        when {
            profileResponse.code() == HttpURLConnection.HTTP_NOT_FOUND ->
                refreshLegacyDonorPresence(ownerId)
            !profileResponse.isSuccessful ->
                logPresenceFailure("Donor profile refresh returned HTTP ${profileResponse.code()}.")
            else -> profileResponse.body()?.let { profile ->
                if (shouldRefreshDonorPresence(profile.verified, profile.availabilityStatus)) {
                    capturePresenceRequest()?.let { request ->
                        val presenceResponse = api.recordDonorPresence(request)
                        if (!presenceResponse.isSuccessful) {
                            logPresenceFailure("Donor presence update returned HTTP ${presenceResponse.code()}.")
                        }
                    }
                }
            } ?: logPresenceFailure("Donor profile refresh returned an empty response.")
        }
    }

    private suspend fun refreshLegacyDonorPresence(ownerId: String) {
        val api = accountContainer(ownerId).api
        val profileResponse = api.getDonorProfile(ownerId)
        when {
            !profileResponse.isSuccessful ->
                logPresenceFailure("Legacy donor profile refresh returned HTTP ${profileResponse.code()}.")
            else -> profileResponse.body()?.let { profile ->
                if (shouldRefreshDonorPresence(profile.verified, profile.availability)) {
                    capturePresenceRequest()?.let { request ->
                        val presenceResponse = api.recordLegacyDonorPresence(ownerId, request)
                        if (!presenceResponse.isSuccessful) {
                            logPresenceFailure("Legacy donor presence update returned HTTP ${presenceResponse.code()}.")
                        }
                    }
                }
            } ?: logPresenceFailure("Legacy donor profile refresh returned an empty response.")
        }
    }

    private suspend fun capturePresenceRequest(): DonorPresenceRequest? {
        val location = LocationProvider(this).currentLocation() ?: return null
        return DonorPresenceRequest(
            latitude = location.latitude,
            longitude = location.longitude,
            precisionMeters = location.precisionMeters.coerceIn(
                MIN_DONOR_LOCATION_PRECISION_METERS,
                MAX_DONOR_LOCATION_PRECISION_METERS,
            ),
        )
    }

    private fun logPresenceFailure(message: String) {
        Log.w("LifeLinkPresence", message)
        DebugFileLogger.warning("donor-presence", message)
    }

    private suspend fun clearLocalAccountData(ownerId: String) = accountData.cleanup {
        WorkManager.getInstance(this).cancelAllWorkByTag("lifelink-account-$ownerId").await()
        LifeLinkDatabase.getInstance(this).clearLocalAccountData(ownerId)
    }

    override fun onCreate() {
        super.onCreate()
        DemoBackendRuntime.startIfEnabled(this)
        DebugFileLogger.install(this)
        LifeLinkNotifications.createChannels(this)
        authRepository = SupabaseAuthRepository(
            AuthSessionStore(this) { ownerId ->
                // Session removal triggers cleanup even when no Activity is composed.
                applicationScope.launch { clearLocalAccountData(ownerId) }
            },
        )
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) {
                startedActivityCount += 1
                if (startedActivityCount == 1 && !BuildConfig.LIFELINK_FAKE_BACKEND) startDonorPresenceHeartbeat()
            }

            override fun onActivityStopped(activity: Activity) {
                startedActivityCount = (startedActivityCount - 1).coerceAtLeast(0)
                if (startedActivityCount == 0) stopDonorPresenceHeartbeat()
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
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
                } else {
                    null
                }
            },
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
                },
            ),
            donorRepository = DonorRepositoryImpl(database.donorDao(), api, { ownerId }),
            donorProfileRepository = DonorProfileRepositoryImpl(api),
            updatesRepository = UpdatesRepository(database.updateDao(), ownerId),
            privacyRepository = PrivacyRepositoryImpl(api, { ownerId }),
        )
    }
}
