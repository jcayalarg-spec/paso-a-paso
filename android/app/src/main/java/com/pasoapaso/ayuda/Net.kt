package com.pasoapaso.ayuda

import android.content.Context
import android.os.Build
import android.util.DisplayMetrics
import android.view.WindowManager
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * Conexión con la base de datos de Firebase (Realtime Database) por su API web.
 * Sin librerías: así la app es pequeña y no necesita archivos extra de Google.
 */
object Net {
    val DB: String = BuildConfig.DB_URL.trimEnd('/')
    val configured: Boolean get() = DB.startsWith("https://")

    private val pool = Executors.newCachedThreadPool()

    fun validId(id: String?): Boolean =
        id != null && id.length in 20..64 && id.all { it.isLetterOrDigit() || it == '-' || it == '_' }

    fun url(path: String): String = "$DB/$path.json"

    /** method: PUT, PATCH, DELETE o GET. Devuelve el texto de la respuesta, o null si falló. */
    fun send(method: String, path: String, body: String?): String? {
        if (!configured) return null
        val conn = URL(url(path)).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 15000
            conn.readTimeout = 20000
            if (method == "PATCH") {
                conn.requestMethod = "POST"
                conn.setRequestProperty("X-HTTP-Method-Override", "PATCH")
            } else {
                conn.requestMethod = method
            }
            if (body != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val code = conn.responseCode
            if (code !in 200..299) return null
            return conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    fun async(block: () -> Unit) {
        pool.execute { try { block() } catch (e: Exception) { } }
    }

    fun now(): JSONObject = JSONObject().put(".sv", "timestamp")

    /** Le cuenta al panel del agente en qué paso va la clienta. */
    fun reportState(id: String, state: String, extra: JSONObject? = null) {
        if (!validId(id)) return
        async {
            val o = extra ?: JSONObject()
            o.put("state", state)
            if (o.has("aviso")) o.put("avisoT", now())
            o.put("model", "${Build.MANUFACTURER} ${Build.MODEL}")
            o.put("android", Build.VERSION.RELEASE)
            o.put("t", now())
            send("PATCH", "sessions/$id/client", o.toString())
        }
    }
}

object Prefs {
    private fun p(c: Context) = c.getSharedPreferences("pap", Context.MODE_PRIVATE)
    fun session(c: Context): String? = p(c).getString("sid", null)?.takeIf { Net.validId(it) }
    fun setSession(c: Context, id: String?) { p(c).edit().putString("sid", id).apply() }
    fun agent(c: Context): String? = p(c).getString("agent", null)
    fun setAgent(c: Context, name: String) { p(c).edit().putString("agent", name).apply() }
}

object Screen {
    /** Tamaño real de la pantalla en pixeles (ancho, alto). */
    fun real(c: Context): Pair<Int, Int> {
        val wm = c.getSystemService(WindowManager::class.java)
        return if (Build.VERSION.SDK_INT >= 30) {
            val b = wm.maximumWindowMetrics.bounds
            Pair(b.width(), b.height())
        } else {
            val m = DisplayMetrics()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealMetrics(m)
            Pair(m.widthPixels, m.heightPixels)
        }
    }
}
