package com.sakura.webapp

import android.Manifest
import android.app.Activity
import android.app.role.RoleManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat

/**
 * "Ajustes del teléfono": the app's own settings, in the same card style as the setup
 * assistant. Reached from Open WebUI's user menu, the launcher shortcut, Android's app info
 * ("Additional settings in the app") or the "Oye, Sakura" notification. «Oye, Sakura» and the
 * one-tap shortcuts have their own screen (WakeWordSettingsActivity).
 */
class SettingsActivity : ComponentActivity() {
    private lateinit var ui: SetupUi
    private lateinit var parts: SetupParts
    private lateinit var connectionText: TextView
    private lateinit var permissionsPill: SetupUi.StatusPill
    private lateinit var assistantPill: SetupUi.StatusPill
    private lateinit var wakeTitle: TextView
    private lateinit var wakeDot: View
    private lateinit var wakeStatus: TextView
    private val lockGate = AppLock.Gate(this)
    private lateinit var lockSwitch: PillSwitch
    private lateinit var lockDot: View
    private lateinit var lockStatus: TextView
    private lateinit var lockDelayBox: View
    private lateinit var lockDelay: SetupParts.Segmented
    private lateinit var lockMissing: View
    private lateinit var updateStatus: TextView
    private lateinit var updateButton: TextView
    private lateinit var updateNowButton: TextView
    private lateinit var updateAuto: PillSwitch
    private var checking = false
    private lateinit var pushSwitch: PillSwitch
    private lateinit var pushDot: View
    private lateinit var pushStatus: TextView
    private lateinit var pushInstall: View
    private val pushPoll = android.os.Handler(android.os.Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppLock.install(this)
        ServerAddress.load(this)
        ui = SetupUi(this)
        parts = SetupParts(ui)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        ui.styleWindow()

        val column = ui.column()
        val scroll = ScrollView(this).apply {
            setBackgroundColor(ui.ground)
            addView(column)
        }
        ViewCompat.setOnApplyWindowInsetsListener(scroll) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        setContentView(scroll)

        column.addView(ui.text(getString(R.string.settings_title), 24f, ui.ink, bold = true).apply {
            setPadding(ui.dp(4), ui.dp(8), 0, ui.dp(16))
        })

        // Connection
        val connection = ui.card()
        connection.addView(ui.cardHeader(
            R.drawable.ic_ui_globe, getString(R.string.connection_heading), getString(R.string.connection_edit_intro)
        ))
        connectionText = ui.text("", 13.5f, ui.ink).apply {
            typeface = android.graphics.Typeface.MONOSPACE
            setPadding(0, ui.dp(12), 0, 0)
        }
        connection.addView(connectionText)
        connection.addView(ui.smallButton(getString(R.string.connection_edit_button)) {
            startActivity(ConnectionActivity.intent(this, setup = false))
        })
        column.addView(connection)

        // Permissions
        val permissions = ui.card()
        permissions.addView(ui.cardHeader(
            R.drawable.ic_ui_bell, getString(R.string.permissions_heading), getString(R.string.permissions_intro)
        ))
        permissionsPill = ui.StatusPill()
        permissions.addView(permissionsPill.view)
        permissions.addView(ui.smallButton(getString(R.string.permissions_button)) {
            startActivity(PermissionsActivity.intent(this, setup = false))
        })
        column.addView(permissions)

        // Phone assistant
        val assistant = ui.card()
        assistant.addView(ui.cardHeader(
            R.drawable.ic_ui_spark, getString(R.string.assistant_heading), getString(R.string.assistant_description),
            chip = getString(R.string.setup_optional)
        ))
        assistantPill = ui.StatusPill()
        assistant.addView(assistantPill.view)
        assistant.addView(ui.smallButton(getString(R.string.assistant_button)) { openAssistantSettings(this) })
        column.addView(assistant)

        // «Oye, Sakura» and the one-tap shortcuts: a summary that opens their screen.
        column.addView(wakeEntry())
        column.addView(lockCard())
        column.addView(pushCard())
        column.addView(updatesCard())

        column.addView(ui.footer(getString(R.string.settings_version, BuildConfig.VERSION_NAME)).apply {
            gravity = Gravity.CENTER
            setPadding(0, ui.dp(12), 0, ui.dp(8))
        })
    }

    private fun wakeEntry() = ui.card().apply {
        isClickable = true
        isFocusable = true
        setOnClickListener { startActivity(Intent(this@SettingsActivity, WakeWordSettingsActivity::class.java)) }
        wakeTitle = ui.text("", 16.5f, ui.ink, bold = true)
        wakeDot = View(context)
        wakeStatus = ui.text("", 13f, ui.muted, semibold = true)
        addView(LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(parts.tile(R.drawable.ic_ui_mic, parts.pink, parts.pinkTile))
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(LinearLayout(context).apply {
                    gravity = Gravity.CENTER_VERTICAL
                    addView(wakeTitle)
                    addView(ui.text(getString(R.string.setup_optional), 12f, ui.muted, semibold = true).apply {
                        setPadding(ui.dp(8), ui.dp(2), ui.dp(8), ui.dp(2))
                        background = GradientDrawable().apply { cornerRadius = ui.dp(11).toFloat(); setColor(ui.tile) }
                    }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                        .apply { marginStart = ui.dp(8) })
                })
                addView(ui.text(getString(R.string.wake_entry_description), 13.5f, ui.muted))
                addView(LinearLayout(context).apply {
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(0, ui.dp(6), 0, 0)
                    addView(wakeDot, LinearLayout.LayoutParams(ui.dp(7), ui.dp(7)).apply { marginEnd = ui.dp(6) })
                    addView(wakeStatus)
                })
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = ui.dp(12)
                marginEnd = ui.dp(8)
            })
            addView(parts.icon(R.drawable.ic_ui_chevron_right, ui.hintColor, 18))
        })
    }

    override fun onStart() {
        super.onStart()
        // Locked app: the settings (addresses, the lock itself) need the fingerprint too.
        if (!lockGate.asking && AppLock.needsUnlock(this)) {
            lockGate.ask(getString(R.string.lock_prompt_title), getString(R.string.lock_prompt_text)) { ok ->
                if (ok) AppLock.markUnlocked() else finish()
            }
        }
    }


    // ── Bloqueo con huella ───────────────────────────────────────────────────────────────────────

    private fun panel(title: String, dot: View, status: TextView, switch: View) = LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL
        setPadding(ui.dp(14), ui.dp(12), ui.dp(14), ui.dp(12))
        background = GradientDrawable().apply {
            cornerRadius = ui.dp(16).toFloat()
            setColor(parts.panel)
            setStroke(ui.dp(1), parts.panelRing)
        }
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(ui.text(title, 15.5f, ui.ink, semibold = true))
            addView(LinearLayout(context).apply {
                gravity = Gravity.CENTER_VERTICAL
                addView(dot, LinearLayout.LayoutParams(ui.dp(7), ui.dp(7)).apply { marginEnd = ui.dp(6) })
                addView(status)
            })
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        addView(switch, LinearLayout.LayoutParams(ui.dp(52), ui.dp(30)).apply { marginStart = ui.dp(12) })
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = ui.dp(14) }
    }

    private fun pillSwitch() = PillSwitch(this, ui.ink, if (ui.night) 0xFF3F3F46.toInt() else 0xFFD4D4D8.toInt(),
        if (ui.night) 0xFF18181B.toInt() else android.graphics.Color.WHITE)

    private fun dotColor(view: View, color: Int) {
        view.background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(color) }
    }

    private fun lockCard() = ui.card().apply {
        addView(ui.cardHeader(R.drawable.ic_ui_fingerprint, getString(R.string.lock_heading), getString(R.string.lock_description),
            chip = getString(R.string.setup_optional)))
        lockDot = View(context)
        lockStatus = ui.text("", 13f, ui.muted, semibold = true)
        lockSwitch = pillSwitch().apply {
            contentDescription = getString(R.string.lock_switch)
            onToggle = { on -> toggleLock(on) }
        }
        addView(panel(getString(R.string.lock_switch), lockDot, lockStatus, lockSwitch))
        lockDelay = parts.Segmented(listOf(getString(R.string.lock_delay_now), getString(R.string.lock_delay_1), getString(R.string.lock_delay_5))) { i ->
            AppLock.setDelayMs(this@SettingsActivity, AppLock.DELAYS[i])
        }
        lockDelayBox = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(ui.text(getString(R.string.lock_delay_title), 15.5f, ui.ink, semibold = true).apply {
                setPadding(0, ui.dp(14), 0, ui.dp(8))
            })
            addView(lockDelay.view)
        }
        addView(lockDelayBox)
        lockMissing = parts.banner(getString(R.string.lock_no_screen_lock), getString(R.string.lock_open_security)) {
            runCatching { startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS)) }
        }
        addView(lockMissing, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = ui.dp(12) })
    }

    /** Turning the lock on or off asks for the fingerprint, face or PIN first. */
    private fun toggleLock(on: Boolean) {
        lockGate.ask(
            getString(if (on) R.string.lock_turn_on_title else R.string.lock_turn_off_title),
            getString(R.string.lock_prompt_text)
        ) { ok ->
            if (ok) {
                AppLock.setEnabled(this, on)
                AppLock.markUnlocked()
            }
            refreshLock()
        }
    }

    private fun refreshLock() {
        val secure = AppLock.deviceSecure(this)
        val on = AppLock.isEnabled(this) && secure
        lockSwitch.set(on)
        lockSwitch.isEnabled = secure
        lockSwitch.alpha = if (secure) 1f else 0.4f
        lockStatus.text = getString(if (on) R.string.lock_state_on else R.string.lock_state_off)
        val color = if (on) parts.green else ui.faint
        lockStatus.setTextColor(color)
        dotColor(lockDot, color)
        lockDelayBox.visibility = if (on) View.VISIBLE else View.GONE
        lockDelay.select(AppLock.DELAYS.indexOf(AppLock.delayMs(this)).coerceAtLeast(0))
        lockMissing.visibility = if (secure) View.GONE else View.VISIBLE
    }

    // ── Avisos con la app cerrada ────────────────────────────────────────────────────────────────

    private fun pushCard() = ui.card().apply {
        addView(ui.cardHeader(R.drawable.ic_ui_bell, getString(R.string.push_heading), getString(R.string.push_description),
            chip = getString(R.string.setup_optional)))
        pushDot = View(context)
        pushStatus = ui.text("", 13f, ui.muted, semibold = true)
        pushSwitch = pillSwitch().apply {
            contentDescription = getString(R.string.push_switch)
            onToggle = { on ->
                if (on) PushNotices.enable(this@SettingsActivity) else PushNotices.disable(this@SettingsActivity)
                refreshPush()
            }
        }
        addView(panel(getString(R.string.push_switch), pushDot, pushStatus, pushSwitch))
        pushInstall = parts.darkButton(getString(R.string.push_install)) { installNtfy() }.apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ui.dp(34)).apply { topMargin = ui.dp(12) }
        }
        addView(pushInstall)
        addView(ui.text(getString(R.string.push_privacy), 12.5f, ui.faint).apply { setPadding(0, ui.dp(12), 0, 0) })
    }

    private fun installNtfy() {
        for (uri in listOf("market://details?id=${PushNotices.NTFY_PACKAGE}", "https://ntfy.sh/docs/subscribe/phone/")) {
            try {
                startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(uri)))
                return
            } catch (_: Exception) {
            }
        }
    }

    private fun refreshPush() {
        // ntfy was installed meanwhile: register now.
        if (PushNotices.isEnabled(this) && PushNotices.state(this) == PushNotices.State.NO_DISTRIBUTOR &&
            PushNotices.distributors(this).isNotEmpty()
        ) PushNotices.enable(this)
        val state = PushNotices.state(this)
        pushSwitch.set(PushNotices.isEnabled(this))
        val (text, color) = when (state) {
            PushNotices.State.OFF -> getString(R.string.push_state_off) to ui.faint
            PushNotices.State.CONNECTING -> getString(R.string.push_state_connecting) to parts.blue
            PushNotices.State.ON -> getString(R.string.push_state_on, PushNotices.pushHost(this) ?: "ntfy") to parts.green
            PushNotices.State.NO_DISTRIBUTOR -> getString(R.string.push_state_no_app) to ui.error
            PushNotices.State.NOT_ADMIN -> getString(R.string.push_state_not_admin) to ui.error
            PushNotices.State.FAILED -> getString(R.string.push_state_failed) to ui.error
        }
        pushStatus.text = text
        pushStatus.setTextColor(color)
        dotColor(pushDot, color)
        pushInstall.visibility = if (state == PushNotices.State.NO_DISTRIBUTOR) View.VISIBLE else View.GONE
        pushPoll.removeCallbacksAndMessages(null)
        if (state == PushNotices.State.CONNECTING) pushPoll.postDelayed({ refreshPush() }, 1_000)
    }

    // ── Actualizaciones ──────────────────────────────────────────────────────────────────────────

    private fun updatesCard() = ui.card().apply {
        addView(ui.cardHeader(R.drawable.ic_ui_refresh, getString(R.string.updates_heading), getString(R.string.updates_description)))
        updateStatus = ui.text("", 13.5f, ui.muted).apply { setPadding(0, ui.dp(12), 0, 0) }
        addView(updateStatus)
        updateButton = parts.ghostButton(getString(R.string.updates_check)) { checkNow() }
        updateNowButton = parts.darkButton("") {
            Updates.available(this@SettingsActivity)?.let { UpdatePrompt.show(this@SettingsActivity, it) }
        }
        addView(LinearLayout(context).apply {
            setPadding(0, ui.dp(12), 0, 0)
            addView(updateButton)
            addView(updateNowButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ui.dp(34)).apply { marginStart = ui.dp(8) })
        })
        updateAuto = pillSwitch().apply {
            contentDescription = getString(R.string.updates_auto)
            onToggle = { on -> Updates.setAutomatic(this@SettingsActivity, on); refreshUpdates() }
        }
        val autoDot = View(context)
        val autoText = ui.text(getString(R.string.updates_auto_text), 13f, ui.faint, semibold = true)
        dotColor(autoDot, ui.faint)
        addView(panel(getString(R.string.updates_auto), autoDot, autoText, updateAuto))
    }

    private fun checkNow() {
        if (checking) return
        checking = true
        updateStatus.text = getString(R.string.updates_checking)
        updateStatus.setTextColor(parts.blue)
        Thread {
            val result = runCatching { Updates.fetch(this) }
            runOnUiThread {
                checking = false
                if (result.isFailure) {
                    updateStatus.text = getString(R.string.updates_failed)
                    updateStatus.setTextColor(ui.error)
                    return@runOnUiThread
                }
                refreshUpdates()
                Updates.available(this)?.let { UpdatePrompt.show(this, it) }
            }
        }.start()
    }

    private fun refreshUpdates() {
        if (checking) return
        val available = Updates.available(this)
        val last = Updates.lastCheck(this)
        val checked = if (last == 0L) getString(R.string.updates_never)
        else android.text.format.DateUtils.getRelativeTimeSpanString(
            last, System.currentTimeMillis(), android.text.format.DateUtils.MINUTE_IN_MILLIS
        ).toString()
        if (available != null) {
            updateStatus.text = getString(R.string.updates_available, available.version, Updates.current)
            updateStatus.setTextColor(parts.blue)
            updateNowButton.text = getString(R.string.updates_install, available.version)
            updateNowButton.visibility = View.VISIBLE
        } else {
            updateStatus.text = getString(R.string.updates_current, BuildConfig.VERSION_NAME, checked)
            updateStatus.setTextColor(ui.muted)
            updateNowButton.visibility = View.GONE
        }
        updateAuto.set(Updates.isAutomatic(this))
    }

    override fun onResume() {
        super.onResume()
        ServerAddress.load(this)
        refreshLock()
        refreshPush()
        refreshUpdates()
        connectionText.text = connectionSummary()
        refreshPermissions()
        // After an update or a reboot the listener is not running yet; start it here too.
        if (WakeWordModel.isEnabled(this) && WakeWordModel.isReady(this) && !WakeWordService.running &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        ) WakeWordService.start(this)
        refreshWakeEntry()
    }

    private fun refreshWakeEntry() {
        wakeTitle.text = getString(R.string.wake_quoted, WakePhrase.phrase(this))
        val on = WakeWordModel.isEnabled(this) && WakeWordModel.isReady(this)
        val color = if (on) parts.green else ui.faint
        wakeStatus.text = getString(if (on) R.string.wake_state_on else R.string.wake_state_off)
        wakeStatus.setTextColor(color)
        wakeDot.background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(color) }
    }

    private fun refreshPermissions() {
        val notifications = Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(
            this, Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
        val microphone = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        if (notifications && microphone) permissionsPill.show(SetupUi.Pill.OK, getString(R.string.permission_granted))
        else permissionsPill.hide()
        val isAssistant = Build.VERSION.SDK_INT >= 29 &&
            getSystemService(RoleManager::class.java)?.isRoleHeld(RoleManager.ROLE_ASSISTANT) == true
        if (isAssistant) assistantPill.show(SetupUi.Pill.OK, getString(R.string.permission_granted))
        else assistantPill.hide()
    }

    private fun connectionSummary(): String {
        val none = getString(R.string.connection_not_set)
        val addresses = getString(
            R.string.connection_addresses,
            ServerAddress.local?.removePrefix("https://")?.trimEnd('/') ?: none,
            ServerAddress.exterior?.removePrefix("https://")?.trimEnd('/') ?: none,
        )
        val mode = getString(
            when {
                ServerAddress.local == null || ServerAddress.exterior == null -> R.string.connection_single
                ServerAddress.usingLan -> R.string.connection_using_lan
                else -> R.string.connection_using_remote
            }
        )
        return "$addresses\n\n$mode"
    }

    companion object {
        /** Android's digital assistant settings, where Sakura can be chosen (headset button, assist gesture). */
        fun openAssistantSettings(activity: Activity) {
            for (intent in listOf(
                Intent(Settings.ACTION_VOICE_INPUT_SETTINGS),
                Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS),
                Intent(Settings.ACTION_SETTINGS),
            )) {
                try {
                    activity.startActivity(intent)
                    return
                } catch (_: Exception) {
                }
            }
        }
    }
}
