package com.sakura.webapp

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Sends the Agenda's queued changes and brings back what changed on the server (`POST /static/sakura-agenda/sync`).
 * Runs when the app or the Agenda opens, after every change (a moment later, so several taps go together), every
 * 15 minutes with a network (JobScheduler) and as soon as the network returns while changes wait. After each sync
 * the reminders are scheduled again and the widgets redraw.
 */
object AgendaSync {
    private const val TAG = "SakuraWebApp"
    private const val PATH = "/static/sakura-agenda/sync"
    private const val JOB_PERIODIC = 4201
    private const val JOB_PENDING = 4202

    enum class Estado { AL_DIA, PENDIENTES, SINCRONIZANDO, SIN_CONEXION, SIN_SESION }

    @Volatile var estado: Estado = Estado.AL_DIA
        private set
    @Volatile private var corriendo = false
    @Volatile private var otraVez = false
    private val main = Handler(Looper.getMainLooper())
    private val pronto = Runnable { }

    fun estadoActual(context: Context): Estado = when {
        estado == Estado.SINCRONIZANDO || estado == Estado.SIN_SESION -> estado
        AgendaStore.pendientes(context) > 0 -> Estado.PENDIENTES
        estado == Estado.SIN_CONEXION -> Estado.SIN_CONEXION
        else -> Estado.AL_DIA
    }

    /** After a change: wait a moment, so a burst of taps travels in one request. */
    fun pronto(context: Context) {
        val app = context.applicationContext
        main.removeCallbacksAndMessages(pronto)
        main.postAtTime({ ahora(app) }, pronto, android.os.SystemClock.uptimeMillis() + 1_500)
    }

    /** Syncs now in the background; [listo] runs on the main thread with whether it worked. */
    fun ahora(context: Context, listo: ((Boolean) -> Unit)? = null) {
        val app = context.applicationContext
        if (corriendo) { otraVez = true; listo?.invoke(false); return }
        corriendo = true
        Thread {
            var ok = false
            try {
                do {
                    otraVez = false
                    ok = sincronizar(app)
                } while (otraVez && ok)
            } finally {
                corriendo = false
                AgendaAlarms.programar(app)
                QuickVoice.refreshAgenda(app)
                AgendaStore.changed()
                main.post { listo?.invoke(ok) }
                if (!ok && AgendaStore.pendientes(app) > 0) programarAlVolverLaRed(app)
            }
        }.start()
    }

    /** One blocking round trip. False when there was no session or no answer (the queue stays). */
    fun sincronizar(context: Context): Boolean {
        val (base, token) = runCatching { ChatModels.session(context) }.getOrNull() ?: run {
            estado = Estado.SIN_SESION
            AgendaStore.changed()
            return false
        }
        // another account signed in on this phone: its agenda is not this one
        val cuenta = token.split('.').getOrNull(1)?.let { runCatching { String(android.util.Base64.decode(it, android.util.Base64.URL_SAFE or android.util.Base64.NO_PADDING or android.util.Base64.NO_WRAP)) }.getOrNull() }
            ?.let { runCatching { JSONObject(it).optString("id") }.getOrNull() }
        if (cuenta != null && cuenta.isNotEmpty()) {
            val antes = AgendaStore.meta(context, "cuenta")
            if (antes != null && antes != cuenta) AgendaStore.vaciar(context)
            AgendaStore.setMeta(context, "cuenta", cuenta)
        }
        estado = Estado.SINCRONIZANDO
        AgendaStore.changed()
        val envio = AgendaStore.envio(context)
        val cuerpo = JSONObject()
            .put("desde", AgendaStore.meta(context, "cursor")?.toLongOrNull() ?: 0L)
            .put("tareas", envio.tareas)
            .put("secciones", envio.secciones)
            .put("eventos", true)
            .put("ahora", System.currentTimeMillis())
        // events edited or deleted on the phone (AGE-15), applied by the server before it answers
        if (envio.eventos.length() > 0) cuerpo.put("eventos_cambios", envio.eventos)
        AgendaStore.meta(context, "eventos_cursor")?.let { cuerpo.put("eventos_desde", it.toLongOrNull() ?: it) }
        return try {
            val connection = URL(base.trimEnd('/') + PATH).openConnection() as HttpURLConnection
            val respuesta = try {
                connection.requestMethod = "POST"
                connection.connectTimeout = 10_000
                connection.readTimeout = 30_000
                connection.doOutput = true
                connection.setRequestProperty("Authorization", "Bearer $token")
                connection.setRequestProperty("Content-Type", "application/json")
                connection.setRequestProperty("X-Sakura-Dispositivo", "android")
                connection.setRequestProperty("X-Sakura-Ahora", System.currentTimeMillis().toString())
                connection.outputStream.use { it.write(cuerpo.toString().toByteArray()) }
                val code = connection.responseCode
                if (code == 401) {
                    estado = Estado.SIN_SESION
                    return false
                }
                check(code == 200) { "HTTP $code" }
                connection.inputStream.bufferedReader().readText()
            } finally {
                connection.disconnect()
            }
            AgendaStore.aplicar(context, envio, JSONObject(respuesta))
            estado = Estado.AL_DIA
            // the server answered: the widgets drop «Sin conexión con tu PC» right away
            PcStatus.markOnline(context)
            PushNotices.asegurarAvisosDeAgenda(context)
            true
        } catch (e: Exception) {
            Log.i(TAG, "Agenda: sin conexión con el servidor (${e.javaClass.simpleName})")
            estado = Estado.SIN_CONEXION
            false
        } finally {
            AgendaStore.changed()
        }
    }

    /** Every 15 minutes with any network, while the phone allows it. */
    fun programarPeriodica(context: Context) {
        val js = context.getSystemService(JobScheduler::class.java) ?: return
        if (js.getPendingJob(JOB_PERIODIC) != null) return
        js.schedule(
            JobInfo.Builder(JOB_PERIODIC, ComponentName(context, AgendaSyncJob::class.java))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPeriodic(15 * 60 * 1000L)
                .setPersisted(true)
                .build()
        )
    }

    /** Changes waiting and no network: one sync as soon as there is one. */
    private fun programarAlVolverLaRed(context: Context) {
        val js = context.getSystemService(JobScheduler::class.java) ?: return
        runCatching {
            js.schedule(
                JobInfo.Builder(JOB_PENDING, ComponentName(context, AgendaSyncJob::class.java))
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                    .setMinimumLatency(30_000)
                    .setPersisted(true)
                    .build()
            )
        }
    }
}

/** JobScheduler's entry: one sync, then done (the periodic one repeats by itself). */
class AgendaSyncJob : JobService() {
    override fun onStartJob(params: JobParameters): Boolean {
        val app = applicationContext
        Thread {
            val ok = runCatching { AgendaSync.sincronizar(app) }.getOrDefault(false)
            AgendaAlarms.programar(app)
            QuickVoice.refreshAgenda(app)
            jobFinished(params, !ok && params.jobId != 4201 && AgendaStore.pendientes(app) > 0)
        }.start()
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean = true
}
