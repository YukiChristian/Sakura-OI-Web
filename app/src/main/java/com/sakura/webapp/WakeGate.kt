package com.sakura.webapp

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.util.Log
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import java.io.IOException
import kotlin.math.sqrt

/**
 * «Oye, Sakura» with a voice gate (pedido de Christian, 21-nov-2026: less battery without losing the phrase).
 *
 * Vosk's own SpeechService hands every tenth of a second of sound to the recognizer, silence included, and the
 * recognizer is the costly part. Here the microphone is read the same way, but a tenth of a second only reaches
 * the recognizer when it is louder than the room's own noise (learned as it listens): something that may be a
 * voice. The 600 ms before it go in too, so the start of «Oye» is not lost, and the gate stays open 1.2 s after
 * the last loud tenth so the phrase finishes. In a quiet room the recognizer barely works.
 *
 * Results reach [listener] on [main], like SpeechService. [lastFrameAt] tells the service's watchdog that the
 * microphone still delivers sound even while the gate is shut (silence is no longer a reason to restart).
 */
class WakeGate(
    private val recognizer: Recognizer,
    private val sampleRate: Int,
    private val listener: RecognitionListener,
    private val main: Handler,
) {
    @Volatile var lastFrameAt = 0L
        private set
    @Volatile private var running = false
    private var thread: Thread? = null
    private var record: AudioRecord? = null

    /** Starts listening; throws when the microphone cannot be opened (another app may be using it). */
    @SuppressLint("MissingPermission") // the service only starts with the microphone granted
    fun start() {
        val minimum = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val audio = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION, sampleRate, AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT, maxOf(minimum, sampleRate * 2 * 2 / 5),
        )
        if (audio.state != AudioRecord.STATE_INITIALIZED) {
            audio.release()
            throw IOException("Failed to initialize the microphone")
        }
        audio.startRecording()
        if (audio.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            audio.release()
            throw IOException("Failed to start recording. Microphone might be already in use.")
        }
        record = audio
        running = true
        lastFrameAt = System.currentTimeMillis()
        thread = Thread({ listen(audio) }, "sakura-wake-gate").apply { start() }
    }

    fun stop() {
        running = false
        thread?.let { if (it !== Thread.currentThread()) runCatching { it.join(1_000) } }
        thread = null
        record?.let { runCatching { it.stop() }; it.release() }
        record = null
    }

    private fun listen(audio: AudioRecord) {
        val frame = ShortArray(sampleRate / 10)
        val before = ArrayDeque<ShortArray>()
        var floor = -1.0 // the room's noise, learned as it listens
        var openFrames = 0 // tenths left with the gate open
        var fed = 0L
        var total = 0L
        var reportAt = System.currentTimeMillis() + REPORT_MS
        try {
            while (running) {
                val n = audio.read(frame, 0, frame.size)
                if (n <= 0) {
                    if (n < 0) throw IOException("Error reading audio ($n)")
                    continue
                }
                lastFrameAt = System.currentTimeMillis()
                total++
                val level = rms(frame, n)
                if (floor < 0) floor = level
                val loud = level > maxOf(floor * LOUDER_THAN_ROOM, MIN_LEVEL)
                // the noise floor follows the room quickly down and slowly up (a voice must not become «room»); a
                // lasting new noise (a fan) still becomes room within a few minutes, so the gate does not stay open
                floor = when {
                    !loud && level < floor -> floor * 0.7 + level * 0.3
                    !loud -> floor * 0.98 + level * 0.02
                    else -> floor * 0.998 + level * 0.002
                }
                if (loud) {
                    if (openFrames == 0) {
                        // the gate opens: first the sound just before, where «Oye» begins
                        for (b in before) feed(b, b.size)
                        fed += before.size
                        before.clear()
                    }
                    openFrames = HOLD_FRAMES
                }
                if (openFrames > 0) {
                    feed(frame, n)
                    fed++
                    openFrames--
                    if (openFrames == 0) {
                        // the gate shuts: close the utterance so the next one starts clean
                        val last = recognizer.finalResult
                        main.post { listener.onFinalResult(last) }
                    }
                } else {
                    before.addLast(frame.copyOf(n))
                    while (before.size > BEFORE_FRAMES) before.removeFirst()
                }
                if (BuildConfig.DEBUG && System.currentTimeMillis() >= reportAt) {
                    Log.d(TAG, "Wake gate: recognizer worked ${fed * 100 / maxOf(total, 1)}% of the last minute (floor ${floor.toInt()})")
                    fed = 0
                    total = 0
                    reportAt = System.currentTimeMillis() + REPORT_MS
                }
            }
        } catch (e: Exception) {
            if (running) main.post { listener.onError(e) }
        }
    }

    private fun feed(samples: ShortArray, n: Int) {
        if (recognizer.acceptWaveForm(samples, n)) {
            val result = recognizer.result
            main.post { listener.onResult(result) }
        } else {
            val partial = recognizer.partialResult
            main.post { listener.onPartialResult(partial) }
        }
    }

    private fun rms(samples: ShortArray, n: Int): Double {
        var sum = 0.0
        for (i in 0 until n) {
            val s = samples[i].toDouble()
            sum += s * s
        }
        return sqrt(sum / n)
    }

    private companion object {
        const val TAG = "SakuraWebApp"
        /** A tenth counts as maybe-voice when it is this many times louder than the room… */
        const val LOUDER_THAN_ROOM = 2.2
        /** …and above this level at all (16-bit samples), so a silent room does not open it with a breath. */
        const val MIN_LEVEL = 90.0
        const val BEFORE_FRAMES = 6 // 600 ms
        const val HOLD_FRAMES = 12 // 1.2 s
        const val REPORT_MS = 60_000L
    }
}
