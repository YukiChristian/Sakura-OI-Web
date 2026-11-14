package com.sakura.webapp

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
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
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService

/**
 * «Entrenar tu voz», from "App de Android · Parte 1" (A5–A7, Hanami): the user says the phrase [SAMPLES]
 * times (the instruction inside the card, steps, the slate mic with its halo and level bars) and the
 * transcripts the offline model writes are kept as accepted variants (see WakePhrase). A take that does
 * not sound like the phrase does not count and turns the mic amber, with what to do. Then «Prueba en
 * vivo» lists what it hears and whether Sakura would wake up, without opening anything. The background
 * listener pauses while this screen listens.
 */
class VoiceTrainingActivity : ComponentActivity(), RecognitionListener {
    private lateinit var ui: SetupUi
    private lateinit var parts: SetupParts
    private val main = Handler(Looper.getMainLooper())

    // Recording
    private lateinit var trainCard: LinearLayout
    private lateinit var trainSteps: SetupParts.Steps
    private lateinit var stepText: TextView
    private lateinit var hintText: TextView
    private lateinit var trainBars: LevelBars
    private lateinit var micHalo: PulseHalo
    private lateinit var micDisc: View
    private lateinit var missedBox: LinearLayout
    private lateinit var missedText: TextView
    private var missed = false

    // Trained + live test
    private lateinit var trainedCard: LinearLayout
    private lateinit var trainedText: TextView
    private lateinit var trainedSteps: SetupParts.Steps
    private lateinit var testCard: LinearLayout
    private lateinit var listeningText: TextView
    private lateinit var testBars: LevelBars
    private lateinit var results: LinearLayout

    private lateinit var saveButton: android.widget.Button
    private var model: Model? = null
    private var recognizer: Recognizer? = null
    private var speech: SpeechService? = null
    private val samples = mutableListOf<String>()
    private val testing get() = samples.size >= SAMPLES || showTrained
    private var showTrained = false

    private val micPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) startListening() else hint(getString(R.string.wake_status_no_mic), ui.error)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ui = SetupUi(this)
        parts = SetupParts(ui)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        ui.styleWindow()
        val phrase = WakePhrase.phrase(this)

        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ui.dp(14), ui.dp(4), ui.dp(14), ui.dp(12))
        }
        column.addView(recordingCard(phrase))
        column.addView(trainedCard())
        column.addView(testCard())

        // The bottom band: the button says what is missing until it can save (A5–A7).
        val bottom = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ui.dp(14), ui.dp(10), ui.dp(14), ui.dp(12))
            setBackgroundColor(if (ui.night) 0xFF17171B.toInt() else 0xFFF1F1F3.toInt())
            saveButton = ui.primaryButton(getString(R.string.training_save)) {
                WakePhrase.setSamples(this@VoiceTrainingActivity, samples)
                VoiceWidgetProvider.refreshAll(this@VoiceTrainingActivity)
                finish()
            }
            addView(saveButton)
            addView(LinearLayout(context).apply {
                gravity = Gravity.CENTER
                addView(parts.icon(R.drawable.ic_ui_shield, ui.faint, 13))
                addView(ui.text(getString(R.string.training_footer_short), 12.5f, ui.faint, semibold = true).apply {
                    setPadding(ui.dp(6), 0, 0, 0)
                })
            })
        }
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(ui.ground)
            addView(parts.navBar(getString(R.string.training_back), getString(R.string.training_screen_title)) { finish() }
                .apply { setPadding(ui.dp(6), 0, ui.dp(6), 0) })
            addView(ScrollView(this@VoiceTrainingActivity).apply { addView(column) },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            addView(bottom)
        }
        ViewCompat.setOnApplyWindowInsetsListener(page) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        setContentView(page)

        samples += WakePhrase.samples(this).take(SAMPLES)
        showTrained = samples.isNotEmpty()
        refresh()
    }

    private fun recordingCard(phrase: String) = parts.card(padTop = 16, padBottom = 18).apply {
        gravity = Gravity.CENTER_HORIZONTAL
        trainCard = this
        // The instruction lives inside the card, on its solid ground (not over the wallpaper).
        addView(ui.text(getString(R.string.training_intro_short, SAMPLES), 13.5f, ui.muted).apply {
            gravity = Gravity.CENTER
            setPadding(ui.dp(6), 0, ui.dp(6), ui.dp(12))
        })
        trainSteps = parts.Steps(SAMPLES)
        addView(trainSteps.view, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { bottomMargin = ui.dp(18) })
        stepText = ui.text("", 13f, ui.muted, semibold = true).apply { gravity = Gravity.CENTER }
        addView(stepText)
        addView(ui.text(getString(R.string.training_say_big, phrase), 26f, ui.ink, bold = true).apply {
            gravity = Gravity.CENTER
            setPadding(0, ui.dp(4), 0, ui.dp(12))
        })
        micHalo = PulseHalo(context, parts.blue)
        micDisc = FrameLayout(context).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(parts.blue) }
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            addView(parts.icon(R.drawable.ic_ui_mic, if (ui.night) 0xFF18181B.toInt() else Color.WHITE, 30).apply {
                layoutParams = FrameLayout.LayoutParams(ui.dp(30), ui.dp(30), Gravity.CENTER)
            })
        }
        addView(FrameLayout(context).apply {
            addView(micHalo, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            addView(micDisc, FrameLayout.LayoutParams(ui.dp(76), ui.dp(76), Gravity.CENTER))
        }, LinearLayout.LayoutParams(ui.dp(104), ui.dp(104)))
        trainBars = LevelBars(context, 11, ui.dp(5).toFloat(), parts.blue)
        addView(trainBars, LinearLayout.LayoutParams(ui.dp(95), ui.dp(34)).apply { topMargin = ui.dp(10) })
        hintText = ui.text("", 13f, ui.muted).apply {
            gravity = Gravity.CENTER
            setPadding(0, ui.dp(8), 0, 0)
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        addView(hintText)
        // «No te escuché bien»: amber, never red; the take does not count and nothing is lost.
        missedText = ui.text("", 13.5f, ui.ink)
        missedBox = LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
            setPadding(ui.dp(14), ui.dp(12), ui.dp(14), ui.dp(12))
            background = GradientDrawable().apply { cornerRadius = ui.dp(14).toFloat(); setColor(parts.amberTile) }
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_ASSERTIVE
            addView(parts.icon(R.drawable.ic_ui_warning, parts.amber, 18))
            addView(missedText, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = ui.dp(10) })
        }
        addView(missedBox, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = ui.dp(12)
        })
    }

    private fun trainedCard() = parts.card(padTop = 18, padBottom = 18).apply {
        trainedCard = this
        trainedText = ui.text("", 13f, ui.faint)
        addView(LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(parts.tile(R.drawable.ic_ui_check_mark, parts.green, parts.greenTile))
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(ui.text(getString(R.string.training_trained_title), 16f, ui.ink, bold = true))
                addView(trainedText)
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = ui.dp(12)
                marginEnd = ui.dp(8)
            })
            addView(parts.ghostButton(getString(R.string.training_repeat)) {
                samples.clear()
                showTrained = false
                results.removeAllViews()
                refresh()
            })
        })
        trainedSteps = parts.Steps(SAMPLES)
        addView(trainedSteps.view, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = ui.dp(12) })
    }

    private fun testCard() = parts.card().apply {
        testCard = this
        addView(ui.text(getString(R.string.training_live_title), 16f, ui.ink, bold = true))
        addView(ui.text(getString(R.string.training_live_text), 13f, ui.muted).apply { setPadding(0, 0, 0, ui.dp(12)) })
        testBars = LevelBars(context, 11, ui.dp(3).toFloat(), parts.blue)
        testBars.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        listeningText = ui.text(getString(R.string.training_listening), 14f, parts.blueText, semibold = true).apply {
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        addView(LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(ui.dp(12), ui.dp(10), ui.dp(12), ui.dp(10))
            background = GradientDrawable().apply { cornerRadius = ui.dp(14).toFloat(); setColor(parts.blueSoft) }
            addView(testBars, LinearLayout.LayoutParams(ui.dp(63), ui.dp(22)))
            addView(listeningText, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = ui.dp(10) })
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = ui.dp(6) })
        results = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        addView(results)
    }

    override fun onResume() {
        super.onResume()
        WakeWordService.send(this, WakeWordService.ACTION_PAUSE)
        if (!WakeWordModel.isReady(this)) {
            hint(getString(R.string.training_no_model), parts.amberText)
            listeningText.text = getString(R.string.training_no_model)
            return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            startListening()
        } else {
            micPermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    override fun onPause() {
        stopListening()
        WakeWordService.send(this, WakeWordService.ACTION_RESUME)
        super.onPause()
    }

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        model?.close()
        model = null
        super.onDestroy()
    }

    private fun startListening() {
        if (speech != null) return
        val loaded = model
        if (loaded == null) {
            hint(getString(R.string.training_loading))
            Thread {
                try {
                    val m = Model(WakeWordModel.dir(this).path)
                    runOnUiThread {
                        if (isFinishing) { m.close(); return@runOnUiThread }
                        model = m
                        startListening()
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Could not load the speech model", e)
                    runOnUiThread { hint(getString(R.string.training_no_model), parts.amberText) }
                }
            }.start()
            return
        }
        try {
            val r = Recognizer(loaded, 16_000f).also { recognizer = it }
            speech = SpeechService(r, 16_000f).also { it.startListening(this) }
            refresh()
        } catch (e: Exception) {
            Log.e(TAG, "Could not start listening", e)
            hint(getString(R.string.wake_status_no_mic), ui.error)
        }
    }

    private fun stopListening() {
        speech?.stop()
        speech?.shutdown()
        speech = null
        recognizer?.close()
        recognizer = null
        trainBars.running = false
        testBars.running = false
        micHalo.running = false
    }

    private fun refresh() {
        val listening = speech != null
        trainCard.visibility = if (testing) View.GONE else View.VISIBLE
        trainedCard.visibility = if (testing) View.VISIBLE else View.GONE
        testCard.visibility = if (testing) View.VISIBLE else View.GONE
        trainSteps.set(samples.size)
        trainedSteps.set(samples.size, current = false)
        stepText.text = getString(R.string.training_recording, minOf(samples.size + 1, SAMPLES), SAMPLES)
        trainedText.text = getString(R.string.training_trained_text, samples.size, SAMPLES)
        trainBars.running = listening && !testing && !missed
        testBars.running = listening && testing
        micHalo.running = listening && !testing && !missed
        if (listening && !testing && !missed) hint(getString(R.string.training_hint))
        paintMic()
        // «Faltan 3 grabaciones» until every take is in; then «Guardar».
        val left = SAMPLES - samples.size
        val ready = left <= 0
        saveButton.isEnabled = ready
        saveButton.alpha = 1f
        saveButton.text = when {
            ready -> getString(R.string.training_save)
            left == 1 -> getString(R.string.training_missing_one)
            else -> getString(R.string.training_missing_many, left)
        }
        saveButton.setTextColor(if (ready) (if (ui.night) 0xFF18181B.toInt() else Color.WHITE) else ui.muted)
        saveButton.background = GradientDrawable().apply {
            cornerRadius = ui.dp(26).toFloat()
            setColor(if (ready) ui.ink else (if (ui.night) 0xFF2A2A2F.toInt() else 0xFFE4E4E7.toInt()))
            if (!ready) setStroke(ui.dp(1), if (ui.night) 0x1FFFFFFF else 0x1418181B)
        }
        saveButton.typeface = android.graphics.Typeface.create(if (ready) "sans-serif" else "sans-serif-medium",
            if (ready) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
    }

    /** Slate while it listens; amber, and still, after a take it did not hear well (200 ms). */
    private fun paintMic() {
        val color = if (missed) parts.amber else parts.blue
        val disc = micDisc.background as GradientDrawable
        disc.setColor(color)
        trainBars.color = color
        missedBox.visibility = if (missed && !testing) View.VISIBLE else View.GONE
        if (missed) hintText.text = ""
    }

    private fun hint(text: String, color: Int = ui.faint) {
        hintText.text = text
        hintText.setTextColor(color)
    }

    override fun onPartialResult(hypothesis: String?) {
        val text = field(hypothesis, "partial") ?: return
        if (testing) listeningText.text = getString(R.string.training_listening_heard, text)
    }

    override fun onResult(hypothesis: String?) = handleFinal(hypothesis)

    override fun onFinalResult(hypothesis: String?) = handleFinal(hypothesis)

    private fun handleFinal(hypothesis: String?) {
        val text = field(hypothesis, "text") ?: return
        if (testing) {
            addResult(text)
            listeningText.text = getString(R.string.training_listening)
            return
        }
        // A take that does not sound like the phrase does not count; the earlier ones stay.
        if (WakePhrase.score(text, WakePhrase.phrase(this), emptyList()) < MISS_SCORE) {
            missed = true
            missedText.text = getString(R.string.training_missed, samples.size + 1)
            refresh()
            main.removeCallbacksAndMessages(null)
            // Back to listening after a moment; the notice stays until the next good take.
            main.postDelayed({ if (speech != null) { trainBars.running = true; micHalo.running = true } }, 1_200)
            return
        }
        missed = false
        samples += WakePhrase.words(text).joinToString(" ")
        if (samples.size >= SAMPLES) {
            showTrained = true
            refresh()
            return
        }
        refresh()
        // Show what was heard for a moment, then ask for the next take.
        hint(getString(R.string.training_got_heard, samples.size, text), parts.greenText)
        main.removeCallbacksAndMessages(null)
        main.postDelayed({ if (!testing && speech != null) hint(getString(R.string.training_hint)) }, 1_800)
    }

    /** «Oye, Sakura» · Se activaría / «Hoy hace calor» · No se activa; newest on top, at most four. */
    private fun addResult(text: String) {
        val wakes = WakePhrase.score(this, text, samples) >= WakePhrase.sensitivity(this).threshold
        val row = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, ui.dp(10), 0, ui.dp(10))
            background = object : android.graphics.drawable.Drawable() {
                private val paint = android.graphics.Paint().apply { color = parts.divider }
                override fun draw(canvas: android.graphics.Canvas) =
                    canvas.drawRect(0f, 0f, bounds.width().toFloat(), ui.dp(1).toFloat(), paint)
                override fun setAlpha(alpha: Int) {}
                override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) {}
                @Deprecated("Deprecated in Java")
                override fun getOpacity() = android.graphics.PixelFormat.TRANSLUCENT
            }
            addView(if (wakes) parts.icon(R.drawable.ic_ui_check_mark, parts.green, 18)
                else View(context).apply {
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                    background = GradientDrawable().apply { cornerRadius = ui.dp(1).toFloat(); setColor(ui.muted) }
                }, if (wakes) LinearLayout.LayoutParams(ui.dp(18), ui.dp(18))
                else LinearLayout.LayoutParams(ui.dp(14), ui.dp(2)).apply { marginStart = ui.dp(2); marginEnd = ui.dp(2) })
            addView(ui.text(getString(R.string.wake_quoted, text), 15f, ui.ink, semibold = true).apply {
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = ui.dp(10)
                marginEnd = ui.dp(8)
            })
            addView(ui.text(getString(if (wakes) R.string.training_would_wake else R.string.training_would_not),
                13f, if (wakes) parts.greenText else ui.muted, semibold = wakes))
        }
        results.addView(row, 0)
        while (results.childCount > 4) results.removeViewAt(results.childCount - 1)
    }

    private fun field(json: String?, key: String): String? =
        runCatching { JSONObject(json ?: return null).optString(key) }.getOrNull()?.takeIf { it.isNotBlank() }

    override fun onError(exception: Exception?) {
        Log.w(TAG, "Training recognizer error", exception)
        stopListening()
        startListening()
    }

    override fun onTimeout() {
        stopListening()
        startListening()
    }

    companion object {
        private const val SAMPLES = 5
        /** Below this likeness to the phrase a take is «No te escuché bien» (the model may still write it oddly). */
        private const val MISS_SCORE = 0.4
        private const val TAG = "SakuraWebApp"
    }
}

/** The halo that beats around the training mic (1.6 s); still when motion is reduced or it stops listening. */
class PulseHalo(context: android.content.Context, private val color: Int) : View(context) {
    private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
    private var phase = 0f
    private val animator = android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 1600
        repeatCount = android.animation.ValueAnimator.INFINITE
        interpolator = android.view.animation.AccelerateDecelerateInterpolator()
        addUpdateListener { phase = it.animatedValue as Float; invalidate() }
    }
    var running = false
        set(value) {
            field = value
            if (value && isAttachedToWindow && motionAllowed()) animator.start() else { animator.cancel(); phase = 0f }
            invalidate()
        }

    init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); if (running && motionAllowed()) animator.start() }
    override fun onDetachedFromWindow() { animator.cancel(); super.onDetachedFromWindow() }

    override fun onDraw(canvas: android.graphics.Canvas) {
        if (!running) return
        val c = width / 2f
        val base = 38 * resources.displayMetrics.density
        val grow = (c - base) * phase
        val alpha = ((1f - phase) * 0.38f * 255).toInt()
        paint.color = color
        paint.alpha = alpha
        canvas.drawCircle(c, height / 2f, base + grow, paint)
    }
}
