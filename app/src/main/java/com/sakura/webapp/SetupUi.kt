package com.sakura.webapp

import android.app.Activity
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import kotlin.math.roundToInt

/**
 * Building blocks for the setup screens, following the "App Android · Conexión inicial"
 * design: light grey ground, white rounded cards with an icon tile, monospace address fields,
 * status pills and full-width pill buttons. Colours switch to a dark set in dark mode.
 */
class SetupUi(private val activity: Activity) {
    val night = (activity.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
        Configuration.UI_MODE_NIGHT_YES

    private fun pick(light: Int, dark: Int) = if (night) dark else light

    val ground = pick(0xFFF4F4F5.toInt(), 0xFF111113.toInt())
    val card = pick(Color.WHITE, 0xFF1F1F23.toInt())
    val ring = pick(0x0D18181B, 0x14FFFFFF)
    val ink = pick(0xFF18181B.toInt(), 0xFFF4F4F5.toInt())
    val muted = pick(0xFF52525B.toInt(), 0xFFA1A1AA.toInt())
    val faint = pick(0xFF6B6B73.toInt(), 0xFF8B8B93.toInt())
    val tile = pick(0xFFF4F4F5.toInt(), 0xFF2A2A2F.toInt())
    val fieldBg = pick(0xFFFAFAFA.toInt(), 0xFF18181B.toInt())
    val fieldBorder = pick(0xFFD4D4D8.toInt(), 0xFF3F3F46.toInt())
    val hintColor = pick(0xFFA1A1AA.toInt(), 0xFF71717A.toInt())
    val focus = pick(0xFF2361C7.toInt(), 0xFF6F9CEB.toInt())
    val error = pick(0xFFC0392B.toInt(), 0xFFE5776B.toInt())

    enum class Pill { OK, ERROR, TESTING }

    fun dp(value: Float): Int = (value * activity.resources.displayMetrics.density).roundToInt()
    fun dp(value: Int): Int = dp(value.toFloat())

    /** Screen column: padding 22/18/20 as in the design. */
    fun column() = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(18), dp(22), dp(18), dp(20))
    }

    /** Logo, title and subtitle, centred. */
    fun header(title: String, subtitle: String) = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        setPadding(0, 0, 0, dp(16))
        addView(ImageView(activity).apply {
            setImageResource(R.drawable.sakura_logo)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(dp(52), dp(52)).apply { bottomMargin = dp(8) })
        addView(text(title, 24f, ink, bold = true).apply { gravity = Gravity.CENTER })
        addView(text(subtitle, 15f, muted).apply {
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(4), dp(8), 0)
        })
    }

    fun text(value: String, sizeSp: Float, color: Int, bold: Boolean = false, semibold: Boolean = false) =
        TextView(activity).apply {
            text = value
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
            setTextColor(color)
            typeface = when {
                bold -> Typeface.create("sans-serif", Typeface.BOLD)
                semibold -> Typeface.create("sans-serif-medium", Typeface.NORMAL)
                else -> Typeface.create("sans-serif", Typeface.NORMAL)
            }
            setLineSpacing(dp(2).toFloat(), 1f)
        }

    /** White card with radius 22, a 1dp ring and a soft shadow. */
    fun card() = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(16), dp(16), dp(16))
        background = GradientDrawable().apply {
            cornerRadius = dp(22).toFloat()
            setColor(card)
            setStroke(dp(1), ring)
        }
        elevation = dp(1.5f).toFloat()
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(12) }
    }

    /** Icon tile, title (with an optional "Opcional" chip) and description. */
    fun cardHeader(icon: Int, title: String, description: String, chip: String? = null) =
        LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(FrameLayout(activity).apply {
                background = GradientDrawable().apply { cornerRadius = dp(12).toFloat(); setColor(tile) }
                addView(ImageView(activity).apply {
                    setImageResource(icon)
                    imageTintList = ColorStateList.valueOf(ink)
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                }, FrameLayout.LayoutParams(dp(20), dp(20), Gravity.CENTER))
            }, LinearLayout.LayoutParams(dp(40), dp(40)).apply { marginEnd = dp(12) })
            addView(LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                addView(LinearLayout(activity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    addView(text(title, 16.5f, ink, bold = true))
                    if (chip != null) addView(text(chip, 12f, muted, semibold = true).apply {
                        setPadding(dp(8), dp(2), dp(8), dp(2))
                        background = GradientDrawable().apply { cornerRadius = dp(11).toFloat(); setColor(tile) }
                    }, LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { marginStart = dp(8) })
                })
                addView(text(description, 13.5f, muted))
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }

    /** Monospace address field: 1dp grey border, 2dp blue when focused, 2dp red on error. */
    inner class AddressField(hintText: String, value: String?) {
        var hasError = false
            set(v) { field = v; paint() }
        val view = EditText(activity).apply {
            hint = hintText
            setText(value ?: "")
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI or
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            imeOptions = EditorInfo.IME_ACTION_NEXT
            typeface = Typeface.MONOSPACE
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setTextColor(ink)
            setHintTextColor(hintColor)
            setPadding(dp(14), 0, dp(14), 0)
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(48))
                .apply { topMargin = dp(10) }
            setOnFocusChangeListener { _, _ -> paint() }
        }

        init { paint() }

        private fun paint() {
            val (width, color) = when {
                hasError -> dp(2) to error
                view.isFocused -> dp(2) to focus
                else -> dp(1) to fieldBorder
            }
            view.background = GradientDrawable().apply {
                cornerRadius = dp(14).toFloat()
                setColor(fieldBg)
                setStroke(width, color)
            }
        }
    }

    /** Status pill ("Conectado", "No responde", "Probando…"); hidden until [show] is called. */
    inner class StatusPill {
        private val icon = ImageView(activity)
        private val spinner = ProgressBar(activity).apply { isIndeterminate = true }
        private val label = text("", 13f, ink, semibold = true)
        val view = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), 0, dp(10), 0)
            visibility = View.GONE
            addView(icon, LinearLayout.LayoutParams(dp(14), dp(14)).apply { marginEnd = dp(6) })
            addView(spinner, LinearLayout.LayoutParams(dp(13), dp(13)).apply { marginEnd = dp(6) })
            addView(label)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(28))
                .apply { topMargin = dp(10) }
        }

        fun show(state: Pill, message: String) {
            val (bg, fg, iconColor) = when (state) {
                Pill.OK -> Triple(pick(0xFFE5F3E7.toInt(), 0xFF173A26.toInt()), pick(0xFF1F6B45.toInt(), 0xFF8BD6A8.toInt()), pick(0xFF2B8A5A.toInt(), 0xFF8BD6A8.toInt()))
                Pill.ERROR -> Triple(pick(0xFFFBE6E3.toInt(), 0xFF3F1C19.toInt()), pick(0xFFA32A22.toInt(), 0xFFF2A49B.toInt()), error)
                Pill.TESTING -> Triple(pick(0xFFE6EEFB.toInt(), 0xFF1A2C4D.toInt()), pick(0xFF1D4FA3.toInt(), 0xFF9DBDF2.toInt()), focus)
            }
            view.background = GradientDrawable().apply { cornerRadius = dp(14).toFloat(); setColor(bg) }
            label.text = message
            label.setTextColor(fg)
            icon.visibility = if (state == Pill.TESTING) View.GONE else View.VISIBLE
            spinner.visibility = if (state == Pill.TESTING) View.VISIBLE else View.GONE
            icon.setImageResource(if (state == Pill.OK) R.drawable.ic_ui_check else R.drawable.ic_ui_alert)
            icon.imageTintList = ColorStateList.valueOf(iconColor)
            spinner.indeterminateTintList = ColorStateList.valueOf(iconColor)
            view.visibility = View.VISIBLE
        }

        fun hide() { view.visibility = View.GONE }
    }

    /** Black full-width pill (white in dark mode); 35 % opacity while disabled. */
    fun primaryButton(label: String, onClick: () -> Unit) = Button(activity).apply {
        text = label
        isAllCaps = false
        stateListAnimator = null
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        typeface = Typeface.create("sans-serif", Typeface.BOLD)
        setTextColor(pick(Color.WHITE, 0xFF18181B.toInt()))
        background = GradientDrawable().apply { cornerRadius = dp(26).toFloat(); setColor(ink) }
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(52))
            .apply { bottomMargin = dp(10) }
    }

    /** White pill with a hairline ring and a leading icon, both centred. */
    fun secondaryButton(label: String, icon: Int?, onClick: () -> Unit) = LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
        isClickable = true
        isFocusable = true
        contentDescription = label
        background = GradientDrawable().apply {
            cornerRadius = dp(24).toFloat()
            setColor(card)
            setStroke(dp(1), pick(0x1418181B, 0x1FFFFFFF))
        }
        if (icon != null) addView(ImageView(activity).apply {
            setImageResource(icon)
            imageTintList = ColorStateList.valueOf(ink)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(dp(17), dp(17)).apply { marginEnd = dp(8) })
        addView(text(label, 15f, ink, semibold = true).apply {
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        })
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(48))
            .apply { bottomMargin = dp(10) }
    }

    /** Small pill button inside a card ("Permitir", "Abrir ajustes"). */
    fun smallButton(label: String, onClick: () -> Unit) = Button(activity).apply {
        text = label
        isAllCaps = false
        stateListAnimator = null
        minHeight = 0
        minimumHeight = 0
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        setTextColor(pick(Color.WHITE, 0xFF18181B.toInt()))
        setPadding(dp(16), 0, dp(16), 0)
        background = GradientDrawable().apply { cornerRadius = dp(18).toFloat(); setColor(ink) }
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(36))
            .apply { topMargin = dp(12) }
    }

    fun footer(value: String) = text(value, 12.5f, faint).apply {
        gravity = Gravity.CENTER
        setPadding(dp(8), dp(2), dp(8), 0)
    }

    fun setEnabledLook(button: View, enabled: Boolean) {
        button.isEnabled = enabled
        button.alpha = if (enabled) 1f else 0.35f
    }

    /** Light or dark system bar icons to match [ground]. */
    fun styleWindow() {
        activity.window.decorView.setBackgroundColor(ground)
        androidx.core.view.WindowInsetsControllerCompat(activity.window, activity.window.decorView).apply {
            isAppearanceLightStatusBars = !night
            isAppearanceLightNavigationBars = !night
        }
    }
}
