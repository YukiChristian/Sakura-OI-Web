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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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

    override fun onResume() {
        super.onResume()
        ServerAddress.load(this)
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
