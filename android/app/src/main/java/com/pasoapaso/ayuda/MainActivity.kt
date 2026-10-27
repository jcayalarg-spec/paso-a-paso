package com.pasoapaso.ayuda

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONObject

/**
 * Pantalla de la clienta. Letras enormes y un solo botón por pantalla.
 *  1. Toca el link del agente en WhatsApp -> se abre esta app con la sesión.
 *  2. Permiso "Mostrar sobre otras apps" (para el círculo).
 *  3. Permiso "Compartir pantalla".
 *  4. Conectada: el agente la ve y le señala dónde tocar.
 */
class MainActivity : Activity() {

    private val REQ_CAPTURE = 7
    private lateinit var root: LinearLayout
    private val ui = Handler(Looper.getMainLooper())
    private var sessionId: String? = null
    private var agentName: String = "tu agente"
    private var lastReported: String? = null

    private val INK = Color.rgb(16, 40, 47)
    private val SUN = Color.rgb(255, 210, 63)
    private val SUN_INK = Color.rgb(43, 34, 0)
    private val BRAND = Color.rgb(11, 110, 117)
    private val RED = Color.rgb(179, 38, 30)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        agentName = Prefs.agent(this) ?: agentName
        readLink(intent)
        val scroll = ScrollView(this)
        scroll.fitsSystemWindows = true
        scroll.setBackgroundColor(Color.rgb(243, 247, 248))
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val p = dp(24)
            setPadding(p, p, p, p)
        }
        scroll.addView(root)
        setContentView(scroll)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        readLink(intent)
        render()
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun readLink(i: Intent?) {
        val u: Uri = i?.data ?: return
        if (u.scheme == "pasoapaso" && u.host == "s") {
            val id = u.lastPathSegment
            if (Net.validId(id)) {
                if (id != Prefs.session(this)) lastReported = null
                sessionId = id
                Prefs.setSession(this, id)
            }
        }
    }

    /* ---------- Pantallas ---------- */

    private fun render() {
        root.removeAllViews()
        if (!Net.configured) {
            title("Falta configurar")
            body("Esta copia de la app no tiene la dirección de la base de datos. Pídele a tu agente una versión nueva.")
            return
        }
        val id = sessionId ?: Prefs.session(this)
        if (id == null) {
            title("Paso a Paso Ayuda")
            body("Pídele a tu agente que te mande el link por WhatsApp.")
            body("Cuando lo toques, esta app se abre sola.")
            return
        }
        sessionId = id
        loadAgentName(id)

        if (ShareService.running) {
            label("Conectada")
            title("$agentName ve tu pantalla")
            body("Te va a señalar dónde tocar con un círculo amarillo.")
            body("Ya puedes salir de aquí. Sigue escuchando a $agentName por teléfono.")
            bigButton("Terminar", RED, Color.WHITE) { stopSharing() }
            return
        }

        if (!Settings.canDrawOverlays(this)) {
            report(id, "permiso_superposicion")
            label("Paso 1 de 2")
            title("Hola. Vamos a conectarte con $agentName")
            body("Toca el botón amarillo. Busca \"Paso a Paso Ayuda\" y prende el interruptor.")
            body("Después vuelve aquí.")
            bigButton("Dar permiso", SUN, SUN_INK) { openOverlaySettings() }
        } else {
            report(id, "permiso_pantalla")
            label("Paso 2 de 2")
            title("Comparte tu pantalla con $agentName")
            body("Toca el botón amarillo. Después toca \"Empezar\" o \"Iniciar ahora\".")
            bigButton("Compartir mi pantalla", SUN, SUN_INK) { askCapture() }
        }
    }

    private fun report(id: String, state: String) {
        if (lastReported == state) return
        lastReported = state
        Net.reportState(id, state)
    }

    private fun loadAgentName(id: String) {
        Net.async {
            val raw = Net.send("GET", "sessions/$id/agent", null) ?: return@async
            val name = try { JSONObject(raw).optString("name") } catch (e: Exception) { "" }
            val first = name.trim().split(Regex("\\s+")).firstOrNull().orEmpty()
            if (first.isNotEmpty() && first != agentName) {
                ui.post {
                    agentName = first
                    Prefs.setAgent(this, first)
                    render()
                }
            }
        }
    }

    /* ---------- Permisos ---------- */

    private fun openOverlaySettings() {
        val i = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
        try { startActivity(i) } catch (e: Exception) {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION))
        }
    }

    private fun askCapture() {
        val mpm = getSystemService(MediaProjectionManager::class.java)
        // En Android 14 o más nuevo pedimos la pantalla completa (sin la opción de "una sola app").
        val i = if (Build.VERSION.SDK_INT >= 34) {
            mpm.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
        } else {
            mpm.createScreenCaptureIntent()
        }
        @Suppress("DEPRECATION")
        startActivityForResult(i, REQ_CAPTURE)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_CAPTURE) return
        val id = sessionId ?: return
        if (resultCode == RESULT_OK && data != null) {
            val s = Intent(this, ShareService::class.java)
                .putExtra(ShareService.EXTRA_CODE, resultCode)
                .putExtra(ShareService.EXTRA_DATA, data)
                .putExtra(ShareService.EXTRA_SID, id)
                .putExtra(ShareService.EXTRA_AGENT, agentName)
            startForegroundService(s)
            lastReported = null
            ui.postDelayed({ render() }, 600)
        } else {
            lastReported = "rechazo_pantalla"
            Net.reportState(id, "rechazo_pantalla")
        }
    }

    private fun stopSharing() {
        startService(Intent(this, ShareService::class.java).setAction(ShareService.STOP))
        ui.postDelayed({ render() }, 600)
    }

    /* ---------- Piezas de la pantalla ---------- */

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun label(t: String) = add(TextView(this).apply {
        text = t.uppercase()
        setTextColor(BRAND)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
        typeface = Typeface.DEFAULT_BOLD
        letterSpacing = 0.06f
    }, 8)

    private fun title(t: String) = add(TextView(this).apply {
        text = t
        setTextColor(INK)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 34f)
        typeface = Typeface.DEFAULT_BOLD
        setLineSpacing(0f, 1.1f)
    }, 12)

    private fun body(t: String) = add(TextView(this).apply {
        text = t
        setTextColor(INK)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 26f)
        setLineSpacing(0f, 1.2f)
    }, 16)

    private fun bigButton(t: String, bg: Int, fg: Int, onClick: () -> Unit) = add(Button(this).apply {
        text = t
        isAllCaps = false
        setTextColor(fg)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 28f)
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        minHeight = dp(96)
        stateListAnimator = null
        background = GradientDrawable().apply { setColor(bg); cornerRadius = dp(24).toFloat() }
        setOnClickListener { onClick() }
    }, 28)

    private fun add(v: android.view.View, topDp: Int) {
        val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        lp.topMargin = dp(topDp)
        root.addView(v, lp)
    }
}

