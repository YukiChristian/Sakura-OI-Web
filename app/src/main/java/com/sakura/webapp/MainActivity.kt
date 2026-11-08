package com.sakura.webapp

import android.Manifest
import android.annotation.SuppressLint
import android.animation.ObjectAnimator
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.net.http.SslError
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.LinearInterpolator
import android.webkit.CookieManager
import android.webkit.ConsoleMessage
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.SslErrorHandler
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {
    private lateinit var webView: WebView
    private lateinit var contentRoot: FrameLayout
    private lateinit var connectionView: FrameLayout
    private lateinit var loadingView: FrameLayout
    private lateinit var statusBarScrim: View
    private lateinit var navigationBarScrim: View
    /** Open WebUI's declared theme color (meta[name=theme-color]) as 0xRRGGBB, or -1 if unknown. */
    private var pageThemeColor = -1
    private lateinit var retryArrow: TextView
    private var retryAnimator: ObjectAnimator? = null
    private var pendingUploadCallback: ValueCallback<Array<Uri>>? = null
    private var pendingAudioPermissionRequest: PermissionRequest? = null
    private var customView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var connectionTimeout: Runnable? = null
    private var mainFrameLoadFailed = false
    private var loadStartedAtMs = 0L
    private var launchSplashDeadlineMs = 0L
    private var pageReadyWhileSplashIsVisible = false
    private var launchSplashRunnable: Runnable? = null
    private lateinit var notificationBridge: WebNotificationBridge
    @Volatile private var trustedPageLoaded = false
    private var pendingNotificationRequestId: String? = null

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ ->
        notificationBridge.markAsked()
        resolveNotificationRequest()
    }

    private val fileChooserLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val callback = pendingUploadCallback
        pendingUploadCallback = null
        val data = result.data
        val selectedUris = if (result.resultCode == RESULT_OK && data != null) {
            val clip = data.clipData
            when {
                clip != null -> Array(clip.itemCount) { index -> clip.getItemAt(index).uri }
                data.data != null -> arrayOf(data.data!!)
                else -> null
            }
        } else null
        callback?.onReceiveValue(selectedUris)
    }

    private val microphonePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        val request = pendingAudioPermissionRequest
        pendingAudioPermissionRequest = null
        if (granted && request != null &&
            request.resources.contains(PermissionRequest.RESOURCE_AUDIO_CAPTURE)
        ) {
            request.grant(arrayOf(PermissionRequest.RESOURCE_AUDIO_CAPTURE))
        } else request?.deny()
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)

        contentRoot = FrameLayout(this).apply { setBackgroundColor(screenBackground) }
        statusBarScrim = View(this)
        navigationBarScrim = View(this)
        contentRoot.addView(statusBarScrim, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, Gravity.TOP
        ))
        contentRoot.addView(navigationBarScrim, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, Gravity.BOTTOM
        ))
        webView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            settings.domStorageEnabled = true
            settings.loadsImagesAutomatically = true
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = true
            settings.setSupportZoom(true)
            settings.builtInZoomControls = true
            settings.displayZoomControls = false
            webViewClient = object : WebViewClient() {
                override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
                    super.onPageStarted(view, url, favicon)
                    trustedPageLoaded = isOpenWebUiOrigin(Uri.parse(url))
                    if (trustedPageLoaded) view.evaluateJavascript(WebNotificationBridge.SHIM_JS, null)
                }

                override fun onPageFinished(view: WebView, url: String) {
                    super.onPageFinished(view, url)
                    trustedPageLoaded = isOpenWebUiOrigin(Uri.parse(url))
                    if (trustedPageLoaded) persistLastPageUrl(url)
                    if (trustedPageLoaded) {
                        view.evaluateJavascript(WebNotificationBridge.SHIM_JS, null)
                        view.evaluateJavascript(PageThemeBridge.WATCH_JS, null)
                    }
                    if (!mainFrameLoadFailed) confirmPageLoaded(view)
                }

                /**
                 * Links that leave Open WebUI open in the phone's default browser instead of
                 * replacing the app's page. Server redirects stay inside the WebView so sign-in
                 * flows that bounce through another host keep working.
                 */
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    if (!request.isForMainFrame || request.isRedirect) return false
                    val uri = request.url
                    if (isOpenWebUiOrigin(uri)) return false
                    if (uri.scheme?.lowercase() in IN_APP_SCHEMES) return false
                    openExternally(uri)
                    return true
                }

                override fun onReceivedError(
                    view: WebView,
                    request: WebResourceRequest,
                    error: WebResourceError
                ) {
                    super.onReceivedError(view, request, error)
                    if (request.isForMainFrame) handleMainFrameFailure()
                }

                override fun onReceivedHttpError(
                    view: WebView,
                    request: WebResourceRequest,
                    errorResponse: WebResourceResponse
                ) {
                    super.onReceivedHttpError(view, request, errorResponse)
                    if (request.isForMainFrame && errorResponse.statusCode >= 500) {
                        handleMainFrameFailure()
                    }
                }

                override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
                    handler.cancel()
                    handleMainFrameFailure()
                }
            }
            webChromeClient = object : WebChromeClient() {
                override fun onShowFileChooser(
                    webView: WebView?,
                    filePathCallback: ValueCallback<Array<Uri>>?,
                    fileChooserParams: FileChooserParams?
                ): Boolean {
                    pendingUploadCallback?.onReceiveValue(null)
                    if (filePathCallback == null) return false
                    pendingUploadCallback = filePathCallback
                    val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE)
                        type = "*/*"
                        putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                    }
                    return try {
                        fileChooserLauncher.launch(intent)
                        true
                    } catch (_: Exception) {
                        pendingUploadCallback = null
                        filePathCallback.onReceiveValue(null)
                        Toast.makeText(this@MainActivity, "No se pudo abrir el selector de archivos", Toast.LENGTH_LONG).show()
                        true
                    }
                }

                override fun onPermissionRequest(request: PermissionRequest) {
                    runOnUiThread {
                        val trustedOrigin = isOpenWebUiOrigin(request.origin)
                        val wantsAudio = request.resources.contains(PermissionRequest.RESOURCE_AUDIO_CAPTURE)
                        Log.i(TAG, "WebView permission request: origin=${request.origin}, resources=${request.resources.contentToString()}")
                        if (!trustedOrigin || !wantsAudio) {
                            Log.w(TAG, "WebView permission request denied: trustedOrigin=$trustedOrigin, wantsAudio=$wantsAudio")
                            request.deny()
                            return@runOnUiThread
                        }
                        if (ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                            Log.i(TAG, "Granting WebView microphone access")
                            request.grant(arrayOf(PermissionRequest.RESOURCE_AUDIO_CAPTURE))
                        } else {
                            pendingAudioPermissionRequest?.deny()
                            pendingAudioPermissionRequest = request
                            microphonePermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        }
                    }
                }

                override fun onPermissionRequestCanceled(request: PermissionRequest) {
                    if (pendingAudioPermissionRequest === request) pendingAudioPermissionRequest = null
                }

                override fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean {
                    val message = consoleMessage.message()
                    if (message.startsWith(MEDIA_DIAGNOSTIC_PREFIX)) {
                        val detail = message.removePrefix(MEDIA_DIAGNOSTIC_PREFIX)
                        Log.w(TAG, "Media diagnostics: $detail")
                        if (detail != MEDIA_READY_MESSAGE) {
                            runOnUiThread {
                                Toast.makeText(this@MainActivity, detail, Toast.LENGTH_LONG).show()
                            }
                        }
                        return true
                    }
                    return super.onConsoleMessage(consoleMessage)
                }

                override fun onShowCustomView(view: View, callback: CustomViewCallback) {
                    if (customView != null) {
                        callback.onCustomViewHidden()
                        return
                    }
                    customView = view
                    customViewCallback = callback
                    contentRoot.visibility = View.GONE
                    (window.decorView as? FrameLayout)?.addView(view, FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    ))
                }

                override fun onHideCustomView() = hideCustomView()
            }
        }
        notificationBridge = WebNotificationBridge(
            context = applicationContext,
            isTrustedPage = { trustedPageLoaded },
            requestSystemPermission = { requestId ->
                runOnUiThread { requestNotificationPermission(requestId) }
            }
        )
        webView.addJavascriptInterface(notificationBridge, WebNotificationBridge.JS_NAME)
        webView.addJavascriptInterface(
            PageThemeBridge(isTrustedPage = { trustedPageLoaded }) { themeColor ->
                runOnUiThread {
                    pageThemeColor = themeColor
                    updateSystemBars()
                }
            },
            PageThemeBridge.JS_NAME
        )
        contentRoot.addView(webView, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ))

        loadingView = createLoadingView()
        contentRoot.addView(loadingView, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ))
        connectionView = createConnectionView().apply { visibility = View.GONE }
        contentRoot.addView(connectionView, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ))
        setContentView(contentRoot)

        ViewCompat.setOnApplyWindowInsetsListener(contentRoot) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            val margins = intArrayOf(
                bars.left,
                bars.top,
                bars.right,
                maxOf(bars.bottom, ime.bottom)
            )
            listOf(webView, loadingView, connectionView).forEach { view ->
                (view.layoutParams as FrameLayout.LayoutParams).apply {
                    setMargins(margins[0], margins[1], margins[2], margins[3])
                }.also { view.layoutParams = it }
            }
            statusBarScrim.layoutParams = statusBarScrim.layoutParams.apply { height = bars.top }
            navigationBarScrim.layoutParams = navigationBarScrim.layoutParams.apply { height = bars.bottom }
            insets
        }
        updateSystemBars()
        ViewCompat.requestApplyInsets(contentRoot)
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(webView, true)
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (customView != null) hideCustomView() else closeWebDialogOrNavigateBack()
            }
        })

        launchSplashDeadlineMs = SystemClock.elapsedRealtime() + SPLASH_MIN_DURATION_MS
        val restored = savedInstanceState != null && webView.restoreState(savedInstanceState) != null
        if (restored) {
            beginHomeLoad(loadPage = false)
        } else {
            // Android may have killed the process while the app was in the background.
            // Reopen the last Open WebUI route behind the Sakura loading screen.
            beginHomeLoad(lastKnownPageUrl())
        }
    }


    private fun confirmPageLoaded(view: WebView) {
        val script = """
            (function() {
                const text = (document.body && document.body.innerText || '').trim();
                const html = (document.documentElement && document.documentElement.innerHTML) || '';
                const title = document.title || '';
                const browserError = /web page not available|net::err_|err_ssl_protocol_error|err_connection_/i;
                return location.hostname === '$HOME_HOST' &&
                    !browserError.test(text + ' ' + title) && html.length > 300;
            })();
        """.trimIndent()
        view.evaluateJavascript(script) { result ->
            if (isFinishing || mainFrameLoadFailed || loadingView.visibility != View.VISIBLE) return@evaluateJavascript
            if (result == "true") {
                installMediaDiagnostics(view)
                val splashRemainingMs = launchSplashDeadlineMs - SystemClock.elapsedRealtime()
                if (splashRemainingMs > 0L) {
                    pageReadyWhileSplashIsVisible = true
                    if (launchSplashRunnable == null) {
                        launchSplashRunnable = Runnable {
                            launchSplashRunnable = null
                            if (pageReadyWhileSplashIsVisible && !mainFrameLoadFailed) {
                                revealLoadedPage()
                            }
                        }.also { mainHandler.postDelayed(it, splashRemainingMs) }
                    }
                    return@evaluateJavascript
                }
                revealLoadedPage()
            } else {
                handleMainFrameFailure()
            }
        }
    }
    private fun revealLoadedPage() {
        if (isFinishing || mainFrameLoadFailed) return
        pageReadyWhileSplashIsVisible = false
        stopRetryAnimation()
        loadingView.visibility = View.GONE
        connectionView.visibility = View.GONE
        webView.visibility = View.VISIBLE
        updateSystemBars()
        cancelConnectionTimeout()
        askForNotificationsOnce()
    }

    /**
     * Open WebUI keeps its notification setting on the server, so a user who enabled it in
     * another browser never triggers Notification.requestPermission() here. Ask Android directly.
     */
    private fun askForNotificationsOnce() {
        if (Build.VERSION.SDK_INT >= 33 && pendingNotificationRequestId == null &&
            notificationBridge.currentPermission() == "default"
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun installMediaDiagnostics(view: WebView) {
        val script = """
            (function() {
                const prefix = '$MEDIA_DIAGNOSTIC_PREFIX';
                try {
                    if (!window.isSecureContext) {
                        console.error(prefix + 'El sitio no es un contexto seguro; revisa el certificado HTTPS.');
                        return;
                    }
                    const devices = navigator.mediaDevices;
                    if (!devices || typeof devices.getUserMedia !== 'function') {
                        console.error(prefix + 'WebView no ofrece mediaDevices.getUserMedia.');
                        return;
                    }
                    if (devices.__sakuraDiagnosticInstalled) return;
                    const original = devices.getUserMedia.bind(devices);
                    devices.getUserMedia = function() {
                        let pending;
                        try {
                            pending = original.apply(devices, arguments);
                        } catch (error) {
                            console.error(prefix + error.name + ': ' + error.message);
                            throw error;
                        }
                        return Promise.resolve(pending).catch(function(error) {
                            console.error(prefix + error.name + ': ' + error.message);
                            throw error;
                        });
                    };
                    Object.defineProperty(devices, '__sakuraDiagnosticInstalled', { value: true });
                    console.info(prefix + '$MEDIA_READY_MESSAGE');
                } catch (error) {
                    console.error(prefix + 'No se pudo revisar el micrófono: ' + error.message);
                }
            })();
        """.trimIndent()
        view.evaluateJavascript(script, null)
    }

    private fun createLoadingView(): FrameLayout {
        val root = FrameLayout(this).apply { setBackgroundColor(screenBackground) }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(32), dp(24), dp(32), dp(24))
        }
        val logo = ImageView(this).apply {
            setImageResource(R.drawable.sakura_logo)
            contentDescription = getString(R.string.app_name)
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        content.addView(logo, LinearLayout.LayoutParams(dp(96), dp(96)).apply {
            gravity = Gravity.CENTER_HORIZONTAL
        })
        val name = TextView(this).apply {
            text = getString(R.string.app_name)
            textSize = 20f
            setTextColor(themed(Color.rgb(32, 32, 32), Color.rgb(236, 236, 236)))
            gravity = Gravity.CENTER
            setPadding(0, dp(14), 0, 0)
        }
        content.addView(name)
        val version = TextView(this).apply {
            text = "Versión ${BuildConfig.VERSION_NAME}"
            textSize = 13f
            setTextColor(themed(Color.rgb(120, 120, 120), Color.rgb(170, 170, 170)))
            gravity = Gravity.CENTER
            setPadding(0, dp(2), 0, dp(8))
        }
        content.addView(version)
        // Black on the light screen; white in dark mode, where black would not show.
        val loadingColor = themed(Color.BLACK, Color.WHITE)
        val progress = LogoSpinnerView(this, loadingColor).apply {
            contentDescription = getString(R.string.loading_description)
        }
        content.addView(progress, LinearLayout.LayoutParams(dp(44), dp(44)).apply {
            topMargin = dp(18)
            gravity = Gravity.CENTER_HORIZONTAL
        })
        val label = TextView(this).apply {
            text = "Conectándose con el servidor…"
            textSize = 18f
            setTextColor(loadingColor)
            gravity = Gravity.CENTER
            setPadding(0, dp(16), 0, 0)
        }
        content.addView(label)
        root.addView(content, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.CENTER
        ))
        return root
    }

    private fun createConnectionView(): FrameLayout {
        val root = FrameLayout(this).apply { setBackgroundColor(screenBackground) }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(32), dp(24), dp(32), dp(24))
        }
        val icon = ImageView(this).apply {
            setImageResource(R.drawable.sakura_logo)
            contentDescription = getString(R.string.connection_icon_description)
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        content.addView(icon, LinearLayout.LayoutParams(dp(88), dp(88)))
        val title = TextView(this).apply {
            text = getString(R.string.connection_title)
            textSize = 24f
            setTextColor(themed(Color.rgb(32, 32, 32), Color.rgb(236, 236, 236)))
            gravity = Gravity.CENTER
            setPadding(0, dp(20), 0, dp(12))
        }
        content.addView(title)
        val message = TextView(this).apply {
            text = getString(R.string.connection_message)
            textSize = 16f
            setTextColor(themed(Color.rgb(95, 95, 95), Color.rgb(180, 180, 180)))
            gravity = Gravity.CENTER
            setLineSpacing(dp(3).toFloat(), 1f)
        }
        content.addView(message)
        // Black button with a white arrow, inverted in dark mode, like the loading screen.
        val retryColor = themed(Color.BLACK, Color.WHITE)
        retryArrow = TextView(this).apply {
            text = "↻"
            textSize = 34f
            gravity = Gravity.CENTER
            setTextColor(themed(Color.WHITE, Color.BLACK))
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(retryColor)
            }
            contentDescription = getString(R.string.connection_retry)
            isClickable = true
            isFocusable = true
            setOnClickListener { retryConnection() }
        }
        content.addView(retryArrow, LinearLayout.LayoutParams(dp(64), dp(64)).apply {
            topMargin = dp(24)
            gravity = Gravity.CENTER_HORIZONTAL
        })
        val retryLabel = TextView(this).apply {
            text = getString(R.string.connection_retry)
            textSize = 15f
            setTextColor(retryColor)
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, 0)
        }
        content.addView(retryLabel)
        root.addView(content, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.CENTER
        ))
        return root
    }

    private fun retryConnection() {
        startRetryAnimation()
        beginHomeLoad(lastKnownPageUrl())
    }
    private fun startRetryAnimation() {
        stopRetryAnimation()
        retryAnimator = ObjectAnimator.ofFloat(retryArrow, View.ROTATION, 0f, 360f).apply {
            duration = 850L
            repeatCount = ObjectAnimator.INFINITE
            interpolator = LinearInterpolator()
            start()
        }
    }

    private fun stopRetryAnimation() {
        retryAnimator?.cancel()
        retryAnimator = null
        if (::retryArrow.isInitialized) retryArrow.rotation = 0f
    }

    private fun beginHomeLoad(url: String = HOME_URL, loadPage: Boolean = true) {
        launchSplashRunnable?.let(mainHandler::removeCallbacks)
        launchSplashRunnable = null
        pageReadyWhileSplashIsVisible = false
        mainFrameLoadFailed = false
        loadStartedAtMs = SystemClock.elapsedRealtime()
        connectionView.visibility = View.GONE
        loadingView.visibility = View.VISIBLE
        webView.visibility = View.VISIBLE
        updateSystemBars()
        scheduleConnectionTimeout(CONNECTION_TIMEOUT_MS)
        if (loadPage) webView.loadUrl(url)
    }

    private fun handleMainFrameFailure() {
        mainFrameLoadFailed = true
        runOnUiThread {
            if (isFinishing || !::loadingView.isInitialized) return@runOnUiThread
            connectionView.visibility = View.GONE
            loadingView.visibility = View.VISIBLE
            webView.visibility = View.VISIBLE
            updateSystemBars()
            val elapsed = SystemClock.elapsedRealtime() - loadStartedAtMs
            val remaining = (CONNECTION_TIMEOUT_MS - elapsed).coerceAtLeast(0L)
            scheduleConnectionTimeout(remaining)
        }
    }

    private fun scheduleConnectionTimeout(delayMs: Long) {
        cancelConnectionTimeout()
        connectionTimeout = Runnable {
            if (loadingView.visibility == View.VISIBLE) showConnectionError()
        }.also { mainHandler.postDelayed(it, delayMs) }
    }

    private fun cancelConnectionTimeout() {
        connectionTimeout?.let(mainHandler::removeCallbacks)
        connectionTimeout = null
    }

    private fun showConnectionError() {
        runOnUiThread {
            if (!isFinishing && ::connectionView.isInitialized) {
                cancelConnectionTimeout()
                stopRetryAnimation()
                loadingView.visibility = View.GONE
                webView.visibility = View.GONE
                connectionView.visibility = View.VISIBLE
                updateSystemBars()
            }
        }
    }

    /**
     * Match both Android system bars to Open WebUI's explicit theme-color metadata.
     * Never infer the theme from screen pixels: dialogs, images and dark overlays can
     * make a light page look dark without changing the selected WebUI theme.
     */
    private fun updateSystemBars() {
        if (!::connectionView.isInitialized) return
        val showingPage = loadingView.visibility != View.VISIBLE &&
            connectionView.visibility != View.VISIBLE
        val barColor = if (showingPage && pageThemeColor >= 0) {
            pageThemeColor or 0xFF000000.toInt()
        } else screenBackground
        statusBarScrim.setBackgroundColor(barColor)
        navigationBarScrim.setBackgroundColor(barColor)
        contentRoot.setBackgroundColor(barColor)
        val useDarkIcons = ColorUtils.calculateLuminance(barColor) > 0.5
        WindowInsetsControllerCompat(window, contentRoot).apply {
            isAppearanceLightStatusBars = useDarkIcons
            isAppearanceLightNavigationBars = useDarkIcons
        }
    }

    private val nightMode: Boolean
        get() = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES

    private val screenBackground: Int
        get() = themed(Color.WHITE, Color.rgb(23, 23, 23))

    private fun themed(light: Int, dark: Int): Int = if (nightMode) dark else light

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()

    private fun isOpenWebUiOrigin(origin: Uri): Boolean =
        origin.scheme.equals("https", ignoreCase = true) &&
            origin.host.equals(HOME_HOST, ignoreCase = true) &&
            httpsPort(origin) == HOME_PORT

    /** Hands [uri] to whichever app handles it: the default browser for web links, the mail app for mailto:, etc. */
    private fun openExternally(uri: Uri) {
        val intent = Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE)
        try {
            startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, getString(R.string.external_link_unavailable), Toast.LENGTH_LONG).show()
        }
    }

    private fun persistLastPageUrl(url: String? = webView.url) {
        val currentUrl = url ?: return
        val uri = runCatching { Uri.parse(currentUrl) }.getOrNull() ?: return
        if (!isOpenWebUiOrigin(uri)) return
        getSharedPreferences(SESSION_PREFS, MODE_PRIVATE)
            .edit()
            .putString(KEY_LAST_PAGE_URL, currentUrl)
            .apply()
    }

    private fun lastKnownPageUrl(): String {
        val savedUrl = getSharedPreferences(SESSION_PREFS, MODE_PRIVATE)
            .getString(KEY_LAST_PAGE_URL, null) ?: return HOME_URL
        val uri = runCatching { Uri.parse(savedUrl) }.getOrNull() ?: return HOME_URL
        return if (isOpenWebUiOrigin(uri)) savedUrl else HOME_URL
    }

    private fun requestNotificationPermission(requestId: String) {
        pendingNotificationRequestId?.let { resolveNotificationRequest() }
        pendingNotificationRequestId = requestId
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            notificationBridge.markAsked()
            resolveNotificationRequest()
        }
    }

    private fun resolveNotificationRequest() {
        val requestId = pendingNotificationRequestId ?: return
        pendingNotificationRequestId = null
        if (!::webView.isInitialized) return
        val state = notificationBridge.currentPermission()
        if (state != "granted") {
            Toast.makeText(this, "Activa las notificaciones de Sakura en los ajustes de Android", Toast.LENGTH_LONG).show()
        }
        webView.evaluateJavascript(WebNotificationBridge.resolveScript(requestId, state), null)
    }

    private fun closeWebDialogOrNavigateBack() {
        val script = """
            (function() {
                const dialog = document.querySelector(
                    '[role="dialog"], [aria-modal="true"], dialog[open], [data-radix-dialog-content], .lightbox'
                );
                if (!dialog) return false;
                document.dispatchEvent(new KeyboardEvent('keydown', {
                    key: 'Escape', code: 'Escape', keyCode: 27, which: 27, bubbles: true
                }));
                return true;
            })();
        """.trimIndent()
        webView.evaluateJavascript(script) { handled ->
            if (handled != "true") {
                if (webView.canGoBack()) webView.goBack() else finish()
            }
        }
    }

    private fun hideCustomView() {
        val view = customView ?: return
        customView = null
        (view.parent as? ViewGroup)?.removeView(view)
        contentRoot.visibility = View.VISIBLE
        customViewCallback?.onCustomViewHidden()
        customViewCallback = null
    }

    override fun onSaveInstanceState(outState: Bundle) {
        persistLastPageUrl()
        webView.saveState(outState)
        super.onSaveInstanceState(outState)
    }

    override fun onPause() {
        persistLastPageUrl()
        webView.onPause()
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        if (::webView.isInitialized) webView.onResume()
        // Keep the service only while Open WebUI is outside the foreground.
        stopService(Intent(this, SakuraKeepAliveService::class.java))
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        // Start while this Activity is still visible, before Android backgrounds it.
        if (::webView.isInitialized && trustedPageLoaded && !isFinishing) {
            try {
                ContextCompat.startForegroundService(
                    this,
                    Intent(this, SakuraKeepAliveService::class.java)
                )
            } catch (error: Exception) {
                Log.e(TAG, "Could not keep the Open WebUI session active in background", error)
            }
        }
    }

    override fun onDestroy() {
        cancelConnectionTimeout()
        launchSplashRunnable?.let(mainHandler::removeCallbacks)
        launchSplashRunnable = null
        stopRetryAnimation()
        pendingUploadCallback?.onReceiveValue(null)
        pendingUploadCallback = null
        pendingAudioPermissionRequest?.deny()
        pendingAudioPermissionRequest = null
        if (::webView.isInitialized) {
            webView.stopLoading()
            webView.webChromeClient = null
            webView.webViewClient = WebViewClient()
            webView.destroy()
        }
        super.onDestroy()
    }

    companion object {
        private const val SESSION_PREFS = "sakura_session"
        private const val KEY_LAST_PAGE_URL = "last_open_webui_url"
        private const val HOME_URL = BuildConfig.WEBUI_URL
        private val HOME_HOST: String? = Uri.parse(HOME_URL).host
        private val HOME_PORT: Int = httpsPort(Uri.parse(HOME_URL))

        /** A URL without a port (such as a Tailscale address) uses the HTTPS default, 443. */
        private fun httpsPort(uri: Uri): Int = if (uri.port == -1) 443 else uri.port
        private const val CONNECTION_TIMEOUT_MS = 5_000L
        private const val SPLASH_MIN_DURATION_MS = 3_000L
        private const val TAG = "SakuraWebApp"
        /** Schemes the WebView handles itself (downloads, inline content); everything else opens outside. */
        private val IN_APP_SCHEMES = setOf("blob", "data", "javascript", "about")
        private const val MEDIA_DIAGNOSTIC_PREFIX = "SAKURA_MEDIA_DIAG:"
        private const val MEDIA_READY_MESSAGE = "Micrófono disponible para WebView."
    }
}
