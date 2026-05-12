package com.overdrive.companion

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.webkit.GeolocationPermissions
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.textfield.TextInputLayout
import com.google.firebase.installations.FirebaseInstallations
import com.google.firebase.messaging.FirebaseMessaging
import com.overdrive.companion.databinding.ActivityMainBinding
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONException
import org.json.JSONObject

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: AppPreferences

    companion object {
        private const val FCM_REGISTER_PATH = "/api/fcm/register"
        private const val CONNECT_TIMEOUT_MS = 8_000
        private const val READ_TIMEOUT_MS = 8_000

        /**
         * Injected into the WebView after every page load.
         * Reads the JWT from the portal's known storage locations:
         *   - localStorage key: 'byd_jwt'
         *   - Cookie name:      'byd_session'
         * Source: auth.js in the OverDrive portal (BYDAuth.STORAGE_KEY / COOKIE_NAME)
         */
        private const val JWT_PROBE_SCRIPT = """
            (function() {
                var token = null;

                // 1. Try localStorage first (primary, works through tunnels)
                try {
                    token = localStorage.getItem('byd_jwt');
                } catch(e) {}

                // 2. Fallback: read the byd_session cookie
                if (!token) {
                    try {
                        var cookies = document.cookie.split(';');
                        for (var i = 0; i < cookies.length; i++) {
                            var parts = cookies[i].trim().split('=');
                            if (parts[0] === 'byd_session' && parts[1]) {
                                token = parts[1];
                                break;
                            }
                        }
                    } catch(e) {}
                }

                if (token && token.length > 10) {
                    AndroidBridge.setAuthToken(token);
                }
            })();
        """
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            attemptFcmRegistration()
        }
        // If denied, we silently skip — push simply won't work
    }

    // Holds the WebChromeClient geolocation callback until the system permission result arrives
    private var locationPermissionCallback: ((Boolean) -> Unit)? = null

    private val locationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        locationPermissionCallback?.invoke(granted)
        locationPermissionCallback = null
    }

    private val scannerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val url = result.data?.getStringExtra(ScannerActivity.EXTRA_SCANNED_URL)
            if (!url.isNullOrBlank()) {
                applyNewUrl(url)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = AppPreferences(this)

        setupWebView()

        binding.btnStartScan.setOnClickListener {
            scannerLauncher.launch(Intent(this, ScannerActivity::class.java))
        }

        binding.btnTypeUrl.setOnClickListener {
            showUrlInputDialog()
        }

        binding.btnSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        binding.btnRefresh.setOnClickListener {
            val url = prefs.savedUrl
            if (!url.isNullOrBlank()) {
                loadUrl(url)
            }
        }

        val savedUrl = prefs.savedUrl
        if (savedUrl.isNullOrBlank()) {
            showWelcome()
        } else {
            showWebView(savedUrl)
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (binding.webViewContainer.visibility == View.VISIBLE && binding.webView.canGoBack()) {
                    binding.webView.goBack()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })
    }

    override fun onResume() {
        super.onResume()
        // Attempt FCM registration each resume if not yet registered and a URL is configured
        if (!prefs.savedUrl.isNullOrBlank() && !prefs.pushNotificationRegistered) {
            requestNotificationPermissionThenRegister()
        }
    }

    // ---------- FCM registration flow ----------

    private fun requestNotificationPermissionThenRegister() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            when {
                ContextCompat.checkSelfPermission(
                    this, Manifest.permission.POST_NOTIFICATIONS
                ) == PackageManager.PERMISSION_GRANTED -> attemptFcmRegistration()

                else -> notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        } else {
            attemptFcmRegistration()
        }
    }

    private fun attemptFcmRegistration() {
        FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
            if (!task.isSuccessful || task.result == null) return@addOnCompleteListener
            val token = task.result
            prefs.fcmToken = token
            val baseUrl = prefs.savedUrl ?: return@addOnCompleteListener
            FirebaseInstallations.getInstance().id.addOnCompleteListener { idTask ->
                val installationId = if (idTask.isSuccessful) idTask.result else null
                if (installationId != null) prefs.installationId = installationId
                registerTokenWithBackend(baseUrl, token, installationId ?: prefs.installationId)
            }
        }
    }

    /**
     * POST the FCM token to the OverDrive backend.
     * If the endpoint is absent (4xx/5xx) or unreachable, show a local notification
     * informing the user that push notifications are not supported on that backend.
     */
    private fun registerTokenWithBackend(baseUrl: String, token: String, installationId: String?) {
        Thread {
            val success = try {
                val normalised = if (baseUrl.startsWith("http://") || baseUrl.startsWith("https://"))
                    baseUrl else "https://$baseUrl"
                val url = URL(normalised.trimEnd('/') + FCM_REGISTER_PATH)
                val conn = url.openConnection() as HttpURLConnection
                conn.apply {
                    requestMethod = "POST"
                    setRequestProperty("Content-Type", "application/json")
                    val jwt = prefs.authJwt
                    if (!jwt.isNullOrBlank()) {
                        setRequestProperty("Authorization", "Bearer $jwt")
                    }
                    connectTimeout = CONNECT_TIMEOUT_MS
                    readTimeout = READ_TIMEOUT_MS
                    doOutput = true
                }
                val body = JSONObject().apply {
                    put("token", token)
                    installationId?.let { put("installationId", it) }
                }.toString()
                OutputStreamWriter(conn.outputStream).use { it.write(body) }
                val responseCode = conn.responseCode
                if (responseCode in 200..299) {
                    val responseBody = conn.inputStream.bufferedReader().readText()
                    conn.disconnect()
                    try { JSONObject(responseBody).optString("status") == "ok" } catch (e: JSONException) { false }
                } else {
                    conn.disconnect()
                    false
                }
            } catch (e: Exception) {
                false
            }

            runOnUiThread {
                prefs.pushNotificationRegistered = success
            }
        }.start()
    }

    // ---------- WebView / UI helpers ----------

    private fun showWelcome() {
        binding.welcomePanel.visibility = View.VISIBLE
        binding.webViewContainer.visibility = View.GONE
        supportActionBar?.hide()
    }

    private fun applyNewUrl(url: String) {
        prefs.savedUrl = url
        prefs.pushNotificationRegistered = false
        showWebView(url)
    }

    private fun showUrlInputDialog() {
        val view = layoutInflater.inflate(R.layout.dialog_url_input, null)
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
            dialog.dismiss()
            applyNewUrl(url)
        }
    }

    private fun showWebView(url: String) {
        binding.welcomePanel.visibility = View.GONE
        binding.webViewContainer.visibility = View.VISIBLE
        loadUrl(url)
    }

    /**
     * JavaScript bridge injected into the portal WebView.
     * The portal page should call window.AndroidBridge.setAuthToken(jwt)
     * after a successful login to enable JWT-authenticated push registration.
     */
    inner class AndroidBridge {
        @JavascriptInterface
        fun setAuthToken(jwt: String) {
            if (jwt.isNotBlank()) {
                prefs.authJwt = jwt
            }
        }
    }

    private fun setupWebView() {
        binding.webView.apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.loadWithOverviewMode = true
            settings.useWideViewPort = true
            settings.setGeolocationEnabled(true)
            addJavascriptInterface(AndroidBridge(), "AndroidBridge")
            webChromeClient = object : WebChromeClient() {
                override fun onGeolocationPermissionsShowPrompt(
                    origin: String,
                    callback: GeolocationPermissions.Callback
                ) {
                    // Check we have the native location permission first
                    val hasPermission = ContextCompat.checkSelfPermission(
                        this@MainActivity, Manifest.permission.ACCESS_FINE_LOCATION
                    ) == PackageManager.PERMISSION_GRANTED
                    if (hasPermission) {
                        // Grant the WebView origin permission, persist for session
                        callback.invoke(origin, true, false)
                    } else {
                        // Request it from the user then re-invoke once granted
                        locationPermissionCallback = { granted ->
                            callback.invoke(origin, granted, false)
                        }
                        locationPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
                    }
                }
            }
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    val uri = request.url
                    val scheme = uri.scheme ?: ""
                    val host = uri.host ?: ""

                    // intent:// — parse and fire at the OS (e.g. Google Maps deep links)
                    if (scheme == "intent") {
                        try {
                            val intent = android.content.Intent.parseUri(
                                uri.toString(), android.content.Intent.URI_INTENT_SCHEME
                            )
                            startActivity(intent)
                        } catch (e: Exception) {
                            // No app can handle it — ignore silently
                        }
                        return true
                    }

                    // geo: links — open directly in Maps
                    if (scheme == "geo") {
                        try {
                            startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, uri))
                        } catch (e: Exception) { }
                        return true
                    }

                    // Google Maps https URLs — open in Maps app, not WebView
                    if ((scheme == "http" || scheme == "https") &&
                        (host == "maps.google.com" || host == "www.google.com" && uri.path?.startsWith("/maps") == true)) {
                        try {
                            startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, uri))
                        } catch (e: Exception) {
                            view.loadUrl(uri.toString()) // fallback to WebView if Maps not installed
                        }
                        return true
                    }

                    // All other http/https — load inside WebView
                    if (scheme == "http" || scheme == "https") {
                        view.loadUrl(uri.toString())
                    }
                    return true
                }

                override fun onPageFinished(view: WebView, url: String) {
                    super.onPageFinished(view, url)
                    // Probe common JWT storage locations so the bridge is populated
                    // even when the user is already logged in on page load.
                    view.evaluateJavascript(JWT_PROBE_SCRIPT, null)
                }
            }
        }
    }

    private fun loadUrl(url: String) {
        val normalised = if (url.startsWith("http://") || url.startsWith("https://")) url
                         else "https://$url"
        binding.webView.loadUrl(normalised)
    }

}


