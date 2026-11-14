package com.sakura.webapp

import android.animation.ValueAnimator
import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat

/**
 * «¿Con quién quieres hablar?» (W7), opened from a widget's model name (▾) or its «+N»: a sheet that rises
 * from the bottom with «Tus compañeras» (Sakura, Lain, Yoko) above and the other pinned models apart, four
 * per row. The widget's model is marked in slate blue, as everything chosen in Hanami. The list shows at
 * once (as last seen) and is read again from the server; the widget remembers the choice.
 */
class WidgetModelPickerActivity : ComponentActivity() {
    private lateinit var ui: SetupUi
    private lateinit var list: LinearLayout
    private lateinit var subtitle: TextView
    private lateinit var sheet: LinearLayout
    private lateinit var scrim: FrameLayout
    private var widgetId = AppWidgetManager.INVALID_APPWIDGET_ID
    private var closing = false

    private val slate get() = if (ui.night) 0xFF8FA9DA.toInt() else 0xFF3E5F99.toInt()
    private val slateTint get() = if (ui.night) 0x298FA9DA else 0x1A3E5F99
    private val motion get() = ValueAnimator.areAnimatorsEnabled()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        widgetId = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        if (widgetId == AppWidgetManager.INVALID_APPWIDGET_ID) { finish(); return }
        ui = SetupUi(this)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        subtitle = ui.text("", 13f, ui.muted).apply { setPadding(0, ui.dp(2), 0, ui.dp(10)) }
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        sheet = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ui.dp(18), ui.dp(10), ui.dp(18), ui.dp(12))
            background = GradientDrawable().apply {
                cornerRadii = FloatArray(8) { if (it < 4) ui.dp(28).toFloat() else 0f }
                setColor(if (ui.night) 0xFF1E1E23.toInt() else 0xFFF7F7F8.toInt())
            }
            elevation = ui.dp(16).toFloat()
            isClickable = true
            addView(View(context).apply {
                background = GradientDrawable().apply {
                    cornerRadius = ui.dp(3).toFloat()
                    setColor(if (ui.night) 0xFF3F3F46.toInt() else 0xFFD4D4D8.toInt())
                }
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LinearLayout.LayoutParams(ui.dp(36), ui.dp(4)).apply { gravity = Gravity.CENTER_HORIZONTAL; bottomMargin = ui.dp(14) })
            addView(ui.text(getString(R.string.picker_title), 19f, ui.ink, bold = true).apply {
                ViewCompat.setAccessibilityHeading(this, true)
            })
            addView(subtitle)
            addView(ScrollView(this@WidgetModelPickerActivity).apply {
                isVerticalScrollBarEnabled = false
                addView(list)
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            addView(LinearLayout(this@WidgetModelPickerActivity).apply {
                gravity = Gravity.END
                setPadding(0, ui.dp(6), 0, 0)
                addView(ui.text(getString(R.string.picker_cancel), 15f, slate, semibold = true).apply {
                    gravity = Gravity.CENTER
                    setPadding(ui.dp(16), 0, ui.dp(16), 0)
                    isClickable = true
                    isFocusable = true
                    setOnClickListener { close() }
                }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ui.dp(44)))
            })
        }
        scrim = FrameLayout(this).apply {
            setBackgroundColor(if (ui.night) 0x8C000000.toInt() else 0x5218181B)
            setOnClickListener { close() }
            addView(sheet, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM).apply {
                topMargin = ui.dp(80)
            })
        }
        ViewCompat.setOnApplyWindowInsetsListener(scrim) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            sheet.setPadding(ui.dp(18), ui.dp(10), ui.dp(18), ui.dp(12) + bars.bottom)
            insets
        }
        setContentView(scrim)
        show(ChatModels.available(this))
        enter()
        loadFromServer()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() = close()

    /** Rises from the bottom in 240 ms; with reduced motion it only fades in (150 ms). */
    private fun enter() {
        val ease = PathInterpolator(0.2f, 0.8f, 0.2f, 1f)
        scrim.alpha = 0f
        scrim.animate().alpha(1f).setDuration(if (motion) 200 else 150).start()
        if (!motion) return
        sheet.post {
            sheet.translationY = sheet.height.toFloat()
            sheet.animate().translationY(0f).setDuration(240).setInterpolator(ease).start()
        }
    }

    private fun close(after: () -> Unit = {}) {
        if (closing) return
        closing = true
        val done = Runnable { after(); finish(); overridePendingTransition(0, 0) }
        if (!motion) { scrim.animate().alpha(0f).setDuration(120).withEndAction(done).start(); return }
        scrim.animate().alpha(0f).setDuration(180).start()
        sheet.animate().translationY(sheet.height.toFloat()).setDuration(180)
            .setInterpolator(PathInterpolator(0.4f, 0f, 1f, 1f)).withEndAction(done).start()
    }

    /** «Tus compañeras» (the characters that are pinned) and «Otros modelos», four per row. */
    private fun show(available: List<ChatModel>) {
        val current = ChatModels.forWidget(this, widgetId)
        val models = if (available.any { it.id == current.id }) available else listOf(current) + available
        subtitle.text = resources.getQuantityString(R.plurals.picker_count, models.size, models.size)
        list.removeAllViews()
        val (companions, others) = models.partition { ChatModels.isPersona(it) }
        if (companions.isNotEmpty()) section(R.string.picker_companions, companions, current, columns = 3)
        if (others.isNotEmpty()) section(R.string.picker_others, others, current, columns = COLUMNS)
    }

    private fun section(title: Int, models: List<ChatModel>, current: ChatModel, columns: Int) {
        list.addView(ui.text(getString(title).uppercase(), 11.5f, ui.faint, bold = true).apply {
            letterSpacing = 0.06f
            setPadding(0, ui.dp(8), 0, ui.dp(6))
            ViewCompat.setAccessibilityHeading(this, true)
        })
        models.chunked(columns).forEach { line ->
            list.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                line.forEach { addView(cell(it, it.id == current.id), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginEnd = ui.dp(4)
                }) }
                // Empty places keep the last row's cells the same width as the others.
                repeat(columns - line.size) { addView(View(context), LinearLayout.LayoutParams(0, 0, 1f).apply { marginEnd = ui.dp(4) }) }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = ui.dp(4)
            })
        }
    }

    /** A 46 dp avatar in a roomy cell; the chosen one gets a slate tint and ring that fade in (160 ms). */
    private fun cell(model: ChatModel, selected: Boolean) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        setPadding(ui.dp(2), ui.dp(8), ui.dp(2), ui.dp(8))
        minimumHeight = ui.dp(48)
        isClickable = true
        isFocusable = true
        contentDescription = model.name
        if (selected) {
            background = GradientDrawable().apply {
                cornerRadius = ui.dp(14).toFloat()
                setColor(slateTint)
                setStroke(ui.dp(1.5f), slate)
            }
            if (motion) background.alpha = 0
            if (motion) ValueAnimator.ofInt(0, 255).apply {
                duration = 160
                startDelay = 120
                addUpdateListener { background?.alpha = it.animatedValue as Int }
                start()
            }
        }
        // A radio button for TalkBack: the group is the list of models, the checked one the widget's.
        accessibilityDelegate = object : View.AccessibilityDelegate() {
            override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfo) {
                super.onInitializeAccessibilityNodeInfo(host, info)
                info.className = "android.widget.RadioButton"
                info.isCheckable = true
                info.isChecked = selected
            }
        }
        addView(ImageView(context).apply {
            setImageBitmap(ChatModels.avatar(context, model, ui.dp(46)))
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(ui.dp(46), ui.dp(46)))
        addView(ui.text(ChatModels.shortName(model), 12f, ui.ink, bold = selected, semibold = !selected).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            gravity = Gravity.CENTER
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = ui.dp(4)
        })
        setOnClickListener { choose(model) }
    }

    /** Reads the server's models; if the list changed, shows it and redraws the widgets. */
    private fun loadFromServer() {
        Thread {
            val models = runCatching { ChatModels.fetchAll(this) }.getOrNull()
            val changed = models != null && ChatModels.saveList(this, models)
            if (changed) VoiceWidgetProvider.refreshAll(this)
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                when {
                    changed -> show(ChatModels.available(this))
                    models == null && !ChatModels.hasServerList(this) -> list.addView(
                        ui.text(getString(R.string.picker_failed), 13.5f, ui.error).apply {
                            setPadding(0, ui.dp(10), 0, 0)
                        }
                    )
                }
            }
        }.start()
    }

    private fun choose(model: ChatModel) {
        ChatModels.setForWidget(this, widgetId, model)
        VoiceWidgetProvider.update(this, AppWidgetManager.getInstance(this), widgetId)
        close()
    }

    companion object {
        private const val COLUMNS = 4

        fun intent(context: Context, widgetId: Int): Intent =
            Intent(context, WidgetModelPickerActivity::class.java)
                .setData(android.net.Uri.parse("sakura-widget://pick/$widgetId"))
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
    }
}
