package com.sakura.webapp

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

/**
 * The Agenda kept on the phone (pedido de Christian, 24-nov-2026: «Agenda sin conexión», variante B).
 *
 * Tasks, sections, calendars and events as the server sends them (`/static/sakura-agenda/sync`, contract in
 * `Interfaz/Open WebUI/AGENDA-SINCRONIZACION.md`), each object kept whole as JSON, plus a queue of the changes
 * not sent yet. Every change made here updates the local copy at once and joins the queue (several changes to
 * one task become one, with the newest `actualizada`), so the screen and the reminders work without a network
 * and the server receives the changes later. All dates are Mexico City's, never the phone's zone.
 */
object AgendaStore {
    val TZ: TimeZone = TimeZone.getTimeZone("America/Mexico_City")
    private const val DB = "agenda.db"

    data class Paso(val id: String, val texto: String, val hecho: Boolean)

    data class Tarea(val json: JSONObject) {
        val id: String get() = json.optString("id")
        val titulo: String get() = json.optString("titulo")
        // a JSON null reads as "null" with optString: an empty note
        val notas: String get() = if (json.isNull("notas")) "" else json.optString("notas")
        val seccionId: String? get() = json.optString("seccion_id").takeIf { it.isNotEmpty() && it != "null" }
        val fecha: String? get() = json.optString("fecha").takeIf { it.length == 10 }
        val hora: String? get() = json.optString("hora").takeIf { it.length == 5 }
        val recordar: String? get() = json.optString("recordar").takeIf { it.isNotEmpty() && it != "null" }
        val repetir: String? get() = json.optString("repetir").takeIf { it.isNotEmpty() && it != "null" }
        val importante: Boolean get() = json.optBoolean("importante")
        val miDia: Boolean get() = json.optString("mi_dia") == hoy()
        val completada: Boolean get() = json.optBoolean("completada")
        val borrada: Boolean get() = !json.isNull("borrada") && json.optLong("borrada") > 0
        val orden: Double get() = json.optDouble("orden", 0.0).let { if (it.isNaN()) 0.0 else it }
        val creada: Long get() = json.optLong("creada")
        val pasos: List<Paso>
            get() = json.optJSONArray("pasos")?.let { a ->
                (0 until a.length()).mapNotNull { i -> a.optJSONObject(i) }
                    .map { Paso(it.optString("id"), it.optString("texto"), it.optBoolean("hecho")) }
            }.orEmpty()
    }

    data class Seccion(val json: JSONObject) {
        val id: String get() = json.optString("id")
        val nombre: String get() = json.optString("nombre")
        val color: String? get() = json.optString("color").takeIf { it.startsWith("#") }
        val orden: Double get() = json.optDouble("orden", 0.0).let { if (it.isNaN()) 0.0 else it }
        val borrada: Boolean get() = !json.isNull("borrada") && json.optLong("borrada") > 0
    }

    data class Evento(val json: JSONObject) {
        val id: String get() = json.optString("id")
        val calendarId: String get() = json.optString("calendar_id")
        val titulo: String get() = json.optString("title")
        val inicioMs: Long get() = json.optLong("start_at") / 1_000_000
        val finMs: Long get() = json.optLong("end_at") / 1_000_000
        val todoElDia: Boolean get() = json.optBoolean("all_day")
        val avisoMin: Int? get() = json.optJSONObject("meta")?.let { m -> if (m.has("alert_minutes") && !m.isNull("alert_minutes")) m.optInt("alert_minutes", -1).takeIf { it >= 0 } else null }
        /** A task's mirror in the «Tareas» calendar, or the one-minute event a task reminder makes: never shown twice. */
        val esDeTarea: Boolean
            get() = json.optJSONObject("data")?.has("sakura_tarea") == true ||
                json.optString("description").startsWith("sakura_tarea:")
    }

    private class Helper(context: Context) : SQLiteOpenHelper(context, DB, null, 1) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE tareas (id TEXT PRIMARY KEY, json TEXT NOT NULL)")
            db.execSQL("CREATE TABLE secciones (id TEXT PRIMARY KEY, json TEXT NOT NULL)")
            db.execSQL("CREATE TABLE calendarios (id TEXT PRIMARY KEY, json TEXT NOT NULL)")
            db.execSQL("CREATE TABLE eventos (id TEXT PRIMARY KEY, json TEXT NOT NULL, inicio INTEGER)")
            // tipo: "tarea" | "seccion"; campos: only the changed fields, merged; actualizada: newest change (ms)
            db.execSQL("CREATE TABLE cola (tipo TEXT NOT NULL, id TEXT NOT NULL, campos TEXT NOT NULL, actualizada INTEGER NOT NULL, PRIMARY KEY (tipo, id))")
            db.execSQL("CREATE TABLE meta (k TEXT PRIMARY KEY, v TEXT)")
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}
    }

    @Volatile private var helper: Helper? = null
    private fun db(context: Context): SQLiteDatabase =
        (helper ?: synchronized(this) { helper ?: Helper(context.applicationContext).also { helper = it } }).writableDatabase

    /** Listeners told when the local copy changes (the screen redraws). */
    private val listeners = mutableSetOf<() -> Unit>()
    fun listen(l: () -> Unit) = synchronized(listeners) { listeners.add(l) }
    fun unlisten(l: () -> Unit) = synchronized(listeners) { listeners.remove(l) }
    fun changed() = synchronized(listeners) { listeners.toList() }.forEach { runCatching { it() } }

    // ── Reading ──

    fun tareas(context: Context): List<Tarea> = rows(context, "tareas").map { Tarea(it) }.filter { !it.borrada }
    fun tarea(context: Context, id: String): Tarea? = row(context, "tareas", id)?.let { Tarea(it) }?.takeIf { !it.borrada }
    fun secciones(context: Context): List<Seccion> = rows(context, "secciones").map { Seccion(it) }.filter { !it.borrada }.sortedBy { it.orden }
    fun calendarios(context: Context): List<JSONObject> = rows(context, "calendarios")
    fun eventos(context: Context): List<Evento> = rows(context, "eventos").map { Evento(it) }

    fun pendientes(context: Context): Int =
        db(context).rawQuery("SELECT COUNT(*) FROM cola", null).use { if (it.moveToFirst()) it.getInt(0) else 0 }

    fun pendiente(context: Context, id: String): Boolean =
        db(context).rawQuery("SELECT 1 FROM cola WHERE id = ?", arrayOf(id)).use { it.moveToFirst() }

    fun meta(context: Context, k: String): String? =
        db(context).rawQuery("SELECT v FROM meta WHERE k = ?", arrayOf(k)).use { if (it.moveToFirst()) it.getString(0) else null }

    fun setMeta(context: Context, k: String, v: String?) {
        if (v == null) db(context).delete("meta", "k = ?", arrayOf(k))
        else db(context).insertWithOnConflict("meta", null, ContentValues().apply { put("k", k); put("v", v) }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    private fun rows(context: Context, table: String): List<JSONObject> =
        db(context).rawQuery("SELECT json FROM $table", null).use { c ->
            val out = ArrayList<JSONObject>(c.count)
            while (c.moveToNext()) runCatching { out.add(JSONObject(c.getString(0))) }
            out
        }

    private fun row(context: Context, table: String, id: String): JSONObject? =
        db(context).rawQuery("SELECT json FROM $table WHERE id = ?", arrayOf(id)).use { c ->
            if (c.moveToFirst()) runCatching { JSONObject(c.getString(0)) }.getOrNull() else null
        }

    private fun put(db: SQLiteDatabase, table: String, obj: JSONObject) {
        val values = ContentValues().apply {
            put("id", obj.optString("id"))
            put("json", obj.toString())
            if (table == "eventos") put("inicio", obj.optLong("start_at") / 1_000_000)
        }
        db.insertWithOnConflict(table, null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    // ── Changes made on the phone ──

    /** Changes [campos] of the task [id] (a new one when it does not exist yet): local copy now, server later. */
    fun cambiarTarea(context: Context, id: String, campos: JSONObject) = cambiar(context, "tareas", "tarea", id, campos)

    fun cambiarSeccion(context: Context, id: String, campos: JSONObject) = cambiar(context, "secciones", "seccion", id, campos)

    private fun cambiar(context: Context, table: String, tipo: String, id: String, campos: JSONObject) {
        val ahora = System.currentTimeMillis()
        campos.put("actualizada", ahora)
        val d = db(context)
        d.beginTransaction()
        try {
            val actual = row(context, table, id) ?: JSONObject().put("id", id).put("creada", ahora)
            val nuevos = campos.keys().asSequence().toList()
            for (k in nuevos) actual.put(k, campos.get(k))
            put(d, table, actual)
            val enCola = d.rawQuery("SELECT campos FROM cola WHERE tipo = ? AND id = ?", arrayOf(tipo, id)).use { c ->
                if (c.moveToFirst()) runCatching { JSONObject(c.getString(0)) }.getOrNull() else null
            } ?: JSONObject().put("id", id).apply { if (!actual.has("creada_enviada")) put("creada", actual.optLong("creada", ahora)) }
            for (k in nuevos) enCola.put(k, campos.get(k))
            if (tipo == "tarea" && !enCola.has("titulo")) enCola.put("titulo", actual.optString("titulo"))
            d.insertWithOnConflict("cola", null, ContentValues().apply {
                put("tipo", tipo); put("id", id); put("campos", enCola.toString()); put("actualizada", ahora)
            }, SQLiteDatabase.CONFLICT_REPLACE)
            d.setTransactionSuccessful()
        } finally {
            d.endTransaction()
        }
        changed()
    }

    fun nuevaTarea(context: Context, titulo: String, extra: JSONObject = JSONObject()): String {
        val id = UUID.randomUUID().toString()
        val orden = (tareas(context).maxOfOrNull { it.orden } ?: 0.0) + 1
        val campos = JSONObject().put("titulo", titulo.take(300)).put("completada", false).put("orden", orden)
            .put("pasos", JSONArray()).put("creada", System.currentTimeMillis())
        for (k in extra.keys()) campos.put(k, extra.get(k))
        cambiarTarea(context, id, campos)
        return id
    }

    fun borrarTarea(context: Context, id: String) = cambiarTarea(context, id, JSONObject().put("borrada", System.currentTimeMillis()))

    // ── Events changed on the phone (AGE-15): the sync's `eventos_cambios` queue, so it works offline too ──

    /** Edits the event [id]: the local copy now, the server on the next sync (`op: editar`). */
    fun cambiarEvento(context: Context, id: String, campos: JSONObject) {
        val ahora = System.currentTimeMillis()
        val d = db(context)
        d.beginTransaction()
        try {
            val actual = row(context, "eventos", id) ?: return
            for (k in campos.keys()) actual.put(k, campos.get(k))
            put(d, "eventos", actual)
            val enCola = d.rawQuery("SELECT campos FROM cola WHERE tipo = 'evento' AND id = ?", arrayOf(id)).use { c ->
                if (c.moveToFirst()) runCatching { JSONObject(c.getString(0)) }.getOrNull() else null
            }?.takeIf { it.optString("op") == "editar" } ?: JSONObject().put("op", "editar").put("id", id)
            for (k in campos.keys()) enCola.put(k, campos.get(k))
            enCola.put("actualizada_ns", ahora * 1_000_000L)
            d.insertWithOnConflict("cola", null, ContentValues().apply {
                put("tipo", "evento"); put("id", id); put("campos", enCola.toString()); put("actualizada", ahora)
            }, SQLiteDatabase.CONFLICT_REPLACE)
            d.setTransactionSuccessful()
        } finally {
            d.endTransaction()
        }
        changed()
    }

    /** Deletes the event [id] here at once and on the server on the next sync (`op: borrar`). */
    fun borrarEvento(context: Context, id: String) {
        val d = db(context)
        d.beginTransaction()
        try {
            d.delete("eventos", "id = ?", arrayOf(id))
            d.insertWithOnConflict("cola", null, ContentValues().apply {
                put("tipo", "evento"); put("id", id); put("campos", JSONObject().put("op", "borrar").put("id", id).toString())
                put("actualizada", System.currentTimeMillis())
            }, SQLiteDatabase.CONFLICT_REPLACE)
            d.setTransactionSuccessful()
        } finally {
            d.endTransaction()
        }
        changed()
    }

    /** A calendar just created on the server (a new label): kept here until the next sync brings the list. */
    fun guardarCalendario(context: Context, cal: JSONObject) {
        put(db(context), "calendarios", cal)
        changed()
    }

    // ── Marks kept only on the phone, like the web keeps them in the browser ──

    private fun marcas(context: Context, k: String): JSONObject = meta(context, k)?.let { runCatching { JSONObject(it) }.getOrNull() } ?: JSONObject()

    /** «Marcar como terminado» of an event on a day (AGE-15). */
    fun eventoHecho(context: Context, id: String, fecha: String): Boolean = marcas(context, "ev_hechos").has("$id|$fecha")

    fun marcarEventoHecho(context: Context, id: String, fecha: String, si: Boolean) {
        val m = marcas(context, "ev_hechos")
        if (si) m.put("$id|$fecha", hoy()) else m.remove("$id|$fecha")
        // the ones of more than 60 days ago are forgotten
        val lim = fechaDe(calendario().apply { add(Calendar.DAY_OF_MONTH, -60) })
        for (k in m.keys().asSequence().toList()) if (k.substringAfter('|') < lim) m.remove(k)
        setMeta(context, "ev_hechos", m.toString())
        changed()
    }

    /** ☆ Importante of an event (AGE-13). */
    fun eventoImportante(context: Context, id: String): Boolean = marcas(context, "ev_importantes").optBoolean(id)

    fun marcarEventoImportante(context: Context, id: String, si: Boolean) {
        val m = marcas(context, "ev_importantes")
        if (si) m.put(id, true) else m.remove(id)
        setMeta(context, "ev_importantes", m.toString())
        changed()
    }

    /** «Personalizado…» reminders already used (AGE-20: they stay in the list to use again). */
    fun avisosPropios(context: Context): List<Int> =
        meta(context, "avisos_propios")?.split(',')?.mapNotNull { it.toIntOrNull() }.orEmpty()

    fun guardarAvisoPropio(context: Context, min: Int) {
        if (min in AgendaEstilo.AVISOS) return
        setMeta(context, "avisos_propios", (listOf(min) + avisosPropios(context).filter { it != min }).take(4).joinToString(","))
    }

    /**
     * Marks the task done or not. Done and repeating: the next one is created here too, with the same fixed id the
     * server would give it (uuid5 of "sakura-repetir:<id>"), so it exists offline and is never duplicated.
     */
    fun completar(context: Context, id: String, hecha: Boolean) {
        val t = tarea(context, id) ?: return
        cambiarTarea(context, id, JSONObject().put("completada", hecha).put("completada_en", if (hecha) System.currentTimeMillis() else JSONObject.NULL))
        if (!hecha) return
        val rep = t.repetir ?: return
        val siguienteId = uuid5Url("sakura-repetir:$id")
        if (row(context, "tareas", siguienteId) != null) return
        val base = t.fecha ?: hoy()
        val pasos = JSONArray().apply { t.pasos.forEach { put(JSONObject().put("id", it.id).put("texto", it.texto).put("hecho", false)) } }
        val campos = JSONObject()
            .put("titulo", t.titulo).put("notas", t.notas).put("seccion_id", t.seccionId ?: JSONObject.NULL)
            .put("fecha", siguienteFecha(base, rep)).put("hora", t.hora ?: JSONObject.NULL)
            .put("importante", t.importante).put("orden", t.orden).put("repetir", rep)
            .put("recordar", t.recordar?.takeIf { r -> r.all { it.isDigit() } } ?: JSONObject.NULL)
            .put("pasos", pasos).put("completada", false).put("creada", System.currentTimeMillis())
        cambiarTarea(context, siguienteId, campos)
    }

    // ── Sync with the server (AgendaSync) ──

    class Envio(val tareas: JSONArray, val secciones: JSONArray, val eventos: JSONArray, val marcas: Map<Pair<String, String>, Long>)

    fun envio(context: Context): Envio {
        val tareas = JSONArray(); val secciones = JSONArray(); val eventos = JSONArray(); val marcas = HashMap<Pair<String, String>, Long>()
        db(context).rawQuery("SELECT tipo, id, campos, actualizada FROM cola", null).use { c ->
            while (c.moveToNext()) {
                val obj = runCatching { JSONObject(c.getString(2)) }.getOrNull() ?: continue
                when (c.getString(0)) {
                    "tarea" -> tareas.put(obj)
                    "evento" -> eventos.put(obj)
                    else -> secciones.put(obj)
                }
                marcas[c.getString(0) to c.getString(1)] = c.getLong(3)
            }
        }
        return Envio(tareas, secciones, eventos, marcas)
    }

    /** Applies the server's answer: its objects win unless a newer change of ours is still waiting in the queue. */
    fun aplicar(context: Context, enviado: Envio, r: JSONObject) {
        val d = db(context)
        d.beginTransaction()
        try {
            // what was sent and did not change meanwhile leaves the queue
            for ((k, marca) in enviado.marcas) d.delete("cola", "tipo = ? AND id = ? AND actualizada = ?", arrayOf(k.first, k.second, marca.toString()))
            fun mezclar(arr: JSONArray?, table: String, tipo: String) {
                arr ?: return
                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i) ?: continue
                    val id = obj.optString("id").takeIf { it.isNotEmpty() } ?: continue
                    val local = d.rawQuery("SELECT actualizada FROM cola WHERE tipo = ? AND id = ?", arrayOf(tipo, id)).use { c ->
                        if (c.moveToFirst()) c.getLong(0) else null
                    }
                    if (local != null && local >= obj.optLong("actualizada")) continue
                    if (local != null) d.delete("cola", "tipo = ? AND id = ?", arrayOf(tipo, id))
                    put(d, table, obj)
                }
            }
            mezclar(r.optJSONArray("tareas"), "tareas", "tarea")
            mezclar(r.optJSONArray("secciones"), "secciones", "seccion")
            r.optJSONArray("calendarios")?.let { cals ->
                d.delete("calendarios", null, null)
                for (i in 0 until cals.length()) cals.optJSONObject(i)?.let { put(d, "calendarios", it) }
            }
            // an event edited or deleted here and not sent yet keeps the phone's version
            val eventosEnCola = d.rawQuery("SELECT id FROM cola WHERE tipo = 'evento'", null).use { c -> buildSet { while (c.moveToNext()) add(c.getString(0)) } }
            r.optJSONArray("eventos")?.let { evs ->
                for (i in 0 until evs.length()) evs.optJSONObject(i)?.takeIf { it.optString("id") !in eventosEnCola }?.let { put(d, "eventos", it) }
            }
            // Open WebUI deletes events without a trace: whatever is not in the window any more goes
            r.optJSONArray("eventos_vigentes")?.let { vig ->
                val vivos = (0 until vig.length()).map { vig.optString(it) }.toHashSet()
                val todos = d.rawQuery("SELECT id FROM eventos", null).use { c -> buildList { while (c.moveToNext()) add(c.getString(0)) } }
                for (id in todos) if (id !in vivos && id !in eventosEnCola) d.delete("eventos", "id = ?", arrayOf(id))
            }
            if (r.has("cursor")) setMetaIn(d, "cursor", r.optLong("cursor").toString())
            if (r.has("eventos_cursor") && !r.isNull("eventos_cursor")) setMetaIn(d, "eventos_cursor", r.optString("eventos_cursor"))
            setMetaIn(d, "sincronizada", System.currentTimeMillis().toString())
            d.setTransactionSuccessful()
        } finally {
            d.endTransaction()
        }
        changed()
    }

    private fun setMetaIn(d: SQLiteDatabase, k: String, v: String) =
        d.insertWithOnConflict("meta", null, ContentValues().apply { put("k", k); put("v", v) }, SQLiteDatabase.CONFLICT_REPLACE)

    /** Another account signed in: the local copy starts over. */
    fun vaciar(context: Context) {
        val d = db(context)
        for (t in listOf("tareas", "secciones", "calendarios", "eventos", "cola", "meta")) d.delete(t, null, null)
        changed()
    }

    // ── Dates (Mexico City), the same rules as sakura_agenda.py ──

    fun calendario(): Calendar = Calendar.getInstance(TZ, Locale("es", "MX"))

    fun hoy(): String = fechaDe(calendario())

    fun fechaDe(c: Calendar): String = String.format(Locale.US, "%04d-%02d-%02d", c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH))

    fun calendarioDe(fecha: String, hora: String? = null): Calendar? {
        val p = fecha.split("-").mapNotNull { it.toIntOrNull() }
        if (p.size != 3) return null
        val h = hora?.split(":")?.mapNotNull { it.toIntOrNull() }?.takeIf { it.size == 2 }
        return calendario().apply {
            clear()
            set(p[0], p[1] - 1, p[2], h?.get(0) ?: 0, h?.get(1) ?: 0, 0)
        }
    }

    /** `_siguiente_fecha`: diario +1; laborables +1 skipping the weekend; semanal +7; mensual/anual clipped to the month's last day. */
    fun siguienteFecha(fecha: String, repetir: String): String {
        val c = calendarioDe(fecha) ?: calendario()
        when (repetir) {
            "diario" -> c.add(Calendar.DAY_OF_MONTH, 1)
            "laborables" -> {
                c.add(Calendar.DAY_OF_MONTH, 1)
                while (c.get(Calendar.DAY_OF_WEEK) == Calendar.SATURDAY || c.get(Calendar.DAY_OF_WEEK) == Calendar.SUNDAY) c.add(Calendar.DAY_OF_MONTH, 1)
            }
            "semanal" -> c.add(Calendar.DAY_OF_MONTH, 7)
            "mensual" -> c.add(Calendar.MONTH, 1) // Calendar clips 31 ene → 28 feb
            "anual" -> c.add(Calendar.YEAR, 1) // 29 feb → 28 feb
            else -> c.add(Calendar.DAY_OF_MONTH, 1)
        }
        return fechaDe(c)
    }

    /** `momento_aviso()`: minutes before the due time (9:00 when it has no time) or an exact "AAAA-MM-DDTHH:MM". */
    fun momentoAviso(t: Tarea): Long? {
        val r = t.recordar ?: return null
        if (r.trimStart('-').all { it.isDigit() } && r.isNotEmpty()) {
            val fecha = t.fecha ?: return null
            val v = calendarioDe(fecha, t.hora ?: "09:00") ?: return null
            return v.timeInMillis - r.toLong() * 60_000
        }
        val partes = r.split("T")
        if (partes.size != 2) return null
        return calendarioDe(partes[0], partes[1].take(5))?.timeInMillis
    }

    fun uuid5Url(name: String): String {
        val ns = UUID.fromString("6ba7b811-9dad-11d1-80b4-00c04fd430c8")
        val nsBytes = ByteArray(16).also { b ->
            for (i in 0 until 8) b[i] = (ns.mostSignificantBits ushr (8 * (7 - i))).toByte()
            for (i in 0 until 8) b[8 + i] = (ns.leastSignificantBits ushr (8 * (7 - i))).toByte()
        }
        val h = MessageDigest.getInstance("SHA-1").digest(nsBytes + name.toByteArray(Charsets.UTF_8))
        h[6] = ((h[6].toInt() and 0x0f) or 0x50).toByte()
        h[8] = ((h[8].toInt() and 0x3f) or 0x80).toByte()
        var msb = 0L; var lsb = 0L
        for (i in 0 until 8) msb = (msb shl 8) or (h[i].toLong() and 0xff)
        for (i in 8 until 16) lsb = (lsb shl 8) or (h[i].toLong() and 0xff)
        return UUID(msb, lsb).toString()
    }
}
