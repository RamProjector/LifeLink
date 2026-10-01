package com.lifelink.app.core.auth

import android.net.Uri
import com.google.gson.annotations.SerializedName
import com.lifelink.app.BuildConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Query

class SupabaseAuthRepository(
    private val sessionStore: AuthSessionStore,
    baseUrl: String = BuildConfig.SUPABASE_URL,
    private val publishableKey: String = BuildConfig.SUPABASE_PUBLISHABLE_KEY,
) {
    private val refreshMutex = Mutex()
    private val _sessionExpired = MutableStateFlow(false)
    val sessionExpired = _sessionExpired.asStateFlow()
    private val api: SupabaseAuthApi? =
        baseUrl.takeIf { it.isNotBlank() }?.let { baseUrl ->
            Retrofit
                .Builder()
                .baseUrl(if (baseUrl.endsWith('/')) baseUrl else "$baseUrl/")
                .client(OkHttpClient.Builder().build())
                .addConverterFactory(GsonConverterFactory.create())
                .build()
                .create(SupabaseAuthApi::class.java)
        }

    val session = sessionStore.session

    suspend fun signUp(email: String, password: String): Result<AuthResult> =
        authenticate {
            api?.signUp(publishableKey, AuthRequest(email, password), SIGNUP_REDIRECT_URI)
                ?: error("Supabase URL is not configured")
        }

    suspend fun signIn(email: String, password: String): Result<AuthResult> =
        authenticate {
            api?.signIn(publishableKey, AuthRequest(email, password))
                ?: error("Supabase URL is not configured")
        }

    suspend fun requestPasswordReset(email: String): Result<Unit> =
        simpleAuthAction {
            api?.recover(publishableKey, PasswordRecoveryRequest(email, RECOVERY_REDIRECT_URI))
                ?: error("Supabase URL is not configured")
        }

    suspend fun completeEmailConfirmation(callback: AuthCallback): Result<AuthSession> =
        withContext(Dispatchers.IO) {
            runCatching {
                val response =
                    api?.getUser(
                        publishableKey,
                        "Bearer ${callback.accessToken}",
                    ) ?: error("Supabase URL is not configured")
                if (!response.isSuccessful) error("Email confirmation could not be completed. Please sign in.")
                val user = response.body() ?: error("Supabase returned an empty account")
                AuthSession(
                    accessToken = callback.accessToken,
                    refreshToken = callback.refreshToken.orEmpty(),
                    userId = user.id,
                    email = user.email.orEmpty(),
                ).also {
                    sessionStore.save(it)
                    _sessionExpired.value = false
                }
            }
        }

    fun parseAuthCallback(uri: Uri): Result<AuthCallback> =
        runCatching {
            require(uri.scheme == "lifelink" && uri.host == "auth") { "This is not a LifeLink authentication link." }
            val type = uri.getQueryParameter("type") ?: uri.getFragmentParameter("type")
            val accessToken = uri.getQueryParameter("access_token") ?: uri.getFragmentParameter("access_token")
            require(!accessToken.isNullOrBlank()) { "The authentication link is missing its confirmation token." }
            val path = uri.path.orEmpty().trimEnd('/')
            val kind =
                when (path) {
                    "/recovery" -> {
                        require(type == "recovery") { "This link is not a password-reset link." }
                        AuthCallbackKind.RECOVERY
                    }
                    "/confirm" -> {
                        require(type == "signup" || type == "email") { "This link is not an account-confirmation link." }
                        AuthCallbackKind.CONFIRMATION
                    }
                    else -> error("This LifeLink authentication link is no longer supported.")
                }
            AuthCallback(
                kind = kind,
                email = uri.getQueryParameter("email"),
                accessToken = accessToken,
                refreshToken = uri.getQueryParameter("refresh_token") ?: uri.getFragmentParameter("refresh_token"),
            )
        }

    suspend fun updatePassword(accessToken: String, password: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                if (publishableKey.isBlank()) error("Add the Supabase URL and publishable key to the Android build")
                val response =
                    api?.updateUser(
                        publishableKey,
                        "Bearer $accessToken",
                        PasswordUpdateRequest(password),
                    ) ?: error("Supabase URL is not configured")
                if (!response.isSuccessful) error(readableAuthError(response.errorBody()?.string().orEmpty(), response.code()))
            }
        }

    suspend fun resendConfirmation(email: String): Result<Unit> =
        simpleAuthAction {
            api?.resend(publishableKey, ResendRequest("signup", email))
                ?: error("Supabase URL is not configured")
        }

    suspend fun refreshAccessToken(): String? =
        withContext(Dispatchers.IO) {
            val requestedSession = sessionStore.session.value ?: return@withContext null
            refreshMutex.withLock {
                val current = sessionStore.session.value ?: return@withLock null
                if (current != requestedSession) {
                    return@withLock current.accessToken.takeIf { current.userId == requestedSession.userId }
                }
                if (current.refreshToken.isBlank()) return@withLock null
                try {
                    val response =
                        api?.refresh(publishableKey, RefreshRequest(current.refreshToken))
                            ?: return@withLock null
                    if (sessionStore.session.value != current) return@withLock null
                    if (!response.isSuccessful) {
                        if (response.code() == 400 || response.code() == 401 || response.code() == 403) {
                            if (sessionStore.replace(current, null)) _sessionExpired.value = true
                        }
                        return@withLock null
                    }
                    val body = response.body() ?: return@withLock null
                    val accessToken = body.accessToken?.takeIf { it.isNotBlank() } ?: return@withLock null
                    if (body.user != null && body.user.id != current.userId) return@withLock null
                    val refreshed =
                        current.copy(
                            accessToken = accessToken,
                            refreshToken = body.refreshToken?.takeIf { it.isNotBlank() } ?: current.refreshToken,
                            email = body.user?.email ?: current.email,
                        )
                    accessToken.takeIf { sessionStore.replace(current, refreshed) }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: java.io.IOException) {
                    null
                }
            }
        }

    fun signOut() = sessionStore.clear()

    private suspend fun authenticate(call: suspend () -> Response<SupabaseAuthResponse>): Result<AuthResult> =
        withContext(Dispatchers.IO) {
            runCatching {
                if (publishableKey.isBlank()) {
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
                        .also {
                            sessionStore.save(it)
                            _sessionExpired.value = false
                        }
                    AuthResult.SignedIn(sessionStore.session.value!!)
                }
            }
        }

    private suspend fun simpleAuthAction(call: suspend () -> Response<Unit>): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                if (publishableKey.isBlank()) error("Add the Supabase URL and publishable key to the Android build")
                val response = call()
                if (!response.isSuccessful) error(readableAuthError(response.errorBody()?.string().orEmpty(), response.code()))
            }
        }

    private fun readableAuthError(raw: String, statusCode: Int): String {
        val message =
            Regex("\\\"msg\\\"\\s*:\\s*\\\"([^\\\"]+)").find(raw)?.groupValues?.get(1)
                ?: Regex("\\\"message\\\"\\s*:\\s*\\\"([^\\\"]+)").find(raw)?.groupValues?.get(1)
        return when {
            statusCode == 429 -> "Too many attempts. Please wait a few minutes and try again."
            message?.contains("already registered", ignoreCase = true) == true -> "This email already has an account. Select Sign in."
            message?.contains("invalid", ignoreCase = true) == true -> "Please enter a valid email address."
            else -> message ?: "Authentication failed ($statusCode). Please try again."
        }
    }

    private fun Uri.getFragmentParameter(name: String): String? =
        fragment
            ?.split('&')
            ?.mapNotNull { part -> part.split('=', limit = 2).takeIf { it.size == 2 } }
            ?.firstOrNull { it[0] == name }
            ?.get(1)
            ?.let(Uri::decode)

    private companion object {
        const val RECOVERY_REDIRECT_URI = "lifelink://auth/recovery"
        const val SIGNUP_REDIRECT_URI = "lifelink://auth/confirm"
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
        @Body request: AuthRequest,
        @Query("redirect_to") redirectTo: String,
    ): Response<SupabaseAuthResponse>

    @POST("auth/v1/token")
    suspend fun signIn(
        @Header("apikey") publishableKey: String,
        @Body request: AuthRequest,
        @Query("grant_type") grantType: String = "password",
    ): Response<SupabaseAuthResponse>

    @POST("auth/v1/token")
    suspend fun refresh(
        @Header("apikey") publishableKey: String,
        @Body request: RefreshRequest,
        @Query("grant_type") grantType: String = "refresh_token",
    ): Response<SupabaseAuthResponse>

    @POST("auth/v1/recover")
    suspend fun recover(
        @Header("apikey") publishableKey: String,
        @Body request: PasswordRecoveryRequest,
    ): Response<Unit>

    @POST("auth/v1/resend")
    suspend fun resend(
        @Header("apikey") publishableKey: String,
        @Body request: ResendRequest,
    ): Response<Unit>

    @GET("auth/v1/user")
    suspend fun getUser(
        @Header("apikey") publishableKey: String,
        @Header("Authorization") authorization: String,
    ): Response<SupabaseUser>

    @PUT("auth/v1/user")
    suspend fun updateUser(
        @Header("apikey") publishableKey: String,
        @Header("Authorization") authorization: String,
        @Body request: PasswordUpdateRequest,
    ): Response<SupabaseUser>
}

// Every field that crosses the Supabase wire is pinned with an explicit
// @SerializedName. R8/ProGuard renames Kotlin property names in the release
// build, and Gson would then serialize the request as {"a":...,"b":...} instead
// of {"email":...,"password":...}. Supabase rejects that with "missing email or
// phone" even though the form sent a valid address. The explicit names make the
// wire contract independent of the obfuscated property names (and the
// proguard-rules.pro keep rule for this package is the second line of defence).
data class AuthRequest(@SerializedName("email") val email: String, @SerializedName("password") val password: String)

data class RefreshRequest(@SerializedName("refresh_token") val refreshToken: String)

data class PasswordRecoveryRequest(@SerializedName("email") val email: String, @SerializedName("redirect_to") val redirectTo: String)

data class ResendRequest(@SerializedName("type") val type: String, @SerializedName("email") val email: String)

enum class AuthCallbackKind { RECOVERY, CONFIRMATION }

data class AuthCallback(val kind: AuthCallbackKind, val email: String?, val accessToken: String, val refreshToken: String?)

data class PasswordUpdateRequest(@SerializedName("password") val password: String)

data class SupabaseAuthResponse(
    @SerializedName("access_token") val accessToken: String?,
    @SerializedName("refresh_token") val refreshToken: String?,
    @SerializedName("user") val user: SupabaseUser?,
)

data class SupabaseUser(@SerializedName("id") val id: String, @SerializedName("email") val email: String?)
