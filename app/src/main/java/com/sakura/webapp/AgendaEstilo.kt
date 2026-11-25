package com.sakura.webapp

import android.content.Context
import android.graphics.Color
import org.json.JSONObject

/**
 * What the Agenda screen and the widgets share (AGE-12, AGE-13, AGE-20 and AGE-23 of «Hanami Mockups», 24-nov-2026):
 * the 13 colours, which calendar colours each task and event, its state (Pendiente, Vencida, Ahora, Hecha) and the
 * texts of the reminders. The rules are the web Agenda's (branding/tema/sakura.js), so the phone and the web agree.
 */
object AgendaEstilo {
    data class Color13(val id: String, val nombre: String, val claro: Int, val oscuro: Int) {
        fun de(night: Boolean) = if (night) oscuro else claro
        val hexClaro: String get() = String.format("#%06x", claro and 0xFFFFFF)
    }

    /** AGE-12: the project colours, in the board's order; each calendar takes the nearest. */
    val PALETA = listOf(
        Color13("rojo", "Rojo", 0xFFC0392B.toInt(), 0xFFE0594A.toInt()),
        Color13("naranja", "Naranja", 0xFFE8772E.toInt(), 0xFFE8772E.toInt()),
        Color13("amarillo", "Amarillo", 0xFFB88A00.toInt(), 0xFFC99A12.toInt()),
        Color13("ambar", "Ámbar", 0xFFF5C542.toInt(), 0xFFF5C542.toInt()),
        Color13("lima", "Lima", 0xFF5C940D.toInt(), 0xFF74B816.toInt()),
        Color13("verde", "Verde", 0xFF2B8A5A.toInt(), 0xFF40A874.toInt()),
        Color13("turquesa", "Turquesa", 0xFF1A8A9A.toInt(), 0xFF22A3B5.toInt()),
        Color13("pizarra", "Azul pizarra", 0xFF3E5F99.toInt(), 0xFF8FA9DA.toInt()),
        Color13("violeta", "Violeta", 0xFF7950F2.toInt(), 0xFF9775FA.toInt()),
        Color13("morado", "Morado", 0xFFAE3EC9.toInt(), 0xFFC45AD9.toInt()),
        Color13("rosa", "Rosa Sakura", 0xFFD6336C.toInt(), 0xFFEF6C8D.toInt()),
        Color13("grafito", "Grafito", 0xFF3F3F46.toInt(), 0xFF8B8B94.toInt()),
        Color13("gris", "Gris", 0xFF71717A.toInt(), 0xFFB4B4BC.toInt()),
    )
    private val PIZARRA = PALETA[7]
    private val cache = HashMap<String, Color13>()

    // Fixed state colours (AGE-13): red only for «Vencida», amber (the star's) for «Ahora», green for «Hecha».
    fun rojo(night: Boolean) = if (night) 0xFFE0594A.toInt() else 0xFFC0392B.toInt()
    fun rojoTexto(night: Boolean) = if (night) 0xFFF08A7C.toInt() else 0xFFB8322A.toInt()
    fun verde(night: Boolean) = if (night) 0xFF40A874.toInt() else 0xFF2B8A5A.toInt()
    fun verdeTexto(night: Boolean) = if (night) 0xFF6CC99A.toInt() else 0xFF237A4E.toInt()
    const val AMBAR = 0xFFF5C542.toInt()
    fun ambarTexto(night: Boolean) = if (night) 0xFFF5C542.toInt() else 0xFF7A5800.toInt()
    fun ambarPunto(night: Boolean) = if (night) 0xFFF5C542.toInt() else 0xFFD99A00.toInt()
    fun gris(night: Boolean) = if (night) 0xFF71717A.toInt() else 0xFFA1A1AA.toInt()

    /** The palette colour nearest to [hex] (Open WebUI's violet «Tareas programadas» goes in Sakura pink). */
    fun paleta(hex: String?): Color13 {
        val k = (hex ?: "").trim().lowercase()
        cache[k]?.let { return it }
        val rgb = runCatching { Color.parseColor(k) }.getOrNull()
        var p = PALETA.firstOrNull { rgb != null && ((it.claro and 0xFFFFFF) == (rgb and 0xFFFFFF) || (it.oscuro and 0xFFFFFF) == (rgb and 0xFFFFFF)) }
        if (p == null && k == "#8b5cf6") p = PALETA.first { it.id == "rosa" }
        if (p == null && rgb != null) {
            val hsl = FloatArray(3).also { androidx.core.graphics.ColorUtils.colorToHSL(rgb, it) }
            p = if (hsl[1] < 0.15f) PALETA.first { it.id == if (hsl[2] < 0.36f) "grafito" else "gris" }
            else PALETA.filter { it.id !in setOf("ambar", "grafito", "gris") }.minByOrNull { c ->
                val h2 = FloatArray(3).also { androidx.core.graphics.ColorUtils.colorToHSL(c.claro, it) }
                val dh = kotlin.math.abs(hsl[0] - h2[0]).let { minOf(it, 360 - it) }
                dh + kotlin.math.abs(hsl[2] - h2[2]) * 40
            }
        }
        return (p ?: PIZARRA).also { cache[k] = it }
    }

    // ── Calendars: a task's label is a calendar (seccion_id «cal-<id>»), like on the web ──

    data class Cal(val nombre: String, val color: Color13)

    private val NOMBRES = mapOf("Scheduled Tasks" to "Tareas programadas")
    fun nombreCalendario(n: String) = NOMBRES[n] ?: n

    fun calendarios(context: Context): Map<String, JSONObject> = AgendaStore.calendarios(context).associateBy { it.optString("id") }

    /** The task's label (its calendar), or null: «Sin calendario». */
    fun calDeTarea(t: AgendaStore.Tarea, secciones: Map<String, AgendaStore.Seccion>, cals: Map<String, JSONObject>): Cal? {
        val sid = t.seccionId ?: return null
        val s = secciones[sid] ?: return null
        val cal = if (sid.startsWith("cal-")) cals[sid.removePrefix("cal-")] else null
        return Cal(s.nombre.ifEmpty { cal?.optString("name").orEmpty() }, paleta(cal?.optString("color")?.takeIf { it.startsWith("#") } ?: s.color))
    }

    fun calDeEvento(e: AgendaStore.Evento, cals: Map<String, JSONObject>): Cal {
        val c = cals[e.calendarId]
        val color = e.json.optString("color").takeIf { it.startsWith("#") } ?: c?.optString("color")
        return Cal(nombreCalendario(c?.optString("name").orEmpty()), paleta(color))
    }

    /** Calendars a label can be: the user's own, not the task mirror «Tareas» nor «Avisos de tareas». */
    fun esCalendarioDeEtiqueta(c: JSONObject): Boolean {
        val n = c.optString("name")
        return n != "Tareas" && n != "Avisos de tareas" && n != "Scheduled Tasks"
    }

    // ── State (AGE-13, AGE-23) ──

    enum class Estado { PENDIENTE, VENCIDA, AHORA, HECHA }
    private const val AHORA_ANTES = 15 * 60_000L

    /** When it falls due: at its time; without a time, when its day ends; without a date, never. */
    fun vence(t: AgendaStore.Tarea): Long? {
        val f = t.fecha ?: return null
        t.hora?.let { h -> return AgendaStore.calendarioDe(f, h)?.timeInMillis }
        return AgendaStore.calendarioDe(f)?.apply { add(java.util.Calendar.DAY_OF_MONTH, 1) }?.timeInMillis
    }

    fun estado(t: AgendaStore.Tarea, ahora: Long = System.currentTimeMillis()): Estado {
        if (t.completada) return Estado.HECHA
        val v = vence(t) ?: return Estado.PENDIENTE
        if (ahora >= v) return Estado.VENCIDA
        if (t.hora != null && ahora >= v - AHORA_ANTES) return Estado.AHORA
        return Estado.PENDIENTE
    }

    /** An event only happens: «Ahora» while it lasts (one hour when it has no end), then past. */
    fun eventoAhora(e: AgendaStore.Evento, ahora: Long = System.currentTimeMillis()) = ahora >= e.inicioMs && ahora < finDe(e)
    fun eventoPasado(e: AgendaStore.Evento, ahora: Long = System.currentTimeMillis()) = ahora >= finDe(e)
    fun finDe(e: AgendaStore.Evento): Long = when {
        e.todoElDia -> maxOf(e.finMs, e.inicioMs + 86_400_000L)
        e.finMs > e.inicioMs -> e.finMs
        else -> e.inicioMs + 3_600_000L
    }

    // ── Reminders (AGE-09, AGE-20) ──

    /** The menu's fixed choices, in minutes before. */
    val AVISOS = listOf(0, 5, 10, 15, 30, 60, 1440, 2880, 4320, 10080)

    /** «A la hora», «2 h 30 min antes», «3 días antes», «1 semana antes». */
    fun minutosTexto(min: Int): String {
        if (min <= 0) return "A la hora"
        if (min % 10080 == 0) return (min / 10080).let { if (it == 1) "1 semana antes" else "$it semanas antes" }
        if (min % 1440 == 0) return (min / 1440).let { if (it == 1) "1 día antes" else "$it días antes" }
        val h = min / 60; val m = min % 60
        return when {
            h == 0 -> "$m min antes"
            m == 0 -> "$h h antes"
            else -> "$h h $m min antes"
        }
    }

    fun esMinutos(r: String) = r.isNotEmpty() && r.trimStart('-').all { it.isDigit() }
}
