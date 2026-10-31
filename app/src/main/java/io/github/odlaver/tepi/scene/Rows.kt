package io.github.odlaver.tepi.scene

import android.graphics.Canvas
import android.graphics.RectF
import io.github.odlaver.tepi.Notes
import io.github.odlaver.tepi.Prayer
import io.github.odlaver.tepi.R
import io.github.odlaver.tepi.Reminders
import io.github.odlaver.tepi.Shelf
import io.github.odlaver.tepi.ShelfItem
import io.github.odlaver.tepi.Timers
import java.text.DateFormat
import java.util.Date

// Two-line row: label above, value below
private fun pair(ui: io.github.odlaver.tepi.Ui, c: Canvas, r: RectF, label: String, value: String, a: Float, end: String? = null) {
    ui.text(c, label, ui.body, r.left, r.top + ui.dp(12f), r.width() - (if (end != null) ui.dp(90f) else 0f), a)
    ui.text(c, value, ui.label, r.left, r.top + ui.dp(30f), r.width() - (if (end != null) ui.dp(90f) else 0f), a)
    if (end != null) ui.textEnd(c, end, ui.body, r.right, r.top + ui.dp(30f), a)
}

class PrayerRow(private val host: Host, private val rem: Reminders) : HomeRow {
    private val fmt = DateFormat.getTimeInstance(DateFormat.SHORT)
    override fun visible() = rem.prayOn
    override fun height(w: Float) = host.ui.dp(38f)
    override fun draw(c: Canvas, r: RectF, a: Float) {
        val city = rem.city() ?: return
        val (i, at) = Prayer.next(city)
        val mins = ((at - System.currentTimeMillis()) / 60_000L).coerceAtLeast(0)
        val left = if (mins >= 60) host.ctx.getString(R.string.in_hm, mins / 60, mins % 60) else host.ctx.getString(R.string.in_m, mins)
        pair(host.ui, c, r, host.ctx.getString(R.string.next_prayer, city.name), "${Prayer.names[i]} ${fmt.format(Date(at))}", a, left)
    }
}

class TimerRow(private val host: Host, private val timers: Timers, private val scene: TimerScene) : HomeRow {
    override fun visible() = timers.active
    override fun height(w: Float) = host.ui.dp(38f)
    override fun draw(c: Canvas, r: RectF, a: Float) {
        pair(host.ui, c, r, scene.describe().substringBefore(' '), host.ui.clock(timers.shownMs()), a)
    }
    override fun tap(x: Float, y: Float): Boolean { host.expand(scene); return true }
}

class NoteRow(private val host: Host, private val notes: Notes, private val openAll: () -> Unit) : HomeRow {
    override fun visible() = notes.all().isNotEmpty()
    override fun height(w: Float) = host.ui.dp(38f)
    override fun draw(c: Canvas, r: RectF, a: Float) {
        val n = notes.all().first()
        pair(host.ui, c, r, host.ctx.getString(if (n.pinned) R.string.note_pinned else R.string.note_latest), n.text.replace('\n', ' '), a)
    }
    override fun tap(x: Float, y: Float): Boolean { openAll(); host.collapse(); return true }
}

// Up to three shelf items as chips
class ShelfRow(private val host: Host, private val shelf: Shelf, private val picked: (ShelfItem) -> Unit) : HomeRow {
    private val hits = ArrayList<Pair<RectF, ShelfItem>>()
    override fun visible() = shelf.all().isNotEmpty()
    override fun height(w: Float) = host.ui.dp(12f) + host.ui.s8 + host.ui.btnH
    override fun draw(c: Canvas, r: RectF, a: Float) {
        val ui = host.ui
        ui.text(c, host.ctx.getString(R.string.shelf), ui.body, r.left, r.top + ui.dp(12f), r.width(), a)
        val items = shelf.all().take(3)
        val gap = ui.s8
        val bw = (r.width() - gap * (items.size - 1)) / items.size
        val y = r.top + ui.dp(12f) + ui.s8
        hits.clear()
        items.forEachIndexed { i, it ->
            val br = RectF(r.left + i * (bw + gap), y, r.left + i * (bw + gap) + bw, y + ui.btnH)
            ui.fill(c, br, ui.radiusButton, ui.fillStrong, a)
            val label = if (it.isText) it.text!!.replace('\n', ' ') else it.name ?: "file"
            ui.centered(c, label, ui.bodyInk, br.centerX(), br.centerY(), br.width() - ui.s16, a)
            hits.add(br to it)
        }
    }
    override fun tap(x: Float, y: Float): Boolean {
        for ((r, it) in hits) if (r.contains(x, y)) { host.haptic(); picked(it); return true }
        return false
    }
}
