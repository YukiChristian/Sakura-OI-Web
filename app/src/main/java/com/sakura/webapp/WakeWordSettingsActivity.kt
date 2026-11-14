package com.sakura.webapp

import android.Manifest
import android.app.Dialog
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat

/**
 * «Oye, Sakura», from "App de Android · Parte 1" (A1–A4, Hanami): the switch with one live state (model
 * download with progress, listening, a missing permission named exactly), the phrase and sensitivity,
 * training with the user's voice, and «Accesos rápidos» in one row; everything fits on one screen.
 */
class WakeWordSettingsActivity : ComponentActivity() {
    private lateinit var ui: SetupUi
    private lateinit var parts: SetupParts
    private lateinit var titleText: TextView
    private lateinit var wakeSwitch: PillSwitch
    private lateinit var statusDot: View
    private lateinit var statusIcon: android.widget.ImageView
    private lateinit var statusText: TextView
    private val dimmed = mutableListOf<View>()
    private lateinit var downloadBox: LinearLayout
    private lateinit var downloadAmount: TextView
    private lateinit var downloadBar: SetupParts.Progress
    private lateinit var overlayBanner: View
    private lateinit var batteryBanner: View
    private lateinit var phraseValue: TextView
    private lateinit var sensitivity: SetupParts.Segmented
    private lateinit var sensitivityHelp: TextView
    private lateinit var voiceRow: SetupParts.Row
    private lateinit var voiceButton: TextView
    private var downloading = false

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        if (results[Manifest.permission.RECORD_AUDIO] == true) turnOn() else {
            wakeSwitch.set(false)
            showStatus(Status.ERROR, getString(R.string.wake_status_no_mic))
        }
    }

    private enum class Status { OFF, ON, BUSY, WARN, ERROR }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ui = SetupUi(this)
        parts = SetupParts(ui)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        ui.styleWindow()

        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ui.dp(14), ui.dp(4), ui.dp(14), ui.dp(30))
        }
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(parts.navBar(getString(R.string.settings_title_short), getString(R.string.wake_screen_title)) { finish() }
                .apply { setPadding(ui.dp(6), 0, ui.dp(6), 0) })
            addView(ScrollView(this@WakeWordSettingsActivity).apply { addView(column) },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            setBackgroundColor(ui.ground)
        }
        ViewCompat.setOnApplyWindowInsetsListener(page) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        setContentView(page)

        column.addView(mainCard())
        // While the model downloads, only what depends on it is dimmed (A2).
        dimmed += phraseCard().also { column.addView(it) }
        dimmed += voiceCard().also { column.addView(it) }
        column.addView(shortcutsCard())
    }

    override fun onResume() {
        super.onResume()
        if (WakeWordModel.isEnabled(this) && WakeWordModel.isReady(this) && !WakeWordService.running &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        ) WakeWordService.start(this)
        refresh()
    }

    // ── «Oye, Sakura»: switch, state, chips and the overlay permission ──────────────────────────

    private fun mainCard() = parts.card().apply {
        titleText = ui.text("", 17f, ui.ink, bold = true)
        addView(LinearLayout(context).apply {
            gravity = Gravity.TOP
            addView(parts.tile(R.drawable.ic_ui_mic, parts.pink, parts.pinkTile))
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(titleText)
                addView(ui.text(getString(R.string.wake_short_description), 13.5f, ui.muted).apply { setPadding(0, ui.dp(2), 0, 0) })
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = ui.dp(12) })
        })

        // The switch panel.
        statusDot = View(context)
        statusIcon = parts.icon(R.drawable.ic_ui_warning, parts.amber, 14).apply { visibility = View.GONE }
        statusText = ui.text("", 13f, ui.muted, semibold = true).apply {
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        // Slate blue when on, as everything chosen in Hanami; it stays blue even while a permission is missing.
        wakeSwitch = PillSwitch(context, parts.blue, if (ui.night) 0xFF3F3F46.toInt() else 0xFFD4D4D8.toInt(), Color.WHITE).apply {
            contentDescription = getString(R.string.wake_listen_title)
            onToggle = { on -> if (on) requestWakeWord() else turnOff() }
        }
        addView(LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(ui.dp(14), ui.dp(12), ui.dp(14), ui.dp(12))
            background = GradientDrawable().apply {
                cornerRadius = ui.dp(16).toFloat()
                setColor(parts.panel)
                setStroke(ui.dp(1), parts.panelRing)
            }
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(ui.text(getString(R.string.wake_listen_title), 15.5f, ui.ink, semibold = true))
                addView(LinearLayout(context).apply {
                    gravity = Gravity.CENTER_VERTICAL
                    addView(statusDot, LinearLayout.LayoutParams(ui.dp(7), ui.dp(7)).apply { marginEnd = ui.dp(6) })
                    addView(statusIcon, LinearLayout.LayoutParams(ui.dp(14), ui.dp(14)).apply { marginEnd = ui.dp(5) })
                    addView(statusText)
                })
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(wakeSwitch, LinearLayout.LayoutParams(ui.dp(52), ui.dp(30)).apply { marginStart = ui.dp(12) })
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = ui.dp(14) })

        // Model download.
        downloadAmount = ui.text("", 13f, ui.muted)
        downloadBar = parts.Progress()
        downloadBox = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            addView(LinearLayout(context).apply {
                addView(ui.text(getString(R.string.wake_model_name), 13f, ui.muted),
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(downloadAmount.apply { setTextColor(ui.ink) })
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = ui.dp(6) })
            addView(downloadBar.view)
            addView(ui.text(getString(R.string.wake_model_once), 12.5f, ui.muted).apply { setPadding(0, ui.dp(6), 0, 0) })
        }
        addView(downloadBox, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = ui.dp(12) })

        overlayBanner = parts.banner(getText(R.string.wake_overlay_missing), getString(R.string.wake_overlay_allow)) {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }
        addView(overlayBanner, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = ui.dp(12) })

        // Without this, Android stops listening after a while to save battery.
        batteryBanner = parts.banner(getString(R.string.wake_battery_missing), getString(R.string.wake_battery_allow)) {
            WakeWordService.requestBatteryUnrestricted(this@WakeWordSettingsActivity)
        }
        addView(batteryBanner, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = ui.dp(12) })
    }

    /** One short state with its dot or, for a warning, its icon: it never relies on colour alone. */
    private fun showStatus(status: Status, text: String) {
        val color = when (status) {
            Status.ON -> parts.greenText
            Status.BUSY -> parts.blueText
            Status.WARN -> parts.amberText
            Status.ERROR -> ui.error
            Status.OFF -> ui.faint
        }
        statusText.text = text
        statusText.setTextColor(color)
        val icon = status == Status.WARN || status == Status.ERROR
        statusIcon.visibility = if (icon) View.VISIBLE else View.GONE
        statusIcon.imageTintList = android.content.res.ColorStateList.valueOf(if (status == Status.WARN) parts.amber else ui.error)
        statusDot.visibility = if (icon) View.GONE else View.VISIBLE
        statusDot.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(if (status == Status.ON) parts.green else if (status == Status.BUSY) parts.blue else color)
        }
    }

    private fun dim(on: Boolean) = dimmed.forEach { card ->
        card.alpha = if (on) 0.5f else 1f
        setEnabledDeep(card, !on)
    }

    private fun setEnabledDeep(view: View, enabled: Boolean) {
        view.isEnabled = enabled
        if (view is ViewGroup) for (i in 0 until view.childCount) setEnabledDeep(view.getChildAt(i), enabled)
    }

    // ── Frase y sensibilidad ─────────────────────────────────────────────────────────────────────

    private fun phraseCard() = parts.card().apply {
        addView(parts.sectionLabel(getString(R.string.wake_section_phrase)))
        phraseValue = ui.text("", 13f, ui.muted)
        addView(LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, ui.dp(4), 0, ui.dp(14))
            background = HairlineBelow(parts.divider, ui.dp(1))
            addView(parts.tile(R.drawable.ic_ui_wave))
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(ui.text(getString(R.string.wake_phrase_label), 15.5f, ui.ink, semibold = true))
                addView(phraseValue)
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = ui.dp(12)
                marginEnd = ui.dp(8)
            })
            addView(parts.ghostButton(getString(R.string.wake_phrase_change)) { choosePhrase() })
        })
        addView(ui.text(getString(R.string.wake_sensitivity), 15.5f, ui.ink, semibold = true).apply {
            setPadding(0, ui.dp(14), 0, ui.dp(8))
        })
        val levels = WakePhrase.Sensitivity.entries
        sensitivity = parts.Segmented(levels.map { getString(sensitivityLabel(it)) }) { i ->
            WakePhrase.setSensitivity(this@WakeWordSettingsActivity, levels[i])
            refresh()
        }
        addView(sensitivity.view)
        sensitivityHelp = ui.text("", 13f, ui.muted).apply {
            setPadding(0, ui.dp(8), 0, 0)
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        addView(sensitivityHelp)
    }

    private fun sensitivityLabel(level: WakePhrase.Sensitivity): Int = when (level) {
        WakePhrase.Sensitivity.STRICT -> R.string.wake_sensitivity_strict
        WakePhrase.Sensitivity.NORMAL -> R.string.wake_sensitivity_normal
        WakePhrase.Sensitivity.RELAXED -> R.string.wake_sensitivity_relaxed
    }

    private fun sensitivityHelp(level: WakePhrase.Sensitivity): Int = when (level) {
        WakePhrase.Sensitivity.STRICT -> R.string.wake_sensitivity_strict_help
        WakePhrase.Sensitivity.NORMAL -> R.string.wake_sensitivity_normal_help
        WakePhrase.Sensitivity.RELAXED -> R.string.wake_sensitivity_relaxed_help
    }

    // ── Tu voz ───────────────────────────────────────────────────────────────────────────────────

    private fun voiceCard() = parts.card(padTop = 4, padBottom = 4).apply {
        voiceButton = parts.ghostButton(getString(R.string.wake_train_button)) {
            startActivity(Intent(this@WakeWordSettingsActivity, VoiceTrainingActivity::class.java))
        }
        voiceRow = parts.Row(R.drawable.ic_ui_user, getString(R.string.wake_voice_title), "", voiceButton, last = true)
        addView(voiceRow.view)
    }

    // ── Accesos rápidos ──────────────────────────────────────────────────────────────────────────

    private fun shortcutsCard() = parts.card(padTop = 4, padBottom = 4).apply {
        addView(parts.Row(
            R.drawable.ic_ui_widget, getString(R.string.quick_heading), getString(R.string.quick_entry_text),
            parts.icon(R.drawable.ic_ui_chevron_right, ui.faint, 18), last = true
        ) { showShortcuts() }.view)
    }

    /** «Accesos rápidos»: the quick settings tile, the widget and the headphones, in a sheet. */
    private fun showShortcuts() {
        val dialog = Dialog(this)
        val body = parts.card(padTop = 14, padBottom = 4).apply {
            elevation = 0f
            addView(parts.sectionLabel(getString(R.string.quick_heading)).apply { setPadding(0, 0, 0, ui.dp(2)) })
            addView(ui.text(getString(R.string.quick_description), 13f, ui.muted).apply { setPadding(0, 0, 0, ui.dp(4)) })
            shortcutRows(this)
        }
        bottomSheet(dialog, body)
    }

    private fun shortcutRows(card: LinearLayout) = card.apply {
        val tile = if (Build.VERSION.SDK_INT >= 33) parts.ghostButton(getString(R.string.quick_add)) { requestTile() } else null
        addView(parts.Row(
            R.drawable.ic_ui_tile, getString(R.string.quick_tile_title),
            getString(if (tile != null) R.string.quick_tile_text else R.string.quick_tile_text_manual), tile, last = false
        ).view)
        val widgets = getSystemService(AppWidgetManager::class.java)
        val widget = if (Build.VERSION.SDK_INT >= 26 && widgets?.isRequestPinAppWidgetSupported == true) {
            parts.ghostButton(getString(R.string.quick_add)) {
                widgets.requestPinAppWidget(ComponentName(this@WakeWordSettingsActivity, VoiceWidgetProvider::class.java), null, null)
            }
        } else null
        addView(parts.Row(R.drawable.ic_ui_widget, getString(R.string.quick_widget_title), getString(R.string.quick_widget_text), widget, last = false).view)
        addView(parts.Row(
            R.drawable.ic_ui_headphones, getString(R.string.quick_headset_title), getString(R.string.quick_headset_text),
            parts.icon(R.drawable.ic_ui_chevron_right, ui.hintColor, 18), last = true
        ) { SettingsActivity.openAssistantSettings(this@WakeWordSettingsActivity) }.view)
    }

    /** Shows [content] in a sheet that rises from the bottom (a fade with reduced motion). */
    private fun bottomSheet(dialog: Dialog, content: View) {
        val sheet = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ui.dp(14), ui.dp(10), ui.dp(14), ui.dp(24))
            background = GradientDrawable().apply {
                cornerRadii = FloatArray(8) { if (it < 4) ui.dp(26).toFloat() else 0f }
                setColor(ui.ground)
            }
            addView(View(context).apply {
                background = GradientDrawable().apply { cornerRadius = ui.dp(3).toFloat(); setColor(if (ui.night) 0xFF3F3F46.toInt() else 0xFFD4D4D8.toInt()) }
            }, LinearLayout.LayoutParams(ui.dp(36), ui.dp(4)).apply { gravity = Gravity.CENTER_HORIZONTAL; bottomMargin = ui.dp(12) })
            addView(content)
        }
        dialog.setContentView(FrameLayout(this).apply { addView(sheet) })
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            setGravity(Gravity.BOTTOM)
            setDimAmount(0.32f)
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            if (!motionAllowed()) setWindowAnimations(0)
        }
        dialog.show()
    }

    @android.annotation.SuppressLint("NewApi")
    private fun requestTile() {
        val manager = getSystemService(android.app.StatusBarManager::class.java) ?: return
        manager.requestAddTileService(
            ComponentName(this, VoiceTileService::class.java),
            getString(R.string.tile_label),
            android.graphics.drawable.Icon.createWithResource(this, R.drawable.ic_tile_mic),
            mainExecutor,
        ) { }
    }

    // ── State ────────────────────────────────────────────────────────────────────────────────────

    private fun refresh() {
        val phrase = WakePhrase.phrase(this)
        titleText.text = getString(R.string.wake_quoted, phrase)
        phraseValue.text = getString(R.string.wake_quoted, phrase)
        val level = WakePhrase.sensitivity(this)
        sensitivity.select(WakePhrase.Sensitivity.entries.indexOf(level))
        sensitivityHelp.text = getString(sensitivityHelp(level))
        val trained = WakePhrase.samples(this).size
        voiceRow.subtitle.text = if (trained > 0) getString(R.string.wake_voice_trained, minOf(trained, 5), 5)
        else getString(R.string.wake_voice_untrained)
        voiceButton.text = getString(if (trained > 0) R.string.wake_retrain_button else R.string.wake_train_button)

        val enabled = WakeWordModel.isEnabled(this)
        val canOverlay = Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(this)
        // One notice at a time: the overlay permission first, then the battery restriction.
        overlayBanner.visibility = if (enabled && !downloading && !canOverlay) View.VISIBLE else View.GONE
        batteryBanner.visibility = if (enabled && !downloading && canOverlay && !WakeWordService.batteryUnrestricted(this)) View.VISIBLE else View.GONE
        if (downloading) return
        dim(false)
        wakeSwitch.set(enabled)
        downloadBox.visibility = View.GONE
        when {
            !enabled -> showStatus(Status.OFF, getString(R.string.wake_state_off))
            ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED ->
                showStatus(Status.ERROR, getString(R.string.wake_status_no_mic))
            !WakeWordModel.isReady(this) -> showStatus(Status.ERROR, getString(R.string.wake_state_no_model))
            !canOverlay -> showStatus(Status.WARN, getString(R.string.wake_state_no_overlay))
            else -> showStatus(Status.ON, getString(R.string.wake_state_on))
        }
    }

    private fun requestWakeWord() {
        val needed = buildList {
            if (ContextCompat.checkSelfPermission(this@WakeWordSettingsActivity, Manifest.permission.RECORD_AUDIO) !=
                PackageManager.PERMISSION_GRANTED
            ) add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(this@WakeWordSettingsActivity, Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
            ) add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (needed.isEmpty()) turnOn() else permissionLauncher.launch(needed.toTypedArray())
    }

    private fun turnOn() {
        WakeWordModel.setEnabled(this, true)
        VoiceWidgetProvider.refreshAll(this)
        // Ask once, right away: listening in the background all day needs it.
        WakeWordService.requestBatteryUnrestricted(this)
        if (WakeWordModel.isReady(this)) {
            WakeWordService.start(this)
            refresh()
            return
        }
        if (downloading) return
        downloading = true
        wakeSwitch.set(true)
        showStatus(Status.BUSY, getString(R.string.wake_state_downloading))
        downloadBox.visibility = View.VISIBLE
        overlayBanner.visibility = View.GONE
        batteryBanner.visibility = View.GONE
        dim(true)
        downloadAmount.text = ""
        downloadBar.set(0)
        Thread {
            try {
                var shown = -1
                WakeWordModel.download(this) { percent, read, total ->
                    if (percent != shown) {
                        shown = percent
                        runOnUiThread {
                            downloadAmount.text = getString(R.string.wake_model_progress, read / 1_000_000, total / 1_000_000)
                            downloadBar.view.contentDescription = "$percent %"
                            downloadBar.set(percent)
                        }
                    }
                }
                runOnUiThread {
                    downloading = false
                    if (WakeWordModel.isEnabled(this)) WakeWordService.start(this)
                    VoiceWidgetProvider.refreshAll(this)
                    refresh()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    downloading = false
                    WakeWordModel.setEnabled(this, false)
                    VoiceWidgetProvider.refreshAll(this)
                    refresh()
                    showStatus(Status.ERROR, getString(R.string.wake_status_download_failed))
                }
            }
        }.start()
    }

    private fun turnOff() {
        WakeWordModel.setEnabled(this, false)
        stopService(Intent(this, WakeWordService::class.java))
        VoiceWidgetProvider.refreshAll(this)
        refresh()
    }

    // ── «Frase de activación» bottom sheet (A3) ─────────────────────────────────────────────────

    /**
     * The presets and the user's own phrase as a radio group: the chosen row in slate blue, «Recomendada» in
     * grey so it does not compete, the own phrase checked while typing (2 to 4 words, not only common ones),
     * and «Guardar y entrenar», which saves it and opens the training.
     */
    private fun choosePhrase() {
        val current = WakePhrase.phrase(this)
        val presets = WakePhrase.PRESETS
        var selected = presets.indexOf(current).let { if (it >= 0) it else presets.size }
        val dialog = Dialog(this)
        val rows = mutableListOf<LinearLayout>()
        val radios = mutableListOf<View>()
        val check = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        val checkIcon = parts.icon(R.drawable.ic_ui_check, parts.green, 14)
        val checkText = ui.text("", 12.5f, parts.greenText, semibold = true)
        check.addView(checkIcon)
        check.addView(checkText, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = ui.dp(6) })
        val custom = EditText(this).apply {
            hint = getString(R.string.wake_phrase_custom)
            setText(if (selected == presets.size) current else "")
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setTextColor(ui.ink)
            setHintTextColor(if (ui.night) 0xFF9D9DA6.toInt() else 0xFF55555C.toInt())
            background = null
            setPadding(0, 0, 0, 0)
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            imeOptions = EditorInfo.IME_ACTION_DONE
        }
        fun validate(): Boolean {
            val verdict = PhraseCheck.of(custom.text.toString())
            val on = selected == presets.size && custom.text.isNotBlank()
            check.visibility = if (on) View.VISIBLE else View.GONE
            val color = if (verdict.ok) parts.greenText else parts.amberText
            checkIcon.setImageResource(if (verdict.ok) R.drawable.ic_ui_check else R.drawable.ic_ui_warning)
            checkIcon.imageTintList = android.content.res.ColorStateList.valueOf(if (verdict.ok) parts.green else parts.amber)
            checkText.setTextColor(color)
            checkText.text = when (verdict) {
                PhraseCheck.Short -> getString(R.string.wake_phrase_short)
                PhraseCheck.Long -> getString(R.string.wake_phrase_long)
                PhraseCheck.Common -> getString(R.string.wake_phrase_common)
                is PhraseCheck.Good -> getString(R.string.wake_phrase_good, verdict.words)
            }
            return verdict.ok
        }
        fun paint() = rows.forEachIndexed { i, row ->
            val on = i == selected
            row.background = GradientDrawable().apply {
                cornerRadius = ui.dp(14).toFloat()
                setColor(if (on) parts.blueSoft else ui.card)
                setStroke(ui.dp(if (on) 1.5f else 1f), if (on) parts.blue else (if (ui.night) 0x1FFFFFFF else 0x1418181B))
            }
            radios[i].background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setStroke(ui.dp(if (on) 6f else 1.5f), if (on) parts.blue else 0xFF71717A.toInt())
            }
            row.isSelected = on
        }
        fun option(index: Int, content: View, trailing: View? = null, below: View? = null) = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ui.dp(14), ui.dp(14), ui.dp(14), ui.dp(14))
            minimumHeight = ui.dp(52)
            isClickable = true
            isFocusable = true
            val radio = View(context)
            radios += radio
            addView(LinearLayout(context).apply {
                gravity = Gravity.CENTER_VERTICAL
                addView(radio, LinearLayout.LayoutParams(ui.dp(20), ui.dp(20)))
                addView(content, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = ui.dp(12) })
                if (trailing != null) addView(trailing)
            })
            if (below != null) addView(below, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginStart = ui.dp(32)
                topMargin = ui.dp(8)
            })
            accessibilityDelegate = object : View.AccessibilityDelegate() {
                override fun onInitializeAccessibilityNodeInfo(host: View, info: android.view.accessibility.AccessibilityNodeInfo) {
                    super.onInitializeAccessibilityNodeInfo(host, info)
                    info.className = "android.widget.RadioButton"
                    info.isCheckable = true
                    info.isChecked = host.isSelected
                }
            }
            setOnClickListener {
                selected = index
                paint()
                validate()
                val ime = getSystemService(InputMethodManager::class.java)
                if (index == presets.size) {
                    custom.requestFocus()
                    ime?.showSoftInput(custom, 0)
                } else {
                    custom.clearFocus()
                    ime?.hideSoftInputFromWindow(custom.windowToken, 0)
                }
            }
            rows += this
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = ui.dp(8) }
        }
        custom.setOnFocusChangeListener { _, focused -> if (focused) { selected = presets.size; paint(); validate() } }
        custom.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) { validate() }
        })

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(ui.text(getString(R.string.wake_phrase_label), 20f, ui.ink, bold = true))
            addView(ui.text(getString(R.string.wake_phrase_sheet_text), 14f, ui.muted).apply { setPadding(0, ui.dp(2), 0, ui.dp(12)) })
            presets.forEachIndexed { i, preset ->
                val recommended = if (i == 0) ui.text(getString(R.string.wake_phrase_recommended), 12f, ui.muted, semibold = true).apply {
                    setPadding(ui.dp(9), ui.dp(3), ui.dp(9), ui.dp(3))
                    background = GradientDrawable().apply { cornerRadius = ui.dp(11).toFloat(); setColor(ui.tile) }
                } else null
                addView(option(i, ui.text(preset, 16f, ui.ink), recommended))
            }
            addView(option(presets.size, custom, below = check))
            addView(ui.text(getString(R.string.wake_phrase_rule), 13f, ui.muted).apply { setPadding(ui.dp(4), ui.dp(2), ui.dp(4), ui.dp(12)) })
            addView(LinearLayout(context).apply {
                addView(TextView(context).apply {
                    text = getString(R.string.picker_cancel)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 15.5f)
                    setTextColor(ui.ink)
                    typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
                    gravity = Gravity.CENTER
                    background = GradientDrawable().apply {
                        cornerRadius = ui.dp(25).toFloat(); setColor(ui.card)
                        setStroke(ui.dp(1), if (ui.night) 0x1FFFFFFF else 0x1F18181B)
                    }
                    setOnClickListener { dialog.dismiss() }
                }, LinearLayout.LayoutParams(0, ui.dp(50), 1f))
                addView(TextView(context).apply {
                    text = getString(R.string.wake_phrase_save_train)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 15.5f)
                    setTextColor(if (ui.night) 0xFF18181B.toInt() else Color.WHITE)
                    typeface = android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.BOLD)
                    gravity = Gravity.CENTER
                    background = GradientDrawable().apply { cornerRadius = ui.dp(25).toFloat(); setColor(ui.ink) }
                    setOnClickListener {
                        val phrase = if (selected < presets.size) presets[selected] else custom.text.toString().trim()
                        if (selected == presets.size && !validate()) {
                            custom.requestFocus()
                            return@setOnClickListener
                        }
                        if (phrase != current) applyPhrase(phrase)
                        dialog.dismiss()
                        startActivity(Intent(this@WakeWordSettingsActivity, VoiceTrainingActivity::class.java))
                    }
                }, LinearLayout.LayoutParams(0, ui.dp(50), 1.5f).apply { marginStart = ui.dp(10) })
            })
        }
        paint()
        validate()
        bottomSheet(dialog, content)
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
    }

    private fun applyPhrase(phrase: String) {
        WakePhrase.setPhrase(this, phrase)
        // Refresh the listening notification and the widgets with the new phrase.
        if (WakeWordService.running) WakeWordService.start(this)
        VoiceWidgetProvider.refreshAll(this)
        refresh()
    }
}

/** Checks the user's own phrase while typing: 2 to 4 words, and not only words said all the time. */
sealed class PhraseCheck(val ok: Boolean) {
    object Short : PhraseCheck(false)
    object Long : PhraseCheck(false)
    object Common : PhraseCheck(false)
    class Good(val words: Int) : PhraseCheck(true)

    companion object {
        private val COMMON = setOf(
            "oye", "hola", "hey", "ey", "ok", "okey", "vale", "bien", "si", "no", "ya", "eh", "a", "la", "el", "lo",
            "que", "y", "o", "de", "en", "un", "una", "me", "te", "se", "mi", "tu", "por", "favor", "escucha", "ven",
        )

        fun of(text: String): PhraseCheck {
            val words = WakePhrase.words(text)
            return when {
                words.size < 2 -> Short
                words.size > 4 -> Long
                words.none { it.length >= 4 && it !in COMMON } -> Common
                else -> Good(words.size)
            }
        }
    }
}
