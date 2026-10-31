package io.github.odlaver.tepi

import android.app.Activity
import android.app.AlertDialog
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView

// Shared layout kit for every settings page
@Suppress("DEPRECATION")
abstract class Page : Activity() {
    protected lateinit var prefs: Prefs
    protected var ink = 0
    protected var paper = 0
    protected var sub = 0
    protected var fill = 0
    protected lateinit var col: LinearLayout

    protected fun dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics)
    protected fun dpi(v: Float) = dp(v).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        ink = getColor(R.color.text)
        paper = getColor(R.color.bg)
        sub = withAlpha(ink, 158)
        fill = withAlpha(ink, if (Color.luminance(paper) > 0.5f) 14 else 22)
        window.setDecorFitsSystemWindows(false)
        col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpi(24f), dpi(16f), dpi(24f), dpi(32f))
        }
        val scroll = ScrollView(this).apply { addView(col) }
        scroll.setOnApplyWindowInsetsListener { v, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout() or WindowInsets.Type.ime())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        setContentView(scroll)
        build()
    }

    protected abstract fun build()

    protected fun rebuild() {
        col.removeAllViews()
        build()
    }

    // Page title with a back control
    protected fun header(title: String, back: Boolean = true) {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        if (back) {
            row.addView(text("‹", 30f, ink).apply {
                gravity = Gravity.CENTER
                minWidth = dpi(48f); minHeight = dpi(48f)
                contentDescription = getString(R.string.back)
                isFocusable = true
                background = ripple(null)
                setOnClickListener { finish() }
            }, LinearLayout.LayoutParams(dpi(48f), dpi(48f)).apply { marginStart = -dpi(12f) })
        }
        row.addView(text(title, 26f, ink, bold = true))
        col.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dpi(8f) })
    }

    protected fun label(s: String) = col.addView(text(s, 13f, sub).apply { setPadding(dpi(4f), dpi(24f), 0, dpi(8f)) })

    protected fun note(s: String) = col.addView(text(s, 13f, sub).apply { setPadding(dpi(4f), dpi(8f), dpi(4f), 0) })

    protected fun group(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = GradientDrawable().apply { setColor(fill); cornerRadius = dp(16f) }
        col.addView(this)
    }

    protected fun line(g: LinearLayout) {
        if (g.childCount == 0) return
        g.addView(View(this).apply { setBackgroundColor(withAlpha(ink, 24)) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, maxOf(1, dpi(0.5f))).apply { marginStart = dpi(16f) })
    }

    // Title, detail, optional trailing view; tappable when onClick set
    protected fun row(g: LinearLayout, title: String, detail: String? = null, trailing: View? = null, onClick: (() -> Unit)? = null): LinearLayout {
        line(g)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dpi(56f)
            setPadding(dpi(16f), dpi(12f), dpi(16f), dpi(12f))
        }
        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(text(title, 16f, ink))
        if (!detail.isNullOrEmpty()) texts.addView(text(detail, 13f, sub).apply { setPadding(0, dpi(2f), 0, 0) })
        box.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dpi(12f) })
        if (trailing != null) box.addView(trailing)
        if (onClick != null) {
            box.isFocusable = true
            box.background = ripple(null)
            box.setOnClickListener { onClick() }
        }
        g.addView(box)
        return box
    }

    // Row that opens a list of choices
    protected fun <T> choice(g: LinearLayout, title: String, options: List<Pair<T, String>>, current: T, set: (T) -> Unit) {
        val shown = options.firstOrNull { it.first == current }?.second ?: ""
        val value = text(shown, 14f, sub)
        row(g, title, null, value) {
            val labels = options.map { it.second }.toTypedArray()
            val idx = options.indexOfFirst { it.first == current }
            AlertDialog.Builder(this).setTitle(title).setSingleChoiceItems(labels, idx) { d, which ->
                set(options[which].first)
                d.dismiss()
                rebuild()
            }.setNegativeButton(R.string.cancel, null).show()
        }
    }

    protected fun toggle(g: LinearLayout, title: String, detail: String?, on: Boolean, enabled: Boolean = true, set: (Boolean) -> Unit): Switch {
        line(g)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpi(16f), dpi(8f), dpi(16f), if (detail.isNullOrEmpty()) dpi(8f) else dpi(14f))
        }
        val sw = Switch(this).apply {
            text = title
            textSize = 16f
            setTextColor(ink)
            isChecked = on
            isEnabled = enabled
            minHeight = dpi(48f)
            val states = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
            thumbTintList = ColorStateList(states, intArrayOf(ink, blend(ink, paper, 0.55f)))
            trackTintList = ColorStateList(states, intArrayOf(ink, withAlpha(ink, 150)))
            setOnCheckedChangeListener { _, v -> set(v) }
        }
        box.addView(sw)
        if (!detail.isNullOrEmpty()) box.addView(text(detail, 13f, sub).apply { setPadding(0, 0, dpi(64f), 0) })
        g.addView(box)
        return sw
    }

    protected fun slider(g: LinearLayout, title: String, min: Int, max: Int, value: Int, fmt: (Int) -> String, changed: (Int) -> Unit) {
        line(g)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpi(16f), dpi(14f), dpi(16f), dpi(8f))
        }
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        top.addView(text(title, 16f, ink), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val v = text(fmt(value), 14f, sub).apply { fontFeatureSettings = "tnum" }
        top.addView(v)
        box.addView(top)
        box.addView(SeekBar(this).apply {
            this.min = min
            this.max = max
            progress = value
            contentDescription = title
            setPadding(0, dpi(12f), 0, dpi(12f))
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    v.text = fmt(p)
                    changed(p)
                }
                override fun onStartTrackingTouch(s: SeekBar) = Unit
                override fun onStopTrackingTouch(s: SeekBar) = Unit
            })
        })
        g.addView(box)
    }

    // Ink-filled button
    protected fun button(label: String, primary: Boolean = true, onClick: () -> Unit): TextView = text(label, 14f, if (primary) paper else ink, bold = true).apply {
        gravity = Gravity.CENTER
        minHeight = dpi(44f)
        minWidth = dpi(88f)
        setPadding(dpi(16f), 0, dpi(16f), 0)
        isFocusable = true
        background = ripple(GradientDrawable().apply { setColor(if (primary) ink else fill); cornerRadius = dp(12f) })
        setOnClickListener { onClick() }
    }

    protected fun addButton(view: View, top: Float = 16f) {
        col.addView(view, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dpi(top) })
    }

    protected fun ripple(content: GradientDrawable?) = RippleDrawable(ColorStateList.valueOf(withAlpha(ink, 40)), content,
        content ?: GradientDrawable().apply { setColor(Color.WHITE); cornerRadius = dp(16f) })

    protected fun text(s: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = s
        textSize = size
        setTextColor(color)
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    protected fun withAlpha(c: Int, a: Int) = Color.argb(a, Color.red(c), Color.green(c), Color.blue(c))

    protected fun blend(a: Int, b: Int, k: Float) = Color.rgb(
        (Color.red(a) * (1 - k) + Color.red(b) * k).toInt(),
        (Color.green(a) * (1 - k) + Color.green(b) * k).toInt(),
        (Color.blue(a) * (1 - k) + Color.blue(b) * k).toInt(),
    )

    protected fun changed() = Listener.instance?.prefsChanged()
}
