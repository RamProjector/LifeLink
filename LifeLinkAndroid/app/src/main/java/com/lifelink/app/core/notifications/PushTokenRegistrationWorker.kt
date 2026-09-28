package com.lifelink.app.core.notifications

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ListenableWorker.Result as WorkResult
import androidx.work.WorkerParameters
import com.lifelink.app.LifeLinkApplication
import com.lifelink.app.data.remote.PushTokenRequest
import kotlinx.coroutines.CancellationException
import retrofit2.Response
import java.io.IOException

class PushTokenRegistrationWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val application = applicationContext as? LifeLinkApplication ?: return Result.failure()
        return PushTokenRegistrationProcessor(
            currentUserId = { application.authRepository.session.value?.userId },
            register = { ownerId, token ->
                application.accountContainer(ownerId).api.registerPushToken(PushTokenRequest(token))
            }
        ).process(
            ownerId = inputData.getString(OWNER_ID),
            token = inputData.getString(TOKEN)
        )
    }

    companion object {
        const val OWNER_ID = "owner_id"
        const val TOKEN = "token"
    }
}

internal class PushTokenRegistrationProcessor(
    private val currentUserId: () -> String?,
    private val register: suspend (ownerId: String, token: String) -> Response<Unit>
) {
    suspend fun process(ownerId: String?, token: String?): WorkResult {
        if (ownerId.isNullOrBlank() || token.isNullOrBlank()) return WorkResult.failure()
        if (currentUserId() != ownerId) return WorkResult.success()

        return try {
            val response = register(ownerId, token)
            when {
                response.isSuccessful -> WorkResult.success()
                response.code() == 408 || response.code() == 429 || response.code() >= 500 -> WorkResult.retry()
                else -> WorkResult.failure()
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: IOException) {
            WorkResult.retry()
        } catch (_: Exception) {
            WorkResult.failure()
        }
    }
}
