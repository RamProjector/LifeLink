package com.lifelink.app.core.notifications

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.google.firebase.messaging.RemoteMessage
import com.lifelink.app.MainActivity

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

    fun showRemoteMessage(context: Context, message: RemoteMessage) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, "android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED
        ) return
        createChannels(context)
        val data = message.data
        val title = message.notification?.title ?: data["title"] ?: "LifeLink update"
        val body = message.notification?.body ?: data["body"] ?: "You have a new LifeLink notification."
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            data["request_id"]?.let { putExtra("request_id", it) }
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            title.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, EMERGENCY_CHANNEL_ID)
            .setSmallIcon(com.lifelink.app.R.drawable.lifelink_icon)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()
        ContextCompat.getSystemService(context, NotificationManager::class.java)
            ?.notify(title.hashCode(), notification)
    }
}
