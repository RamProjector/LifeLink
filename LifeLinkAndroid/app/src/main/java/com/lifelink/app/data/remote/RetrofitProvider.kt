package com.lifelink.app.data.remote

import com.lifelink.app.BuildConfig
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

object RetrofitProvider {
    private const val DEFAULT_BASE_URL = "http://10.0.2.2:8000/"

    /**
     * The token provider is deliberately injected so Firebase/Auth0/another
     * identity provider can own refresh and secure storage in the production app.
     */
    fun create(
        baseUrl: String = BuildConfig.LIFELINK_API_BASE_URL.ifBlank { DEFAULT_BASE_URL },
        tokenProvider: () -> String? = { null }
    ): LifeLinkApi {
        val logging = HttpLoggingInterceptor().apply {
            level = if (BuildConfig.DEBUG) HttpLoggingInterceptor.Level.BASIC else HttpLoggingInterceptor.Level.NONE
        }
        val authInterceptor = Interceptor { chain ->
            val request = chain.request().newBuilder().apply {
                tokenProvider()?.takeIf { it.isNotBlank() }?.let { header("Authorization", "Bearer $it") }
            }.build()
            chain.proceed(request)
        }
        val client = OkHttpClient.Builder()
            .addInterceptor(authInterceptor)
            .addInterceptor(logging)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()

        return Retrofit.Builder()
            .baseUrl(if (baseUrl.endsWith('/')) baseUrl else "$baseUrl/")
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(LifeLinkApi::class.java)
    }
}
