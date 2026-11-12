package com.sakura.webapp

import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

/** «Hay una actualización»: what is new and the buttons Más tarde / Actualizar, in the app's card style. */
object UpdatePrompt {
    fun show(activity: Activity, release: Updates.Release) {
        if (activity.isFinishing) return
        val ui = SetupUi(activity)
        val parts = SetupParts(ui)
        val dialog = Dialog(activity)
        fun pill(label: String, primary: Boolean, onClick: () -> Unit) = TextView(activity).apply {
            text = label
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15.5f)
            gravity = Gravity.CENTER
            typeface = android.graphics.Typeface.create("sans-serif", if (primary) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
            setTextColor(if (primary) (if (ui.night) 0xFF18181B.toInt() else Color.WHITE) else ui.ink)
            background = GradientDrawable().apply {
                cornerRadius = ui.dp(24).toFloat()
                setColor(if (primary) ui.ink else ui.tile)
                if (!primary) setStroke(ui.dp(1), if (ui.night) 0xFF3F3F46.toInt() else 0xFFE4E4E7.toInt())
            }
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
        }
        val card = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ui.dp(20), ui.dp(20), ui.dp(20), ui.dp(16))
            background = GradientDrawable().apply { cornerRadius = ui.dp(28).toFloat(); setColor(ui.card) }
            addView(parts.tile(R.drawable.ic_ui_refresh, parts.blue, parts.blueSoft))
            addView(ui.text(activity.getString(R.string.update_title), 20f, ui.ink, bold = true).apply {
                setPadding(0, ui.dp(12), 0, ui.dp(4))
            })
            addView(ui.text(activity.getString(R.string.update_text, release.version, Updates.current), 14.5f, ui.muted))
            addView(LinearLayout(activity).apply {
                setPadding(0, ui.dp(18), 0, 0)
                addView(pill(activity.getString(R.string.update_later), false) { dialog.dismiss() },
                    LinearLayout.LayoutParams(0, ui.dp(48), 1f))
                addView(pill(activity.getString(R.string.update_now), true) {
                    dialog.dismiss()
                    Updates.install(activity, release) { message ->
                        Toast.makeText(activity.applicationContext, message, Toast.LENGTH_LONG).show()
                    }
                }, LinearLayout.LayoutParams(0, ui.dp(48), 1.3f).apply { marginStart = ui.dp(10) })
            })
        }
        dialog.setContentView(card)
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout(ui.dp(340), ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        dialog.show()
    }
}
