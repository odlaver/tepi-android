package io.github.odlaver.tepi

import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.util.TypedValue
import android.view.Choreographer
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.LinearLayout
import android.widget.TextView

// First-run tour, also from About
class Tour : Activity() {
    private lateinit var prefs: Prefs
    private lateinit var stage: Stage
    private lateinit var title: TextView
    private lateinit var body: TextView
    private lateinit var next: TextView
    private lateinit var choices: LinearLayout
    private lateinit var dots: LinearLayout
    private var page = 0

    private fun dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics)
    private fun dpi(v: Float) = dp(v).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        page = savedInstanceState?.getInt("page") ?: 0
        if (Build.VERSION.SDK_INT >= 30) window.setDecorFitsSystemWindows(false)
        window.statusBarColor = Color.BLACK
        window.navigationBarColor = Color.BLACK
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
            setPadding(dpi(28f), dpi(24f), dpi(28f), dpi(24f))
        }
        root.setOnApplyWindowInsetsListener { v, insets ->
            if (Build.VERSION.SDK_INT >= 30) {
                val b = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                v.setPadding(dpi(28f) + b.left, dpi(24f) + b.top, dpi(28f) + b.right, dpi(24f) + b.bottom)
            } else {
                @Suppress("DEPRECATION")
                v.setPadding(dpi(28f), dpi(24f) + insets.systemWindowInsetTop, dpi(28f), dpi(24f) + insets.systemWindowInsetBottom)
            }
            insets
        }
        stage = Stage(this)
        root.addView(stage, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        dots = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        root.addView(dots, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dpi(24f)))
        title = TextView(this).apply { textSize = 26f; setTextColor(Color.WHITE); typeface = Typeface.DEFAULT_BOLD; setPadding(0, dpi(16f), 0, dpi(8f)) }
        body = TextView(this).apply { textSize = 15f; setTextColor(Color.argb(200, 255, 255, 255)); setLineSpacing(0f, 1.2f) }
        root.addView(title)
        root.addView(body)
        choices = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dpi(12f), 0, 0) }
        root.addView(choices)
        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dpi(20f), 0, 0) }
        bar.addView(chip(getString(R.string.tour_skip), false) { finishTour() }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dpi(48f)))
        bar.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        next = chip(getString(R.string.tour_next), true) { if (page < LAST) show(page + 1) else finishTour() }
        bar.addView(next, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dpi(48f)))
        root.addView(bar)
        setContentView(root)
        if (Build.VERSION.SDK_INT >= 33) onBackInvokedDispatcher.registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT) { back() }
        show(page)
    }

    override fun onSaveInstanceState(out: Bundle) {
        super.onSaveInstanceState(out)
        out.putInt("page", page)
    }

    // Back steps through pages
    private fun back() { if (page > 0) show(page - 1) else finishTour() }

    @Deprecated("Back before Android 13")
    @Suppress("GestureBackNavigation")
    override fun onBackPressed() = back()

    private fun show(p: Int) {
        page = p
        title.text = getString(TITLES[p])
        body.text = getString(BODIES[p])
        next.text = getString(if (p == LAST) R.string.tour_done else R.string.tour_next)
        choices.removeAllViews()
        if (p == 2) for ((key, label) in listOf("pill" to R.string.idle_pill, "ring" to R.string.idle_ring, "hide" to R.string.idle_hide)) {
            choices.addView(option(getString(label), prefs.idleShape == key) {
                prefs.idleShape = key
                Listener.instance?.prefsChanged()
                show(2)
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dpi(8f) })
        }
        dots.removeAllViews()
        for (i in 0..LAST) dots.addView(View(this).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(if (i == p) Color.WHITE else Color.argb(80, 255, 255, 255)) }
        }, LinearLayout.LayoutParams(dpi(6f), dpi(6f)).apply { marginStart = dpi(4f); marginEnd = dpi(4f) })
        stage.go(p, prefs.idleShape)
        title.announceForAccessibility(title.text)
    }

    private fun finishTour() {
        prefs.set("tour.done", true)
        finish()
    }

    private fun chip(label: String, primary: Boolean, onClick: () -> Unit) = TextView(this).apply {
        text = label
        textSize = 15f
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        setTextColor(if (primary) Color.BLACK else Color.WHITE)
        setPadding(dpi(20f), 0, dpi(20f), 0)
        minWidth = dpi(64f)
        background = GradientDrawable().apply { setColor(if (primary) Color.WHITE else Color.TRANSPARENT); cornerRadius = dp(14f) }
        isFocusable = true
        setOnClickListener { onClick() }
    }

    private fun option(label: String, on: Boolean, onClick: () -> Unit) = TextView(this).apply {
        text = label
        textSize = 15f
        setTextColor(Color.WHITE)
        gravity = Gravity.CENTER_VERTICAL
        minHeight = dpi(48f)
        setPadding(dpi(16f), 0, dpi(16f), 0)
        background = GradientDrawable().apply {
            setColor(if (on) Color.argb(46, 255, 255, 255) else Color.TRANSPARENT)
            setStroke(dpi(1f), Color.argb(if (on) 200 else 70, 255, 255, 255))
            cornerRadius = dp(14f)
        }
        isFocusable = true
        contentDescription = label
        isSelected = on
        setOnClickListener { onClick() }
    }

    // Phone top with a moving island
    private class Stage(context: Context) : View(context) {
        private val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        private val black = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK }
        private val frame = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(28, 28, 30) }
        private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = Color.WHITE; strokeCap = Paint.Cap.ROUND }
        private val w = Spring(0f, 0.3f, 300f, 25f)
        private val h = Spring(0f, 0.3f, 300f, 25f)
        private val a = Spring(0f, 0.004f, 420f, 41f)
        private val r = RectF()
        private var page = 0
        private var idle = "pill"
        private var last = 0L
        private val d = context.resources.displayMetrics.density
        private val tick = Choreographer.FrameCallback { ns -> step(ns) }
        private var running = false

        fun go(p: Int, idleShape: String) {
            page = p
            idle = idleShape
            val (tw, th) = when (p) {
                0 -> 200f to 40f
                1 -> 300f to 150f
                2 -> if (idle == "pill") 84f to 34f else 26f to 26f
                3 -> 84f to 34f
                4 -> 200f to 40f
                else -> 84f to 34f
            }
            w.target = tw * d; h.target = th * d
            a.target = 0f
            a.snap(0f)
            if (!running) { running = true; last = 0; Choreographer.getInstance().postFrameCallback(tick) }
        }

        private fun step(ns: Long) {
            val dt = if (last == 0L) 1f / 60f else ((ns - last) / 1e9f).coerceIn(0f, 0.05f)
            last = ns
            var moving = w.step(dt) or h.step(dt)
            if (kotlin.math.abs(w.value - w.target) < w.target * 0.4f) a.target = 1f
            moving = a.step(dt) or moving
            invalidate()
            if (moving) Choreographer.getInstance().postFrameCallback(tick) else running = false
        }

        override fun onDetachedFromWindow() {
            Choreographer.getInstance().removeFrameCallback(tick)
            running = false
            super.onDetachedFromWindow()
        }

        override fun onDraw(c: Canvas) {
            val cx = width / 2f
            val top = height * 0.18f
            // Phone outline as context
            r.set(cx - 150 * d, top - 24 * d, cx + 150 * d, height.toFloat() + 40 * d)
            c.drawRoundRect(r, 36 * d, 36 * d, frame)
            val cam = 9 * d
            val camY = top + 4 * d
            val ww = kotlin.math.max(w.value, cam * 2)
            val hh = kotlin.math.max(h.value, cam * 2)
            r.set(cx - ww / 2, camY - cam - 4 * d, cx + ww / 2, camY - cam - 4 * d + hh)
            val showBody = !(page == 2 && idle != "pill")
            if (showBody) c.drawRoundRect(r, kotlin.math.min(hh / 2, 24 * d), kotlin.math.min(hh / 2, 24 * d), black)
            c.drawCircle(cx, camY, cam * 0.62f, frame)
            if (page == 2 && idle == "ring") {
                stroke.strokeWidth = 3 * d
                r.set(cx - cam - 5 * d, camY - cam - 5 * d, cx + cam + 5 * d, camY + cam + 5 * d)
                c.drawArc(r, -90f, 260f, false, stroke)
            }
            if (page == 3) {
                stroke.strokeWidth = 1.5f * d
                c.drawCircle(cx, camY, cam + 2 * d, stroke)
            }
            val al = (a.value.coerceIn(0f, 1f) * 255).toInt()
            ink.alpha = al
            when (page) {
                0, 4 -> {
                    r.set(cx - ww / 2 + 10 * d, camY - 11 * d, cx - ww / 2 + 32 * d, camY + 11 * d)
                    c.drawRoundRect(r, 6 * d, 6 * d, ink)
                    for (i in 0..3) {
                        val bh = (6 + (i * 5) % 12) * d
                        val x = cx + ww / 2 - 34 * d + i * 6 * d
                        r.set(x, camY - bh / 2, x + 3 * d, camY + bh / 2)
                        c.drawRoundRect(r, 1.5f * d, 1.5f * d, ink)
                    }
                }
                1 -> {
                    val y0 = camY + 26 * d
                    r.set(cx - 130 * d, y0, cx - 82 * d, y0 + 48 * d)
                    c.drawRoundRect(r, 10 * d, 10 * d, ink)
                    ink.alpha = al * 7 / 10
                    r.set(cx - 70 * d, y0 + 8 * d, cx + 40 * d, y0 + 18 * d); c.drawRoundRect(r, 5 * d, 5 * d, ink)
                    r.set(cx - 70 * d, y0 + 28 * d, cx + 10 * d, y0 + 36 * d); c.drawRoundRect(r, 4 * d, 4 * d, ink)
                    r.set(cx - 130 * d, y0 + 66 * d, cx + 130 * d, y0 + 70 * d); c.drawRoundRect(r, 2 * d, 2 * d, ink)
                }
                else -> Unit
            }
            ink.alpha = 255
        }
    }

    companion object {
        private const val LAST = 5
        private val TITLES = intArrayOf(R.string.tour1_title, R.string.tour2_title, R.string.tour3_title, R.string.tour4_title, R.string.tour5_title, R.string.tour6_title)
        private val BODIES = intArrayOf(R.string.tour1_body, R.string.tour2_body, R.string.tour3_body, R.string.tour4_body, R.string.tour5_body, R.string.tour6_body)
    }
}
