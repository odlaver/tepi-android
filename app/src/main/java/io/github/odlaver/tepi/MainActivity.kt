package io.github.odlaver.tepi

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout

// The island's own pill, as on the Windows tray icon
class Mark(context: Context) : View(context) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val r = RectF()
    override fun onDraw(c: Canvas) {
        val h = height.toFloat()
        val w = width.toFloat()
        p.color = Color.argb(60, 255, 255, 255)
        r.set(0f, 0f, w, h)
        c.drawRoundRect(r, h / 2, h / 2, p)
        p.color = Color.BLACK
        r.inset(h * 0.08f, h * 0.08f)
        c.drawRoundRect(r, r.height() / 2, r.height() / 2, p)
        p.color = Color.WHITE
        c.drawCircle(w - h / 2, h / 2, h * 0.22f, p)
    }
}

data class Quad<A, B, C, D>(val a: A, val b: B, val c: C, val d: D)

class MainActivity : Page() {
    private val main = Handler(Looper.getMainLooper())
    private var resumedAt = 0L
    private var lastState = ""

    override fun onResume() {
        super.onResume()
        resumedAt = SystemClock.uptimeMillis()
        // Access just granted: ask Android to bind
        if (Listener.hasAccess(this) && !Listener.connected) {
            NotificationListenerService.requestRebind(ComponentName(this, Listener::class.java))
        }
        Listener.instance?.ensureIsland()
        lastState = ""
        poll.run()
    }

    override fun onPause() {
        main.removeCallbacks(poll)
        super.onPause()
    }

    // Rebuild only when a permission state moved
    private val poll = object : Runnable {
        override fun run() {
            val s = state()
            if (s != lastState) { lastState = s; rebuild() }
            main.postDelayed(this, 1000)
        }
    }

    private fun overlay() = Settings.canDrawOverlays(this)
    private fun access() = Listener.hasAccess(this)
    private fun a11y() = Access.instance != null
    private fun canDraw() = overlay() || a11y()
    private fun blocked() = canDraw() && access() && !Listener.connected && SystemClock.uptimeMillis() - resumedAt > 5000
    private fun running() = canDraw() && access() && Listener.connected
    private fun state() = "${overlay()}${access()}${a11y()}${Listener.connected}${blocked()}${Oem.batteryFree(this)}"

    override fun build() {
        hero()
        steps()
        label(getString(R.string.section_settings))
        val g = group()
        for ((mode, title, detail, icon) in listOf(
            Quad(Section.ISLAND, R.string.section_island, R.string.sec_island_sub, Icon.Layers),
            Quad(Section.ACTIVITIES, R.string.section_activities, R.string.sec_activities_sub, Icon.Spark),
            Quad(Section.PRODUCTIVITY, R.string.section_productivity, R.string.sec_productivity_sub, Icon.Timer),
            Quad(Section.REMINDERS, R.string.section_reminders, R.string.sec_reminders_sub, Icon.Bell),
            Quad(Section.CONNECT, R.string.section_connect, R.string.sec_connect_sub, Icon.Link),
            Quad(Section.ABOUT, R.string.section_about, R.string.sec_about_sub, Icon.Info),
        )) row(g, getString(title), getString(detail), icon = icon) { startActivity(Intent(this, Section::class.java).putExtra(Section.MODE, mode)) }
        coffee()
    }

    // Black card in the island's shape language
    private fun hero() {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpi(18f), dpi(18f), dpi(18f), dpi(16f))
            // Hairline keeps it visible on a black page
            background = GradientDrawable().apply { setColor(Color.BLACK); cornerRadius = dp(22f); setStroke(dpi(1f), Color.argb(40, 255, 255, 255)) }
        }
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        top.addView(Mark(this), LinearLayout.LayoutParams(dpi(44f), dpi(22f)).apply { marginEnd = dpi(12f) })
        top.addView(text(getString(R.string.app_name), 22f, Color.WHITE, bold = true))
        top.addView(text(BuildConfig.VERSION_NAME, 12f, Color.argb(150, 255, 255, 255)).apply { setPadding(dpi(6f), dpi(6f), 0, 0) })
        card.addView(top)
        val run = running()
        val statusRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dpi(12f), 0, 0) }
        statusRow.addView(View(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                if (run) setColor(Color.rgb(52, 199, 89)) else setStroke(dpi(1.5f), Color.argb(150, 255, 255, 255))
            }
        }, LinearLayout.LayoutParams(dpi(8f), dpi(8f)).apply { marginEnd = dpi(8f) })
        statusRow.addView(text(getString(when {
            run -> R.string.status_running
            blocked() -> R.string.status_blocked
            canDraw() && access() -> R.string.status_waiting
            else -> R.string.status_missing
        }), 13f, Color.argb(200, 255, 255, 255)), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        if (run) statusRow.addView(text(getString(R.string.test), 12f, Color.BLACK, bold = true).apply {
            gravity = Gravity.CENTER
            minHeight = dpi(30f)
            setPadding(dpi(12f), 0, dpi(12f), 0)
            background = ripple(GradientDrawable().apply { setColor(Color.WHITE); cornerRadius = dp(15f) })
            isFocusable = true
            setOnClickListener { Listener.instance?.test() }
        })
        card.addView(statusRow)
        col.addView(card, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dpi(12f) })
    }

    private fun done(ok: Boolean, label: Int, action: () -> Unit): View =
        if (ok) View(this).apply { background = IconDrawable(Icon.Check, ink, dpi(18f)); layoutParams = LinearLayout.LayoutParams(dpi(18f), dpi(18f)) }
        else button(getString(label)) { action() }

    private fun steps() {
        val need = listOf(overlay(), access(), Oem.batteryFree(this), a11y()).count { !it } + if (Oem.hasAutostart && blocked()) 1 else 0
        label(if (need == 0) getString(R.string.steps_done) else resources.getQuantityString(R.plurals.steps_left, need, need))
        val g = group()
        row(g, getString(R.string.perm_overlay), if (overlay()) null else getString(R.string.perm_overlay_sub), done(overlay(), R.string.grant) {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }, Icon.Layers)
        row(g, getString(R.string.perm_listener), if (access()) null else getString(R.string.perm_listener_sub), done(access(), R.string.grant) { openListenerSettings() }, Icon.Bell)
        if (!access() && Build.VERSION.SDK_INT >= 33) restricted(g)
        if (Oem.hasAutostart) {
            val detail = getString(if (blocked()) R.string.autostart_blocked else R.string.autostart_sub, Oem.name)
            row(g, getString(R.string.autostart), detail, button(getString(R.string.open), blocked()) { Oem.openAutostart(this) }, Icon.Power)
        }
        row(g, getString(R.string.battery), if (Oem.batteryFree(this)) null else getString(R.string.battery_sub),
            done(Oem.batteryFree(this), R.string.grant) { Oem.askBattery(this) }, Icon.Battery)
        row(g, getString(R.string.perm_access), if (a11y()) null else getString(R.string.perm_access_sub), done(a11y(), R.string.turn_on) {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }, Icon.Hand)
        if (!a11y() && Build.VERSION.SDK_INT >= 33) restricted(g)
    }

    private fun restricted(g: LinearLayout) {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dpi(56f), 0, dpi(14f), dpi(12f)) }
        box.addView(text(getString(R.string.restricted), 12f, sub))
        box.addView(button(getString(R.string.app_info), false) { Oem.appInfo(this) },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dpi(8f) })
        g.addView(box)
    }

    private fun coffee() {
        label(getString(R.string.support))
        val g = group()
        row(g, getString(R.string.coffee), getString(R.string.coffee_sub), icon = Icon.Coffee) {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(COFFEE)))
        }
        note(getString(R.string.made_by, getString(R.string.publisher_name)))
    }

    private fun openListenerSettings() {
        val me = ComponentName(this, Listener::class.java).flattenToString()
        try {
            if (Build.VERSION.SDK_INT >= 30) startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
                .putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, me))
            else startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        } catch (_: Exception) {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }
    }

    companion object {
        const val COFFEE = "https://odlaver.github.io/tepi/#kopi"
    }
}
