package com.sakura.webapp

import android.Manifest
import android.app.role.RoleManager
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat

/**
 * "Ajustes del teléfono": the app's own settings, in the same card style as the setup
 * assistant. Reached from Open WebUI's user menu, the launcher shortcut, Android's app info
 * ("Additional settings in the app") or the "Oye, Sakura" notification.
 */
class SettingsActivity : ComponentActivity() {
    private lateinit var ui: SetupUi
    private lateinit var wakeSwitch: Switch
    private lateinit var wakeStatus: TextView
    private lateinit var overlayButton: View
    private lateinit var connectionText: TextView
    private lateinit var permissionsPill: SetupUi.StatusPill
    private lateinit var assistantPill: SetupUi.StatusPill
    private var downloading = false

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        if (results[Manifest.permission.RECORD_AUDIO] == true) turnOnWakeWord() else {
            wakeSwitch.isChecked = false
            wakeStatus.text = getString(R.string.wake_status_no_mic)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ServerAddress.load(this)
        ui = SetupUi(this)
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
        assistant.addView(ui.smallButton(getString(R.string.assistant_button)) { openAssistantSettings() })
        column.addView(assistant)

        // "Oye, Sakura"
        val wake = ui.card()
        wake.addView(ui.cardHeader(
            R.drawable.ic_ui_mic, getString(R.string.wake_heading), getString(R.string.wake_description),
            chip = getString(R.string.setup_optional)
        ))
        wakeSwitch = Switch(this).apply {
            text = getString(R.string.wake_switch)
            textSize = 16f
            setTextColor(ui.ink)
            thumbTintList = ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                intArrayOf(ui.ink, ui.fieldBorder)
            )
            isChecked = WakeWordModel.isEnabled(this@SettingsActivity)
            setPadding(0, ui.dp(12), 0, ui.dp(4))
            setOnCheckedChangeListener { _, checked -> if (checked) requestWakeWord() else turnOffWakeWord() }
        }
        wake.addView(wakeSwitch)
        wakeStatus = ui.text("", 13.5f, ui.muted)
        wake.addView(wakeStatus)
        overlayButton = ui.smallButton(getString(R.string.wake_overlay_button)) {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }
        wake.addView(overlayButton)
        column.addView(wake)

        column.addView(ui.footer(getString(R.string.settings_version, BuildConfig.VERSION_NAME)).apply {
            gravity = Gravity.CENTER
            setPadding(0, ui.dp(12), 0, ui.dp(8))
        })
    }

    override fun onResume() {
        super.onResume()
        ServerAddress.load(this)
        connectionText.text = connectionSummary()
        refreshPermissions()
        refreshWakeStatus()
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

    private fun requestWakeWord() {
        val needed = buildList {
            if (ContextCompat.checkSelfPermission(this@SettingsActivity, Manifest.permission.RECORD_AUDIO) !=
                PackageManager.PERMISSION_GRANTED
            ) add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(this@SettingsActivity, Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
            ) add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (needed.isEmpty()) turnOnWakeWord() else permissionLauncher.launch(needed.toTypedArray())
    }

    private fun turnOnWakeWord() {
        WakeWordModel.setEnabled(this, true)
        if (WakeWordModel.isReady(this)) {
            WakeWordService.start(this)
            refreshWakeStatus()
            return
        }
        if (downloading) return
        downloading = true
        wakeStatus.text = getString(R.string.wake_status_downloading, 0)
        Thread {
            try {
                var shown = -1
                WakeWordModel.download(this) { percent ->
                    if (percent != shown) {
                        shown = percent
                        runOnUiThread { wakeStatus.text = getString(R.string.wake_status_downloading, percent) }
                    }
                }
                runOnUiThread {
                    downloading = false
                    if (WakeWordModel.isEnabled(this)) WakeWordService.start(this)
                    refreshWakeStatus()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    downloading = false
                    WakeWordModel.setEnabled(this, false)
                    wakeSwitch.isChecked = false
                    wakeStatus.text = getString(R.string.wake_status_download_failed)
                }
            }
        }.start()
    }

    private fun turnOffWakeWord() {
        WakeWordModel.setEnabled(this, false)
        stopService(Intent(this, WakeWordService::class.java))
        refreshWakeStatus()
    }

    private fun refreshWakeStatus() {
        if (downloading) return
        val enabled = WakeWordModel.isEnabled(this)
        wakeSwitch.isChecked = enabled
        wakeStatus.text = getString(
            when {
                !enabled -> R.string.wake_status_off
                !WakeWordModel.isReady(this) -> R.string.wake_status_no_model
                else -> R.string.wake_status_on
            }
        )
        val canOverlay = Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(this)
        overlayButton.visibility = if (enabled && !canOverlay) View.VISIBLE else View.GONE
    }

    private fun openAssistantSettings() {
        for (intent in listOf(
            Intent(Settings.ACTION_VOICE_INPUT_SETTINGS),
            Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS),
            Intent(Settings.ACTION_SETTINGS),
        )) {
            try {
                startActivity(intent)
                return
            } catch (_: Exception) {
            }
        }
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
}
