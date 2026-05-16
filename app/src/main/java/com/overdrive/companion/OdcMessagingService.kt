package com.overdrive.companion

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

/**
 * Handles FCM token refresh and incoming messages.
 *
 * When the app is in the foreground, FCM does not auto-display the notification —
 * onMessageReceived fires instead. We build and post the notification here so the
 * user always sees motion/recording alerts regardless of app state.
 */
class OdcMessagingService : FirebaseMessagingService() {

    companion object {
        const val CHANNEL_ID = "overdrive_alerts"
        // Key names match the FCM data payload fields so they work for both
        // foreground (set by this service) and background (put by FCM into the intent).
        const val EXTRA_VIDEO_URL     = "video_url"
        const val EXTRA_THUMBNAIL_URL = "thumbnail_url"
        const val EXTRA_ACTION        = "action"
        // Title/body are NOT in the FCM data block, so they are only available
        // for foreground notifications (set below). Background taps fall back to
        // the app name in MainActivity.
        const val EXTRA_TITLE         = "notification_title"
        const val EXTRA_BODY          = "notification_body"
        private const val NOTIFICATION_ID = 1001
    }

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        val prefs = AppPreferences(applicationContext)
        prefs.fcmToken = token
        // Clear registered flag — MainActivity will re-register on next resume
        prefs.pushNotificationRegistered = false
    }

    override fun onMessageReceived(message: RemoteMessage) {
        super.onMessageReceived(message)

        // Resolve title/body — prefer the notification block, fall back to data fields
        val title = message.notification?.title
            ?: message.data["title"]
            ?: getString(R.string.app_name)
        val body = message.notification?.body
            ?: message.data["body"]
            ?: return  // nothing meaningful to show

        val videoUrl     = message.data[EXTRA_VIDEO_URL]
        val thumbnailUrl = message.data[EXTRA_THUMBNAIL_URL]
        val action       = message.data[EXTRA_ACTION]

        // Build the intent that fires when the user taps the notification.
        // Title and body are included so the dialog can display them even when
        // onMessageReceived fired in the foreground (FCM background taps carry
        // video_url / thumbnail_url / action directly from the data block).
        val tapIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_TITLE, title)
            putExtra(EXTRA_BODY, body)
            if (!videoUrl.isNullOrBlank())     putExtra(EXTRA_VIDEO_URL, videoUrl)
            if (!thumbnailUrl.isNullOrBlank()) putExtra(EXTRA_THUMBNAIL_URL, thumbnailUrl)
            if (!action.isNullOrBlank())       putExtra(EXTRA_ACTION, action)
        }
        val pendingFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        else
            PendingIntent.FLAG_UPDATE_CURRENT
        val pendingIntent = PendingIntent.getActivity(this, 0, tapIntent, pendingFlags)

        ensureNotificationChannel()

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_qr_scan)
            .setContentTitle(title)
            .setContentText(body)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()

        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, notification)
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (manager.getNotificationChannel(CHANNEL_ID) == null) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.notification_channel_name),
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = getString(R.string.notification_channel_description)
                }
                manager.createNotificationChannel(channel)
            }
        }
    }
}
