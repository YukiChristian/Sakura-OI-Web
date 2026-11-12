package com.sakura.webapp

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
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity

/**
 * «¿Con quién quieres hablar?», opened from a widget's model name (▾) or its «+N»: every model the
 * server offers, five per row, with the widget's model marked. The list shows at once (as last seen)
 * and is read again from the server; the widget remembers the choice.
 */
class WidgetModelPickerActivity : ComponentActivity() {
    private lateinit var ui: SetupUi
    private lateinit var grid: LinearLayout
    private lateinit var subtitle: TextView
    private var widgetId = AppWidgetManager.INVALID_APPWIDGET_ID

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        widgetId = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        if (widgetId == AppWidgetManager.INVALID_APPWIDGET_ID) { finish(); return }
        ui = SetupUi(this)
        window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        subtitle = ui.text("", 13.5f, ui.muted).apply { setPadding(ui.dp(6), ui.dp(2), ui.dp(6), ui.dp(12)) }
        grid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ui.dp(14), ui.dp(18), ui.dp(14), ui.dp(12))
            background = GradientDrawable().apply { cornerRadius = ui.dp(28).toFloat(); setColor(ui.card) }
            elevation = ui.dp(12).toFloat()
            isClickable = true
            addView(ui.text(getString(R.string.picker_title), 19f, ui.ink, bold = true).apply {
                setPadding(ui.dp(6), 0, ui.dp(6), 0)
            })
            addView(subtitle)
            addView(ScrollView(this@WidgetModelPickerActivity).apply {
                isVerticalScrollBarEnabled = false
                addView(grid)
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            addView(LinearLayout(this@WidgetModelPickerActivity).apply {
                gravity = Gravity.END
                setPadding(0, ui.dp(8), 0, 0)
                addView(ui.text(getString(R.string.picker_cancel), 15f, ui.focus, semibold = true).apply {
                    gravity = Gravity.CENTER
                    setPadding(ui.dp(16), 0, ui.dp(16), 0)
                    isClickable = true
                    isFocusable = true
                    setOnClickListener { finish() }
                }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ui.dp(40)))
            })
        }
        val width = minOf(ui.dp(360), resources.displayMetrics.widthPixels - ui.dp(32))
        setContentView(FrameLayout(this).apply {
            setBackgroundColor(0x66000000)
            setOnClickListener { finish() }
            addView(card, FrameLayout.LayoutParams(width, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER).apply {
                topMargin = ui.dp(48)
                bottomMargin = ui.dp(48)
            })
        })
        show(ChatModels.available(this))
        loadFromServer()
    }

    /** Five models per row; the widget's model first gets a tinted cell and the ring of its colour. */
    private fun show(available: List<ChatModel>) {
        val current = ChatModels.forWidget(this, widgetId)
        val models = if (available.any { it.id == current.id }) available else available + current
        subtitle.text = resources.getQuantityString(R.plurals.picker_count, models.size, models.size)
        grid.removeAllViews()
        models.chunked(COLUMNS).forEach { line ->
            grid.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                line.forEach { addView(cell(it, it.id == current.id), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)) }
                // Empty places keep the last row's cells the same width as the others.
                repeat(COLUMNS - line.size) { addView(View(context), LinearLayout.LayoutParams(0, 0, 1f)) }
            })
        }
    }

    private fun cell(model: ChatModel, selected: Boolean) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        setPadding(ui.dp(2), ui.dp(6), ui.dp(2), ui.dp(8))
        isClickable = true
        isFocusable = true
        contentDescription = model.name
        background = GradientDrawable().apply {
            cornerRadius = ui.dp(16).toFloat()
            setColor(if (selected) (if (ui.night) 0xFF3A1F2A.toInt() else 0xFFFDF1F5.toInt()) else Color.TRANSPARENT)
        }
        // A 44 dp avatar in a 52 dp box: room for the ring of the selected one.
        addView(ImageView(context).apply {
            setImageBitmap(ChatModels.avatar(context, model, ui.dp(52), ring = selected, padded = true))
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(ui.dp(52), ui.dp(52)))
        addView(ui.text(ChatModels.shortName(model), 12f, ui.ink, bold = selected, semibold = !selected).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            gravity = Gravity.CENTER
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = ui.dp(2)
        })
        setOnClickListener { choose(model) }
    }

    /** Reads the server's models; if the list changed, shows it and redraws the widgets. */
    private fun loadFromServer() {
        Thread {
            val models = runCatching { ChatModels.fetchAll(this) }.getOrNull()
            val changed = !models.isNullOrEmpty() && ChatModels.saveList(this, models)
            if (changed) VoiceWidgetProvider.refreshAll(this)
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                when {
                    changed -> show(ChatModels.available(this))
                    models.isNullOrEmpty() && !ChatModels.hasServerList(this) -> grid.addView(
                        ui.text(getString(R.string.picker_failed), 13.5f, ui.error).apply {
                            setPadding(ui.dp(6), ui.dp(10), ui.dp(6), 0)
                        }
                    )
                }
            }
        }.start()
    }

    private fun choose(model: ChatModel) {
        ChatModels.setForWidget(this, widgetId, model)
        VoiceWidgetProvider.update(this, AppWidgetManager.getInstance(this), widgetId)
        finish()
    }

    companion object {
        private const val COLUMNS = 5

        fun intent(context: Context, widgetId: Int): Intent =
            Intent(context, WidgetModelPickerActivity::class.java)
                .setData(android.net.Uri.parse("sakura-widget://pick/$widgetId"))
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
    }
}
