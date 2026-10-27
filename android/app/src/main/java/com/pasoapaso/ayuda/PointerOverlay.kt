package com.pasoapaso.ayuda

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.os.Build
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import kotlin.math.PI
import kotlin.math.sin

/**
 * Capa transparente encima de todas las apps. Muestra:
 *  - una etiqueta arriba ("Ana te está viendo"), para que ella sepa que comparte pantalla;
 *  - el círculo amarillo que parpadea donde el agente señala.
 * No recibe toques: los dedos de ella pasan a la app de abajo.
 */
class PointerOverlay(private val ctx: Context, agent: String) {
    private val wm = ctx.getSystemService(WindowManager::class.java)
    private val view = PointerView(ctx, agent)
    private var attached = false

    fun attach() {
        if (attached) return
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        lp.gravity = Gravity.TOP or Gravity.START
        // Android 12+ bloquea los toques que pasan a través de una capa más opaca que 0.8.
        lp.alpha = 0.8f
        if (Build.VERSION.SDK_INT >= 28) {
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        try {
            wm.addView(view, lp)
            attached = true
        } catch (e: Exception) {
        }
    }

    fun point(x: Double, y: Double) = view.point(x.toFloat(), y.toFloat())
    fun clear() = view.clear()

    fun detach() {
        if (!attached) return
        try { wm.removeView(view) } catch (e: Exception) { }
        attached = false
    }
}

private class PointerView(ctx: Context, agent: String) : View(ctx) {
    private val d = ctx.resources.displayMetrics.density
    private var px = -1f
    private var py = -1f
    private var until = 0L
    private val loc = IntArray(2)
    private val label = "$agent te está viendo"

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(90, 255, 210, 63) }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 9 * d; color = Color.rgb(255, 210, 63)
    }
    private val halo = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 4 * d; color = Color.rgb(11, 110, 117)
    }
    private val pillBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(11, 110, 117) }
    private val pillTx = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; textSize = 15 * d; typeface = Typeface.DEFAULT_BOLD; textAlign = Paint.Align.CENTER
    }

    /** x, y: posición de 0 a 1 sobre la pantalla completa. */
    fun point(x: Float, y: Float) {
        val (w, h) = Screen.real(context)
        getLocationOnScreen(loc)
        px = x * w - loc[0]
        py = y * h - loc[1]
        until = SystemClock.uptimeMillis() + 15000
        postInvalidateOnAnimation()
    }

    fun clear() {
        until = 0
        postInvalidate()
    }

    override fun onDraw(c: Canvas) {
        super.onDraw(c)
        val cx = width / 2f
        val tw = pillTx.measureText(label) + 36 * d
        c.drawRoundRect(cx - tw / 2, 44 * d, cx + tw / 2, 80 * d, 18 * d, 18 * d, pillBg)
        c.drawText(label, cx, 67 * d, pillTx)

        val now = SystemClock.uptimeMillis()
        if (now < until && px >= 0f && py >= 0f) {
            val phase = (now % 1200L) / 1200.0
            val r = (40 * d) + (12 * d) * sin(phase * 2 * PI).toFloat()
            c.drawCircle(px, py, r, fill)
            c.drawCircle(px, py, r, ring)
            c.drawCircle(px, py, r + 8 * d, halo)
            postInvalidateOnAnimation()
        }
    }
}
