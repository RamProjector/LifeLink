package com.lifelink.app.core.notifications

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build

object LifeLinkNotifications {
    const val EMERGENCY_CHANNEL_ID = "lifelink_emergency_requests"

    fun createChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                EMERGENCY_CHANNEL_ID,
                "Emergency blood requests",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Time-sensitive requests relevant to the donor or coordinator"
                enableVibration(true)
            }
        )
    }
}
