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
import com.lifelink.app.data.local.LifeLinkDatabase
import com.lifelink.app.data.repository.UpdatesRepository
import com.lifelink.app.domain.UpdateType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

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
        val canPostNotification = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, "android.permission.POST_NOTIFICATIONS") == PackageManager.PERMISSION_GRANTED
        createChannels(context)
        val data = message.data
        val title = message.notification?.title ?: data["title"] ?: "LifeLink update"
        val body = message.notification?.body ?: data["body"] ?: "You have a new LifeLink notification."
        val requestId = data["request_id"]
        val eventId = data["event_id"] ?: listOf(data["type"], requestId, title, body).joinToString(":").hashCode().toString()
        val updateType = when (data["type"]?.lowercase()) {
            "donor_response" -> UpdateType.DONOR_RESPONSE
            "contact_status" -> UpdateType.CONTACT_STATUS
            "account" -> UpdateType.ACCOUNT
            "request_status" -> UpdateType.REQUEST_STATUS
            else -> UpdateType.SYSTEM
        }
        CoroutineScope(Dispatchers.IO).launch {
            UpdatesRepository(LifeLinkDatabase.getInstance(context).updateDao()).record(
                id = eventId,
                type = updateType,
                title = title,
                body = body,
                requestId = requestId,
                actionKey = if (requestId != null) "request" else "updates"
            )
        }
        if (!canPostNotification) return
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            if (requestId != null) putExtra("request_id", requestId) else putExtra("open_updates", true)
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
