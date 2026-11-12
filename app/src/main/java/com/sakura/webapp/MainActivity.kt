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
    /** Container of the WebView. Pull-to-refresh is off: swiping down never reloads the page. */
    private lateinit var swipeLayout: FrameLayout
    /** Thin bar shown while the phone has no network. */
    private lateinit var offlineBanner: TextView
    /** Voice mode was asked for (shortcut, assistant, "Oye, Sakura"): accept Open WebUI's prompt. */
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
    /** An Open WebUI page finished loading and is on screen, so requests can be opened inside it. */
    private var pageReady = false
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

    /** Fingerprint, face or PIN prompt for the optional app lock (see AppLock). */
    private val lockGate = AppLock.Gate(this)
    private var lockCover: View? = null
    /** Our own file picker is about to cover the app: coming back from it is not leaving the app. */
    private var skipLockOnce = false
    /** Voice mode asked for while locked; opened once the user unlocks. */
    private var requestAfterUnlock: IncomingIntents.Request? = null

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
        AppLock.install(this)
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
                    pageReady = false
                    trustedPageLoaded = isOpenWebUiOrigin(Uri.parse(url))
                    if (trustedPageLoaded) {
                        view.evaluateJavascript(WebNotificationBridge.SHIM_JS, null)
                        view.evaluateJavascript(DownloadBridge.SHIM_JS, null)
                    }
                }

                // As soon as the new page is on screen (long before onPageFinished on a slow
                // network), start watching for Open WebUI's voice call prompt.
                override fun onPageCommitVisible(view: WebView, url: String) {
                    super.onPageCommitVisible(view, url)
                    if (isOpenWebUiOrigin(Uri.parse(url))) view.evaluateJavascript(CONFIRM_VOICE_JS, null)
                    if (isOpenWebUiOrigin(Uri.parse(url)) && keepKeyboardClosed(url)) view.evaluateJavascript(NO_AUTOFOCUS_JS, null)
                }

                override fun onPageFinished(view: WebView, url: String) {
                    super.onPageFinished(view, url)
                    trustedPageLoaded = isOpenWebUiOrigin(Uri.parse(url))
                    if (trustedPageLoaded) persistLastPageUrl(url)
                    if (trustedPageLoaded) {
                        view.evaluateJavascript(WebNotificationBridge.SHIM_JS, null)
                        view.evaluateJavascript(DownloadBridge.SHIM_JS, null)
                        view.evaluateJavascript(PageThemeBridge.WATCH_JS, null)
                        view.evaluateJavascript(SessionSync.SYNC_JS, null)
                        view.evaluateJavascript(appMenuScript(), null)
                        if (shareBridge.hasPending) view.evaluateJavascript(ShareBridge.ATTACH_JS, null)
                        // On every page: Open WebUI's "¿Iniciar una llamada de voz con tu micrófono?"
                        // is accepted as soon as it shows, however the page was opened.
                        view.evaluateJavascript(CONFIRM_VOICE_JS, null)
                        if (keepKeyboardClosed(url)) view.evaluateJavascript(NO_AUTOFOCUS_JS, null)
                        if (Uri.parse(url).getQueryParameter("q") != null) {
                            view.evaluateJavascript(DROP_QUERY_JS, null)
                        }
                    }
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
                override fun onProgressChanged(view: WebView, newProgress: Int) {
                    super.onProgressChanged(view, newProgress)
                    // Also while the page loads; the script installs itself only once per page.
                    if (newProgress >= 30 && view.url?.let { isOpenWebUiOrigin(Uri.parse(it)) } == true) {
                        view.evaluateJavascript(CONFIRM_VOICE_JS, null)
                    }
                }

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
                        skipLockOnce = true
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
        swipeLayout = FrameLayout(this).apply {
            addView(webView, ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            ))
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
        lockCover = buildLockCover().also {
            contentRoot.addView(it, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }

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

        VoiceWidgetProvider.remember(this, intent)
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
        VoiceWidgetProvider.remember(this, intent)
        val request = IncomingIntents.parse(intent) ?: return
        // Still choosing the address for the first load: open this instead of the last page.
        if (!firstLoadStarted) requestBeforeFirstLoad = request else openRequest(request)
    }

    /** Opens what a share, selected text, shortcut or the assistant asked for. */
    private fun openRequest(request: IncomingIntents.Request?) {
        // Locked: do not start voice mode (and the microphone) behind the lock screen.
        if (lockCover?.visibility == View.VISIBLE && request?.path?.contains("call=true") == true) {
            requestAfterUnlock = request
            if (!pageReady) beginHomeLoad(lastKnownPageUrl())
            return
        }
        if (request != null && openInPage(request)) return
        val url = request?.path?.let(ServerAddress::at) ?: lastKnownPageUrl()
        request?.streams?.let(shareBridge::prepare)
        beginHomeLoad(url)
    }

    /**
     * The keyboard stays closed when a page opens, except for a share or a text to send (?q=), where
     * the user is about to write and the app itself puts the cursor in the box.
     */
    private fun keepKeyboardClosed(url: String): Boolean =
        !shareBridge.hasPending && runCatching { Uri.parse(url).getQueryParameter("q") }.getOrNull() == null

    /**
     * Voice mode, a new chat (with the widget's model) or the last chat, opened inside the Open WebUI
     * page that is already loaded instead of reloading it: SvelteKit's router takes the link, so voice
     * mode is up in about half a second instead of several. Shares, text and anything else still load
     * the page as before. Returns false when the page is not ready, so the caller loads it.
     */
    private fun openInPage(request: IncomingIntents.Request): Boolean {
        if (!pageReady || mainFrameLoadFailed || !trustedPageLoaded || request.streams.isNotEmpty()) return false
        val current = webView.url?.let { runCatching { Uri.parse(it) }.getOrNull() } ?: return false
        if (!isOpenWebUiOrigin(current)) return false
        // "Último chat": the page on screen already is the last one.
        val path = request.path ?: return true
        val uri = Uri.parse(path)
        if (uri.queryParameterNames.any { it != "call" && it != "models" }) return false
        webView.evaluateJavascript(
            "(function(p){const a=document.createElement('a');a.href=p;document.body.appendChild(a);" +
                "a.click();a.remove();})(" + JSONObject.quote(path) + ")",
            null
        )
        webView.evaluateJavascript("window.__sakuraNoAutofocus && window.__sakuraNoAutofocus.arm();", null)
        return true
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
        pageReady = trustedPageLoaded
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
        // A shared text (?q=), voice launch (?call=) or widget model (?models=) should not come back next time.
        val keep = if (uri.getQueryParameter("q") != null || uri.getQueryParameter("call") != null ||
            uri.getQueryParameter("models") != null
        ) {
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
                // On the first page, go to the background instead of closing: the page stays
                // loaded, so voice mode and shortcuts open instantly next time.
                if (webView.canGoBack()) webView.goBack() else moveTaskToBack(true)
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

    override fun onStart() {
        super.onStart()
        if (skipLockOnce) {
            // Back from our own file picker: not leaving the app.
            skipLockOnce = false
            if (AppLock.unlocked) AppLock.markUnlocked()
        } else if (!lockGate.asking && AppLock.needsUnlock(this)) {
            showLock()
        }
    }

    /** Full-screen cover with the logo and "Desbloquear"; asks for the fingerprint right away. */
    private fun buildLockCover(): View {
        val ui = SetupUi(this)
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(ui.dp(32), 0, ui.dp(32), 0)
            setBackgroundColor(ui.ground)
            isClickable = true
            visibility = View.GONE
            addView(ImageView(context).apply {
                setImageResource(R.drawable.sakura_logo)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LinearLayout.LayoutParams(ui.dp(72), ui.dp(72)).apply { bottomMargin = ui.dp(16) })
            addView(ui.text(getString(R.string.lock_title), 22f, ui.ink, bold = true).apply { gravity = Gravity.CENTER })
            addView(ui.text(getString(R.string.lock_text), 15f, ui.muted).apply {
                gravity = Gravity.CENTER
                setPadding(0, ui.dp(6), 0, ui.dp(24))
            })
            addView(ui.primaryButton(getString(R.string.lock_unlock)) { askUnlock() }.apply {
                layoutParams = LinearLayout.LayoutParams(ui.dp(220), ui.dp(52))
            })
        }
    }

    private fun showLock() {
        val cover = lockCover ?: return
        cover.visibility = View.VISIBLE
        cover.bringToFront()
        askUnlock()
    }

    private fun askUnlock() {
        lockGate.ask(getString(R.string.lock_prompt_title), getString(R.string.lock_prompt_text)) { ok ->
            if (!ok) return@ask
            AppLock.markUnlocked()
            lockCover?.visibility = View.GONE
            requestAfterUnlock?.let { pending ->
                requestAfterUnlock = null
                openRequest(pending)
            }
            checkForUpdates()
        }
    }

    /** Once a day: "Hay una actualización" when GitHub has a newer version. */
    private fun checkForUpdates() {
        if (lockCover?.visibility == View.VISIBLE) return
        Updates.checkIfDue(this) { release -> if (!isFinishing) UpdatePrompt.show(this, release) }
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
        // With the lock on, the recent apps screen shows no chat.
        if (Build.VERSION.SDK_INT >= 33) setRecentsScreenshotEnabled(!AppLock.isEnabled(this))
        checkForUpdates()
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
         * Open WebUI asks "¿Iniciar una llamada de voz con tu micrófono?" ("Start a voice call using
         * your microphone?", titled "Abrir enlace") whenever a page has ?call=true. The user already
         * chose voice mode in the app, so the prompt is never shown: a watcher on every page presses
         * its Confirmar button as soon as it appears, with no time limit and whatever element holds
         * it, then drops ?call (and the widget's ?models) from the address. Checks are debounced so
         * a streaming answer does not keep the page busy.
         */
        private val CONFIRM_VOICE_JS = """
            (function() {
                if (window.__sakuraVoiceConfirm || !document.documentElement) return;
                window.__sakuraVoiceConfirm = true;
                $DROP_PARAM_JS
                const PROMPT = /(micr[oó]fono|microphone)/i;
                function isPrompt(text) { return PROMPT.test(text) && /(llamada|call)/i.test(text); }
                function check() {
                    const buttons = document.querySelectorAll('button');
                    for (const b of buttons) {
                        if (!/^\s*(confirmar|confirm)\s*$/i.test(b.textContent || '')) continue;
                        let box = b.parentElement;
                        for (let i = 0; i < 6 && box; i++, box = box.parentElement) {
                            if (!isPrompt(box.textContent || '')) continue;
                            // Hide the prompt's own layer (the nearest fixed one) so it does not even flash.
                            let layer = box;
                            for (let el = box; el && el !== document.body; el = el.parentElement) {
                                if (getComputedStyle(el).position === 'fixed') { layer = el; break; }
                            }
                            layer.style.visibility = 'hidden';
                            setTimeout(function() { layer.style.visibility = ''; }, 800);
                            b.click();
                            dropParam('call');
                            dropParam('models');
                            return;
                        }
                    }
                }
                // React in the same frame to anything added that mentions the microphone.
                new MutationObserver(function(records) {
                    for (const r of records) {
                        for (const n of r.addedNodes) {
                            if (n.nodeType === 1 && PROMPT.test(n.textContent || '')) { check(); return; }
                        }
                    }
                }).observe(document.documentElement, { childList: true, subtree: true });
                check();
            })();
        """.trimIndent()

        /**
         * Open WebUI puts the cursor in the message box whenever a chat opens (a page load, a chat chosen
         * in the sidebar, a new chat), and Android then opens the keyboard over half the screen. For a
         * few seconds after any of those, a focus on the box is undone unless the user's last touch (or
         * key) was on the box or its own buttons, so tapping the box, typing and sending work as always.
         */
        private const val NO_AUTOFOCUS_JS = """
            (function() {
                if (window.__sakuraNoAutofocus) { window.__sakuraNoAutofocus.arm(); return; }
                let until = 0, touchedAt = 0, touchedComposer = false;
                const composer = function() {
                    const box = document.getElementById('chat-input');
                    return box ? (box.closest('form') || box) : null;
                };
                const typing = function(el) {
                    return !!el && (el.id === 'chat-input' || el.tagName === 'TEXTAREA' || el.isContentEditable ||
                        (el.closest && !!el.closest('#chat-input')));
                };
                const asked = function() { return touchedComposer && Date.now() - touchedAt < 1500; };
                const guard = function(el) {
                    if (Date.now() < until && !asked() && typing(el)) setTimeout(function() { el.blur(); }, 0);
                };
                document.addEventListener('pointerdown', function(e) {
                    const area = composer();
                    touchedAt = Date.now();
                    touchedComposer = !!area && area.contains(e.target);
                }, true);
                document.addEventListener('keydown', function() { touchedAt = Date.now(); touchedComposer = true; }, true);
                document.addEventListener('focusin', function(e) { guard(e.target); }, true);
                const arm = function() {
                    until = Date.now() + 4000;
                    guard(document.activeElement);
                };
                // Chats change inside the page (sidebar, new chat, back) without a page load.
                ['pushState', 'replaceState'].forEach(function(name) {
                    const original = history[name];
                    history[name] = function() {
                        const result = original.apply(this, arguments);
                        arm();
                        return result;
                    };
                });
                window.addEventListener('popstate', arm);
                window.__sakuraNoAutofocus = { arm: arm };
                arm();
            })();
        """

        /**
         * Removes a one-off launch parameter from the address bar once Open WebUI has used it,
         * so reloading the page (pull to refresh) does not ask to start a voice call again or
         * refill the shared text. history.replaceState changes the URL without a reload.
         */
        private const val DROP_PARAM_JS = """
            function dropParam(name) {
                const u = new URL(location.href);
                if (!u.searchParams.has(name)) return;
                u.searchParams.delete(name);
                history.replaceState(history.state, '', u.pathname + u.search + u.hash);
            }
        """

        /** After Open WebUI copies ?q= (shared or selected text) into the message box, drop it. */
        private val DROP_QUERY_JS = """
            (function() {
                if (window.__sakuraDropQuery) return;
                window.__sakuraDropQuery = true;
                $DROP_PARAM_JS
                let tries = 0;
                (function attempt() {
                    const box = document.querySelector('#chat-input');
                    if ((box && box.innerText.trim()) || ++tries >= 80) { dropParam('q'); return; }
                    setTimeout(attempt, 250);
                })();
            })();
        """.trimIndent()

        private const val TAG = "SakuraWebApp"
        /** Schemes the WebView handles itself (downloads, inline content); everything else opens outside. */
        private val IN_APP_SCHEMES = setOf("blob", "data", "javascript", "about")
        private const val MEDIA_DIAGNOSTIC_PREFIX = "SAKURA_MEDIA_DIAG:"
        private const val MEDIA_READY_MESSAGE = "Micrófono disponible para WebView."
    }
}
