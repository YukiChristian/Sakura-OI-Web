package com.sakura.webapp

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity

/**
 * «¿Con quién quieres hablar?», opened from the model capsule of the one-row widget: Sakura, Lain and
 * Yoko, plus «Ver todos los modelos» (every visible model on the server). The widget remembers the choice.
 */
class WidgetModelPickerActivity : ComponentActivity() {
    private lateinit var ui: SetupUi
    private lateinit var list: LinearLayout
    private var widgetId = AppWidgetManager.INVALID_APPWIDGET_ID

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        widgetId = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        if (widgetId == AppWidgetManager.INVALID_APPWIDGET_ID) { finish(); return }
        ui = SetupUi(this)
        window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        val current = ChatModels.forWidget(this, widgetId)

        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        ChatModels.PERSONAS.forEach { list.addView(row(it, it.id == current.id)) }
        if (ChatModels.PERSONAS.none { it.id == current.id }) list.addView(row(current, true))

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ui.dp(14), ui.dp(18), ui.dp(14), ui.dp(14))
            background = GradientDrawable().apply { cornerRadius = ui.dp(28).toFloat(); setColor(ui.card) }
            elevation = ui.dp(12).toFloat()
            isClickable = true
            addView(ui.text(getString(R.string.picker_title), 19f, ui.ink, bold = true).apply {
                setPadding(ui.dp(6), 0, ui.dp(6), 0)
            })
            addView(ui.text(getString(R.string.picker_subtitle), 13.5f, ui.muted).apply {
                setPadding(ui.dp(6), ui.dp(2), ui.dp(6), ui.dp(12))
            })
            addView(ScrollView(this@WidgetModelPickerActivity).apply {
                isVerticalScrollBarEnabled = false
                addView(list)
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            addView(allModelsRow())
            addView(LinearLayout(this@WidgetModelPickerActivity).apply {
                gravity = Gravity.END
                setPadding(0, ui.dp(12), 0, 0)
                addView(ui.text(getString(R.string.picker_cancel), 15f, ui.focus, semibold = true).apply {
                    gravity = Gravity.CENTER
                    setPadding(ui.dp(16), 0, ui.dp(16), 0)
                    isClickable = true
                    isFocusable = true
                    setOnClickListener { finish() }
                }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ui.dp(40)))
            })
        }
        val root = FrameLayout(this).apply {
            setBackgroundColor(0x66000000)
            setOnClickListener { finish() }
            addView(card, FrameLayout.LayoutParams(ui.dp(340), ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER).apply {
                topMargin = ui.dp(48)
                bottomMargin = ui.dp(48)
            })
        }
        setContentView(root)
    }

    private fun row(model: ChatModel, selected: Boolean) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(ui.dp(12), 0, ui.dp(12), 0)
        isClickable = true
        isFocusable = true
        contentDescription = model.name
        background = GradientDrawable().apply {
            cornerRadius = ui.dp(14).toFloat()
            setColor(if (selected) (if (ui.night) 0xFF3A1F2A.toInt() else 0xFFFDF1F5.toInt()) else Color.TRANSPARENT)
        }
        // 48 dp box: the selected avatar adds its ring around the 40 dp picture; the others are padded to match.
        addView(ImageView(context).apply {
            setImageBitmap(ChatModels.avatar(context, model, ui.dp(if (selected) 48 else 40), ring = selected))
            if (!selected) setPadding(ui.dp(4), ui.dp(4), ui.dp(4), ui.dp(4))
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(ui.dp(48), ui.dp(48)).apply { marginStart = -ui.dp(4) })
        addView(ui.text(model.name, 16f, ui.ink, bold = selected, semibold = !selected).apply {
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = ui.dp(8) })
        if (selected) addView(icon(R.drawable.ic_ui_check_mark, 0xFFD6336C.toInt(), 18))
        setOnClickListener { choose(model) }
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ui.dp(56))
    }

    private fun allModelsRow(): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(ui.dp(12), 0, ui.dp(12), 0)
        isClickable = true
        isFocusable = true
        background = GradientDrawable().apply {
            cornerRadius = ui.dp(14).toFloat()
            setStroke(ui.dp(1), if (ui.night) 0xFF2E2E33.toInt() else 0xFFECECEE.toInt())
        }
        addView(icon(R.drawable.ic_ui_spark, ui.muted, 17))
        addView(ui.text(getString(R.string.picker_all), 14f, ui.ink, semibold = true),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = ui.dp(10) })
        addView(icon(R.drawable.ic_ui_chevron_right, ui.hintColor, 16))
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ui.dp(46)).apply { topMargin = ui.dp(4) }
        setOnClickListener { row -> loadAll(row) }
    }

    /** Replaces «Ver todos los modelos» with the server's list (Sakura, Lain and Yoko stay on top). */
    private fun loadAll(button: View) {
        button.isEnabled = false
        val status = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(ui.dp(12), ui.dp(10), ui.dp(12), ui.dp(10))
            addView(ProgressBar(context).apply {
                isIndeterminate = true
                indeterminateTintList = ColorStateList.valueOf(ui.focus)
            }, LinearLayout.LayoutParams(ui.dp(16), ui.dp(16)).apply { marginEnd = ui.dp(10) })
            addView(ui.text(getString(R.string.picker_loading), 14f, ui.muted))
        }
        list.addView(status)
        Thread {
            val result = runCatching { ChatModels.fetchAll(this) }
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                list.removeView(status)
                val models = result.getOrNull()
                if (models == null) {
                    button.isEnabled = true
                    list.addView(ui.text(getString(R.string.picker_failed), 13.5f, ui.error).apply {
                        setPadding(ui.dp(12), ui.dp(8), ui.dp(12), ui.dp(8))
                    })
                    return@runOnUiThread
                }
                button.visibility = View.GONE
                val current = ChatModels.forWidget(this, widgetId)
                val shown = (0 until list.childCount).mapNotNull { list.getChildAt(it).contentDescription?.toString() }.toSet()
                models.filter { it.name !in shown }.forEach { list.addView(row(it, it.id == current.id)) }
            }
        }.start()
    }

    private fun icon(res: Int, color: Int, sizeDp: Int) = ImageView(this).apply {
        setImageResource(res)
        imageTintList = ColorStateList.valueOf(color)
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        layoutParams = LinearLayout.LayoutParams(ui.dp(sizeDp), ui.dp(sizeDp))
    }

    private fun choose(model: ChatModel) {
        ChatModels.setForWidget(this, widgetId, model)
        VoiceWidgetProvider.update(this, AppWidgetManager.getInstance(this), widgetId)
        finish()
    }

    companion object {
        fun intent(context: Context, widgetId: Int): Intent =
            Intent(context, WidgetModelPickerActivity::class.java)
                .setData(android.net.Uri.parse("sakura-widget://pick/$widgetId"))
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
    }
}
