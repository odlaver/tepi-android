package io.github.odlaver.tepi

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.service.notification.NotificationListenerService
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

@Suppress("DEPRECATION")
class MainActivity : Activity() {
    private lateinit var prefs: Prefs
    private val main = Handler(Looper.getMainLooper())
    private var ink = 0
    private var paper = 0
    private var sub = 0
    private var fill = 0

    private lateinit var status: TextView
    private lateinit var statusDot: View
    private lateinit var overlayAction: TextView
    private lateinit var listenerAction: TextView
    private lateinit var accessAction: TextView
    private lateinit var restricted: View
    private lateinit var lyricsOnline: Switch
    private lateinit var notifContent: Switch
    private lateinit var offsetValue: TextView

    private fun dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics)
    private fun dpi(v: Float) = dp(v).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        ink = getColor(R.color.text)
        paper = getColor(R.color.bg)
        sub = withAlpha(ink, 158)
        fill = withAlpha(ink, if (Color.luminance(paper) > 0.5f) 14 else 22)
        window.setDecorFitsSystemWindows(false)
        setContentView(build())
    }

    override fun onResume() {
        super.onResume()
        // Access just granted: ask Android to bind
        if (Listener.hasAccess(this) && !Listener.connected) {
            NotificationListenerService.requestRebind(ComponentName(this, Listener::class.java))
        }
        Listener.instance?.ensureIsland()
        update()
        main.postDelayed(poll, 1000)
    }

    override fun onPause() {
        main.removeCallbacks(poll)
        super.onPause()
    }

    private val poll = object : Runnable {
        override fun run() {
            update()
            main.postDelayed(this, 1000)
        }
    }

    private fun build(): View {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpi(20f), dpi(28f), dpi(20f), dpi(28f))
        }

        // Name large, version small
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.BOTTOM }
        head.addView(text(getString(R.string.app_name), 34f, ink, bold = true))
        head.addView(text(BuildConfig.VERSION_NAME, 15f, sub).apply { setPadding(dpi(8f), 0, 0, dpi(6f)) })
        col.addView(head)

        val statusRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dpi(6f), 0, dpi(8f))
        }
        statusDot = View(this)
        statusRow.addView(statusDot, LinearLayout.LayoutParams(dpi(8f), dpi(8f)).apply { marginEnd = dpi(8f) })
        status = text("", 14f, sub)
        statusRow.addView(status)
        col.addView(statusRow)

        col.addView(label(getString(R.string.section_access)))
        val access = group()
        overlayAction = action(getString(R.string.grant)) {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }
        access.addView(row(getString(R.string.perm_overlay), getString(R.string.perm_overlay_sub), overlayAction))
        access.addView(line())
        listenerAction = action(getString(R.string.grant)) { openListenerSettings() }
        access.addView(row(getString(R.string.perm_listener), getString(R.string.perm_listener_sub), listenerAction))
        restricted = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpi(16f), 0, dpi(16f), dpi(14f))
            addView(text(getString(R.string.restricted), 13f, sub))
            addView(action(getString(R.string.app_info)) {
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dpi(10f) })
        }
        access.addView(restricted)
        access.addView(line())
        accessAction = action(getString(R.string.grant)) { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        access.addView(row(getString(R.string.perm_access), getString(R.string.perm_access_sub), accessAction))
        col.addView(access)

        col.addView(label(getString(R.string.section_island)))
        val island = group()
        lateinit var lyrics: Switch
        lyrics = toggle(island, getString(R.string.lyrics), getString(R.string.lyrics_sub), prefs.lyrics) {
            prefs.lyrics = it
            lyricsOnline.isEnabled = it
            changed()
        }
        island.addView(line())
        lyricsOnline = toggle(island, getString(R.string.lyrics_online), getString(R.string.lyrics_online_sub), prefs.lyricsOnline) {
            prefs.lyricsOnline = it
            changed()
        }
        lyricsOnline.isEnabled = lyrics.isChecked
        island.addView(line())
        toggle(island, getString(R.string.notifs), getString(R.string.notifs_sub), prefs.notifs) {
            prefs.notifs = it
            notifContent.isEnabled = it
        }
        island.addView(line())
        notifContent = toggle(island, getString(R.string.notif_content), getString(R.string.notif_content_sub), prefs.notifContent) {
            prefs.notifContent = it
        }
        notifContent.isEnabled = prefs.notifs
        island.addView(line())
        island.addView(offsetRow())
        col.addView(island)

        col.addView(action(getString(R.string.test)) { Listener.instance?.test() },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dpi(16f) })

        val source = text(getString(R.string.source), 14f, sub).apply {
            minHeight = dpi(48f)
            gravity = Gravity.CENTER_VERTICAL
            paintFlags = paintFlags or android.graphics.Paint.UNDERLINE_TEXT_FLAG
            isFocusable = true
            setOnClickListener { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/odlaver/tepi"))) }
        }
        col.addView(source, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dpi(20f) })

        val scroll = ScrollView(this).apply { addView(col) }
        scroll.setOnApplyWindowInsetsListener { v, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        return scroll
    }

    private fun update() {
        val overlay = Settings.canDrawOverlays(this)
        val access = Listener.hasAccess(this)
        setDone(overlayAction, overlay)
        setDone(listenerAction, access)
        setDone(accessAction, Access.instance != null)
        restricted.visibility = if (!access && Build.VERSION.SDK_INT >= 33) View.VISIBLE else View.GONE
        val canDraw = overlay || Access.instance != null
        val running = canDraw && access && Listener.connected
        status.setText(when {
            running -> R.string.status_running
            canDraw && access -> R.string.status_waiting
            else -> R.string.status_missing
        })
        statusDot.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            if (running) setColor(ink) else setStroke(dpi(1.5f), sub)
        }
    }

    private fun changed() {
        Listener.instance?.prefsChanged()
    }

    private fun openListenerSettings() {
        val me = ComponentName(this, Listener::class.java).flattenToString()
        val detail = Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
            .putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, me)
        try {
            if (Build.VERSION.SDK_INT >= 30) startActivity(detail) else startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        } catch (_: Exception) {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }
    }

    private fun offsetRow(): View {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpi(16f), dpi(14f), dpi(16f), dpi(10f))
        }
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        top.addView(text(getString(R.string.offset), 16f, ink), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        offsetValue = text(offsetText(prefs.offsetDp), 14f, sub).apply { fontFeatureSettings = "tnum" }
        top.addView(offsetValue)
        box.addView(top)
        val seek = SeekBar(this).apply {
            min = Prefs.OFFSET_MIN
            max = Prefs.OFFSET_MAX
            progress = prefs.offsetDp
            contentDescription = getString(R.string.offset)
            setPadding(0, dpi(12f), 0, dpi(12f))
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar, v: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    prefs.offsetDp = v
                    offsetValue.text = offsetText(v)
                    changed()
                    // Keep the island up while moving it
                    Listener.instance?.test()
                }
                override fun onStartTrackingTouch(s: SeekBar) { Listener.instance?.test() }
                override fun onStopTrackingTouch(s: SeekBar) = Unit
            })
        }
        box.addView(seek)
        return box
    }

    private fun offsetText(v: Int) = (if (v > 0) "+$v" else "$v") + " dp"

    private fun toggle(parent: LinearLayout, title: String, detail: String, on: Boolean, set: (Boolean) -> Unit): Switch {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpi(16f), dpi(10f), dpi(16f), dpi(14f))
        }
        val sw = Switch(this).apply {
            text = title
            textSize = 16f
            setTextColor(ink)
            isChecked = on
            minHeight = dpi(48f)
            // Platform track draws at 30%: ink thumb on
            val states = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
            thumbTintList = ColorStateList(states, intArrayOf(ink, blend(ink, paper, 0.55f)))
            trackTintList = ColorStateList(states, intArrayOf(ink, withAlpha(ink, 150)))
            setOnCheckedChangeListener { _, v -> set(v) }
        }
        box.addView(sw)
        box.addView(text(detail, 13f, sub).apply { setPadding(0, 0, dpi(64f), 0) })
        parent.addView(box)
        return sw
    }

    private fun row(title: String, detail: String, trailing: View): View {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dpi(16f), dpi(14f), dpi(16f), dpi(14f))
        }
        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(text(title, 16f, ink))
        texts.addView(text(detail, 13f, sub).apply { setPadding(0, dpi(2f), 0, 0) })
        box.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dpi(12f) })
        box.addView(trailing)
        return box
    }

    // Filled ink button; plain label once done
    private fun action(label: String, onClick: () -> Unit): TextView = text(label, 14f, paper, bold = true).apply {
        gravity = Gravity.CENTER
        minHeight = dpi(44f)
        minWidth = dpi(88f)
        setPadding(dpi(16f), 0, dpi(16f), 0)
        isFocusable = true
        background = button()
        setOnClickListener { onClick() }
    }

    private fun setDone(v: TextView, done: Boolean) {
        v.text = getString(if (done) R.string.granted else R.string.grant)
        v.isEnabled = !done
        v.setTextColor(if (done) sub else paper)
        v.background = if (done) null else button()
    }

    private fun button() = RippleDrawable(
        ColorStateList.valueOf(withAlpha(paper, 60)),
        GradientDrawable().apply { setColor(ink); cornerRadius = dp(10f) },
        null,
    )

    private fun group() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = GradientDrawable().apply { setColor(fill); cornerRadius = dp(16f) }
    }

    private fun line() = View(this).apply {
        setBackgroundColor(withAlpha(ink, 24))
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, maxOf(1, dpi(0.5f))).apply { marginStart = dpi(16f) }
    }

    private fun label(s: String) = text(s, 13f, sub).apply { setPadding(dpi(4f), dpi(22f), 0, dpi(8f)) }

    private fun text(s: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = s
        textSize = size
        setTextColor(color)
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    private fun withAlpha(c: Int, a: Int) = Color.argb(a, Color.red(c), Color.green(c), Color.blue(c))

    private fun blend(a: Int, b: Int, k: Float) = Color.rgb(
        (Color.red(a) * (1 - k) + Color.red(b) * k).toInt(),
        (Color.green(a) * (1 - k) + Color.green(b) * k).toInt(),
        (Color.blue(a) * (1 - k) + Color.blue(b) * k).toInt(),
    )
}
