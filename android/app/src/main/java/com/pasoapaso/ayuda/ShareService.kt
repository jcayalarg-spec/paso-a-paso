package com.pasoapaso.ayuda

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.drawable.Icon
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.util.Base64
import org.json.JSONObject
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.CRC32

/**
 * Comparte la pantalla con el agente (una imagen pequeña cada ~0.7 s),
 * escucha sus indicaciones (señalar, ir al inicio, abrir WhatsApp...) y dibuja el círculo.
 * Nunca toca nada por ella: solo señala y abre apps.
 */
class ShareService : Service() {

    companion object {
        const val STOP = "com.pasoapaso.ayuda.STOP"
        const val EXTRA_CODE = "code"
        const val EXTRA_DATA = "data"
        const val EXTRA_SID = "sid"
        const val EXTRA_AGENT = "agent"
        private const val CHANNEL = "compartir"
        private const val FRAME_MS = 700L
        private const val CAPTURE_WIDTH = 540

        @Volatile
        var running = false
            private set
    }

    private val main = Handler(Looper.getMainLooper())
    private val uploader = Executors.newSingleThreadExecutor()
    private val uploading = AtomicBoolean(false)
    private val stopped = AtomicBoolean(false)

    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var thread: HandlerThread? = null
    private var worker: Handler? = null
    private var overlay: PointerOverlay? = null

    @Volatile private var alive = false
    @Volatile private var lastCrc = -1L
    @Volatile private var lastCmd: String? = null
    private var sid = ""

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP) {
            shutdown(true)
            return START_NOT_STICKY
        }
        if (alive) return START_NOT_STICKY

        val code = intent?.getIntExtra(EXTRA_CODE, 0) ?: 0
        val data: Intent? = if (Build.VERSION.SDK_INT >= 33) {
            intent?.getParcelableExtra(EXTRA_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent?.getParcelableExtra(EXTRA_DATA)
        }
        sid = intent?.getStringExtra(EXTRA_SID).orEmpty()
        val agent = intent?.getStringExtra(EXTRA_AGENT) ?: "Tu agente"

        // Android exige mostrar la notificación ANTES de pedir la captura.
        startForegroundNow(agent)

        if (data == null || !Net.validId(sid)) {
            shutdown(false)
            return START_NOT_STICKY
        }
        val mpm = getSystemService(MediaProjectionManager::class.java)
        val p = try { mpm.getMediaProjection(code, data) } catch (e: Exception) { null }
        if (p == null) {
            Net.reportState(sid, "error_captura")
            shutdown(false)
            return START_NOT_STICKY
        }

        projection = p
        alive = true
        running = true

        val t = HandlerThread("paso-a-paso").also { it.start() }
        thread = t
        val h = Handler(t.looper)
        worker = h

        p.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                // Ella tocó "Dejar de compartir" desde el sistema.
                main.post { shutdown(true) }
            }
        }, h)

        val (rw, rh) = Screen.real(this)
        val w = minOf(CAPTURE_WIDTH, rw)
        val hgt = ((rh.toLong() * w / rw).toInt()) and 1.inv()
        val dpi = maxOf(1, resources.displayMetrics.densityDpi * w / rw)
        val r = ImageReader.newInstance(w, hgt, PixelFormat.RGBA_8888, 2)
        reader = r
        try {
            virtualDisplay = p.createVirtualDisplay(
                "paso-a-paso", w, hgt, dpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                r.surface, null, h
            )
        } catch (e: Exception) {
            Net.reportState(sid, "error_captura")
            shutdown(false)
            return START_NOT_STICKY
        }

        overlay = PointerOverlay(this, agent).also { it.attach() }
        Net.reportState(sid, "compartiendo", JSONObject().put("w", rw).put("h", rh))
        h.postDelayed(frameTask, 400)
        h.postDelayed(beat, 5000)
        listenCommands()
        return START_NOT_STICKY
    }

    /* ---------- Imagen de la pantalla ---------- */

    private val frameTask = object : Runnable {
        override fun run() {
            if (!alive) return
            try { grab() } catch (e: Exception) { }
            worker?.postDelayed(this, FRAME_MS)
        }
    }

    private fun grab() {
        val img = reader?.acquireLatestImage() ?: return
        try {
            if (uploading.get()) return
            val plane = img.planes[0]
            val pixelStride = plane.pixelStride
            val rowPadding = plane.rowStride - pixelStride * img.width
            val full = Bitmap.createBitmap(img.width + rowPadding / pixelStride, img.height, Bitmap.Config.ARGB_8888)
            full.copyPixelsFromBuffer(plane.buffer)
            val bmp = if (rowPadding > 0) Bitmap.createBitmap(full, 0, 0, img.width, img.height) else full
            val out = ByteArrayOutputStream()
            bmp.compress(Bitmap.CompressFormat.JPEG, 50, out)
            if (bmp !== full) bmp.recycle()
            full.recycle()

            val bytes = out.toByteArray()
            val crc = CRC32().apply { update(bytes) }.value
            if (crc == lastCrc) return // la pantalla no cambió
            val w = img.width
            val h = img.height
            uploading.set(true)
            uploader.execute {
                try {
                    val body = JSONObject()
                        .put("d", Base64.encodeToString(bytes, Base64.NO_WRAP))
                        .put("w", w).put("h", h)
                        .put("t", Net.now())
                        .toString()
                    if (Net.send("PUT", "sessions/$sid/frame", body) != null) lastCrc = crc
                } catch (e: Exception) {
                } finally {
                    uploading.set(false)
                }
            }
        } finally {
            img.close()
        }
    }

    private val beat = object : Runnable {
        override fun run() {
            if (!alive) return
            Net.reportState(sid, "compartiendo")
            worker?.postDelayed(this, 5000)
        }
    }

    /* ---------- Indicaciones del agente ---------- */

    private fun listenCommands() {
        Thread {
            var firstConnection = true
            while (alive) {
                var conn: HttpURLConnection? = null
                try {
                    conn = URL(Net.url("sessions/$sid/cmd")).openConnection() as HttpURLConnection
                    conn.setRequestProperty("Accept", "text/event-stream")
                    conn.connectTimeout = 15000
                    conn.readTimeout = 70000 // Firebase manda una señal cada 30 s
                    val reader = BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8))
                    var event = ""
                    var initial = true
                    while (alive) {
                        val line = reader.readLine() ?: break
                        when {
                            line.startsWith("event:") -> event = line.substring(6).trim()
                            line.startsWith("data:") -> {
                                val payload = line.substring(5).trim()
                                if (event == "put" || event == "patch") {
                                    // La primera vez solo recordamos la última orden, sin ejecutarla.
                                    handle(payload, execute = !(initial && firstConnection))
                                    initial = false
                                } else if (event == "cancel" || event == "auth_revoked") {
                                    break
                                }
                            }
                        }
                    }
                    firstConnection = false
                } catch (e: Exception) {
                } finally {
                    conn?.disconnect()
                }
                if (alive) try { Thread.sleep(2000) } catch (e: InterruptedException) { }
            }
        }.apply { isDaemon = true; start() }
    }

    private fun handle(payload: String, execute: Boolean) {
        val o = try { JSONObject(payload) } catch (e: Exception) { return }
        if (o.optString("path") != "/") return
        val cmd = o.optJSONObject("data") ?: return
        val id = cmd.optString("id")
        if (id.isEmpty() || id == lastCmd) return
        lastCmd = id
        if (!execute) return
        main.post { doCommand(cmd) }
    }

    private fun doCommand(cmd: JSONObject) {
        when (cmd.optString("type")) {
            "point" -> overlay?.point(cmd.optDouble("x", -1.0), cmd.optDouble("y", -1.0))
            "clear" -> overlay?.clear()
            "home" -> launch(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), "inicio")
            "whatsapp" -> {
                val i = packageManager.getLaunchIntentForPackage("com.whatsapp")
                    ?: packageManager.getLaunchIntentForPackage("com.whatsapp.w4b")
                launch(i, "WhatsApp")
            }
            "mensajes" -> launch(Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_MESSAGING), "Mensajes")
            "correo" -> launch(Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_EMAIL), "Correo")
            "app" -> launch(
                Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT),
                "Paso a Paso"
            )
            "stop" -> shutdown(true)
        }
    }

    private fun launch(i: Intent?, name: String) {
        if (i == null) {
            Net.reportState(sid, "compartiendo", JSONObject().put("aviso", "No encontré $name en su teléfono"))
            return
        }
        try {
            startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            Net.reportState(sid, "compartiendo", JSONObject().put("aviso", "No se pudo abrir $name"))
        }
    }

    /* ---------- Notificación y cierre ---------- */

    private fun startForegroundNow(agent: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Pantalla compartida", NotificationManager.IMPORTANCE_LOW)
        )
        val stopPi = PendingIntent.getService(
            this, 1, Intent(this, ShareService::class.java).setAction(STOP), PendingIntent.FLAG_IMMUTABLE
        )
        val openPi = PendingIntent.getActivity(
            this, 2, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val n = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("$agent ve tu pantalla")
            .setContentText("Toca \"Terminar\" para dejar de compartir.")
            .setOngoing(true)
            .setContentIntent(openPi)
            .addAction(Notification.Action.Builder(null as Icon?, "Terminar", stopPi).build())
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(1, n)
        }
    }

    private fun shutdown(report: Boolean) {
        if (!stopped.compareAndSet(false, true)) return
        val wasSharing = alive
        alive = false
        running = false
        try { virtualDisplay?.release() } catch (e: Exception) { }
        try { reader?.close() } catch (e: Exception) { }
        try { projection?.stop() } catch (e: Exception) { }
        virtualDisplay = null; reader = null; projection = null
        main.post { overlay?.detach(); overlay = null }
        thread?.quitSafely()
        val id = sid
        if (Net.validId(id) && wasSharing) {
            Net.async {
                Net.send("DELETE", "sessions/$id/frame", null)
                if (report) Net.send("PATCH", "sessions/$id/client",
                    JSONObject().put("state", "terminado").put("t", Net.now()).toString())
            }
        }
        try { stopForeground(STOP_FOREGROUND_REMOVE) } catch (e: Exception) { }
        stopSelf()
    }

    override fun onDestroy() {
        shutdown(false)
        super.onDestroy()
    }
}
