package com.sakura.webapp

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Agenda reminders that ring without internet: the phone schedules each task with «Recordarme» and each event with
 * its alert (AlarmManager, exact when Android allows it), again after every sync and after the phone restarts.
 * When a reminder also arrives by push from the server (`calendar.alert`), it is shown only if the phone has not
 * already shown the same one in the last 5 minutes, and the other way round.
 */
object AgendaAlarms {
    private const val TAG = "SakuraWebApp"
    const val CHANNEL = "agenda_recordatorios"
    private const val PREFS = "agenda_alarmas"
    private const val ACTION_FIRE = "com.sakura.webapp.agenda.FIRE"
    const val ACTION_DONE = "com.sakura.webapp.agenda.DONE"
    const val ACTION_SNOOZE = "com.sakura.webapp.agenda.SNOOZE"
    const val EXTRA_KEY = "clave" // "t:<task id>" or "e:<event id>"
    const val EXTRA_TITLE = "titulo"
    const val EXTRA_TEXT = "texto"
    private const val RECENT_MS = 5 * 60 * 1000L
    private const val HORIZON_MS = 35L * 24 * 60 * 60 * 1000

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private data class Aviso(val clave: String, val cuando: Long, val titulo: String, val texto: String)

    /** Everything that should ring from now on, within the next weeks. */
    private fun avisos(context: Context): List<Aviso> {
        val ahora = System.currentTimeMillis()
        val out = ArrayList<Aviso>()
        val secciones = AgendaStore.secciones(context).associateBy { it.id }
        for (t in AgendaStore.tareas(context)) {
            if (t.completada) continue
            val cuando = AgendaStore.momentoAviso(t) ?: continue
            if (cuando < ahora - 60_000 || cuando > ahora + HORIZON_MS) continue
            val partes = listOfNotNull(vence(t), t.seccionId?.let { secciones[it]?.nombre })
            out.add(Aviso("t:${t.id}", cuando, t.titulo, partes.joinToString(" · ")))
        }
        for (e in AgendaStore.eventos(context)) {
            if (e.esDeTarea) continue
            val min = e.avisoMin ?: continue
            val cuando = e.inicioMs - min * 60_000L
            if (cuando < ahora - 60_000 || cuando > ahora + HORIZON_MS) continue
            out.add(Aviso("e:${e.id}", cuando, e.titulo, if (e.todoElDia) "Todo el día" else "A las " + hora(e.inicioMs)))
        }
        return out
    }

    fun vence(t: AgendaStore.Tarea): String? {
        val f = t.fecha ?: return null
        val dia = when (f) {
            AgendaStore.hoy() -> "hoy"
            else -> AgendaStore.calendarioDe(f)?.let { c ->
                SimpleDateFormat("EEE d 'de' MMM", Locale("es", "MX")).apply { timeZone = AgendaStore.TZ }.format(c.time)
            } ?: f
        }
        return "Vence " + dia + (t.hora?.let { " a las " + hora(AgendaStore.calendarioDe(f, it)!!.timeInMillis) } ?: "")
    }

    private fun hora(ms: Long): String =
        SimpleDateFormat("h:mm a", Locale("es", "MX")).apply { timeZone = AgendaStore.TZ }.format(java.util.Date(ms))
            .replace("a. m.", "a. m.").replace("p. m.", "p. m.")

    /** Cancels what was scheduled and schedules the current reminders. */
    fun programar(context: Context) {
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        val p = prefs(context)
        val antes = p.getStringSet("programadas", emptySet()).orEmpty()
        val ahora = avisos(context)
        val claves = ahora.map { it.clave }.toSet()
        for (clave in antes) if (clave !in claves) am.cancel(fire(context, clave, "", "", PendingIntent.FLAG_NO_CREATE) ?: continue)
        val sonadas = p.getStringSet("sonadas", emptySet()).orEmpty()
        for (a in ahora) {
            // already shown at this exact moment (the alarm rang and a sync came after): not again
            if ("${a.clave}@${a.cuando}" in sonadas) continue
            val pi = fire(context, a.clave, a.titulo, a.texto, 0) ?: continue
            val cuando = maxOf(a.cuando, System.currentTimeMillis() + 1_000)
            runCatching {
                if (Build.VERSION.SDK_INT >= 31 && !am.canScheduleExactAlarms()) am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, cuando, pi)
                else am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, cuando, pi)
            }.onFailure { Log.w(TAG, "Agenda: no se pudo programar un recordatorio", it) }
        }
        p.edit().putStringSet("programadas", claves).putString("momentos", JSONObject(ahora.associate { it.clave to it.cuando }).toString()).apply()
    }

    private fun fire(context: Context, clave: String, titulo: String, texto: String, flags: Int): PendingIntent? =
        PendingIntent.getBroadcast(
            context, clave.hashCode(),
            Intent(context, AgendaAlarmReceiver::class.java).setAction(ACTION_FIRE)
                .setData(Uri.parse("sakura-agenda://aviso/" + Uri.encode(clave)))
                .putExtra(EXTRA_KEY, clave).putExtra(EXTRA_TITLE, titulo).putExtra(EXTRA_TEXT, texto),
            flags or PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    /** In 10 minutes, only on this phone. */
    fun posponer(context: Context, clave: String, titulo: String, texto: String) {
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        val pi = fire(context, clave, titulo, texto, 0) ?: return
        val cuando = System.currentTimeMillis() + 10 * 60_000L
        runCatching {
            if (Build.VERSION.SDK_INT >= 31 && !am.canScheduleExactAlarms()) am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, cuando, pi)
            else am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, cuando, pi)
        }
    }

    private fun reciente(context: Context, clave: String): Boolean =
        System.currentTimeMillis() - prefs(context).getLong("visto:$clave", 0L) < RECENT_MS

    private fun marcar(context: Context, clave: String, momento: Long?) {
        val p = prefs(context)
        val sonadas = p.getStringSet("sonadas", emptySet()).orEmpty().toMutableSet()
        if (momento != null) sonadas.add("$clave@$momento")
        p.edit().putLong("visto:$clave", System.currentTimeMillis())
            .putStringSet("sonadas", sonadas.toList().takeLast(200).toSet()).apply()
    }

    /** The local alarm rang. */
    fun alSonar(context: Context, clave: String, titulo: String, texto: String) {
        val momento = runCatching { JSONObject(prefs(context).getString("momentos", "{}")!!).optLong(clave) }.getOrNull()?.takeIf { it > 0 }
        if (reciente(context, clave)) { marcar(context, clave, momento); return }
        marcar(context, clave, momento)
        mostrar(context, clave, titulo, texto)
    }

    /**
     * A `calendar_alert` push from Open WebUI ({action, title, event_id, minutes_until}). A task's reminder event
     * carries `description = "sakura_tarea:<id>"`; the push does not, so the event is read to find the task.
     * Blocking (it may ask the server); runs off the main thread.
     */
    fun alPush(context: Context, json: JSONObject) {
        val eventId = json.optString("event_id").takeIf { it.isNotEmpty() && it != "null" }
        var titulo = json.optString("title").ifEmpty { "Recordatorio" }
        var clave = eventId?.let { "e:$it" } ?: "p:$titulo"
        val descripcion = eventId?.let { descripcionDelEvento(context, it) }
        if (descripcion != null && descripcion.startsWith("sakura_tarea:")) {
            val tid = descripcion.removePrefix("sakura_tarea:").trim()
            clave = "t:$tid"
            AgendaStore.tarea(context, tid)?.let { titulo = it.titulo }
        } else if (titulo.startsWith("Recordatorio: ")) {
            // last resort: «Recordatorio: <tarea> · vence …»
            val nombre = titulo.removePrefix("Recordatorio: ").substringBefore(" · ")
            AgendaStore.tareas(context).firstOrNull { it.titulo == nombre }?.let { clave = "t:${it.id}"; titulo = it.titulo }
        }
        if (reciente(context, clave)) return
        marcar(context, clave, null)
        val texto = AgendaStore.tarea(context, clave.removePrefix("t:"))?.let { vence(it) } ?: json.optString("message").ifEmpty { "Agenda" }
        mostrar(context, clave, titulo, texto)
    }

    private fun descripcionDelEvento(context: Context, id: String): String? = runCatching {
        val (base, token) = ChatModels.session(context)
        val c = URL(base.trimEnd('/') + "/api/v1/calendars/events/" + Uri.encode(id)).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 6_000; c.readTimeout = 8_000
            c.setRequestProperty("Authorization", "Bearer $token")
            if (c.responseCode != 200) null else JSONObject(c.inputStream.bufferedReader().readText()).optString("description")
        } finally { c.disconnect() }
    }.getOrNull()

    private fun mostrar(context: Context, clave: String, titulo: String, texto: String) {
        crearCanal(context)
        val id = clave.hashCode()
        val abrir = PendingIntent.getActivity(
            context, id, AgendaActivity.intent(context, clave.takeIf { it.startsWith("t:") }?.removePrefix("t:")),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(titulo)
            .setContentText(texto)
            .setSubText("Agenda")
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(abrir)
        if (clave.startsWith("t:")) {
            builder.addAction(0, "Completar", accion(context, ACTION_DONE, clave, titulo, texto))
        }
        builder.addAction(0, "En 10 min", accion(context, ACTION_SNOOZE, clave, titulo, texto))
        builder.addAction(0, "Abrir", abrir)
        runCatching { NotificationManagerCompat.from(context).notify(id, builder.build()) }
    }

    private fun accion(context: Context, action: String, clave: String, titulo: String, texto: String): PendingIntent =
        PendingIntent.getBroadcast(
            context, (action + clave).hashCode(),
            Intent(context, AgendaAlarmReceiver::class.java).setAction(action)
                .setData(Uri.parse("sakura-agenda://$action/" + Uri.encode(clave)))
                .putExtra(EXTRA_KEY, clave).putExtra(EXTRA_TITLE, titulo).putExtra(EXTRA_TEXT, texto),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    fun crearCanal(context: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = context.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) != null) return
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Recordatorios de la Agenda", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Tareas y eventos de tu Agenda; suenan aunque no haya internet."
        })
    }
}

/** The alarm and the buttons of a reminder (Completar, En 10 min). */
class AgendaAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val clave = intent.getStringExtra(AgendaAlarms.EXTRA_KEY) ?: return
        val titulo = intent.getStringExtra(AgendaAlarms.EXTRA_TITLE).orEmpty()
        val texto = intent.getStringExtra(AgendaAlarms.EXTRA_TEXT).orEmpty()
        val app = context.applicationContext
        when (intent.action) {
            AgendaAlarms.ACTION_DONE -> {
                NotificationManagerCompat.from(app).cancel(clave.hashCode())
                val pending = goAsync()
                Thread {
                    try {
                        AgendaStore.completar(app, clave.removePrefix("t:"), true)
                        AgendaSync.sincronizar(app)
                        AgendaAlarms.programar(app)
                        QuickVoice.refreshAgenda(app)
                    } finally { pending.finish() }
                }.start()
            }
            AgendaAlarms.ACTION_SNOOZE -> {
                NotificationManagerCompat.from(app).cancel(clave.hashCode())
                AgendaAlarms.posponer(app, clave, titulo, texto)
            }
            else -> AgendaAlarms.alSonar(app, clave, titulo, texto)
        }
    }
}

/** After a restart (or an update of the app) the alarms are gone: schedule them again. */
class AgendaBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        val pending = goAsync()
        Thread {
            try {
                AgendaAlarms.programar(app)
                AgendaSync.programarPeriodica(app)
            } finally { pending.finish() }
        }.start()
    }
}
