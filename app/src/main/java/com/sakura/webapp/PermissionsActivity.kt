package com.sakura.webapp

import android.Manifest
import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat

/**
 * Step two of the setup assistant (and a page of the phone settings): one card per permission
 * the app uses, each with a button to grant it and a pill once it is granted. Nothing here is
 * mandatory; "Continuar" opens Open WebUI.
 */
class PermissionsActivity : ComponentActivity() {
    private lateinit var ui: SetupUi
    private val setupMode by lazy { intent.getBooleanExtra(EXTRA_SETUP, false) }
    private val rows = mutableListOf<Row>()
    /** Permissions already asked for once: a second "Permitir" opens Android's app settings. */
    private val asked = mutableSetOf<String>()

    private class Row(val granted: () -> Boolean, val button: Button, val pill: SetupUi.StatusPill)

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { refresh() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ui = SetupUi(this)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        ui.styleWindow()

        val column = ui.column()
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            setBackgroundColor(ui.ground)
            addView(column)
        }
        ViewCompat.setOnApplyWindowInsetsListener(scroll) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        setContentView(scroll)

        column.addView(ui.header(getString(R.string.permissions_title), getString(R.string.permissions_intro)))

        column.addView(row(
            R.drawable.ic_ui_bell, R.string.permission_notifications_title, R.string.permission_notifications_text,
            optional = false, action = getString(R.string.permission_allow),
            granted = {
                Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(
                    this, Manifest.permission.POST_NOTIFICATIONS
                ) == PackageManager.PERMISSION_GRANTED
            },
        ) { request(Manifest.permission.POST_NOTIFICATIONS) })

        column.addView(row(
            R.drawable.ic_ui_mic, R.string.permission_microphone_title, R.string.permission_microphone_text,
            optional = false, action = getString(R.string.permission_allow),
            granted = {
                ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
                    PackageManager.PERMISSION_GRANTED
            },
        ) { request(Manifest.permission.RECORD_AUDIO) })

        column.addView(row(
            R.drawable.ic_ui_spark, R.string.permission_assistant_title, R.string.permission_assistant_text,
            optional = true, action = getString(R.string.permission_choose_assistant),
            granted = ::isAssistant,
        ) { openAssistantSettings() })

        column.addView(View(this), LinearLayout.LayoutParams(0, 0, 1f).apply { topMargin = ui.dp(16) })
        column.addView(ui.primaryButton(
            getString(if (setupMode) R.string.permissions_continue else R.string.permissions_done)
        ) { finishStep() })
        column.addView(ui.footer(getString(R.string.permissions_footer)))
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun row(
        icon: Int, title: Int, text: Int, optional: Boolean, action: String,
        granted: () -> Boolean, onClick: () -> Unit,
    ): View {
        val card = ui.card()
        card.addView(ui.cardHeader(
            icon, getString(title), getString(text),
            chip = if (optional) getString(R.string.setup_optional) else null
        ))
        val button = ui.smallButton(action, onClick)
        val pill = ui.StatusPill()
        card.addView(button)
        card.addView(pill.view)
        rows += Row(granted, button, pill)
        return card
    }

    private fun refresh() {
        for (row in rows) {
            val ok = row.granted()
            row.button.visibility = if (ok) View.GONE else View.VISIBLE
            if (ok) row.pill.show(SetupUi.Pill.OK, getString(R.string.permission_granted)) else row.pill.hide()
        }
    }

    private fun request(permission: String) {
        if (permission == Manifest.permission.POST_NOTIFICATIONS && Build.VERSION.SDK_INT < 33) return refresh()
        // Android stops showing the dialog after a refusal; send the user to the app's page then.
        if (permission in asked && !shouldShowRequestPermissionRationale(permission)) {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
            return
        }
        asked += permission
        permissionLauncher.launch(permission)
    }

    private fun isAssistant(): Boolean =
        Build.VERSION.SDK_INT >= 29 &&
            getSystemService(RoleManager::class.java)?.isRoleHeld(RoleManager.ROLE_ASSISTANT) == true

    private fun openAssistantSettings() {
        for (intent in listOf(
            Intent(Settings.ACTION_VOICE_INPUT_SETTINGS),
            Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS),
        )) {
            try {
                startActivity(intent)
                return
            } catch (_: Exception) {
            }
        }
    }

    private fun finishStep() {
        if (setupMode) {
            startActivity(
                Intent(this, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            )
        }
        finish()
    }

    companion object {
        private const val EXTRA_SETUP = "setup"

        fun intent(context: Context, setup: Boolean): Intent =
            Intent(context, PermissionsActivity::class.java).putExtra(EXTRA_SETUP, setup)
    }
}
