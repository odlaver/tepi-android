package io.github.odlaver.tepi

import android.content.ComponentName
import android.content.Intent
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
import android.widget.TextView

class MainActivity : Page() {
    private val main = Handler(Looper.getMainLooper())
    private var resumedAt = 0L
    private var lastState = ""
    private lateinit var status: TextView
    private lateinit var statusDot: View

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
    private fun blocked() = overlay() && access() && !Listener.connected && SystemClock.uptimeMillis() - resumedAt > 5000

    private fun state() = "${overlay()}${access()}${a11y()}${Listener.connected}${blocked()}${Oem.batteryFree(this)}"

    override fun build() {
        // Name large, version small
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.BOTTOM }
        head.addView(text(getString(R.string.app_name), 34f, ink, bold = true))
        head.addView(text(BuildConfig.VERSION_NAME, 15f, sub).apply { setPadding(dpi(8f), 0, 0, dpi(6f)) })
        col.addView(head, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dpi(12f) })

        val statusRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dpi(6f), 0, 0) }
        statusDot = View(this)
        statusRow.addView(statusDot, LinearLayout.LayoutParams(dpi(8f), dpi(8f)).apply { marginEnd = dpi(8f) })
        status = text("", 14f, sub)
        statusRow.addView(status)
        col.addView(statusRow)
        val running = overlay() && access() && Listener.connected
        status.setText(when {
            running -> R.string.status_running
            blocked() -> R.string.status_blocked
            overlay() && access() -> R.string.status_waiting
            else -> R.string.status_missing
        })
        statusDot.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            if (running) setColor(ink) else setStroke(dpi(1.5f), sub)
        }

        steps()
        island()
        activities()
        productivity()
        about()
    }

    private fun done(ok: Boolean, label: Int, action: () -> Unit): View =
        if (ok) text(getString(R.string.granted), 14f, sub) else button(getString(label)) { action() }

    private fun steps() {
        label(getString(R.string.section_setup))
        val g = group()
        row(g, "1  " + getString(R.string.perm_overlay), getString(R.string.perm_overlay_sub), done(overlay(), R.string.grant) {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        })
        row(g, "2  " + getString(R.string.perm_listener), getString(R.string.perm_listener_sub), done(access(), R.string.grant) { openListenerSettings() })
        if (!access() && Build.VERSION.SDK_INT >= 33) restricted(g)
        if (Oem.hasAutostart) {
            val detail = getString(if (blocked()) R.string.autostart_blocked else R.string.autostart_sub, Oem.name)
            row(g, "3  " + getString(R.string.autostart), detail, button(getString(R.string.open), !Listener.connected) { Oem.openAutostart(this) })
        }
        row(g, (if (Oem.hasAutostart) "4  " else "3  ") + getString(R.string.battery), getString(R.string.battery_sub),
            done(Oem.batteryFree(this), R.string.grant) { Oem.askBattery(this) })
        row(g, getString(R.string.perm_access), getString(R.string.perm_access_sub), done(a11y(), R.string.turn_on) {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        })
        if (!a11y() && Build.VERSION.SDK_INT >= 33) restricted(g)
    }

    private fun restricted(g: LinearLayout) {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpi(16f), 0, dpi(16f), dpi(14f))
        }
        box.addView(text(getString(R.string.restricted), 13f, sub))
        box.addView(button(getString(R.string.app_info), false) { Oem.appInfo(this) },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dpi(10f) })
        g.addView(box)
    }

    private fun island() {
        label(getString(R.string.section_island))
        val g = group()
        toggle(g, getString(R.string.camera_tap), getString(if (a11y()) R.string.camera_tap_sub else R.string.camera_tap_off), prefs.cameraTap, a11y()) {
            prefs.cameraTap = it; changed()
        }
        slider(g, getString(R.string.offset), Prefs.OFFSET_MIN, Prefs.OFFSET_MAX, prefs.offsetDp, { (if (it > 0) "+$it" else "$it") + " dp" }) {
            prefs.offsetDp = it
            changed()
            Listener.instance?.test()
        }
        row(g, getString(R.string.triggers), getString(R.string.triggers_sub))
        addButton(button(getString(R.string.test)) { Listener.instance?.test() })
    }

    private fun activities() {
        label(getString(R.string.section_activities))
        val g = group()
        toggle(g, getString(R.string.music), getString(R.string.music_sub), prefs.music) { prefs.music = it; changed() }
        toggle(g, getString(R.string.lyrics), getString(R.string.lyrics_sub), prefs.lyrics) { prefs.lyrics = it; changed() }
        toggle(g, getString(R.string.lyrics_online), getString(R.string.lyrics_online_sub), prefs.lyricsOnline) { prefs.lyricsOnline = it; changed() }
        toggle(g, getString(R.string.notifs), getString(R.string.notifs_sub), prefs.notifs) { prefs.notifs = it }
        toggle(g, getString(R.string.notif_content), getString(R.string.notif_content_sub), prefs.notifContent) { prefs.notifContent = it }
        for ((key, title, detail) in listOf(
            Triple("act.charge", R.string.act_charge, R.string.act_charge_sub),
            Triple("act.call", R.string.act_call, R.string.act_call_sub),
            Triple("act.progress", R.string.act_progress, R.string.act_progress_sub),
            Triple("act.nav", R.string.act_nav, R.string.act_nav_sub),
            Triple("act.ringer", R.string.act_ringer, R.string.act_ringer_sub),
            Triple("act.audio", R.string.act_audio, R.string.act_audio_sub),
            Triple("act.privacy", R.string.act_privacy, R.string.act_privacy_sub),
        )) toggle(g, getString(title), getString(detail), prefs.bool(key, true)) { prefs.set(key, it); changed() }
    }

    private fun productivity() {
        label(getString(R.string.section_productivity))
        val g = group()
        choice(g, getString(R.string.focus_break), listOf(5, 10, 15, 20).map { it to getString(R.string.t_min_n, it) }, prefs.int("focus.break", 5)) { prefs.set("focus.break", it) }
        row(g, getString(R.string.notes), getString(R.string.notes_sub)) { startActivity(Intent(this, Items::class.java).putExtra(Items.MODE, Items.NOTES)) }
        row(g, getString(R.string.shelf), getString(R.string.shelf_sub)) { startActivity(Intent(this, Items::class.java).putExtra(Items.MODE, Items.SHELF)) }

        label(getString(R.string.section_reminders))
        val r = group()
        toggle(r, getString(R.string.pray), getString(R.string.pray_sub), prefs.bool("pray", false)) { prefs.set("pray", it); rebuild() }
        if (prefs.bool("pray", false)) {
            choice(r, getString(R.string.pray_city), Prayer.cities.map { it.name to it.name }, prefs.str("pray.city", "")) { prefs.set("pray.city", it) }
            choice(r, getString(R.string.pray_before), listOf(0, 5, 10, 15).map { it to if (it == 0) getString(R.string.off) else getString(R.string.t_min_n, it) },
                prefs.int("pray.before", 10)) { prefs.set("pray.before", it) }
        }
        toggle(r, getString(R.string.drink), getString(R.string.drink_sub), prefs.bool("drink", false)) { prefs.set("drink", it); rebuild() }
        if (prefs.bool("drink", false)) {
            choice(r, getString(R.string.every), listOf(30, 45, 60, 90).map { it to getString(R.string.t_min_n, it) }, prefs.int("drink.every", 60)) { prefs.set("drink.every", it) }
            choice(r, getString(R.string.drink_goal), listOf(6, 8, 10, 12).map { it to getString(R.string.glasses, it) }, prefs.int("drink.goal", 8)) { prefs.set("drink.goal", it) }
        }
        toggle(r, getString(R.string.eyes), getString(R.string.eyes_sub), prefs.bool("eyes", false)) { prefs.set("eyes", it) }
        toggle(r, getString(R.string.stretch), getString(R.string.stretch_sub), prefs.bool("stretch", false)) { prefs.set("stretch", it); rebuild() }
        if (prefs.bool("stretch", false)) {
            choice(r, getString(R.string.every), listOf(30, 60, 90).map { it to getString(R.string.t_min_n, it) }, prefs.int("stretch.every", 60)) { prefs.set("stretch.every", it) }
        }
    }

    private fun about() {
        label(getString(R.string.section_about))
        val g = group()
        row(g, getString(R.string.reads), getString(R.string.reads_sub)) { startActivity(Intent(this, Reads::class.java)) }
        row(g, getString(R.string.publisher), getString(R.string.publisher_name))
        row(g, getString(R.string.source), "github.com/odlaver/tepi") {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/odlaver/tepi")))
        }
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
}
