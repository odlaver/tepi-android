package io.github.odlaver.tepi.scene

import android.graphics.Canvas
import android.graphics.RectF
import io.github.odlaver.tepi.R
import io.github.odlaver.tepi.Timers
import kotlin.math.min

// Running timer, or a picker when idle
class TimerScene(host: Host, private val timers: Timers) : Scene(host) {
    override val id = "timer"
    override val priority = 50
    override val order = 2
    private val tmp = RectF()
    private val hits = ArrayList<Pair<RectF, () -> Unit>>()

    override fun live() = timers.active
    override fun keepOpen() = true
    override fun tab() = true
    override fun ticking() = timers.active && !timers.paused

    private fun label(): String = host.ctx.getString(when (timers.kind) {
        Timers.Kind.Focus -> R.string.t_focus
        Timers.Kind.Break -> R.string.t_break
        Timers.Kind.Countdown -> R.string.t_timer
        Timers.Kind.Stopwatch -> R.string.t_stopwatch
        null -> R.string.t_timer
    })

    override fun wing() = ui.compact.measureText("00:00") + ui.dp(30f)

    override fun drawCompact(c: Canvas, left: RectF, right: RectF, a: Float) {
        val s = min(left.height(), ui.dp(22f))
        ui.ring(c, left.left + s / 2, left.centerY(), s / 2 - ui.dp(1.5f), ui.dp(3f), timers.fraction(), a)
        val text = ui.clock(timers.shownMs())
        val p = ui.compact
        val base = p.color
        if (timers.paused) p.color = ui.sub
        ui.textEnd(c, text, p, right.right, ui.baseline(p, right.centerY()), a)
        p.color = base
    }

    override fun drawBubble(c: Canvas, r: RectF, a: Float) {
        ui.ring(c, r.centerX(), r.centerY(), r.width() * 0.28f, ui.dp(3f), timers.fraction(), a)
    }

    override fun panelHeight(w: Float): Float {
        if (!timers.active) return ui.dp(18f) + ui.s8 + ui.btnH * 2 + ui.s8
        var h = ui.dp(18f) + ui.s8 + ui.dp(100f) + ui.s12 + ui.btnH
        if (timers.kind == Timers.Kind.Stopwatch && timers.laps.isNotEmpty()) h += ui.s8 + min(3, timers.laps.size) * ui.dp(20f)
        return h
    }

    override fun drawPanel(c: Canvas, r: RectF, a: Float) {
        hits.clear()
        if (!timers.active) { picker(c, r, a); return }
        val pausedNote = if (timers.paused) " · " + host.ctx.getString(R.string.t_paused) else ""
        ui.text(c, label() + pausedNote, ui.label, r.left, ui.baseline(ui.label, r.top + ui.dp(9f)), r.width(), a)
        val cy = r.top + ui.dp(18f) + ui.s8 + ui.dp(50f)
        ui.ring(c, r.centerX(), cy, ui.dp(46f), ui.dp(5f), timers.fraction(), a)
        ui.centered(c, ui.clock(timers.shownMs()), ui.big, r.centerX(), cy, ui.dp(86f), a)
        val y = cy + ui.dp(50f) + ui.s12
        val items = ArrayList<Triple<String, io.github.odlaver.tepi.Icon, () -> Unit>>()
        items.add(Triple(host.ctx.getString(if (timers.paused) R.string.t_resume else R.string.t_pause), if (timers.paused) io.github.odlaver.tepi.Icon.Play else io.github.odlaver.tepi.Icon.Pause) { timers.togglePause() })
        if (timers.kind == Timers.Kind.Stopwatch) items.add(Triple(host.ctx.getString(R.string.t_lap), io.github.odlaver.tepi.Icon.Flag) { timers.lap() })
        else items.add(Triple(host.ctx.getString(R.string.t_plus), io.github.odlaver.tepi.Icon.Plus) { timers.plusMinute() })
        items.add(Triple(host.ctx.getString(R.string.t_stop), io.github.odlaver.tepi.Icon.Stop) { timers.stop() })
        iconRow(c, r, y, items, a)
        if (timers.kind == Timers.Kind.Stopwatch && timers.laps.isNotEmpty()) {
            var ly = y + ui.btnH + ui.s8
            timers.laps.take(3).forEachIndexed { i, ms ->
                val n = timers.laps.size - i
                ui.text(c, host.ctx.getString(R.string.t_lap_n, n), ui.body, r.left, ly + ui.dp(14f), r.width() / 2, a)
                ui.textEnd(c, ui.clock(ms), ui.bodyInk, r.right, ly + ui.dp(14f), a)
                ly += ui.dp(20f)
            }
        }
    }

    private fun picker(c: Canvas, r: RectF, a: Float) {
        ui.text(c, host.ctx.getString(R.string.t_start), ui.label, r.left, ui.baseline(ui.label, r.top + ui.dp(9f)), r.width(), a)
        val y = r.top + ui.dp(18f) + ui.s8
        row(c, r, y, listOf(
            host.ctx.getString(R.string.t_focus_n, 25) to { timers.focus(25) },
            host.ctx.getString(R.string.t_focus_n, 50) to { timers.focus(50) },
            host.ctx.getString(R.string.t_stopwatch) to { timers.stopwatch() },
        ), a, primaryFirst = true)
        row(c, r, y + ui.btnH + ui.s8, listOf(5, 10, 15).map { m ->
            host.ctx.getString(R.string.t_min_n, m) to { timers.countdown(m) }
        }, a, primaryFirst = false)
    }

    private fun row(c: Canvas, r: RectF, y: Float, items: List<Pair<String, () -> Unit>>, a: Float, primaryFirst: Boolean) {
        val gap = ui.s8
        val bw = (r.width() - gap * (items.size - 1)) / items.size
        items.forEachIndexed { i, (label, act) ->
            val br = RectF(r.left + i * (bw + gap), y, r.left + i * (bw + gap) + bw, y + ui.btnH)
            ui.button(c, br, label, a, primaryFirst && i == 0, if (i == 0 && primaryFirst) io.github.odlaver.tepi.Icon.Timer else null)
            hits.add(br to act)
        }
    }

    // Icon chips: first one filled
    private fun iconRow(c: Canvas, r: RectF, y: Float, items: List<Triple<String, io.github.odlaver.tepi.Icon, () -> Unit>>, a: Float) {
        val gap = ui.s8
        val bw = (r.width() - gap * (items.size - 1)) / items.size
        items.forEachIndexed { i, (label, icon, act) ->
            val br = RectF(r.left + i * (bw + gap), y, r.left + i * (bw + gap) + bw, y + ui.btnH)
            ui.button(c, br, label, a, i == 0, icon)
            hits.add(br to act)
        }
    }

    override fun tap(x: Float, y: Float): Boolean {
        for ((r, act) in hits) if (r.contains(x, y)) { host.haptic(); act(); host.refresh(); return true }
        return false
    }

    override fun dismiss() {}

    override fun describe() = if (timers.active) "${label()} ${ui.clock(timers.shownMs())}" else ""
}

// Generic card: prayer, nudges, timer end, class
class Alert(
    val key: String,
    val title: String,
    val body: String,
    val glyph: Glyph = Glyph.Bell,
    val actions: List<Action> = emptyList(),
    val ms: Long = 6000,
    val onTap: (() -> Unit)? = null,
)

enum class Glyph { Bell, Moon, Drop, Eye, Stretch, Bolt, Book, Check }

class AlertScene(host: Host) : Scene(host) {
    override val id = "alert"
    override val priority = 85
    private val queue = ArrayList<Alert>()
    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    private val hits = ArrayList<Pair<RectF, Action>>()
    private val tmp = RectF()
    private val drop = Runnable { next() }

    fun show(al: Alert) {
        queue.removeAll { it.key == al.key }
        queue.add(al)
        if (queue.size == 1) arm()
        host.refresh()
    }

    private fun arm() {
        main.removeCallbacks(drop)
        queue.firstOrNull()?.let { main.postDelayed(drop, it.ms) }
    }

    private fun next() {
        if (queue.isNotEmpty()) queue.removeAt(0)
        arm()
        host.refresh()
    }

    override fun live() = queue.isNotEmpty()
    override fun card() = true
    override fun dismiss() = next()
    override fun drawCompact(c: Canvas, left: RectF, right: RectF, a: Float) {}

    override fun panelHeight(w: Float): Float {
        val al = queue.firstOrNull() ?: return 0f
        var h = ui.dp(36f)
        if (al.actions.isNotEmpty()) h += ui.s12 + ui.btnH
        return h
    }

    override fun drawPanel(c: Canvas, r: RectF, a: Float) {
        val al = queue.firstOrNull() ?: return
        val icon = ui.dp(36f)
        tmp.set(r.left, r.top, r.left + icon, r.top + icon)
        ui.fill(c, tmp, icon / 2, ui.fill, a)
        ui.solid.color = ui.alpha(ui.ink, a)
        drawGlyph(c, al.glyph, tmp.centerX(), tmp.centerY(), icon * 0.42f)
        val tx = r.left + icon + ui.s12
        ui.text(c, al.title, ui.title, tx, r.top + ui.dp(15f), r.right - tx, a)
        ui.text(c, al.body, ui.body, tx, r.top + ui.dp(32f), r.right - tx, a)
        hits.clear()
        if (al.actions.isEmpty()) return
        val y = r.top + icon + ui.s12
        val gap = ui.s8
        val bw = (r.width() - gap * (al.actions.size - 1)) / al.actions.size
        al.actions.forEachIndexed { i, act ->
            val br = RectF(r.left + i * (bw + gap), y, r.left + i * (bw + gap) + bw, y + ui.btnH)
            ui.button(c, br, act.label, a, act.primary, act.icon)
            hits.add(br to act)
        }
    }

    private fun drawGlyph(c: Canvas, g: Glyph, cx: Float, cy: Float, s: Float) {
        when (g) {
            Glyph.Bolt -> ui.bolt(c, cx, cy, s * 1.3f)
            Glyph.Check -> { ui.stroke.strokeWidth = s * 0.16f; ui.stroke.color = ui.solid.color; c.drawLine(cx - s * 0.4f, cy, cx - s * 0.1f, cy + s * 0.3f, ui.stroke); c.drawLine(cx - s * 0.1f, cy + s * 0.3f, cx + s * 0.45f, cy - s * 0.35f, ui.stroke) }
            Glyph.Moon -> { c.drawCircle(cx, cy, s * 0.48f, ui.solid); ui.solid.color = android.graphics.Color.argb(255, 40, 40, 42); c.drawCircle(cx + s * 0.22f, cy - s * 0.16f, s * 0.4f, ui.solid) }
            Glyph.Drop -> { val p = android.graphics.Path(); p.moveTo(cx, cy - s * 0.55f); p.cubicTo(cx + s * 0.55f, cy, cx + s * 0.45f, cy + s * 0.5f, cx, cy + s * 0.5f); p.cubicTo(cx - s * 0.45f, cy + s * 0.5f, cx - s * 0.55f, cy, cx, cy - s * 0.55f); c.drawPath(p, ui.solid) }
            Glyph.Eye -> { ui.stroke.strokeWidth = s * 0.12f; ui.stroke.color = ui.solid.color; tmp.set(cx - s * 0.55f, cy - s * 0.3f, cx + s * 0.55f, cy + s * 0.3f); c.drawOval(tmp, ui.stroke); c.drawCircle(cx, cy, s * 0.16f, ui.solid) }
            Glyph.Stretch -> { c.drawCircle(cx, cy - s * 0.4f, s * 0.13f, ui.solid); ui.stroke.strokeWidth = s * 0.13f; ui.stroke.color = ui.solid.color; c.drawLine(cx, cy - s * 0.2f, cx, cy + s * 0.2f, ui.stroke); c.drawLine(cx - s * 0.45f, cy - s * 0.3f, cx + s * 0.45f, cy - s * 0.3f, ui.stroke); c.drawLine(cx, cy + s * 0.2f, cx - s * 0.3f, cy + s * 0.55f, ui.stroke); c.drawLine(cx, cy + s * 0.2f, cx + s * 0.3f, cy + s * 0.55f, ui.stroke) }
            Glyph.Book -> { tmp.set(cx - s * 0.45f, cy - s * 0.4f, cx + s * 0.45f, cy + s * 0.4f); c.drawRoundRect(tmp, s * 0.08f, s * 0.08f, ui.solid) }
            Glyph.Bell -> { tmp.set(cx - s * 0.4f, cy - s * 0.45f, cx + s * 0.4f, cy + s * 0.3f); c.drawRoundRect(tmp, s * 0.35f, s * 0.35f, ui.solid); c.drawCircle(cx, cy + s * 0.45f, s * 0.12f, ui.solid) }
        }
    }

    override fun tap(x: Float, y: Float): Boolean {
        for ((r, act) in hits) if (r.contains(x, y)) { host.haptic(); act.run(); next(); return true }
        val al = queue.firstOrNull() ?: return false
        al.onTap?.let { it(); next(); return true }
        return false
    }

    override fun describe(): String {
        val al = queue.firstOrNull() ?: return ""
        return "${al.title}. ${al.body}"
    }
}
