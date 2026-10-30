package com.lifelink.app

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

/**
 * Keeps the fake backend out of normal debug/release builds. The uiTest build type
 * supplies FakeBackendController in its own source set; reflection avoids linking
 * that test-only server into production APKs.
 */
internal object DemoBackendRuntime {
    private const val CONTROLLER_CLASS = "com.lifelink.app.FakeBackendController"

    fun startIfEnabled(context: Context) {
        if (!BuildConfig.LIFELINK_FAKE_BACKEND) return
        val controller = Class.forName(CONTROLLER_CLASS)
        // MockWebServer performs socket/DNS setup; keep that work off Android's main thread.
        // This is a test-only build and startup waits for the local server before repositories resolve its URL.
        runBlocking(Dispatchers.IO) {
            controller.getMethod("start", Context::class.java).invoke(null, context.applicationContext)
        }

        // The UI-test application ID is separate, so this synthetic session cannot
        // overwrite the real app's saved session.
        context.getSharedPreferences("lifelink_auth", Context.MODE_PRIVATE)
            .edit()
            .putString("access_token", "ui-test-only-fake-token")
            .putString("refresh_token", "ui-test-only-fake-refresh-token")
            .putString("user_id", "demo-user")
            .putString("email", "demo.requester@lifelink.test")
            .apply()
    }

    fun resolveBaseUrl(configuredBaseUrl: String): String {
        if (!BuildConfig.LIFELINK_FAKE_BACKEND) return configuredBaseUrl
        val controller = Class.forName(CONTROLLER_CLASS)
        return controller.getMethod("baseUrl").invoke(null) as? String
            ?: error("The UI-test fake backend did not provide a base URL.")
    }
}
