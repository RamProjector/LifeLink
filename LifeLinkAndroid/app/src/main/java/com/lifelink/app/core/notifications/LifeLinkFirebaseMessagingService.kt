package com.lifelink.app.core.notifications

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

class LifeLinkFirebaseMessagingService : FirebaseMessagingService() {
    override fun onNewToken(token: String) {
        super.onNewToken(token)
        // Send this token to the authenticated backend user profile when Firebase
        // project credentials and authentication are connected.
    }

    override fun onMessageReceived(message: RemoteMessage) {
        super.onMessageReceived(message)
        // Notification rendering is intentionally centralized here later so that
        // request_id, status, and deep-link payloads can be handled consistently.
        LifeLinkNotifications.createChannels(this)
    }
}
