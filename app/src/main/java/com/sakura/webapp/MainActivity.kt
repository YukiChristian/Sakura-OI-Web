package com.sakura.webapp

import android.os.SystemClock
import android.view.PixelCopy
import android.os.Looper
import android.os.Handler
import android.graphics.Rect
import android.graphics.Bitmap
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
import android.view.inputmethod.InputMethodManager
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
import androidx.lifecycle.Lifecycle
import androidx.core.view.WindowInsetsAnimationCompat
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
    /** Colors drawn at the page's top and bottom edges (0xRRGGBB, -1 = unknown), see PageThemeBridge. */
    private var pageTopColor = -1
    private var pageBottomColor = -1
    /** Last edge colours the page read from its CSS (instant), and what each one really looked like on screen. */
    private var cssTopColor = -1
    private var cssBottomColor = -1
    private val edgeLooks = HashMap<Int, Int>()
    private val sampleEdgesTask = Runnable { sampleEdgeColors() }
    /**
     * The colour at the page's bottom edge with the keyboard closed (the wallpaper under the message box):
     * exactly what the keyboard uncovers as it slides away, so it paints what the page has not drawn yet.
     */
    private var restBottomColor = -1
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
    /**
     * Just back on screen and the window has no focus yet: the keyboard height Android hands over then
     * can still be the one from before leaving, and making room for it showed a white box for a moment.
     */
    private var returningToScreen = false
    /** Leaving the screen: the page takes the keyboard's room first, so the keyboard slides away over it. */
    private var leavingScreen = false
    private var barsBottom = 0
    /** While the keyboard slides in or out, the page's bottom follows it frame by frame (see [followKeyboard]). */
    private var keyboardAnimating = false
    /** The keyboard is sliding away: the page already has its full height. */
    private var keyboardHiding = false
    /** Whether the keyboard ends up shown, from the last insets Android laid the window out with. */
    private var imeTargetVisible = false
    /** The page's current bottom margin: the navigation bar or the keyboard, whichever is taller. */
    private var pageBottom = 0
    /** The page's process died and its WebView was destroyed; the activity is being rebuilt. */
    private var rendererGone = false
    /** Until when a Nuevo chat from the widget or the shortcut wants the keyboard up. */
    private var keyboardWantedUntil = 0L

    private fun openKeyboard() {
        webView.requestFocus()
        getSystemService(InputMethodManager::class.java)?.showSoftInput(webView, InputMethodManager.SHOW_IMPLICIT)
    }
    private val pauseWebViewTask = Runnable {
        if (::webView.isInitialized && !lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) webView.onPause()
    }

    // ── Battery in the background (PageSleep): awake 10 minutes after leaving, longer only while a model answers ──
    private val sleepHandler = Handler(Looper.getMainLooper())
    private var leftAt = 0L
    private var pageAsleep = false
    private val sleepCheck = Runnable { checkPageSleep() }

    private fun startSleepWatch() {
        leftAt = SystemClock.elapsedRealtime()
        sleepHandler.removeCallbacks(sleepCheck)
        sleepHandler.postDelayed(sleepCheck, PageSleep.IDLE_MS)
    }

    private fun checkPageSleep() {
        if (pageAsleep || isFinishing || !::webView.isInitialized || lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) return
        val chatId = PageSleep.chatOf(webView.url)
        val app = applicationContext
        Thread {
            val answering = PageSleep.isAnswering(app, chatId)
            sleepHandler.post {
                if (pageAsleep || isFinishing || lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) return@post
                val awakeFor = SystemClock.elapsedRealtime() - leftAt
                if (answering && awakeFor < PageSleep.MAX_AWAKE_MS) sleepHandler.postDelayed(sleepCheck, PageSleep.BUSY_RECHECK_MS)
                else putPageToSleep()
            }
        }.start()
    }

    /** The page's timers stop and the service ends: nothing of Open WebUI runs until you come back. */
    private fun putPageToSleep() {
        if (rendererGone) return
        pageAsleep = true
        Log.i(TAG, "Open WebUI sleeps in the background (battery)")
        webView.pauseTimers()
        stopService(Intent(this, SakuraKeepAliveService::class.java))
    }

    private fun wakePage() {
        sleepHandler.removeCallbacks(sleepCheck)
        if (!pageAsleep) return
        pageAsleep = false
        if (::webView.isInitialized) webView.resumeTimers()
    }
    /** A Nuevo chat that should open with the keyboard, waiting for the page to finish loading. */
    private var focusComposerPending = false
    /** "wifi" or "other"; address checks only run when this changes, not on every signal update. */
    private var lastTransport: String? = null
    private val reconcileAddressTask = Runnable { reconcileAddress() }
    /** Each page load gets a number; a watchdog only acts for the load it was started for. */
    private var loadWatch = 0
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
            // The page's own process (the renderer) keeps an important priority while the app is in the
            // background, so Android does not kill it and the page does not have to load again on return.
            if (Build.VERSION.SDK_INT >= 26) setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, false)
            webViewClient = object : WebViewClient() {
                /**
                 * Android killed the page's process anyway (or it crashed). Without this the whole app
                 * closed and started over on the way back; now the screen is rebuilt on the same page.
                 */
                override fun onRenderProcessGone(view: WebView, detail: android.webkit.RenderProcessGoneDetail): Boolean {
                    Log.w(TAG, "Page process gone (crash: ${detail.didCrash()}); rebuilding the screen")
                    // The chat on screen (changed inside the page, so not saved by a page load) is the one
                    // the rebuilt screen opens.
                    runCatching { persistLastPageUrl(view.url) }
                    rendererGone = true
                    (view.parent as? ViewGroup)?.removeView(view)
                    view.destroy()
                    recreate()
                    return true
                }

                override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
                    super.onPageStarted(view, url, favicon)
                    pageReady = false
                    trustedPageLoaded = isOpenWebUiOrigin(Uri.parse(url))
                    if (trustedPageLoaded) armLoadWatch()
                    if (trustedPageLoaded) {
                        view.evaluateJavascript(WebNotificationBridge.SHIM_JS, null)
                        view.evaluateJavascript(DownloadBridge.SHIM_JS, null)
                        view.evaluateJavascript(WebShareBridge.SHIM_JS, null)
                    }
                }

                // As soon as the new page is on screen (long before onPageFinished on a slow
                // network), start watching for Open WebUI's voice call prompt.
                override fun onPageCommitVisible(view: WebView, url: String) {
                    super.onPageCommitVisible(view, url)
                    if (isOpenWebUiOrigin(Uri.parse(url))) view.evaluateJavascript(CONFIRM_VOICE_JS, null)
                    if (isOpenWebUiOrigin(Uri.parse(url))) view.evaluateJavascript(MIC_WATCH_JS, null)
                    if (isOpenWebUiOrigin(Uri.parse(url)) && keepKeyboardClosed(url)) view.evaluateJavascript(NO_AUTOFOCUS_JS, null)
                    if (isOpenWebUiOrigin(Uri.parse(url))) view.evaluateJavascript(KEEP_BOTTOM_JS, null)
                }

                override fun onPageFinished(view: WebView, url: String) {
                    super.onPageFinished(view, url)
                    trustedPageLoaded = isOpenWebUiOrigin(Uri.parse(url))
                    if (trustedPageLoaded) persistLastPageUrl(url)
                    if (trustedPageLoaded) {
                        view.evaluateJavascript(WebNotificationBridge.SHIM_JS, null)
                        view.evaluateJavascript(DownloadBridge.SHIM_JS, null)
                        view.evaluateJavascript(WebShareBridge.SHIM_JS, null)
                        view.evaluateJavascript(PageThemeBridge.WATCH_JS, null)
                        if (restBottomColor >= 0) applyBackdrop(restBottomColor, force = true)
                        view.evaluateJavascript(SessionSync.SYNC_JS, null)
                        view.evaluateJavascript(appMenuScript(), null)
                        view.evaluateJavascript(phoneButtonScript(), null)
                        if (shareBridge.hasPending) view.evaluateJavascript(ShareBridge.ATTACH_JS, null)
                        // On every page: Open WebUI's "¿Iniciar una llamada de voz con tu micrófono?"
                        // is accepted as soon as it shows, however the page was opened.
                        view.evaluateJavascript(CONFIRM_VOICE_JS, null)
                        view.evaluateJavascript(MIC_WATCH_JS, null)
                        if (keepKeyboardClosed(url)) view.evaluateJavascript(NO_AUTOFOCUS_JS, null)
                        view.evaluateJavascript(KEEP_BOTTOM_JS, null)
                        if (focusComposerPending) {
                            focusComposerPending = false
                            view.evaluateJavascript(FOCUS_COMPOSER_JS, null)
                        }
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

                // Open WebUI opens /error when it cannot reach its server: the app's connection screen
                // (with Reintentar, and a retry when the network comes back) takes its place.
                override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) {
                    super.doUpdateVisitedHistory(view, url, isReload)
                    val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return
                    if (isOpenWebUiOrigin(uri) && isErrorRoute(uri)) handleMainFrameFailure()
                    // Agenda: with the PC answering it is the web one (the sidebar's «Agenda» stays here); without it
                    // the page cannot load and the connection screen offers the Agenda kept on the phone
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
        webView.addJavascriptInterface(
            WebShareBridge(applicationContext, isTrustedPage = { trustedPageLoaded }) { chooser ->
                runOnUiThread {
                    try {
                        startActivity(chooser)
                    } catch (e: android.content.ActivityNotFoundException) {
                        showToast(getString(R.string.share_out_failed))
                    }
                }
            },
            WebShareBridge.JS_NAME
        )
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
            fun hideKeyboard() {
                if (!trustedPageLoaded) return
                runOnUiThread {
                    getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(webView.windowToken, 0)
                }
            }

            @JavascriptInterface
            fun showKeyboard() {
                if (!trustedPageLoaded) return
                runOnUiThread {
                    // Asked while coming back, before the window has focus: Android then hides the keyboard
                    // (stateAlwaysHidden) once it does, so it is asked again on focus (onWindowFocusChanged).
                    keyboardWantedUntil = SystemClock.uptimeMillis() + 3_000
                    openKeyboard()
                }
            }

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
            PageThemeBridge(
                isTrustedPage = { trustedPageLoaded },
                onThemeColor = { themeColor ->
                    runOnUiThread {
                        pageThemeColor = themeColor
                        updateSystemBars()
                    }
                },
                onEdgeColors = { top, bottom -> runOnUiThread { onCssEdges(top, bottom) } },
                onEdgesChanged = {
                    // The real pixels are read once the page is still (not on every frame of a slide).
                    runOnUiThread {
                        contentRoot.removeCallbacks(sampleEdgesTask)
                        contentRoot.postDelayed(sampleEdgesTask, EDGE_SAMPLE_DELAY_MS)
                    }
                },
            ),
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
        // The bars go over the page's edges (see pageEdge), so a hairline the page draws there never shows.
        statusBarScrim.bringToFront()
        navigationBarScrim.bringToFront()
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
            barsBottom = bars.bottom
            // Room for the keyboard only when it is really up; not right after coming back until the
            // window has focus again (returningToScreen), nor while leaving (leavingScreen).
            imeTargetVisible = insets.isVisible(WindowInsetsCompat.Type.ime())
            val imeShown = imeTargetVisible && !returningToScreen && !leavingScreen
            val ime = if (imeShown) insets.getInsets(WindowInsetsCompat.Type.ime()).bottom else 0
            // While the keyboard moves, its animation sets the bottom (followKeyboard); jumping to the end
            // state here is what left an empty space between the page and the keyboard.
            if (!keyboardAnimating) pageBottom = maxOf(bars.bottom, ime)
            val margins = intArrayOf(
                bars.left,
                underBar(bars.top),
                bars.right,
                underBar(pageBottom)
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
        // The page's bottom edge rides on top of the keyboard while it slides in or out, frame by frame, as
        // in native apps: there is never a gap between the page and the keyboard for the window behind to
        // show (white, or a solid colour that cannot match a wallpaper). Android lays the window out in
        // its final state before the animation starts; the listener above keeps the bottom where it was
        // until the frames arrive.
        ViewCompat.setWindowInsetsAnimationCallback(contentRoot, object : WindowInsetsAnimationCompat.Callback(
            WindowInsetsAnimationCompat.Callback.DISPATCH_MODE_STOP
        ) {
            override fun onPrepare(animation: WindowInsetsAnimationCompat) {
                if (animation.typeMask and WindowInsetsCompat.Type.ime() == 0) return
                // A new keyboard animation may cut the previous one short (closed and reopened at once).
                keyboardAnimating = true
                keyboardHiding = false
            }

            override fun onStart(
                animation: WindowInsetsAnimationCompat,
                bounds: WindowInsetsAnimationCompat.BoundsCompat,
            ): WindowInsetsAnimationCompat.BoundsCompat {
                // Hiding: the page takes its full height right away, behind the keyboard, so the page has
                // drawn its new bottom before the keyboard uncovers it (following frame by frame left a
                // strip the page had not drawn yet, one or two frames behind the keyboard).
                val endsHidden = !imeTargetVisible ||
                    ViewCompat.getRootWindowInsets(contentRoot)?.isVisible(WindowInsetsCompat.Type.ime()) == false
                if (keyboardAnimating && endsHidden) {
                    keyboardHiding = true
                    followKeyboard(barsBottom)
                }
                return bounds
            }

            override fun onProgress(
                insets: WindowInsetsCompat,
                runningAnimations: MutableList<WindowInsetsAnimationCompat>,
            ): WindowInsetsCompat {
                if (!keyboardAnimating || keyboardHiding) return insets
                val ime = if (returningToScreen || leavingScreen) 0 else insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
                followKeyboard(maxOf(barsBottom, ime))
                return insets
            }

            override fun onEnd(animation: WindowInsetsAnimationCompat) {
                if (animation.typeMask and WindowInsetsCompat.Type.ime() == 0) return
                keyboardAnimating = false
                keyboardHiding = false
                // Settle on the final state Android reports.
                ViewCompat.requestApplyInsets(contentRoot)
            }
        })
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
        // the Agenda keeps its own copy on the phone: sync now, every 15 min, and the reminders stay scheduled
        AgendaSync.programarPeriodica(this)
        AgendaSync.ahora(this)
        if (intent?.action == IncomingIntents.ACTION_CALENDAR) startActivity(AgendaActivity.intent(this))
        val request = IncomingIntents.parse(intent)?.takeIf { intent?.action != IncomingIntents.ACTION_CALENDAR }
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
        // A tapped notification of a reply: the page opens that chat (its notification's own click).
        intent.getStringExtra(WebNotificationBridge.EXTRA_NOTIFICATION)?.let { id ->
            if (::webView.isInitialized && trustedPageLoaded) webView.evaluateJavascript(WebNotificationBridge.clickScript(id), null)
        }
        if (intent.action == IncomingIntents.ACTION_CALENDAR) { startActivity(AgendaActivity.intent(this)); return }
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
        focusComposerPending = request?.focusComposer == true
        if (request != null && openInPage(request)) {
            if (focusComposerPending) {
                focusComposerPending = false
                webView.evaluateJavascript(FOCUS_COMPOSER_JS, null)
            }
            return
        }
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
                if (moved != url) { webView.loadUrl(moved); armLoadWatch() }
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
        // Agenda: it lives on the phone too, so it opens without the PC (the native one, never the web)
        content.addView(TextView(this).apply {
            text = "Abrir mi agenda"
            textSize = 16f
            setTextColor(retryColor)
            typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
            gravity = Gravity.CENTER
            setPadding(dp(22), 0, dp(22), 0)
            background = GradientDrawable().apply {
                cornerRadius = dp(24).toFloat()
                setStroke(dp(1), themed(Color.argb(46, 24, 24, 27), Color.argb(60, 255, 255, 255)))
            }
            isClickable = true
            isFocusable = true
            setOnClickListener { startActivity(AgendaActivity.intent(this@MainActivity, nativa = true)) }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(48)).apply {
            topMargin = dp(22)
            gravity = Gravity.CENTER_HORIZONTAL
        })
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
        armLoadWatch()
    }

    /**
     * A server that takes the connection but never answers (or Open WebUI stuck on its loading screen while
     * its backend hangs) raises no error in the WebView: only the empty background stayed on screen. 12 s
     * after a page starts, the watchdog checks that something of Open WebUI is drawn (the message box, the
     * sidebar, the sign-in form…); if not, it asks the server itself. No answer: the connection screen with
     * Reintentar. The server answers but the page is still blank: 12 s more, then the same screen.
     */
    private fun armLoadWatch() {
        val id = ++loadWatch
        contentRoot.postDelayed({ checkLoad(id, 1) }, LOAD_WATCH_MS)
    }

    private fun checkLoad(id: Int, round: Int) {
        if (id != loadWatch || mainFrameLoadFailed || isFinishing || !::webView.isInitialized) return
        if (connectionView.visibility == View.VISIBLE) return
        // A page whose script never lets go of the main thread never answers either: after 5 s without a
        // reply it counts as not drawn, so the hung page cannot keep the watchdog waiting forever.
        var replied = false
        val noReply = Runnable { if (!replied) { replied = true; Log.w(TAG, "Page did not answer the watchdog"); notDrawn(id, round) } }
        contentRoot.postDelayed(noReply, JS_REPLY_MS)
        webView.evaluateJavascript(PAGE_DRAWN_JS) { value ->
            if (replied) return@evaluateJavascript
            replied = true
            contentRoot.removeCallbacks(noReply)
            if (id != loadWatch || value == "true") return@evaluateJavascript
            notDrawn(id, round)
        }
    }

    /** The page is still blank (or hung) at check [round]: ask the server, then wait or show Reintentar. */
    private fun notDrawn(id: Int, round: Int) {
        if (id != loadWatch || mainFrameLoadFailed || isFinishing) return
        val base = (webView.url?.let { Uri.parse(it) }?.takeIf { ServerAddress.isServer(it) }
            ?.let { "${it.scheme}://${it.encodedAuthority}/" }) ?: ServerAddress.current
        Thread {
            val answers = base.isNotEmpty() && PcStatus.answersNow(base)
            runOnUiThread {
                if (id != loadWatch || mainFrameLoadFailed || isFinishing) return@runOnUiThread
                if (!answers || round >= 2) {
                    Log.w(TAG, "Page not drawn after ${round * LOAD_WATCH_MS / 1000} s (server answers: $answers)")
                    webView.stopLoading()
                    handleMainFrameFailure()
                } else {
                    contentRoot.postDelayed({ checkLoad(id, round + 1) }, LOAD_WATCH_MS)
                }
            }
        }.start()
    }

    /**
     * After «Oye, Sakura»: lets the ring fade once voice mode has opened the microphone (the moment it goes from
     * «te oí» to «te escucho»), checked every 150 ms; after 15 s (a slow start) it fades anyway.
     */
    private fun waitForVoice(tries: Int) {
        if (!WakeActivation.isShowing) return
        if (tries >= 100 || mainFrameLoadFailed) { WakeActivation.appShown(); return }
        webView.evaluateJavascript("(window.__sakuraMic && window.__sakuraMic.live()) || 0") { value ->
            if ((value?.trim('"')?.toIntOrNull() ?: 0) > 0) WakeActivation.appShown()
            else webView.postDelayed({ waitForVoice(tries + 1) }, 150)
        }
    }

    /** Real failures (no network, DNS or TLS errors, 5xx) and a page the watchdog gave up on. */
    private fun handleMainFrameFailure() {
        mainFrameLoadFailed = true
        // No voice mode is coming: the «Oye, Sakura» activation fades and shows the connection screen.
        WakeActivation.appShown()
        // The widgets check too, so they can say «Tu PC no responde».
        PcStatus.check(this, force = true)
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
    /**
     * The color drawn next to each system bar, read from the screen (a short strip 4 dp inside the
     * page's top and bottom edges), so each bar continues the page: the open sidebar's grey, the
     * header with its blur, a gradient at the bottom.
     */
    /**
     * The page's CSS colours at its top and bottom edges, sent on the same frame they change (the sidebar sliding
     * in, a dialog's backdrop): the bars follow at once. Where the pixels there once looked different (a header
     * with blur, a gradient), the colour seen then is used, so it does not jump twice.
     */
    private fun onCssEdges(top: Int, bottom: Int) {
        cssTopColor = top
        cssBottomColor = bottom
        val shownTop = if (top >= 0) edgeLooks[top] ?: top else -1
        val shownBottom = if (bottom >= 0) edgeLooks[bottom] ?: bottom else -1
        if (shownTop == pageTopColor && shownBottom == pageBottomColor) return
        pageTopColor = shownTop
        pageBottomColor = shownBottom
        updateSystemBars()
    }

    private fun sampleEdgeColors() {
        if (Build.VERSION.SDK_INT < 26 || !::webView.isInitialized || isFinishing) return
        val w = webView.width
        val h = webView.height
        if (w < 40 || h < 40 || !webView.isShown) return
        val at = IntArray(2).also { webView.getLocationInWindow(it) }
        val x = at[0] + (12 * resources.displayMetrics.density).toInt()
        fun read(y: Int, done: (Int) -> Unit) {
            val strip = Bitmap.createBitmap(16, 1, Bitmap.Config.ARGB_8888)
            try {
                PixelCopy.request(window, Rect(x, y, x + 16, y + 1), strip, { result ->
                    if (result == PixelCopy.SUCCESS) {
                        var r = 0; var g = 0; var b = 0
                        for (i in 0 until 16) {
                            val c = strip.getPixel(i, 0)
                            r += Color.red(c); g += Color.green(c); b += Color.blue(c)
                        }
                        done(Color.rgb(r / 16, g / 16, b / 16) and 0xFFFFFF)
                    }
                    strip.recycle()
                }, Handler(Looper.getMainLooper()))
            } catch (e: IllegalArgumentException) {
                strip.recycle()
            }
        }
        // A little inside each edge: the page's outermost pixels are often a hairline (a border, a shadow).
        val inset = (4 * resources.displayMetrics.density).toInt()
        read(at[1] + inset) { top ->
            read(at[1] + h - 1 - inset) { bottom ->
                val atRest = !keyboardAnimating && pageBottom <= barsBottom
                if (atRest && bottom != restBottomColor) {
                    restBottomColor = bottom
                    updateSystemBars()
                }
                // Remember what each CSS colour really looks like at the edge, for the instant path next time.
                if (cssTopColor >= 0) edgeLooks[cssTopColor] = top
                if (cssBottomColor >= 0) edgeLooks[cssBottomColor] = bottom
                if (edgeLooks.size > 64) edgeLooks.clear()
                if (top != pageTopColor || bottom != pageBottomColor) {
                    pageTopColor = top
                    pageBottomColor = bottom
                    updateSystemBars()
                }
            }
        }
    }

    private var backdropApplied = -1

    /**
     * The page's own base background (Open WebUI paints `body` white) takes [color], the colour the page shows
     * at its bottom. Under a wallpaper that base only shows for a moment while the page grows (the keyboard
     * closing) and the picture is drawn again at the new size; with the same colour it cannot be seen.
     */
    private fun applyBackdrop(color: Int, force: Boolean = false) {
        if (!::webView.isInitialized || !trustedPageLoaded || (color == backdropApplied && !force)) return
        backdropApplied = color
        val css = String.format("#%06X", color and 0xFFFFFF)
        webView.evaluateJavascript(
            "(function(c){var s=document.getElementById('sakura-backdrop');" +
                "if(!s){s=document.createElement('style');s.id='sakura-backdrop';(document.head||document.documentElement).appendChild(s);}" +
                "s.textContent='html,body{background-color:'+c+' !important}';})('$css');",
            null
        )
    }

    /** Moves the page's bottom edge to [bottom] px above the screen's bottom (one keyboard animation frame). */
    private fun followKeyboard(bottom: Int) {
        if (bottom == pageBottom) return
        pageBottom = bottom
        listOf(swipeLayout, connectionView).forEach { view ->
            (view.layoutParams as FrameLayout.LayoutParams).apply { bottomMargin = underBar(bottom) }
                .also { view.layoutParams = it }
        }
    }

    /**
     * The page reaches 2 dp under each system bar (and the keyboard). Open WebUI draws a hairline border at the
     * edges of some full-screen panels (voice mode); under the bar it never shows, and the bar's colour, read
     * from the page itself, flows straight into the page.
     */
    private val pageEdge by lazy { (2 * resources.displayMetrics.density).toInt() }

    private fun underBar(margin: Int) = if (margin > pageEdge) margin - pageEdge else margin

    private fun updateSystemBars() {
        if (!::connectionView.isInitialized) return
        val showingPage = connectionView.visibility != View.VISIBLE
        val barColor = if (showingPage && pageThemeColor >= 0) {
            pageThemeColor or 0xFF000000.toInt()
        } else screenBackground
        // Each bar takes the color drawn at its edge of the page (the open sidebar's grey, for example).
        val topColor = if (showingPage && pageTopColor >= 0) pageTopColor or 0xFF000000.toInt() else barColor
        val bottomColor = if (showingPage && pageBottomColor >= 0) pageBottomColor or 0xFF000000.toInt() else barColor
        statusBarScrim.setBackgroundColor(topColor)
        navigationBarScrim.setBackgroundColor(bottomColor)
        // What shows for a moment where the page has not drawn yet (its new bottom while the keyboard moves):
        // the colour really drawn at the page's bottom edge, not the theme's base colour (white in light mode,
        // which stood out over Hanami's wallpaper).
        val restColor = if (showingPage && restBottomColor >= 0) restBottomColor or 0xFF000000.toInt() else bottomColor
        contentRoot.setBackgroundColor(restColor)
        if (::webView.isInitialized) webView.setBackgroundColor(restColor)
        if (showingPage && restBottomColor >= 0) applyBackdrop(restBottomColor)
        WindowInsetsControllerCompat(window, contentRoot).apply {
            isAppearanceLightStatusBars = ColorUtils.calculateLuminance(topColor) > 0.5
            isAppearanceLightNavigationBars = ColorUtils.calculateLuminance(bottomColor) > 0.5
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

    private fun phoneButtonScript(): String =
        PHONE_BUTTON_JS.replace("__LABEL__", JSONObject.quote(getString(R.string.phone_button)))

    private fun showToast(message: String) {
        runOnUiThread { Toast.makeText(this, message, Toast.LENGTH_LONG).show() }
    }

    private fun persistLastPageUrl(url: String? = webView.url) {
        val currentUrl = url ?: return
        val uri = runCatching { Uri.parse(currentUrl) }.getOrNull() ?: return
        if (!isOpenWebUiOrigin(uri) || isErrorRoute(uri)) return
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
        // Saved by an older version while Open WebUI showed its error page: start from the beginning.
        if (runCatching { isErrorRoute(Uri.parse(savedUrl)) }.getOrDefault(false)) return ServerAddress.current
        return ServerAddress.rebase(savedUrl)
    }

    /** Open WebUI's page for "the server does not answer" (/error), which the app replaces with its own screen. */
    private fun isErrorRoute(uri: Uri): Boolean = uri.path?.trimEnd('/') == "/error"

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
        // A dead WebView has no state to keep; the rebuilt screen opens the last page instead.
        if (!::webView.isInitialized || rendererGone) return super.onSaveInstanceState(outState)
        persistLastPageUrl()
        // A failed load or Open WebUI's error page is not kept: restored later, Android's own error page
        // came back without any new load, so the app never showed its connection screen. Without the
        // state, the app loads the last page again and handles a failure as usual.
        val onError = mainFrameLoadFailed || webView.url?.let { runCatching { isErrorRoute(Uri.parse(it)) }.getOrDefault(false) } == true
        if (!onError) webView.saveState(outState)
        super.onSaveInstanceState(outState)
    }

    override fun onStart() {
        super.onStart()
        wakePage()
        if (skipLockOnce) {
            // Back from our own file picker: not leaving the app.
            skipLockOnce = false
            if (AppLock.unlocked) AppLock.markUnlocked()
        } else if (!lockGate.asking && AppLock.needsUnlock(this)) {
            showLock()
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // On screen: the «Oye, Sakura» ring stays until voice mode listens, then fades as it grows from it.
        if (hasFocus && WakeActivation.isShowing) waitForVoice(0)
        if (hasFocus && SystemClock.uptimeMillis() < keyboardWantedUntil && ::webView.isInitialized) {
            webView.postDelayed({ if (SystemClock.uptimeMillis() < keyboardWantedUntil) openKeyboard() }, 120)
        }
        if (hasFocus && returningToScreen) {
            returningToScreen = false
            // Now the keyboard state is the current one: lay the page out again with it.
            if (::contentRoot.isInitialized) ViewCompat.requestApplyInsets(contentRoot)
        }
    }

    override fun onStop() {
        // Off screen (not just our own file picker): a voice call left open lets go of the microphone,
        // so «Oye, Sakura» can hear the phrase again right away.
        if (::webView.isInitialized && !skipLockOnce && !rendererGone) {
            webView.evaluateJavascript("window.__sakuraMic && window.__sakuraMic.release();", null)
        }
        if (::webView.isInitialized && !skipLockOnce && !isChangingConfigurations) startSleepWatch()
        super.onStop()
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
        if (!::webView.isInitialized || rendererGone) return super.onPause()
        persistLastPageUrl()
        WakeWordService.send(this, WakeWordService.ACTION_RESUME)
        // Leave without a cursor in the message box or a keyboard up: coming back, Android would show the
        // keyboard again for a moment (its space as a white box) before the app hides it.
        // The page first takes the keyboard's room (right now, not on the next insets), so while the
        // keyboard slides away during the exit animation it covers the page instead of an empty space.
        leavingScreen = true
        followKeyboard(barsBottom)
        webView.evaluateJavascript("window.__sakuraNoAutofocus && window.__sakuraNoAutofocus.leave && window.__sakuraNoAutofocus.leave();", null)
        getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(webView.windowToken, 0)
        // Pause the page a moment later, once it has drawn itself at the new size: paused right away,
        // the old (shorter) picture came back for a moment on the way in, with white space under it.
        contentRoot.removeCallbacks(pauseWebViewTask)
        contentRoot.postDelayed(pauseWebViewTask, 200)
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        leavingScreen = false
        if (!hasWindowFocus()) returningToScreen = true
        else if (::contentRoot.isInitialized) ViewCompat.requestApplyInsets(contentRoot)
        if (::contentRoot.isInitialized) contentRoot.removeCallbacks(pauseWebViewTask)
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
        // pauseTimers() holds every WebView of the process: never leave it on for the next screen
        wakePage()
        networkCallback?.let { getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(it) }
        networkCallback = null
        stopRetryAnimation()
        pendingUploadCallback?.onReceiveValue(null)
        pendingUploadCallback = null
        pendingAudioPermissionRequest?.deny()
        pendingAudioPermissionRequest = null
        if (::webView.isInitialized && !rendererGone) {
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
        /** How long a page may stay blank before the watchdog checks the server (and again before giving up). */
        private const val LOAD_WATCH_MS = 12_000L
        /** How long the page must stay still before the app reads the real pixels at its edges. */
        private const val EDGE_SAMPLE_DELAY_MS = 180L
        /** How long the page may take to answer the watchdog's check before it counts as hung. */
        private const val JS_REPLY_MS = 5_000L

        /**
         * True once Open WebUI has drawn something a person can use: not its loading screen, and some real
         * content (the message box, the sidebar, a form or a button with text on the page).
         */
        private const val PAGE_DRAWN_JS = """
            (function() {
                var d = document;
                if (!d.body || d.readyState === 'loading') return false;
                var splash = d.getElementById('splash-screen');
                if (splash && splash.getClientRects().length) return false;
                var ui = d.querySelector('#chat-input, #sidebar, nav, main, form, input, textarea');
                return !!ui && (d.body.innerText || '').trim().length > 0;
            })();
        """

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
         * «Teléfono» in the phone sidebar's bottom row, right after the user's own picture (the theme's
         * `.sk-barra-fila`, with the profile and «Buscar»): it opens the app's own settings. It takes the look of
         * the «Agenda» pill at the top (colours, rounding, font, shadow — read live, so it follows light and dark
         * mode) and the height of «Buscar», so the row lines up. Only the app adds it: a browser has no phone
         * settings.
         */
        private val PHONE_BUTTON_JS = """
            (function() {
                if (window.__sakuraPhoneButton || !window.$APP_MENU_JS_NAME) return;
                window.__sakuraPhoneButton = true;
                const label = __LABEL__;
                const icon = '<svg viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" stroke-width="1.6" ' +
                    'stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><rect x="6.5" y="2.5" width="11" height="19" rx="2.6"/>' +
                    '<path d="M10.5 5.6h3"/><path d="M11.2 18.3h1.6"/></svg>';
                // The box (background, ring, corners) is the one of «Buscar» right beside it, so the two read as a
                // pair in light and dark; the label keeps the ink of the sidebar's buttons (Agenda), not the
                // greyer placeholder of the search box.
                const BOX = ['borderRadius', 'backgroundColor', 'boxShadow', 'borderTop', 'borderRight', 'borderBottom', 'borderLeft',
                    'fontFamily', 'fontSize', 'letterSpacing'];
                function copy(btn, from, props) {
                    if (!from) return;
                    const cs = getComputedStyle(from);
                    props.forEach(function(p) { if (btn.style[p] !== cs[p]) btn.style[p] = cs[p]; });
                }
                function paint(btn, row) {
                    const search = row.querySelector('.sk-barra-buscar-arriba');
                    copy(btn, search, BOX);
                    copy(btn, document.getElementById('sidebar-calendar-button') || search, ['color']);
                    if (btn.style.fontWeight !== '500') btn.style.fontWeight = '500';
                    const h = search ? getComputedStyle(search).height : '44px';
                    if (btn.style.height !== h) btn.style.height = h;
                }
                function add() {
                    const row = document.querySelector('#sidebar .sk-barra-fila');
                    if (!row) return;
                    let btn = document.getElementById('sakura-phone-button');
                    if (!btn) {
                        btn = document.createElement('button');
                        btn.type = 'button';
                        btn.id = 'sakura-phone-button';
                        btn.setAttribute('aria-label', label);
                        btn.innerHTML = icon + '<span>' + label + '</span>';
                        Object.assign(btn.style, {
                            display: 'inline-flex', alignItems: 'center', justifyContent: 'center', gap: '8px',
                            padding: '0 16px', flex: 'none', cursor: 'pointer', whiteSpace: 'nowrap', lineHeight: '20px',
                            boxSizing: 'border-box', margin: '0'
                        });
                        btn.addEventListener('click', function(e) {
                            e.preventDefault();
                            e.stopPropagation();
                            window.$APP_MENU_JS_NAME.openSettings();
                        });
                    }
                    if (btn.parentElement !== row) row.appendChild(btn);
                    // Placed by order, not by moving nodes (the theme keeps «Buscar» right after the picture):
                    // picture, «Teléfono», «Buscar».
                    const me = row.querySelector('.sk-barra-yo');
                    if (me && me.style.order !== '-2') me.style.order = '-2';
                    if (btn.style.order !== '-1') btn.style.order = '-1';
                    paint(btn, row);
                }
                // The sidebar is drawn again now and then (and the theme switches light/dark): put it back, at most
                // once per frame.
                let queued = false;
                new MutationObserver(function() {
                    if (queued) return;
                    queued = true;
                    requestAnimationFrame(function() { queued = false; add(); });
                }).observe(document.documentElement, { childList: true, subtree: true, attributes: true, attributeFilter: ['class'] });
                add();
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
         * Keeps the microphone streams the page opens (Open WebUI's voice mode), so the app can stop them
         * when it leaves the screen. A call left open in the background kept the microphone busy for a
         * few seconds, and «Oye, Sakura» could not hear the phrase meanwhile.
         */
        /**
         * The conversation stays at its end when the page changes size (pedido de Christian, 21-nov-2026). The
         * keyboard opening makes the page shorter one frame at a time and Open WebUI keeps the conversation's
         * position, so the last answer ended up hidden behind the message box ("a veces el chat no se pone en la
         * posición adecuada"). Opening the keyboard to write (the message box takes the cursor) always goes to the
         * last message, wherever you were ("se tiene que ir al último mensaje"). Any other change of size (the
         * keyboard closing) keeps you at the end only if you were there; if you had scrolled up, it stays put.
         */
        private const val KEEP_BOTTOM_JS = """
            (function() {
                if (window.__sakuraKeepBottom) return;
                window.__sakuraKeepBottom = true;
                const NEAR = 120;
                let atEnd = true, list = null, lastHeight = 0;
                const find = function() {
                    const el = document.getElementById('messages-container');
                    return el && el.offsetParent !== null ? el : null;
                };
                const distance = function(el) { return el.scrollHeight - el.scrollTop - el.clientHeight; };
                const writing = function() {
                    const box = document.getElementById('chat-input');
                    const form = box && (box.closest('form') || box);
                    return !!form && form.contains(document.activeElement);
                };
                const toEnd = function(el) { el.scrollTop = el.scrollHeight; atEnd = true; };
                document.addEventListener('scroll', function(e) {
                    const el = e.target;
                    if (!el || el.id !== 'messages-container') return;
                    // a scroll that only follows a shorter box (the keyboard) does not count as leaving the end
                    if (el.clientHeight !== lastHeight) return;
                    atEnd = distance(el) < NEAR;
                }, true);
                const follow = function() {
                    const el = find();
                    if (!el) return;
                    if (el !== list) { list = el; lastHeight = el.clientHeight; atEnd = distance(el) < NEAR; return; }
                    if (el.clientHeight === lastHeight) return;
                    const shrank = el.clientHeight < lastHeight;
                    lastHeight = el.clientHeight;
                    if ((shrank && writing()) || atEnd) toEnd(el);
                };
                // the message box takes the cursor: to the last message now and again as the keyboard finishes opening
                document.addEventListener('focusin', function(e) {
                    if (!writing()) return;
                    [0, 250, 500].forEach(function(ms) { setTimeout(function() { const el = find(); if (el && writing()) toEnd(el); }, ms); });
                }, true);
                const watch = new ResizeObserver(function() { follow(); });
                const attach = function() {
                    const el = find();
                    if (el && el !== list) { watch.disconnect(); watch.observe(el); follow(); }
                };
                window.addEventListener('resize', function() { attach(); follow(); });
                if (window.visualViewport) window.visualViewport.addEventListener('resize', function() { attach(); follow(); });
                new MutationObserver(attach).observe(document.body, { childList: true, subtree: true });
                attach();
            })();
        """

        private const val MIC_WATCH_JS = """
            (function() {
                if (window.__sakuraMic) return;
                const md = navigator.mediaDevices;
                if (!md || !md.getUserMedia) return;
                const live = [];
                const original = md.getUserMedia.bind(md);
                md.getUserMedia = function(constraints) {
                    return original(constraints).then(function(stream) { live.push(stream); return stream; });
                };
                window.__sakuraMic = {
                    live: function() {
                        return live.reduce(function(n, s) {
                            return n + s.getAudioTracks().filter(function(t) { return t.readyState === 'live'; }).length;
                        }, 0);
                    },
                    release: function() {
                        // In a call: end it with Open WebUI's own button, so the screen does not stay on
                        // "Escuchando" with the microphone off when the user comes back.
                        if (this.live() > 0) {
                            // Visible buttons only; the label first (Spanish or English), the icon as a fallback.
                            const shown = Array.prototype.filter.call(document.querySelectorAll('button'), function(b) {
                                return b.getClientRects().length > 0;
                            });
                            const end = shown.find(function(b) {
                                return /finalizar llamada|end call|hang up|colgar/i.test(b.getAttribute('aria-label') || '');
                            }) || shown.find(function(b) {
                                const icon = b.querySelector('path');
                                return !!icon && (icon.getAttribute('d') || '').indexOf('M6.28 5.22') === 0 &&
                                    b.getBoundingClientRect().top > window.innerHeight * 0.6;
                            });
                            if (end) end.click();
                        }
                        let stopped = 0;
                        live.splice(0).forEach(function(stream) {
                            stream.getAudioTracks().forEach(function(track) {
                                if (track.readyState === 'live') { track.stop(); stopped++; }
                            });
                        });
                        return stopped;
                    }
                };
            })();
        """

        /**
         * Nuevo chat from the widget or the launcher shortcut: once the new chat's message box is on screen,
         * put the cursor in it and ask the app to open the keyboard (a focus from a script alone does not).
         */
        private const val FOCUS_COMPOSER_JS = """
            (function() {
                const guard = window.__sakuraNoAutofocus;
                if (guard && guard.allow) guard.allow();
                const focus = function() {
                    const box = document.getElementById('chat-input');
                    if (!box || !box.getClientRects().length) return false;
                    if (guard && guard.allow) guard.allow();
                    box.focus();
                    if (window.SakuraApp && window.SakuraApp.showKeyboard) window.SakuraApp.showKeyboard();
                    return true;
                };
                // Open WebUI may draw the box again right after the chat opens (and the cursor goes with
                // the old one): for 3 seconds after the first focus, put it back whenever it is lost,
                // unless the user touches something else.
                let stop = false, tries = 0, focusedAt = 0, asked = false;
                const cancel = function(e) {
                    const box = document.getElementById('chat-input');
                    const area = box ? (box.closest('form') || box) : null;
                    if (!area || !area.contains(e.target)) stop = true;
                };
                document.addEventListener('pointerdown', cancel, true);
                // The user closed the keyboard (Atrás): it stays closed; the cursor is not put back.
                const vv = window.visualViewport;
                let lowest = vv ? vv.height : 0;
                const closed = function() {
                    if (!vv) return;
                    if (vv.height < lowest) lowest = vv.height;
                    else if (focusedAt && vv.height > lowest + 120) stop = true;
                };
                if (vv) vv.addEventListener('resize', closed);
                const finish = function() {
                    document.removeEventListener('pointerdown', cancel, true);
                    if (vv) vv.removeEventListener('resize', closed);
                };
                // A moment first: a new chat opened inside the page replaces the box that is on screen.
                setTimeout(function tick() {
                    if (stop) return finish();
                    const box = document.getElementById('chat-input');
                    const inside = !!box && box.contains(document.activeElement);
                    if (!inside && focus()) {
                        asked = true;
                        if (!focusedAt) focusedAt = Date.now();
                    }
                    if (inside) {
                        // Open WebUI may have put the cursor there itself (allowed now): still open the keyboard.
                        if (!asked && window.SakuraApp && window.SakuraApp.showKeyboard) window.SakuraApp.showKeyboard();
                        asked = true;
                        if (!focusedAt) focusedAt = Date.now();
                    }
                    if ((focusedAt && Date.now() - focusedAt > 3000) || ++tries > 80) return finish();
                    setTimeout(tick, 200);
                }, 400);
            })();
        """

        /**
         * The message box takes the cursor (and Android opens the keyboard) only when the user touches it.
         * Open WebUI puts the cursor there by itself in many moments: a page load, a chat opened from the
         * sidebar, a new chat, the end of a reply, the return to the app. Any focus on the box is undone
         * unless the user's last touch (or key) was on the box or its own buttons. When the user closes
         * the keyboard, the box lets go of the cursor too, so nothing can bring the keyboard back later.
         * Other fields (editing a message, notes, search) are not touched.
         */
        private const val NO_AUTOFOCUS_JS = """
            (function() {
                if (window.__sakuraNoAutofocus) return;
                let touchedAt = 0, touchedComposer = false, allowUntil = 0;
                const composer = function() {
                    const box = document.getElementById('chat-input');
                    return box ? (box.closest('form') || box) : null;
                };
                const inBox = function(el) {
                    return !!el && el.nodeType === 1 && (el.id === 'chat-input' || !!el.closest('#chat-input'));
                };
                const asked = function() {
                    return Date.now() < allowUntil || (touchedComposer && Date.now() - touchedAt < 1500);
                };
                const hideKeyboard = function() {
                    if (window.SakuraApp && window.SakuraApp.hideKeyboard) window.SakuraApp.hideKeyboard();
                };
                const release = function() {
                    const el = document.activeElement;
                    if (inBox(el)) { el.blur(); hideKeyboard(); }
                };
                const guard = function(el) {
                    if (inBox(el) && !asked()) setTimeout(function() { if (!asked()) { el.blur(); hideKeyboard(); } }, 0);
                };
                // Open WebUI (and its editor) put the cursor there with focus(): those calls are ignored
                // unless the user asked, so the keyboard does not even start to open.
                const nativeFocus = HTMLElement.prototype.focus;
                HTMLElement.prototype.focus = function() {
                    if (inBox(this) && !asked()) return;
                    return nativeFocus.apply(this, arguments);
                };
                document.addEventListener('pointerdown', function(e) {
                    const area = composer();
                    touchedAt = Date.now();
                    touchedComposer = !!area && area.contains(e.target);
                }, true);
                document.addEventListener('keydown', function(e) {
                    if (inBox(e.target) || !inBox(document.activeElement)) { touchedAt = Date.now(); touchedComposer = true; }
                }, true);
                document.addEventListener('focusin', function(e) { guard(e.target); }, true);
                // Back in the app: the box keeps no cursor it was given while away.
                document.addEventListener('visibilitychange', function() {
                    if (document.visibilityState === 'visible' && !asked()) release();
                });
                window.addEventListener('focus', function() { if (!asked()) release(); });
                // The user closed the keyboard: let go of the cursor so it cannot reopen by itself.
                if (window.visualViewport) {
                    let tallest = window.visualViewport.height;
                    window.visualViewport.addEventListener('resize', function() {
                        const h = window.visualViewport.height;
                        if (h > tallest - 80 && inBox(document.activeElement) && !asked()) release();
                        tallest = Math.max(tallest, h);
                    });
                }
                window.__sakuraNoAutofocus = {
                    arm: function() { if (!asked()) release(); },
                    // Nuevo chat from the widget or the shortcut: the box may take the cursor for a moment.
                    allow: function() { allowUntil = Date.now() + 3000; },
                    // The app leaves the screen: the box lets go of the cursor, so Android has no keyboard to
                    // bring back on the way in (it reserved its space, a white box, until the app hid it).
                    leave: function() {
                        allowUntil = 0;
                        touchedComposer = false;
                        const el = document.activeElement;
                        if (inBox(el)) el.blur();
                    }
                };
                window.__sakuraNoAutofocus.arm();
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
