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
 * «Entrenar tu voz», from "App Android · widgets y «Oye, Sakura»": the user says the phrase [SAMPLES]
 * times (progress bar, big mic, level bars) and the transcripts the offline model writes are kept as
 * accepted variants (see WakePhrase). Then «Prueba en vivo» lists what it hears and whether Sakura
 * would wake up, without opening anything. The background listener pauses while this screen listens.
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

    // Trained + live test
    private lateinit var trainedCard: LinearLayout
    private lateinit var trainedText: TextView
    private lateinit var trainedSteps: SetupParts.Steps
    private lateinit var testCard: LinearLayout
    private lateinit var listeningText: TextView
    private lateinit var testBars: LevelBars
    private lateinit var results: LinearLayout

    private lateinit var saveButton: View
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
            addView(ui.text(getString(R.string.training_intro_short, SAMPLES), 14.5f, ui.muted).apply {
                gravity = Gravity.CENTER
                setPadding(ui.dp(10), ui.dp(4), ui.dp(10), ui.dp(14))
            })
        }
        column.addView(recordingCard(phrase))
        column.addView(trainedCard())
        column.addView(testCard())

        val bottom = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ui.dp(14), ui.dp(8), ui.dp(14), ui.dp(14))
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

    private fun recordingCard(phrase: String) = parts.card(padTop = 18, padBottom = 18).apply {
        gravity = Gravity.CENTER_HORIZONTAL
        trainCard = this
        trainSteps = parts.Steps(SAMPLES)
        addView(trainSteps.view, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { bottomMargin = ui.dp(18) })
        stepText = ui.text("", 13f, ui.muted, semibold = true).apply { gravity = Gravity.CENTER }
        addView(stepText)
        addView(ui.text(getString(R.string.training_say_big, phrase), 26f, ui.ink, bold = true).apply {
            gravity = Gravity.CENTER
            setPadding(0, ui.dp(4), 0, ui.dp(12))
        })
        addView(FrameLayout(context).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(parts.blueSoft) }
            addView(FrameLayout(context).apply {
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(parts.blue) }
                elevation = ui.dp(6).toFloat()
                addView(parts.icon(R.drawable.ic_ui_mic, Color.WHITE, 38).apply {
                    layoutParams = FrameLayout.LayoutParams(ui.dp(38), ui.dp(38), Gravity.CENTER)
                })
            }, FrameLayout.LayoutParams(ui.dp(96), ui.dp(96), Gravity.CENTER))
        }, LinearLayout.LayoutParams(ui.dp(116), ui.dp(116)))
        trainBars = LevelBars(context, 11, ui.dp(5).toFloat(), parts.blue)
        addView(trainBars, LinearLayout.LayoutParams(ui.dp(95), ui.dp(34)).apply { topMargin = ui.dp(16) })
        hintText = ui.text("", 13f, ui.faint, semibold = true).apply { gravity = Gravity.CENTER; setPadding(0, ui.dp(6), 0, 0) }
        addView(hintText)
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
        addView(ui.text(getString(R.string.training_live_text), 13f, ui.faint).apply { setPadding(0, 0, 0, ui.dp(12)) })
        testBars = LevelBars(context, 11, ui.dp(3).toFloat(), parts.blue)
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
            hint(getString(R.string.training_no_model), ui.error)
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
                    runOnUiThread { hint(getString(R.string.training_no_model), ui.error) }
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
        trainBars.running = listening && !testing
        testBars.running = listening && testing
        if (listening && !testing) hint(getString(R.string.training_hint))
        ui.setEnabledLook(saveButton, samples.size >= MIN_SAMPLES)
        if (!saveButton.isEnabled) saveButton.alpha = 0.3f
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
        samples += WakePhrase.words(text).joinToString(" ")
        if (samples.size >= SAMPLES) {
            showTrained = true
            refresh()
            return
        }
        refresh()
        // Show what was heard for a moment, then ask for the next take.
        hint(getString(R.string.training_got_heard, samples.size, text), parts.green)
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
            addView(FrameLayout(context).apply {
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(if (wakes) parts.greenTile else parts.redTile) }
                addView(parts.icon(if (wakes) R.drawable.ic_ui_check_mark else R.drawable.ic_ui_close,
                    if (wakes) parts.green else ui.error, 14).apply {
                    layoutParams = FrameLayout.LayoutParams(ui.dp(14), ui.dp(14), Gravity.CENTER)
                })
            }, LinearLayout.LayoutParams(ui.dp(26), ui.dp(26)))
            addView(ui.text(getString(R.string.wake_quoted, text), 15f, ui.ink, semibold = true).apply {
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = ui.dp(10)
                marginEnd = ui.dp(8)
            })
            addView(ui.text(getString(if (wakes) R.string.training_would_wake else R.string.training_would_not),
                12.5f, if (wakes) parts.greenText else parts.redText, semibold = true))
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
        private const val MIN_SAMPLES = 3
        private const val TAG = "SakuraWebApp"
    }
}
