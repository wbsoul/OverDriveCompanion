package com.overdrive.companion

import android.content.Context
import android.content.SharedPreferences

class AppPreferences(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("overdrive_prefs", Context.MODE_PRIVATE)

    var savedUrl: String?
        get() = prefs.getString(KEY_URL, null)
        set(value) = prefs.edit().putString(KEY_URL, value).apply()

    var fcmToken: String?
        get() = prefs.getString(KEY_FCM_TOKEN, null)
        set(value) = prefs.edit().putString(KEY_FCM_TOKEN, value).apply()

    var pushNotificationRegistered: Boolean
        get() = prefs.getBoolean(KEY_PUSH_REGISTERED, false)
        set(value) = prefs.edit().putBoolean(KEY_PUSH_REGISTERED, value).apply()

    var authJwt: String?
        get() = prefs.getString(KEY_AUTH_JWT, null)
        set(value) = prefs.edit().putString(KEY_AUTH_JWT, value).apply()

    var installationId: String?
        get() = prefs.getString(KEY_INSTALLATION_ID, null)
        set(value) = prefs.edit().putString(KEY_INSTALLATION_ID, value).apply()

    companion object {
        private const val KEY_URL = "portal_url"
        private const val KEY_FCM_TOKEN = "fcm_token"
        private const val KEY_PUSH_REGISTERED = "push_registered"
        private const val KEY_AUTH_JWT = "auth_jwt"
        private const val KEY_INSTALLATION_ID = "installation_id"
    }
}
