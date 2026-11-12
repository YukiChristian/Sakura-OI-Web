package com.sakura.webapp

import android.content.Context
import java.text.Normalizer
import kotlin.math.max
import kotlin.math.min

/**
 * Which phrase wakes Sakura and how strictly it must match. The small Spanish speech model
 * writes unknown words like "Sakura" as whatever sounds close ("sacra", "sa cura"), so the
 * phrase is compared by sound: both sides are simplified (c/k/q → k, z → s, v → b, silent h…)
 * and scored by edit distance. The training screen adds the exact transcripts the model
 * produces for the user's own voice, which then match almost perfectly.
 */
object WakePhrase {
    private const val PREFS = "wake_word"
    private const val KEY_PHRASE = "phrase"
    private const val KEY_SAMPLES = "samples"
    private const val KEY_SENSITIVITY = "sensitivity"

    val PRESETS = listOf("Oye, Sakura", "Hola, Sakura", "Sakura, escucha")

    /** Minimum similarity (0–1) between what was heard and the phrase or a trained sample. */
    enum class Sensitivity(val threshold: Double) { STRICT(0.86), NORMAL(0.76), RELAXED(0.64) }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun phrase(context: Context): String = prefs(context).getString(KEY_PHRASE, null) ?: PRESETS[0]

    /** A new phrase invalidates the samples recorded for the old one. */
    fun setPhrase(context: Context, phrase: String) {
        prefs(context).edit().putString(KEY_PHRASE, phrase.trim()).remove(KEY_SAMPLES).apply()
    }

    fun samples(context: Context): Set<String> = prefs(context).getStringSet(KEY_SAMPLES, emptySet()) ?: emptySet()

    fun setSamples(context: Context, samples: Collection<String>) {
        prefs(context).edit()
            .putStringSet(KEY_SAMPLES, samples.map { words(it).joinToString(" ") }.filter { it.isNotEmpty() }.toSet())
            .apply()
    }

    fun sensitivity(context: Context): Sensitivity =
        runCatching { Sensitivity.valueOf(prefs(context).getString(KEY_SENSITIVITY, null) ?: "") }
            .getOrDefault(Sensitivity.NORMAL)

    fun setSensitivity(context: Context, value: Sensitivity) {
        prefs(context).edit().putString(KEY_SENSITIVITY, value.name).apply()
    }

    fun matches(context: Context, heard: String): Boolean =
        score(context, heard) >= sensitivity(context).threshold

    /**
     * Best similarity between [heard] and the phrase or any trained sample, from 0 to 1.
     * [trained] defaults to the saved samples; the training screen passes the ones on screen.
     */
    fun score(context: Context, heard: String, trained: Collection<String> = samples(context)): Double =
        score(heard, phrase(context), trained)

    /** [score] without Android: [phrase] and [trained] given directly (used by the unit tests). */
    fun score(heard: String, phrase: String, trained: Collection<String>): Double {
        val heardWords = words(heard)
        if (heardWords.isEmpty()) return 0.0
        val targets = (trained.map { words(it).joinToString(" ") } + words(phrase).joinToString(" "))
            .map { it.split(' ').filter(String::isNotEmpty) }
            .filter { it.isNotEmpty() }
        var best = 0.0
        for (target in targets) {
            val goal = sound(target.joinToString(""))
            // Try every run of heard words about as long as the target (the model may split
            // or join words), anywhere in what was heard.
            for (size in max(1, target.size - 1)..(target.size + 1)) {
                for (start in 0..heardWords.size - size) {
                    val window = sound(heardWords.subList(start, start + size).joinToString(""))
                    best = max(best, similarity(window, goal))
                }
            }
            if (heardWords.size < target.size - 1) {
                best = max(best, similarity(sound(heardWords.joinToString("")), goal))
            }
        }
        return best
    }

    /** Lower-case words without accents or punctuation. */
    fun words(text: String): List<String> =
        Normalizer.normalize(text.lowercase(), Normalizer.Form.NFD)
            .replace(Regex("\\p{M}"), "")
            .replace(Regex("[^a-zñ ]"), " ")
            .split(' ')
            .filter { it.isNotEmpty() }

    /** Rough Spanish sound key: letters that sound alike become the same letter. */
    private fun sound(text: String): String {
        val s = text
            .replace("ll", "y").replace("qu", "k").replace("ch", "x")
            .replace('c', 'k').replace('q', 'k').replace('z', 's').replace('v', 'b')
            .replace('w', 'u').replace('ñ', 'n').replace("h", "")
        val out = StringBuilder()
        for (ch in s) if (out.isEmpty() || out.last() != ch) out.append(ch)
        return out.toString()
    }

    private fun similarity(a: String, b: String): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        return 1.0 - distance(a, b).toDouble() / max(a.length, b.length)
    }

    private fun distance(a: String, b: String): Int {
        var prev = IntArray(b.length + 1) { it }
        var cur = IntArray(b.length + 1)
        for (i in 1..a.length) {
            cur[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                cur[j] = min(min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost)
            }
            val t = prev; prev = cur; cur = t
        }
        return prev[b.length]
    }
}
