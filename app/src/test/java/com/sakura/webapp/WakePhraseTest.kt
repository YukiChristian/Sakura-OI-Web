package com.sakura.webapp

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Transcripts like the ones the small Spanish model writes for "Oye, Sakura" must reach the
 * Normal threshold; ordinary speech must stay below it. Trained samples must lift a phrase the
 * model mangles badly above even the Strict threshold.
 */
class WakePhraseTest {
    private val phrase = "Oye, Sakura"
    private val normal = WakePhrase.Sensitivity.NORMAL.threshold
    private val strict = WakePhrase.Sensitivity.STRICT.threshold

    private fun score(heard: String, trained: List<String> = emptyList()) =
        WakePhrase.score(heard, phrase, trained)

    @Test
    fun recognizesCloseTranscripts() {
        for (heard in listOf("oye sakura", "oye sacura", "oye sa cura", "hoy sacra", "oye zakura", "bueno oye sakura ya")) {
            val s = score(heard)
            println("\"$heard\" -> %.2f".format(s))
            assertTrue("\"$heard\" should wake (%.2f)".format(s), s >= normal)
        }
    }

    @Test
    fun ignoresOrdinarySpeech() {
        for (heard in listOf("hola cómo estás", "qué hora es", "voy a la tienda", "cuéntame un chiste", "mañana", "o sea")) {
            val s = score(heard)
            println("\"$heard\" -> %.2f".format(s))
            assertTrue("\"$heard\" should not wake (%.2f)".format(s), s < normal)
        }
    }

    @Test
    fun trainingFixesBadlyMangledPhrase() {
        val mangled = "olla saber"
        val before = score(mangled)
        val after = score(mangled, trained = listOf("olla saber", "oye saber"))
        println("\"$mangled\" before %.2f, after %.2f".format(before, after))
        assertTrue(before < normal)
        assertTrue(after >= strict)
    }
}
