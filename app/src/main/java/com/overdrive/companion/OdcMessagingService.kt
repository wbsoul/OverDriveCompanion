package com.overdrive.companion

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

/**
 * Handles FCM token refresh events.
 * When a new token is issued, clear any previously registered state
 * so MainActivity will re-register on next launch.
 */
class OdcMessagingService : FirebaseMessagingService() {

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        val prefs = AppPreferences(applicationContext)
        prefs.fcmToken = token
        // Clear registered flag — MainActivity will re-register on next resume
        prefs.pushNotificationRegistered = false
    }

    override fun onMessageReceived(message: RemoteMessage) {
        super.onMessageReceived(message)
        // Future: handle incoming push messages from OverDrive backend
    }
}
