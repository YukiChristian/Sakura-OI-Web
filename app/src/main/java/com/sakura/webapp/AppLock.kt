package com.sakura.webapp

import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.Build
import android.os.CancellationSignal
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts

/**
 * Optional lock with the fingerprint, face or screen lock PIN, because the app opens every chat.
 * It asks again when the app comes back after [delayMs] in the background (0 = always).
 * Turning it on or off also asks, so someone holding the unlocked phone cannot just disable it.
 */
object AppLock {
    private const val PREFS = "app_lock"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_DELAY = "delay_ms"
    val DELAYS = listOf(0L, 60_000L, 300_000L)

    /** Elapsed-realtime when the app last went to the background; 0 = never unlocked in this process. */
    @Volatile private var backgroundAt = 0L
    @Volatile var unlocked = false
        private set

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isEnabled(context: Context) = prefs(context).getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
        if (enabled) unlocked = true
    }

    fun delayMs(context: Context) = prefs(context).getLong(KEY_DELAY, 60_000L)

    fun setDelayMs(context: Context, delay: Long) = prefs(context).edit().putLong(KEY_DELAY, delay).apply()

    /** The phone has a screen lock (PIN, pattern, password or biometrics on top of one). */
    fun deviceSecure(context: Context): Boolean =
        context.getSystemService(KeyguardManager::class.java)?.isDeviceSecure == true

    private var installed = false
    private var started = 0

    /**
     * Counts the app's visible screens, so going from one Sakura screen to another (chat, settings,
     * «Oye, Sakura») is not "leaving the app"; only when the last one stops does the clock start.
     */
    fun install(context: Context) {
        if (installed) return
        installed = true
        (context.applicationContext as android.app.Application).registerActivityLifecycleCallbacks(
            object : android.app.Application.ActivityLifecycleCallbacks {
                override fun onActivityStarted(activity: Activity) { started++ }
                override fun onActivityStopped(activity: Activity) {
                    started = (started - 1).coerceAtLeast(0)
                    if (started == 0 && unlocked) backgroundAt = SystemClock.elapsedRealtime()
                }
                override fun onActivityCreated(activity: Activity, savedInstanceState: android.os.Bundle?) {}
                override fun onActivityResumed(activity: Activity) {}
                override fun onActivityPaused(activity: Activity) {}
                override fun onActivitySaveInstanceState(activity: Activity, outState: android.os.Bundle) {}
                override fun onActivityDestroyed(activity: Activity) {}
            }
        )
    }

    /** True when the app must be unlocked before showing anything. */
    fun needsUnlock(context: Context): Boolean {
        if (!isEnabled(context) || !deviceSecure(context)) return false
        if (!unlocked) return true
        val away = SystemClock.elapsedRealtime() - backgroundAt
        if (backgroundAt != 0L && away >= delayMs(context)) unlocked = false
        return !unlocked
    }

    fun markUnlocked() {
        unlocked = true
        backgroundAt = 0L
    }

    /**
     * Asks for the fingerprint, face or screen lock. Create it while the activity is being built
     * (it registers an activity result for Android 6 to 9, which use the screen lock screen).
     */
    class Gate(private val activity: ComponentActivity) {
        private var pending: ((Boolean) -> Unit)? = null
        /** A prompt is on screen (Android 6 to 9 show it as another screen, which stops this one). */
        var asking = false
            private set
        private val credential = activity.registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            asking = false
            val callback = pending
            pending = null
            callback?.invoke(result.resultCode == Activity.RESULT_OK)
        }

        fun ask(title: String, subtitle: String, onResult: (Boolean) -> Unit) {
            if (asking) return
            asking = true
            val done: (Boolean) -> Unit = { ok -> asking = false; onResult(ok) }
            if (Build.VERSION.SDK_INT >= 29) {
                val builder = BiometricPrompt.Builder(activity).setTitle(title).setSubtitle(subtitle)
                if (Build.VERSION.SDK_INT >= 30) {
                    builder.setAllowedAuthenticators(
                        BiometricManager.Authenticators.BIOMETRIC_WEAK or BiometricManager.Authenticators.DEVICE_CREDENTIAL
                    )
                } else {
                    @Suppress("DEPRECATION")
                    builder.setDeviceCredentialAllowed(true)
                }
                try {
                    builder.build().authenticate(
                        CancellationSignal(), activity.mainExecutor,
                        object : BiometricPrompt.AuthenticationCallback() {
                            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult?) = done(true)
                            override fun onAuthenticationError(errorCode: Int, errString: CharSequence?) = done(false)
                        }
                    )
                    return
                } catch (e: Exception) {
                    // Fall back to the screen lock below.
                }
            }
            val keyguard = activity.getSystemService(KeyguardManager::class.java)
            @Suppress("DEPRECATION")
            val intent = keyguard?.createConfirmDeviceCredentialIntent(title, subtitle)
            if (intent == null) {
                done(true)
                return
            }
            pending = onResult
            credential.launch(intent)
        }
    }
}
