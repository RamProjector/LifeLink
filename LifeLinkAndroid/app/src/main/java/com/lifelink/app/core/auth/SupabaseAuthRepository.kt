package com.lifelink.app.core.auth

import com.google.gson.annotations.SerializedName
import com.lifelink.app.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Query

class SupabaseAuthRepository(private val sessionStore: AuthSessionStore) {
    private val api: SupabaseAuthApi? = BuildConfig.SUPABASE_URL.takeIf { it.isNotBlank() }?.let { baseUrl ->
        Retrofit.Builder()
            .baseUrl(if (baseUrl.endsWith('/')) baseUrl else "$baseUrl/")
            .client(OkHttpClient.Builder().build())
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(SupabaseAuthApi::class.java)
    }

    val session = sessionStore.session

    suspend fun signUp(email: String, password: String): Result<AuthResult> = authenticate {
        api?.signUp(BuildConfig.SUPABASE_PUBLISHABLE_KEY, AuthRequest(email, password))
            ?: error("Supabase URL is not configured")
    }

    suspend fun signIn(email: String, password: String): Result<AuthResult> = authenticate {
        api?.signIn(BuildConfig.SUPABASE_PUBLISHABLE_KEY, AuthRequest(email, password))
            ?: error("Supabase URL is not configured")
    }

    suspend fun requestPasswordReset(email: String): Result<Unit> = simpleAuthAction {
        api?.recover(BuildConfig.SUPABASE_PUBLISHABLE_KEY, EmailRequest(email))
            ?: error("Supabase URL is not configured")
    }

    suspend fun resendConfirmation(email: String): Result<Unit> = simpleAuthAction {
        api?.resend(BuildConfig.SUPABASE_PUBLISHABLE_KEY, ResendRequest("signup", email))
            ?: error("Supabase URL is not configured")
    }

    suspend fun refreshAccessToken(): String? = withContext(Dispatchers.IO) {
        val current = sessionStore.session.value ?: return@withContext null
        if (current.refreshToken.isBlank()) return@withContext null
        runCatching {
            val response = api?.refresh(BuildConfig.SUPABASE_PUBLISHABLE_KEY, RefreshRequest(current.refreshToken))
                ?: return@runCatching null
            if (!response.isSuccessful) return@runCatching null
            val body = response.body() ?: return@runCatching null
            val accessToken = body.accessToken?.takeIf { it.isNotBlank() } ?: return@runCatching null
            val refreshed = AuthSession(
                accessToken = accessToken,
                refreshToken = body.refreshToken?.takeIf { it.isNotBlank() } ?: current.refreshToken,
                userId = body.user?.id ?: current.userId,
                email = body.user?.email ?: current.email
            )
            sessionStore.save(refreshed)
            accessToken
        }.getOrNull()
    }

    fun signOut() = sessionStore.clear()

    private suspend fun authenticate(call: suspend () -> Response<SupabaseAuthResponse>): Result<AuthResult> = withContext(Dispatchers.IO) {
        runCatching {
            if (BuildConfig.SUPABASE_PUBLISHABLE_KEY.isBlank()) {
                error("Add the Supabase URL and publishable key to the Android build")
            }
            val response = call()
            if (!response.isSuccessful) {
                error(readableAuthError(response.errorBody()?.string().orEmpty(), response.code()))
            }
            val body = response.body() ?: error("Supabase returned an empty response")
            val user = body.user
            val accessToken = body.accessToken?.takeIf { it.isNotBlank() }
            if (user == null || accessToken == null) {
                AuthResult.EmailConfirmationRequired
            } else {
                AuthSession(accessToken, body.refreshToken.orEmpty(), user.id, user.email.orEmpty())
                    .also(sessionStore::save)
                AuthResult.SignedIn(sessionStore.session.value!!)
            }
        }
    }

    private suspend fun simpleAuthAction(call: suspend () -> Response<Unit>): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            if (BuildConfig.SUPABASE_PUBLISHABLE_KEY.isBlank()) error("Add the Supabase URL and publishable key to the Android build")
            val response = call()
            if (!response.isSuccessful) error(readableAuthError(response.errorBody()?.string().orEmpty(), response.code()))
        }
    }

    private fun readableAuthError(raw: String, statusCode: Int): String {
        val message = Regex("\\\"msg\\\"\\s*:\\s*\\\"([^\\\"]+)").find(raw)?.groupValues?.get(1)
            ?: Regex("\\\"message\\\"\\s*:\\s*\\\"([^\\\"]+)").find(raw)?.groupValues?.get(1)
        return when {
            statusCode == 429 -> "Too many attempts. Please wait a few minutes and try again."
            message?.contains("already registered", ignoreCase = true) == true -> "This email already has an account. Select Sign in."
            message?.contains("invalid", ignoreCase = true) == true -> "Please enter a valid email address."
            else -> message ?: "Authentication failed ($statusCode). Please try again."
        }
    }
}

sealed interface AuthResult {
    data class SignedIn(val session: AuthSession) : AuthResult
    data object EmailConfirmationRequired : AuthResult
}

private interface SupabaseAuthApi {
    @POST("auth/v1/signup")
    suspend fun signUp(
        @Header("apikey") publishableKey: String,
        @Body request: AuthRequest
    ): Response<SupabaseAuthResponse>

    @POST("auth/v1/token")
    suspend fun signIn(
        @Header("apikey") publishableKey: String,
        @Body request: AuthRequest,
        @Query("grant_type") grantType: String = "password"
    ): Response<SupabaseAuthResponse>

    @POST("auth/v1/token")
    suspend fun refresh(
        @Header("apikey") publishableKey: String,
        @Body request: RefreshRequest,
        @Query("grant_type") grantType: String = "refresh_token"
    ): Response<SupabaseAuthResponse>

    @POST("auth/v1/recover")
    suspend fun recover(@Header("apikey") publishableKey: String, @Body request: EmailRequest): Response<Unit>

    @POST("auth/v1/resend")
    suspend fun resend(@Header("apikey") publishableKey: String, @Body request: ResendRequest): Response<Unit>
}

data class AuthRequest(val email: String, val password: String)
data class RefreshRequest(@SerializedName("refresh_token") val refreshToken: String)
data class EmailRequest(val email: String)
data class ResendRequest(val type: String, val email: String)

data class SupabaseAuthResponse(
    @SerializedName("access_token") val accessToken: String?,
    @SerializedName("refresh_token") val refreshToken: String?,
    val user: SupabaseUser?
)

data class SupabaseUser(val id: String, val email: String?)
