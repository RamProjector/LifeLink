package com.lifelink.app.core.notifications

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.lifelink.app.LifeLinkApplication

@Suppress("DEPRECATION")
class LifeLinkFirebaseMessagingService : FirebaseMessagingService() {
    @Suppress("DEPRECATION")
    @Deprecated("Required by the FirebaseMessagingService compatibility API.")
    override fun onNewToken(token: String) {
        super.onNewToken(token)
        val app = application as? LifeLinkApplication ?: return
        val ownerId = app.authRepository.session.value?.userId ?: return
        app.enqueuePushTokenRegistration(ownerId, token)
    }

    override fun onMessageReceived(message: RemoteMessage) {
        super.onMessageReceived(message)
        LifeLinkNotifications.showRemoteMessage(this, message)
    }
}
