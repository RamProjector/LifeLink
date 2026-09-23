package com.lifelink.app.core.notifications

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

@Suppress("DEPRECATION")
class LifeLinkFirebaseMessagingService : FirebaseMessagingService() {
    @Suppress("DEPRECATION")
    @Deprecated("Required by the FirebaseMessagingService compatibility API.")
    override fun onNewToken(token: String) {
        super.onNewToken(token)
        // Send this token to the authenticated backend user profile when Firebase
        // project credentials and authentication are connected.
    }

    override fun onMessageReceived(message: RemoteMessage) {
        super.onMessageReceived(message)
        LifeLinkNotifications.showRemoteMessage(this, message)
    }
}
