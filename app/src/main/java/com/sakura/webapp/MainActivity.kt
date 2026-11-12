package com.sakura.webapp

import android.Manifest
import android.annotation.SuppressLint
import android.animation.ObjectAnimator
import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.Uri
import android.net.http.SslError
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.LinearInterpolator
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.ConsoleMessage
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.SslErrorHandler
import android.webkit.URLUtil
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
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import kotlin.math.roundToInt
import org.json.JSONObject

class MainActivity : ComponentActivity() {
    private lateinit var webView: WebView
    private lateinit var contentRoot: FrameLayout
    private lateinit var connectionView: FrameLayout
    private lateinit var statusBarScrim: View
    private lateinit var navigationBarScrim: View
    /** Open WebUI's declared theme color (meta[name=theme-color]) as 0xRRGGBB, or -1 if unknown. */
    private var pageThemeColor = -1
    private lateinit var assetCache: ImmutableAssetCache
    private lateinit var shareBridge: ShareBridge
    private lateinit var sessionSync: SessionSync
    /** Pull-to-refresh around the WebView (Open WebUI scrolls inside the page, see REFRESH_JS). */
    private lateinit var swipeLayout: SwipeRefreshLayout
    /** Thin bar shown while the phone has no network. */
    private lateinit var offlineBanner: TextView
    @Volatile private var pageCanScrollUp = true
    /** Voice mode was asked for (shortcut, assistant, "Oye, Sakura"): accept Open WebUI's prompt. */
    private var confirmVoiceCall = false
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var online = true
    /** Saved-address version this screen loaded; a change in settings triggers a reload. */
    private var addressVersion = 0
    /** False until the first page is opened; a share or shortcut arriving earlier waits here. */
    private var firstLoadStarted = true
    private var requestBeforeFirstLoad: IncomingIntents.Request? = null
    /** "wifi" or "other"; address checks only run when this changes, not on every signal update. */
    private var lastTransport: String? = null
    private val reconcileAddressTask = Runnable { reconcileAddress() }
    private lateinit var retryArrow: TextView
    private var retryAnimator: ObjectAnimator? = null
    private var pendingUploadCallback: ValueCallback<Array<Uri>>? = null
    private var pendingAudioPermissionRequest: PermissionRequest? = null
    private var customView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null
    private var mainFrameLoadFailed = false
    private lateinit var notificationBridge: WebNotificationBridge
    private lateinit var downloadBridge: DownloadBridge
    @Volatile private var trustedPageLoaded = false
    private var pendingNotificationRequestId: String? = null

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ ->
        notificationBridge.markAsked()
        resolveNotificationRequest()
    }

    /** Android 9 and older only: saving to Downloads needs this permission. */
    private val storagePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) showToast(getString(R.string.download_retry))
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
        ServerAddress.load(this)
        if (!ServerAddress.isConfigured) {
            // No server address yet: run the setup assistant first.
            startActivity(ConnectionActivity.intent(this, setup = true))
            finish()
            return
        }
        addressVersion = ServerAddress.version(this)
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
        assetCache = ImmutableAssetCache(applicationContext, ServerAddress::isServer)
        Thread { assetCache.prune() }.start()
        webView = WebView(this).apply {
            // Shown while the first page loads, so dark mode does not flash white.
            setBackgroundColor(screenBackground)
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
                    pageCanScrollUp = true
                    trustedPageLoaded = isOpenWebUiOrigin(Uri.parse(url))
                    if (trustedPageLoaded) {
                        view.evaluateJavascript(WebNotificationBridge.SHIM_JS, null)
                        view.evaluateJavascript(DownloadBridge.SHIM_JS, null)
                    }
                }

                override fun onPageFinished(view: WebView, url: String) {
                    super.onPageFinished(view, url)
                    trustedPageLoaded = isOpenWebUiOrigin(Uri.parse(url))
                    if (trustedPageLoaded) persistLastPageUrl(url)
                    if (trustedPageLoaded) {
                        view.evaluateJavascript(WebNotificationBridge.SHIM_JS, null)
                        view.evaluateJavascript(DownloadBridge.SHIM_JS, null)
                        view.evaluateJavascript(PageThemeBridge.WATCH_JS, null)
                        view.evaluateJavascript(REFRESH_JS, null)
                        view.evaluateJavascript(SessionSync.SYNC_JS, null)
                        view.evaluateJavascript(appMenuScript(), null)
                        if (shareBridge.hasPending) view.evaluateJavascript(ShareBridge.ATTACH_JS, null)
                        // Every load of a voice-mode page, not just the first: the page can be
                        // reloaded on the home address or after the session is copied.
                        if (confirmVoiceCall || Uri.parse(url).getQueryParameter("call") == "true") {
                            confirmVoiceCall = false
                            view.evaluateJavascript(CONFIRM_VOICE_JS, null)
                        }
                    }
                    swipeLayout.isRefreshing = false
                    if (!mainFrameLoadFailed) revealLoadedPage(view)
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

                override fun shouldInterceptRequest(
                    view: WebView,
                    request: WebResourceRequest
                ): WebResourceResponse? = assetCache.intercept(request)

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
        downloadBridge = DownloadBridge(
            context = applicationContext,
            isTrustedPage = { trustedPageLoaded },
            hasLegacyStoragePermission = ::hasLegacyStoragePermission,
            onResult = { savedName, error ->
                when {
                    savedName != null -> showToast(getString(R.string.download_saved, savedName))
                    error != null -> showToast(getString(R.string.download_failed))
                }
            }
        )
        webView.addJavascriptInterface(downloadBridge, DownloadBridge.JS_NAME)
        shareBridge = ShareBridge(applicationContext, isTrustedPage = { trustedPageLoaded }) { skipped ->
            runOnUiThread {
                if (skipped > 0) showToast(getString(R.string.share_skipped, skipped))
                if (trustedPageLoaded) webView.evaluateJavascript(ShareBridge.ATTACH_JS, null)
            }
        }
        webView.addJavascriptInterface(shareBridge, ShareBridge.JS_NAME)
        sessionSync = SessionSync(applicationContext, isTrustedPage = { trustedPageLoaded }) {
            runOnUiThread { scheduleAddressCheck(500) }
        }
        webView.addJavascriptInterface(sessionSync, SessionSync.JS_NAME)
        webView.addJavascriptInterface(object {
            @JavascriptInterface
            fun setCanScrollUp(value: Boolean) {
                pageCanScrollUp = value
            }
        }, REFRESH_JS_NAME)
        webView.addJavascriptInterface(object {
            @JavascriptInterface
            fun openSettings() {
                if (!trustedPageLoaded) return
                runOnUiThread { startActivity(Intent(this@MainActivity, SettingsActivity::class.java)) }
            }
        }, APP_MENU_JS_NAME)
        webView.setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
            handleDownload(url, userAgent, contentDisposition, mimeType)
        }
        webView.addJavascriptInterface(
            PageThemeBridge(isTrustedPage = { trustedPageLoaded }) { themeColor ->
                runOnUiThread {
                    pageThemeColor = themeColor
                    updateSystemBars()
                }
            },
            PageThemeBridge.JS_NAME
        )
        swipeLayout = SwipeRefreshLayout(this).apply {
            addView(webView, ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            ))
            setOnChildScrollUpCallback { _, _ -> pageCanScrollUp || webView.scrollY > 0 }
            setOnRefreshListener { webView.reload() }
        }
        contentRoot.addView(swipeLayout, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ))

        connectionView = createConnectionView().apply { visibility = View.GONE }
        contentRoot.addView(connectionView, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ))
        offlineBanner = TextView(this).apply {
            text = getString(R.string.offline_banner)
            textSize = 13f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.argb(230, 60, 60, 60))
            setPadding(dp(12), dp(6), dp(12), dp(6))
            visibility = View.GONE
        }
        contentRoot.addView(offlineBanner, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.TOP
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
            listOf(swipeLayout, connectionView).forEach { view ->
                (view.layoutParams as FrameLayout.LayoutParams).apply {
                    setMargins(margins[0], margins[1], margins[2], margins[3])
                }.also { view.layoutParams = it }
            }
            statusBarScrim.layoutParams = statusBarScrim.layoutParams.apply { height = bars.top }
            navigationBarScrim.layoutParams = navigationBarScrim.layoutParams.apply { height = bars.bottom }
            (offlineBanner.layoutParams as FrameLayout.LayoutParams).apply { topMargin = bars.top }
                .also { offlineBanner.layoutParams = it }
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

        val request = IncomingIntents.parse(intent)
        val restored = request == null && savedInstanceState != null &&
            webView.restoreState(savedInstanceState) != null
        if (restored) {
            beginHomeLoad(loadPage = false)
            scheduleAddressCheck(0)
        } else {
            firstLoadStarted = false
            // Pick the home or exterior address first (a quick probe on Wi-Fi), then open the
            // requested page, or the last Open WebUI route if Android killed the process.
            Thread {
                ServerAddress.choose(applicationContext, allowLan = sessionSync.hasToken)
                runOnUiThread {
                    if (isFinishing) return@runOnUiThread
                    firstLoadStarted = true
                    openRequest(requestBeforeFirstLoad ?: request)
                    requestBeforeFirstLoad = null
                }
            }.start()
        }
        watchNetwork()
        if (WakeWordModel.isEnabled(this) && WakeWordModel.isReady(this) && !WakeWordService.running) {
            WakeWordService.start(this, paused = true)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val request = IncomingIntents.parse(intent) ?: return
        // Still choosing the address for the first load: open this instead of the last page.
        if (!firstLoadStarted) requestBeforeFirstLoad = request else openRequest(request)
    }

    /** Opens what a share, selected text, shortcut or the assistant asked for. */
    private fun openRequest(request: IncomingIntents.Request?) {
        val url = request?.path?.let(ServerAddress::at) ?: lastKnownPageUrl()
        confirmVoiceCall = request?.path?.contains("call=true") == true
        request?.streams?.let(shareBridge::prepare)
        beginHomeLoad(url)
    }

    /**
     * Follows network changes: shows the offline bar, retries after a failed load when the
     * network returns, and switches between the home and exterior address when needed.
     */
    private fun watchNetwork() {
        if (Build.VERSION.SDK_INT < 24) return
        val cm = getSystemService(ConnectivityManager::class.java) ?: return
        online = cm.activeNetwork != null
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = runOnUiThread { setOnline(true) }

            override fun onLost(network: Network) = runOnUiThread {
                if (cm.activeNetwork == null || cm.activeNetwork == network) setOnline(false)
            }

            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                val transport = if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
                ) "wifi" else "other"
                runOnUiThread {
                    if (lastTransport != null && lastTransport != transport) scheduleAddressCheck(1_500)
                    lastTransport = transport
                }
            }
        }
        cm.registerDefaultNetworkCallback(callback)
        networkCallback = callback
        offlineBanner.visibility = if (online) View.GONE else View.VISIBLE
    }

    private fun setOnline(value: Boolean) {
        if (isFinishing) return
        val cameBack = value && !online
        online = value
        offlineBanner.visibility = if (value) View.GONE else View.VISIBLE
        if (!value) return
        if (cameBack && connectionView.visibility == View.VISIBLE) retryConnection()
        if (cameBack) scheduleAddressCheck(1_500)
    }

    private fun scheduleAddressCheck(delayMs: Long) {
        if (ServerAddress.local == null || ServerAddress.exterior == null) return
        contentRoot.removeCallbacks(reconcileAddressTask)
        contentRoot.postDelayed(reconcileAddressTask, delayMs)
    }

    /** Reloads the same page on the other address when the network calls for it. */
    private fun reconcileAddress() {
        Thread {
            ServerAddress.choose(applicationContext, allowLan = sessionSync.hasToken)
            runOnUiThread {
                val url = webView.url ?: return@runOnUiThread
                if (isFinishing || !ServerAddress.isServer(Uri.parse(url))) return@runOnUiThread
                val moved = ServerAddress.rebase(url)
                if (moved != url) webView.loadUrl(moved)
            }
        }.start()
    }


    /**
     * The page finished loading without a network error: show it (and hide the connection
     * screen if a retry just succeeded).
     */
    private fun revealLoadedPage(view: WebView) {
        if (isFinishing || mainFrameLoadFailed) return
        installMediaDiagnostics(view)
        stopRetryAnimation()
        connectionView.visibility = View.GONE
        webView.visibility = View.VISIBLE
        updateSystemBars()
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
        content.addView(TextView(this).apply {
            text = getString(R.string.connection_configure)
            textSize = 15f
            setTextColor(themed(Color.rgb(95, 95, 95), Color.rgb(180, 180, 180)))
            gravity = Gravity.CENTER
            paintFlags = paintFlags or android.graphics.Paint.UNDERLINE_TEXT_FLAG
            setPadding(dp(16), dp(28), dp(16), dp(8))
            isClickable = true
            setOnClickListener { startActivity(ConnectionActivity.intent(this@MainActivity, setup = false)) }
        })
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

    /**
     * Shows Open WebUI straight away while it loads. The connection screen stays up only
     * during a retry, until the page either loads or fails again.
     */
    private fun beginHomeLoad(url: String = ServerAddress.current, loadPage: Boolean = true) {
        mainFrameLoadFailed = false
        if (connectionView.visibility != View.VISIBLE) webView.visibility = View.VISIBLE
        updateSystemBars()
        if (loadPage) webView.loadUrl(url)
    }

    /** Called only for real failures: no network, DNS or TLS errors, or the server answering 5xx. */
    private fun handleMainFrameFailure() {
        mainFrameLoadFailed = true
        runOnUiThread { if (::swipeLayout.isInitialized) swipeLayout.isRefreshing = false }
        showConnectionError()
    }

    private fun showConnectionError() {
        runOnUiThread {
            if (!isFinishing && ::connectionView.isInitialized) {
                stopRetryAnimation()
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
        val showingPage = connectionView.visibility != View.VISIBLE
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

    private fun isOpenWebUiOrigin(origin: Uri): Boolean = ServerAddress.isServer(origin)

    /** Hands [uri] to whichever app handles it: the default browser for web links, the mail app for mailto:, etc. */
    private fun openExternally(uri: Uri) {
        val intent = Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE)
        try {
            startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, getString(R.string.external_link_unavailable), Toast.LENGTH_LONG).show()
        }
    }

    /**
     * Downloads that reach the WebView itself: data: URLs are decoded here, blob: URLs are
     * read back by the page shim, and http(s) files go to Android's download manager with
     * the WebView's cookies so Open WebUI's protected files still download.
     */
    private fun handleDownload(url: String, userAgent: String?, contentDisposition: String?, mimeType: String?) {
        when {
            url.startsWith("data:") -> downloadBridge.saveDataUrl(url, null)
            url.startsWith("blob:") -> webView.evaluateJavascript(
                "window.__sakuraSaveUrl && window.__sakuraSaveUrl(${JSONObject.quote(url)}, '')",
                null
            )
            url.startsWith("https://") || url.startsWith("http://") -> {
                if (!hasLegacyStoragePermission()) return
                val guessed = URLUtil.guessFileName(url, contentDisposition, mimeType)
                val ext = guessed.substringAfterLast('.', "")
                val name = DownloadBridge.downloadKey() + if (ext.isNotEmpty()) ".$ext" else ""
                val request = DownloadManager.Request(Uri.parse(url)).apply {
                    setMimeType(mimeType)
                    CookieManager.getInstance().getCookie(url)?.let { addRequestHeader("Cookie", it) }
                    userAgent?.let { addRequestHeader("User-Agent", it) }
                    setTitle(name)
                    setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                    setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, "${DownloadBridge.FOLDER}/$name")
                }
                getSystemService(DownloadManager::class.java).enqueue(request)
                showToast(getString(R.string.download_started, name))
            }
        }
    }

    /** True on Android 10+; on older versions asks for WRITE_EXTERNAL_STORAGE and returns false until granted. */
    private fun hasLegacyStoragePermission(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) return true
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
            PackageManager.PERMISSION_GRANTED
        ) return true
        runOnUiThread { storagePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE) }
        return false
    }

    /** [APP_MENU_JS] with the menu label in the app's language. */
    private fun appMenuScript(): String =
        APP_MENU_JS.replace("__LABEL__", JSONObject.quote(getString(R.string.app_menu_item)))

    private fun showToast(message: String) {
        runOnUiThread { Toast.makeText(this, message, Toast.LENGTH_LONG).show() }
    }

    private fun persistLastPageUrl(url: String? = webView.url) {
        val currentUrl = url ?: return
        val uri = runCatching { Uri.parse(currentUrl) }.getOrNull() ?: return
        if (!isOpenWebUiOrigin(uri)) return
        // A shared text (?q=) or voice launch (?call=) should not come back next time.
        val keep = if (uri.getQueryParameter("q") != null || uri.getQueryParameter("call") != null) {
            uri.buildUpon().clearQuery().build().toString()
        } else currentUrl
        getSharedPreferences(SESSION_PREFS, MODE_PRIVATE)
            .edit()
            .putString(KEY_LAST_PAGE_URL, keep)
            .apply()
    }

    private fun lastKnownPageUrl(): String {
        val savedUrl = getSharedPreferences(SESSION_PREFS, MODE_PRIVATE)
            .getString(KEY_LAST_PAGE_URL, null) ?: return ServerAddress.current
        return ServerAddress.rebase(savedUrl)
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
        if (!::webView.isInitialized) return super.onSaveInstanceState(outState)
        persistLastPageUrl()
        webView.saveState(outState)
        super.onSaveInstanceState(outState)
    }

    override fun onPause() {
        if (!::webView.isInitialized) return super.onPause()
        persistLastPageUrl()
        WakeWordService.send(this, WakeWordService.ACTION_RESUME)
        webView.onPause()
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        if (::webView.isInitialized) webView.onResume()
        // The server addresses were changed in settings: open the same page on the new ones.
        if (::webView.isInitialized && ServerAddress.version(this) != addressVersion) {
            addressVersion = ServerAddress.version(this)
            ServerAddress.load(this)
            Thread {
                ServerAddress.choose(applicationContext, allowLan = sessionSync.hasToken)
                runOnUiThread { if (!isFinishing) beginHomeLoad(lastKnownPageUrl()) }
            }.start()
        }
        // Free the microphone for Open WebUI's own voice mode while Sakura is on screen.
        WakeWordService.send(this, WakeWordService.ACTION_PAUSE)
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
        networkCallback?.let { getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(it) }
        networkCallback = null
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
        private const val REFRESH_JS_NAME = "SakuraRefresh"
        private const val APP_MENU_JS_NAME = "SakuraApp"

        /**
         * Adds "Ajustes de la app" to Open WebUI's user menu (the one with Settings and
         * Sign out), right above Settings, by copying that item's look. Tapping it opens
         * the app's own settings, where the server addresses and voice options live.
         */
        private val APP_MENU_JS = """
            (function() {
                if (window.__sakuraAppMenu || !window.$APP_MENU_JS_NAME) return;
                window.__sakuraAppMenu = true;
                const label = __LABEL__;
                const icon = 'M10.5 1.5H8.25A2.25 2.25 0 006 3.75v16.5a2.25 2.25 0 002.25 2.25h7.5A2.25 2.25 0 0018 20.25V3.75a2.25 2.25 0 00-2.25-2.25H13.5m-3 0V3h3V1.5m-3 0h3m-3 18.75h3';
                function addItem(menu) {
                    if (menu.querySelector('#sakura-app-settings')) return;
                    const settings = Array.from(menu.querySelectorAll('[role="menuitem"]'))
                        .find(function(el) { return el.id !== 'sakura-app-settings' && /^(Settings|Configuraci|Ajustes)/i.test(el.innerText.trim()); });
                    if (!settings) return;
                    const item = settings.cloneNode(true);
                    item.id = 'sakura-app-settings';
                    const path = item.querySelector('svg path');
                    if (path) path.setAttribute('d', icon);
                    // Drop Settings' keyboard-shortcut hint (hidden on phones), then rename the
                    // visible label: Open WebUI puts it in a .truncate element.
                    item.querySelectorAll('.hidden, kbd').forEach(function(el) { el.remove(); });
                    const labelEl = item.querySelector('.truncate');
                    if (labelEl) {
                        labelEl.textContent = label;
                    } else {
                        const walker = document.createTreeWalker(item, NodeFilter.SHOW_TEXT);
                        let textNode = null;
                        for (let t = walker.nextNode(); t; t = walker.nextNode()) {
                            if (t.textContent.trim()) { textNode = t; break; }
                        }
                        if (textNode) textNode.textContent = label; else item.append(label);
                    }
                    item.addEventListener('click', function(e) {
                        e.preventDefault();
                        e.stopPropagation();
                        document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }));
                        window.$APP_MENU_JS_NAME.openSettings();
                    }, true);
                    settings.parentNode.insertBefore(item, settings);
                }
                new MutationObserver(function() {
                    document.querySelectorAll('[role="menu"]').forEach(addItem);
                }).observe(document.body, { childList: true, subtree: true });
            })();
        """.trimIndent()

        /**
         * Open WebUI asks "Start a voice call using your microphone?" when opened with
         * ?call=true. The user already asked for voice mode, so press its confirm button
         * (the second one) as soon as that dialog appears. Open WebUI can take several
         * seconds to finish loading before it shows the dialog, so keep watching for 30 s.
         */
        private val CONFIRM_VOICE_JS = """
            (function() {
                if (window.__sakuraVoiceConfirm) return;
                window.__sakuraVoiceConfirm = true;
                let tries = 0;
                (function attempt() {
                    const dialog = Array.from(document.querySelectorAll('[role="dialog"]'))
                        .find(function(d) { return /micr/i.test(d.innerText); });
                    const buttons = dialog ? dialog.querySelectorAll('button') : [];
                    if (buttons.length >= 2) { buttons[buttons.length - 1].click(); return; }
                    if (++tries < 120) setTimeout(attempt, 250);
                })();
            })();
        """.trimIndent()

        /**
         * Open WebUI scrolls inside its own panels, so the WebView itself is always at the top.
         * Android decides at the very start of a touch whether a pull may reload, so the page
         * keeps the app told in advance: pulling down scrolls instead of reloading while the
         * chat or the last panel used is scrolled down, or while a dialog is open.
         */
        private val REFRESH_JS = """
            (function() {
                if (window.__sakuraRefresh || !window.$REFRESH_JS_NAME) return;
                window.__sakuraRefresh = true;
                let lastScrolled = null;
                let reported = null;
                function visible(el) { return !!el && el.isConnected && el.getClientRects().length > 0; }
                function blocked() {
                    if (document.querySelector('[role="dialog"], [aria-modal="true"]')) return true;
                    const chat = document.getElementById('messages-container');
                    if (chat && chat.scrollTop > 0) return true;
                    if (lastScrolled && visible(lastScrolled) && lastScrolled.scrollTop > 0) return true;
                    return !!document.scrollingElement && document.scrollingElement.scrollTop > 0;
                }
                function report() {
                    const value = blocked();
                    if (value !== reported) {
                        reported = value;
                        window.$REFRESH_JS_NAME.setCanScrollUp(value);
                    }
                }
                document.addEventListener('scroll', function(e) {
                    if (e.target && e.target.nodeType === 1) lastScrolled = e.target;
                    report();
                }, { capture: true, passive: true });
                document.addEventListener('touchend', report, { capture: true, passive: true });
                new MutationObserver(report).observe(document.body, { childList: true, subtree: false });
                setInterval(report, 1000);
                report();
            })();
        """.trimIndent()
        private const val TAG = "SakuraWebApp"
        /** Schemes the WebView handles itself (downloads, inline content); everything else opens outside. */
        private val IN_APP_SCHEMES = setOf("blob", "data", "javascript", "about")
        private const val MEDIA_DIAGNOSTIC_PREFIX = "SAKURA_MEDIA_DIAG:"
        private const val MEDIA_READY_MESSAGE = "Micrófono disponible para WebView."
    }
}
