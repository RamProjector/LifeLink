package com.lifelink.app.data

import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.firebase.messaging.RemoteMessage
import com.lifelink.app.core.notifications.LifeLinkNotifications
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = android.app.Application::class)
class NotificationOwnershipTest {
    @Test
    fun unownedOrForeignMessagesAreNotDisplayedWhileSignedOut() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.cancelAll()
        listOf(null, " ", "another-account").forEach { owner ->
            val message = RemoteMessage.Builder("synthetic-sender")
                .addData("title", "Private request update")
                .addData("request_id", "synthetic-request")
                .apply { if (owner != null) addData("user_id", owner) }
                .build()
            LifeLinkNotifications.showRemoteMessage(context, message)
            assertEquals("No notification should be posted for owner=$owner", 0, manager.activeNotifications.size)
        }
    }
}
