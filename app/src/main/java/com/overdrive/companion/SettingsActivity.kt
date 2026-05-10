package com.overdrive.companion

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.EditText
import androidx.activity.result.contract.ActivityResultContracts
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.textfield.TextInputLayout
import com.google.firebase.messaging.FirebaseMessaging
import com.overdrive.companion.databinding.ActivitySettingsBinding
import org.json.JSONException
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var prefs: AppPreferences

    companion object {
        private const val FCM_REGISTER_PATH = "/api/fcm/register"
        private const val FCM_STATUS_PATH = "/api/fcm/status"
        private const val CONNECT_TIMEOUT_MS = 8_000
        private const val READ_TIMEOUT_MS = 8_000
    }

    private val scannerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val url = result.data?.getStringExtra(ScannerActivity.EXTRA_SCANNED_URL)
            if (!url.isNullOrBlank()) {
                prefs.savedUrl = url
                // Return to MainActivity so it reloads with the new URL
                val intent = Intent(this, MainActivity::class.java)
                intent.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK
                startActivity(intent)
                finish()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        prefs = AppPreferences(this)

        val currentUrl = prefs.savedUrl ?: getString(R.string.no_url_saved)
        binding.tvCurrentUrl.text = getString(R.string.current_portal_url, currentUrl)

        updatePushStatusUi()

        binding.btnClearLog.setOnClickListener {
            binding.tvDebugLog.text = getString(R.string.debug_log_empty)
        }

        log("JWT stored: ${if (prefs.authJwt != null) "yes (${prefs.authJwt!!.length} chars)" else "NO — not captured from portal"}")
        log("Portal URL: ${prefs.savedUrl ?: "not set"}")
        log("Local push registered: ${prefs.pushNotificationRegistered}")

        checkServerRegistrationStatus()

        binding.btnRegisterPush.setOnClickListener {
            onRegisterPushClicked()
        }

        binding.btnLogout.setOnClickListener {
            showLogoutConfirmation(currentUrl)
        }
    }

    private fun log(message: String) {
        val ts = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        runOnUiThread {
            val current = binding.tvDebugLog.text.toString()
            val empty = getString(R.string.debug_log_empty)
            binding.tvDebugLog.text = if (current == empty) "[$ts] $message"
                                      else "$current\n[$ts] $message"
        }
    }

    private fun updatePushStatusUi() {
        val registered = prefs.pushNotificationRegistered
        val pushStatusText = if (registered)
            getString(R.string.push_status_registered)
        else
            getString(R.string.push_status_not_registered)
        binding.tvPushStatus.text = getString(R.string.push_status_label, pushStatusText)
        binding.btnRegisterPush.visibility = if (registered) View.GONE else View.VISIBLE
    }

    /**
     * Query GET /api/fcm/status to sync the real server-side registration state
     * into local prefs and refresh the UI. Runs on a background thread.
     */
    private fun checkServerRegistrationStatus() {
        val jwt = prefs.authJwt
        if (jwt == null) {
            log("Status check skipped: no JWT")
            return
        }
        val baseUrl = prefs.savedUrl
        if (baseUrl == null) {
            log("Status check skipped: no portal URL")
            return
        }
        log("Checking server registration status…")
        Thread {
            val registered: Boolean? = try {
                val normalised = if (baseUrl.startsWith("http://") || baseUrl.startsWith("https://"))
                    baseUrl else "https://$baseUrl"
                val statusUrl = java.net.URL(normalised.trimEnd('/') + FCM_STATUS_PATH)
                log("GET $statusUrl")
                val conn = statusUrl.openConnection() as HttpURLConnection
                conn.apply {
                    requestMethod = "GET"
                    setRequestProperty("Authorization", "Bearer $jwt")
                    connectTimeout = CONNECT_TIMEOUT_MS
                    readTimeout = READ_TIMEOUT_MS
                }
                val responseCode = conn.responseCode
                log("Status GET → HTTP $responseCode")
                if (responseCode in 200..299) {
                    val body = conn.inputStream.bufferedReader().readText()
                    conn.disconnect()
                    log("Status body: $body")
                    try { JSONObject(body).optBoolean("registered", false) } catch (e: JSONException) { null }
                } else {
                    val errBody = conn.errorStream?.bufferedReader()?.readText() ?: ""
                    conn.disconnect()
                    log("Status error body: $errBody")
                    null
                }
            } catch (e: Exception) {
                log("Status check exception: ${e.message}")
                null
            }
            runOnUiThread {
                if (registered != null) {
                    log("Server says registered=$registered — updating local prefs")
                    prefs.pushNotificationRegistered = registered
                    updatePushStatusUi()
                } else {
                    log("Status check inconclusive — local state unchanged")
                }
            }
        }.start()
    }

    private fun onRegisterPushClicked() {
        log("------------------------------------")
        val jwt = prefs.authJwt
        log("Register tapped — JWT: ${if (jwt != null) "present (${jwt.length} chars)" else "MISSING"}")
        if (jwt.isNullOrBlank()) {
            showErrorDialog(R.string.push_register_no_jwt)
            return
        }

        binding.btnRegisterPush.isEnabled = false
        binding.btnRegisterPush.text = getString(R.string.push_register_checking)

        // Step 1: check whether another device is already registered on the server
        val baseUrl = prefs.savedUrl ?: run {
            resetRegisterButton()
            return
        }
        log("Checking status before registration…")
        Thread {
            val alreadyRegistered: Boolean? = try {
                val normalised = if (baseUrl.startsWith("http://") || baseUrl.startsWith("https://"))
                    baseUrl else "https://$baseUrl"
                val statusUrl = URL(normalised.trimEnd('/') + FCM_STATUS_PATH)
                log("GET $statusUrl")
                val conn = statusUrl.openConnection() as HttpURLConnection
                conn.apply {
                    requestMethod = "GET"
                    setRequestProperty("Authorization", "Bearer $jwt")
                    connectTimeout = CONNECT_TIMEOUT_MS
                    readTimeout = READ_TIMEOUT_MS
                }
                val responseCode = conn.responseCode
                log("Pre-register status GET → HTTP $responseCode")
                if (responseCode in 200..299) {
                    val body = conn.inputStream.bufferedReader().readText()
                    conn.disconnect()
                    log("Pre-register status body: $body")
                    try { JSONObject(body).optBoolean("registered", false) } catch (e: JSONException) { null }
                } else {
                    val errBody = conn.errorStream?.bufferedReader()?.readText() ?: ""
                    conn.disconnect()
                    log("Pre-register status error body: $errBody")
                    null
                }
            } catch (e: Exception) {
                log("Pre-register status exception: ${e.message}")
                null
            }

            runOnUiThread {
                when {
                    alreadyRegistered == null -> {
                        log("Status unreachable — proceeding anyway")
                        proceedWithFcmRegistration(jwt)
                    }
                    alreadyRegistered -> {
                        // Another companion is registered — ask user for confirmation
                        binding.btnRegisterPush.text = getString(R.string.push_register_button)
                        AlertDialog.Builder(this, R.style.Theme_OverDriveCompanion_Dialog)
                            .setTitle(R.string.push_register_replace_title)
                            .setMessage(R.string.push_register_replace_message)
                            .setPositiveButton(R.string.push_register_replace_confirm) { _, _ ->
                                binding.btnRegisterPush.isEnabled = false
                                binding.btnRegisterPush.text = getString(R.string.push_register_in_progress)
                                proceedWithFcmRegistration(jwt)
                            }
                            .setNegativeButton(R.string.cancel) { _, _ ->
                                resetRegisterButton()
                            }
                            .setOnCancelListener { resetRegisterButton() }
                            .show()
                    }
                    else -> {
                        log("No existing registration — proceeding")
                        proceedWithFcmRegistration(jwt)
                    }
                }
            }
        }.start()
    }

    private fun resetRegisterButton() {
        binding.btnRegisterPush.isEnabled = true
        binding.btnRegisterPush.text = getString(R.string.push_register_button)
    }

    private fun proceedWithFcmRegistration(jwt: String) {
        binding.btnRegisterPush.text = getString(R.string.push_register_in_progress)
        log("Fetching FCM token…")
        FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
            if (!task.isSuccessful || task.result == null) {
                val exMsg = task.exception?.message ?: ""
                log("FCM token fetch failed: $exMsg")
                resetRegisterButton()
                val userMsg = when {
                    exMsg.contains("MISSING_INSTANCEID_SERVICE", ignoreCase = true) ||
                    exMsg.contains("SERVICE_NOT_AVAILABLE", ignoreCase = true) ->
                        getString(R.string.push_error_play_services)
                    else -> getString(R.string.push_error_fcm_token)
                }
                showErrorDialog(userMsg)
                return@addOnCompleteListener
            }
            val fcmToken = task.result
            log("FCM token obtained (${fcmToken.length} chars)")
            prefs.fcmToken = fcmToken
            registerTokenWithJwt(fcmToken, jwt)
        }
    }

    private fun registerTokenWithJwt(fcmToken: String, jwt: String) {
        val baseUrl = prefs.savedUrl ?: return
        log("POSTing FCM token to backend…")
        Thread {
            var httpCode: Int? = null
            val success = try {
                val normalised = if (baseUrl.startsWith("http://") || baseUrl.startsWith("https://"))
                    baseUrl else "https://$baseUrl"
                val registerUrl = URL(normalised.trimEnd('/') + FCM_REGISTER_PATH)
                log("POST $registerUrl")
                val conn = registerUrl.openConnection() as HttpURLConnection
                conn.apply {
                    requestMethod = "POST"
                    setRequestProperty("Content-Type", "application/json")
                    setRequestProperty("Authorization", "Bearer $jwt")
                    connectTimeout = CONNECT_TIMEOUT_MS
                    readTimeout = READ_TIMEOUT_MS
                    doOutput = true
                }
                val body = """{"token":"$fcmToken"}"""
                OutputStreamWriter(conn.outputStream).use { it.write(body) }
                httpCode = conn.responseCode
                log("Register POST → HTTP $httpCode")
                if (httpCode!! in 200..299) {
                    val responseBody = conn.inputStream.bufferedReader().readText()
                    conn.disconnect()
                    log("Register response body: $responseBody")
                    try { JSONObject(responseBody).optString("status") == "ok" } catch (e: JSONException) { false }
                } else {
                    val errBody = conn.errorStream?.bufferedReader()?.readText() ?: ""
                    conn.disconnect()
                    log("Register error body: $errBody")
                    false
                }
            } catch (e: Exception) {
                log("Register exception: ${e.message}")
                false
            }

            runOnUiThread {
                if (success) {
                    log("Registration successful")
                    prefs.pushNotificationRegistered = true
                    updatePushStatusUi()
                } else {
                    log("Registration failed")
                    resetRegisterButton()
                    val code = httpCode
                    val message = when {
                        code == null        -> getString(R.string.push_error_network)
                        code == 401         -> getString(R.string.push_error_auth)
                        code in 500..599    -> getString(R.string.push_error_server, code)
                        else                -> getString(R.string.push_error_unknown, code)
                    }
                    showErrorDialog(message)
                }
            }
        }.start()
    }

    private fun showErrorDialog(messageRes: Int) {
        showErrorDialog(getString(messageRes))
    }

    private fun showErrorDialog(message: String) {
        AlertDialog.Builder(this, R.style.Theme_OverDriveCompanion_Dialog)
            .setTitle(R.string.error_title)
            .setMessage(message)
            .setPositiveButton(R.string.ok, null)
            .show()
    }

    private fun showLogoutConfirmation(currentUrl: String) {
        AlertDialog.Builder(this, R.style.Theme_OverDriveCompanion_Dialog)
            .setTitle(R.string.logout_title)
            .setMessage(getString(R.string.logout_message, currentUrl))
            .setPositiveButton(R.string.change_portal_scan) { _, _ ->
                prefs.savedUrl = null
                prefs.pushNotificationRegistered = false
                scannerLauncher.launch(Intent(this, ScannerActivity::class.java))
            }
            .setNeutralButton(R.string.change_portal_type) { _, _ ->
                prefs.savedUrl = null
                prefs.pushNotificationRegistered = false
                showUrlInputDialog()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showUrlInputDialog() {
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_url_input, null)
        val etUrl = view.findViewById<EditText>(R.id.etUrl)
        val tilUrl = view.findViewById<TextInputLayout>(R.id.tilUrl)

        val dialog = AlertDialog.Builder(this, R.style.Theme_OverDriveCompanion_Dialog)
            .setTitle(R.string.enter_url_dialog_title)
            .setView(view)
            .setPositiveButton(R.string.enter_url_confirm, null)
            .setNegativeButton(R.string.cancel, null)
            .create()

        dialog.show()

        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val url = etUrl.text?.toString()?.trim() ?: ""
            if (url.isBlank()) {
                tilUrl.error = getString(R.string.enter_url_invalid)
                return@setOnClickListener
            }
            tilUrl.error = null
            prefs.savedUrl = url
            dialog.dismiss()
            val intent = Intent(this, MainActivity::class.java)
            intent.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK
            startActivity(intent)
            finish()
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}
