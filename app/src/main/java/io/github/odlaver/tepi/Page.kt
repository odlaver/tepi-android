package io.github.odlaver.tepi

import android.app.Activity
import android.app.AlertDialog
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Build
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
        // Edge-to-edge needs Android 11
        if (Build.VERSION.SDK_INT >= 30) window.setDecorFitsSystemWindows(false)
        col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpi(24f), dpi(16f), dpi(24f), dpi(32f))
        }
        val scroll = ScrollView(this).apply { addView(col) }
        scroll.setOnApplyWindowInsetsListener { v, insets ->
            if (Build.VERSION.SDK_INT >= 30) {
                val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout() or WindowInsets.Type.ime())
                v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            } else {
                v.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop, insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
            }
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
        row.addView(text(title, 22f, ink, bold = true))
        col.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dpi(8f) })
    }

    protected fun label(s: String) = col.addView(text(s, 12f, sub).apply { setPadding(dpi(4f), dpi(20f), 0, dpi(6f)) })

    protected fun note(s: String) = col.addView(text(s, 12f, sub).apply { setPadding(dpi(4f), dpi(6f), dpi(4f), 0) })

    protected fun group(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = GradientDrawable().apply { setColor(fill); cornerRadius = dp(14f) }
        col.addView(this)
    }

    protected fun line(g: LinearLayout) {
        if (g.childCount == 0) return
        g.addView(View(this).apply { setBackgroundColor(withAlpha(ink, 24)) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, maxOf(1, dpi(0.5f))).apply { marginStart = dpi(14f) })
    }

    // Title, detail, optional trailing view; tappable when onClick set
    protected fun row(g: LinearLayout, title: String, detail: String? = null, trailing: View? = null, icon: Icon? = null, onClick: (() -> Unit)? = null): LinearLayout {
        line(g)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dpi(48f)
            setPadding(dpi(14f), dpi(10f), dpi(14f), dpi(10f))
        }
        if (icon != null) box.addView(glyph(icon), LinearLayout.LayoutParams(dpi(30f), dpi(30f)).apply { marginEnd = dpi(12f) })
        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(text(title, 14f, ink))
        if (!detail.isNullOrEmpty()) texts.addView(text(detail, 12f, sub).apply { setPadding(0, dpi(1f), 0, 0) })
        box.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dpi(12f) })
        if (trailing != null) box.addView(trailing)
        else if (onClick != null) box.addView(View(this).apply { background = IconDrawable(Icon.Chevron, sub, dpi(16f)) }, LinearLayout.LayoutParams(dpi(16f), dpi(16f)))
        if (onClick != null) {
            box.isFocusable = true
            box.background = ripple(null)
            box.setOnClickListener { onClick() }
        }
        g.addView(box)
        return box
    }

    // Row that opens a list of choices
    protected fun <T> choice(g: LinearLayout, title: String, options: List<Pair<T, String>>, current: T, icon: Icon? = null, set: (T) -> Unit) {
        val shown = options.firstOrNull { it.first == current }?.second ?: ""
        val value = text(shown, 13f, sub)
        row(g, title, null, value, icon) {
            val labels = options.map { it.second }.toTypedArray()
            val idx = options.indexOfFirst { it.first == current }
            AlertDialog.Builder(this).setTitle(title).setSingleChoiceItems(labels, idx) { d, which ->
                set(options[which].first)
                d.dismiss()
                rebuild()
            }.setNegativeButton(R.string.cancel, null).show()
        }
    }

    protected fun toggle(g: LinearLayout, title: String, detail: String?, on: Boolean, enabled: Boolean = true, icon: Icon? = null, set: (Boolean) -> Unit): Switch {
        line(g)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpi(14f), dpi(4f), dpi(14f), if (detail.isNullOrEmpty()) dpi(4f) else dpi(10f))
        }
        val sw = Switch(this).apply {
            text = title
            textSize = 14f
            if (icon != null) {
                setCompoundDrawablesRelative(IconDrawable(icon, ink, dpi(30f), fill, dp(8f)).apply { setBounds(0, 0, dpi(30f), dpi(30f)) }, null, null, null)
                compoundDrawablePadding = dpi(12f)
            }
            setTextColor(ink)
            isChecked = on
            isEnabled = enabled
            minHeight = dpi(44f)
            val states = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
            thumbTintList = ColorStateList(states, intArrayOf(ink, blend(ink, paper, 0.55f)))
            trackTintList = ColorStateList(states, intArrayOf(ink, withAlpha(ink, 150)))
            setOnCheckedChangeListener { _, v -> set(v) }
        }
        box.addView(sw)
        if (!detail.isNullOrEmpty()) box.addView(text(detail, 12f, sub).apply { setPadding(if (icon != null) dpi(42f) else 0, 0, dpi(60f), 0) })
        g.addView(box)
        return sw
    }

    protected fun slider(g: LinearLayout, title: String, min: Int, max: Int, value: Int, fmt: (Int) -> String, icon: Icon? = null, changed: (Int) -> Unit) {
        line(g)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpi(14f), dpi(12f), dpi(14f), dpi(4f))
        }
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        if (icon != null) top.addView(glyph(icon), LinearLayout.LayoutParams(dpi(30f), dpi(30f)).apply { marginEnd = dpi(12f) })
        top.addView(text(title, 14f, ink), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val v = text(fmt(value), 13f, sub).apply { fontFeatureSettings = "tnum" }
        top.addView(v)
        box.addView(top)
        box.addView(SeekBar(this).apply {
            this.min = min
            this.max = max
            progress = value
            contentDescription = title
            setPadding(0, dpi(10f), 0, dpi(10f))
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
    protected fun button(label: String, primary: Boolean = true, icon: Icon? = null, onClick: () -> Unit): TextView = text(label, 13f, if (primary) paper else ink, bold = true).apply {
        gravity = Gravity.CENTER
        minHeight = dpi(36f)
        minWidth = dpi(64f)
        setPadding(dpi(14f), 0, dpi(14f), 0)
        if (icon != null) {
            setCompoundDrawablesRelative(IconDrawable(icon, if (primary) paper else ink, dpi(16f)).apply { setBounds(0, 0, dpi(16f), dpi(16f)) }, null, null, null)
            compoundDrawablePadding = dpi(6f)
        }
        isFocusable = true
        background = ripple(GradientDrawable().apply { setColor(if (primary) ink else fill); cornerRadius = dp(10f) })
        setOnClickListener { onClick() }
    }

    // Icon on a small rounded tile
    protected fun glyph(icon: Icon) = View(this).apply { background = IconDrawable(icon, ink, dpi(30f), fill, dp(8f)) }

    protected fun field(hint: String, secret: Boolean = false, value: String = "") = android.widget.EditText(this).apply {
        this.hint = hint
        setText(value)
        textSize = 14f
        setTextColor(ink)
        setHintTextColor(withAlpha(ink, 110))
        setSingleLine()
        inputType = if (secret) android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        else android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI
        background = GradientDrawable().apply { setColor(fill); cornerRadius = dp(10f) }
        setPadding(dpi(12f), dpi(10f), dpi(12f), dpi(10f))
    }

    // Field plus button under a group
    protected fun form(g: LinearLayout, vararg views: View) {
        line(g)
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dpi(14f), dpi(12f), dpi(14f), dpi(12f)) }
        views.forEachIndexed { i, v ->
            box.addView(v, LinearLayout.LayoutParams(if (v is android.widget.EditText) ViewGroup.LayoutParams.MATCH_PARENT else ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                if (i > 0) topMargin = dpi(8f)
            })
        }
        g.addView(box)
    }

    // Runs off the main thread, result back on it
    protected fun <T> work(job: () -> T, done: (T) -> Unit) {
        Thread { val r = job(); runOnUiThread { if (!isFinishing) done(r) } }.start()
    }

    protected fun toast(msg: String) = android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_SHORT).show()

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
