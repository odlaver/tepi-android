package io.github.odlaver.tepi.scene

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Path
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import io.github.odlaver.tepi.Brief
import io.github.odlaver.tepi.Live
import io.github.odlaver.tepi.LiveItem
import io.github.odlaver.tepi.LiveKind
import io.github.odlaver.tepi.R
import kotlin.math.min

private val glyphPath = Path()

// Small line glyphs shared by system scenes
private fun drawPhone(c: Canvas, ui: io.github.odlaver.tepi.Ui, cx: Float, cy: Float, s: Float) {
    ui.stroke.strokeWidth = s * 0.16f
    ui.stroke.color = ui.solid.color
    val r = RectF(cx - s * 0.5f, cy - s * 0.5f, cx + s * 0.5f, cy + s * 0.5f)
    c.drawArc(r, 100f, 160f, false, ui.stroke)
    c.drawCircle(cx - s * 0.12f, cy - s * 0.48f, s * 0.11f, ui.solid)
    c.drawCircle(cx - s * 0.12f + s * 0.0f, cy + s * 0.48f, s * 0.11f, ui.solid)
}

private fun drawPhones(c: Canvas, ui: io.github.odlaver.tepi.Ui, cx: Float, cy: Float, s: Float) {
    ui.stroke.strokeWidth = s * 0.14f
    ui.stroke.color = ui.solid.color
    val r = RectF(cx - s * 0.45f, cy - s * 0.5f, cx + s * 0.45f, cy + s * 0.4f)
    c.drawArc(r, 180f, 180f, false, ui.stroke)
    val box = RectF()
    box.set(cx - s * 0.5f, cy, cx - s * 0.25f, cy + s * 0.5f); c.drawRoundRect(box, s * 0.08f, s * 0.08f, ui.solid)
    box.set(cx + s * 0.25f, cy, cx + s * 0.5f, cy + s * 0.5f); c.drawRoundRect(box, s * 0.08f, s * 0.08f, ui.solid)
}

private fun drawBell(c: Canvas, ui: io.github.odlaver.tepi.Ui, cx: Float, cy: Float, s: Float, slash: Boolean) {
    glyphPath.reset()
    glyphPath.moveTo(cx - s * 0.38f, cy + s * 0.25f)
    glyphPath.lineTo(cx - s * 0.32f, cy - s * 0.1f)
    glyphPath.quadTo(cx - s * 0.3f, cy - s * 0.45f, cx, cy - s * 0.45f)
    glyphPath.quadTo(cx + s * 0.3f, cy - s * 0.45f, cx + s * 0.32f, cy - s * 0.1f)
    glyphPath.lineTo(cx + s * 0.38f, cy + s * 0.25f)
    glyphPath.close()
    c.drawPath(glyphPath, ui.solid)
    c.drawCircle(cx, cy + s * 0.38f, s * 0.1f, ui.solid)
    if (slash) {
        ui.stroke.strokeWidth = s * 0.12f
        ui.stroke.color = Color.BLACK
        c.drawLine(cx - s * 0.5f, cy - s * 0.5f, cx + s * 0.5f, cy + s * 0.5f, ui.stroke)
        ui.stroke.strokeWidth = s * 0.07f
        ui.stroke.color = ui.solid.color
        c.drawLine(cx - s * 0.5f, cy - s * 0.5f, cx + s * 0.5f, cy + s * 0.5f, ui.stroke)
    }
}

private fun drawVibrate(c: Canvas, ui: io.github.odlaver.tepi.Ui, cx: Float, cy: Float, s: Float) {
    val box = RectF(cx - s * 0.2f, cy - s * 0.42f, cx + s * 0.2f, cy + s * 0.42f)
    c.drawRoundRect(box, s * 0.08f, s * 0.08f, ui.solid)
    ui.stroke.strokeWidth = s * 0.08f
    ui.stroke.color = ui.solid.color
    for (side in intArrayOf(-1, 1)) {
        c.drawLine(cx + side * s * 0.33f, cy - s * 0.22f, cx + side * s * 0.33f, cy + s * 0.22f, ui.stroke)
        c.drawLine(cx + side * s * 0.46f, cy - s * 0.12f, cx + side * s * 0.46f, cy + s * 0.12f, ui.stroke)
    }
}

// Short pill: charging, ring mode, audio device
class BriefScene(host: Host) : Scene(host) {
    override val id = "brief"
    override val priority = 80
    private val main = Handler(Looper.getMainLooper())
    private var kind: Brief? = null
    private var text = ""
    private val drop = Runnable { kind = null; host.refresh() }

    fun show(k: Brief, t: String) {
        kind = k
        text = when (k) {
            Brief.Silent -> host.ctx.getString(R.string.b_silent)
            Brief.Vibrate -> host.ctx.getString(R.string.b_vibrate)
            Brief.Ring -> host.ctx.getString(R.string.b_ring)
            Brief.Audio -> t.ifEmpty { host.ctx.getString(R.string.b_audio) }
            else -> t
        }
        main.removeCallbacks(drop)
        main.postDelayed(drop, 3200)
        host.refresh()
    }

    override fun live() = kind != null
    override fun dismiss() { kind = null }
    override fun wing() = (ui.compact.measureText(text) + ui.dp(12f)).coerceAtMost(ui.dp(120f))
    override fun tapCompact() = true

    override fun drawCompact(c: Canvas, left: RectF, right: RectF, a: Float) {
        val k = kind ?: return
        val s = min(left.height(), ui.dp(20f))
        val cx = left.left + s / 2
        val cy = left.centerY()
        ui.solid.color = ui.alpha(if (k == Brief.Charging) CHARGE else ui.ink, a)
        when (k) {
            Brief.Charging, Brief.Unplugged -> ui.bolt(c, cx, cy, s)
            Brief.Silent -> drawBell(c, ui, cx, cy, s, slash = true)
            Brief.Vibrate -> drawVibrate(c, ui, cx, cy, s)
            Brief.Ring -> drawBell(c, ui, cx, cy, s, slash = false)
            Brief.Audio -> drawPhones(c, ui, cx, cy, s)
        }
        val p = ui.compact
        val base = p.color
        if (k == Brief.Charging) p.color = CHARGE
        ui.textEnd(c, text, p, right.right, ui.baseline(p, right.centerY()), a)
        p.color = base
    }

    override fun panelHeight(w: Float) = 0f
    override fun drawPanel(c: Canvas, r: RectF, a: Float) {}
    override fun describe() = text

    companion object {
        private val CHARGE = Color.rgb(52, 199, 89)
    }
}

// Dot beside the camera while it or the mic is busy
class PrivacyScene(host: Host) : Scene(host) {
    override val id = "privacy"
    override val priority = 5
    var cam = false
    var mic = false

    override fun live() = cam || mic
    override fun tapCompact() = true
    override fun wing() = ui.dp(10f)

    override fun drawCompact(c: Canvas, left: RectF, right: RectF, a: Float) {
        ui.dot(c, right.right - ui.dp(4f), right.centerY(), ui.dp(4f), if (cam) CAM else MIC, a)
    }

    override fun drawBubble(c: Canvas, r: RectF, a: Float) = ui.dot(c, r.centerX(), r.centerY(), ui.dp(4f), if (cam) CAM else MIC, a)
    override fun panelHeight(w: Float) = 0f
    override fun drawPanel(c: Canvas, r: RectF, a: Float) {}
    override fun describe() = host.ctx.getString(if (cam) R.string.cd_cam else R.string.cd_mic)

    companion object {
        private val CAM = Color.rgb(52, 199, 89)
        private val MIC = Color.rgb(255, 159, 10)
    }
}

// Base for activities fed by ongoing notifications
abstract class LiveScene(host: Host, protected val live: Live, private val kind: LiveKind) : Scene(host) {
    protected val tmp = RectF()
    protected val hits = ArrayList<Pair<RectF, () -> Unit>>()
    protected fun item(): LiveItem? = live.of(kind).lastOrNull()
    override fun live() = item() != null

    protected fun elapsed(it: LiveItem): Long {
        val now = System.currentTimeMillis()
        return if (it.countDown) (it.whenMs - now).coerceAtLeast(0) else (now - it.whenMs).coerceAtLeast(0)
    }

    protected fun icon(c: Canvas, it: LiveItem, r: RectF, a: Float) = ui.art(c, it.icon, r, a, 0.5f)

    protected fun buttons(c: Canvas, r: RectF, y: Float, items: List<Pair<String, () -> Unit>>, a: Float, primary: Int = -1) {
        val gap = ui.s8
        val bw = (r.width() - gap * (items.size - 1)) / items.size
        items.forEachIndexed { i, (label, act) ->
            val br = RectF(r.left + i * (bw + gap), y, r.left + i * (bw + gap) + bw, y + ui.btnH)
            ui.button(c, br, label, a, i == primary)
            hits.add(br to act)
        }
    }

    override fun tap(x: Float, y: Float): Boolean {
        for ((r, act) in hits) if (r.contains(x, y)) { host.haptic(); act(); return true }
        return false
    }

    override fun tapCompact(): Boolean {
        val it = item() ?: return false
        if (panelHeight(0f) > 0f) return false
        it.intent?.let { p -> host.send(p) }
        return true
    }
}

class IncomingScene(host: Host, live: Live) : LiveScene(host, live, LiveKind.Incoming) {
    override val id = "incoming"
    override val priority = 96
    override fun card() = true
    override fun drawCompact(c: Canvas, left: RectF, right: RectF, a: Float) {}
    override fun panelHeight(w: Float) = ui.dp(40f) + ui.s12 + ui.btnH

    override fun drawPanel(c: Canvas, r: RectF, a: Float) {
        val it = item() ?: return
        hits.clear()
        tmp.set(r.left, r.top, r.left + ui.dp(40f), r.top + ui.dp(40f))
        icon(c, it, tmp, a)
        val tx = tmp.right + ui.s12
        ui.text(c, it.title, ui.title, tx, r.top + ui.dp(17f), r.right - tx, a)
        ui.text(c, it.text.ifEmpty { host.ctx.getString(R.string.call_incoming) }, ui.body, tx, r.top + ui.dp(34f), r.right - tx, a)
        val acts = it.actions.take(2).map { act -> act.title.toString() to { try { act.actionIntent.send() } catch (_: Exception) { } } }
        if (acts.isNotEmpty()) buttons(c, r, r.top + ui.dp(40f) + ui.s12, acts, a, primary = acts.size - 1)
    }

    override fun tap(x: Float, y: Float): Boolean {
        if (super.tap(x, y)) return true
        item()?.intent?.let { host.send(it) }
        return true
    }

    override fun describe() = item()?.let { "${host.ctx.getString(R.string.call_incoming)}: ${it.title}" } ?: ""
}

class CallScene(host: Host, live: Live) : LiveScene(host, live, LiveKind.Call) {
    override val id = "call"
    override val priority = 60
    override fun ticking() = true
    override fun wing() = ui.compact.measureText("00:00") + ui.dp(14f)

    override fun drawCompact(c: Canvas, left: RectF, right: RectF, a: Float) {
        val it = item() ?: return
        val s = min(left.height(), ui.dp(20f))
        ui.solid.color = ui.alpha(CALL, a)
        drawPhone(c, ui, left.left + s / 2, left.centerY(), s)
        val p = ui.compact
        val base = p.color
        p.color = CALL
        ui.textEnd(c, ui.clock(elapsed(it)), p, right.right, ui.baseline(p, right.centerY()), a)
        p.color = base
    }

    override fun panelHeight(w: Float) = ui.dp(40f) + if ((item()?.actions?.size ?: 0) > 0) ui.s12 + ui.btnH else 0f

    override fun drawPanel(c: Canvas, r: RectF, a: Float) {
        val it = item() ?: return
        hits.clear()
        tmp.set(r.left, r.top, r.left + ui.dp(40f), r.top + ui.dp(40f))
        icon(c, it, tmp, a)
        val tx = tmp.right + ui.s12
        ui.text(c, it.title, ui.title, tx, r.top + ui.dp(17f), r.right - tx - ui.dp(70f), a)
        ui.textEnd(c, ui.clock(elapsed(it)), ui.label, r.right, r.top + ui.dp(17f), a)
        ui.text(c, it.text, ui.body, tx, r.top + ui.dp(34f), r.right - tx, a)
        val acts = it.actions.take(3).map { act -> act.title.toString() to { try { act.actionIntent.send() } catch (_: Exception) { } } }
        if (acts.isNotEmpty()) buttons(c, r, r.top + ui.dp(40f) + ui.s12, acts, a)
    }

    override fun tap(x: Float, y: Float): Boolean {
        if (super.tap(x, y)) return true
        item()?.intent?.let { host.send(it); host.collapse() }
        return true
    }

    override fun describe() = item()?.let { "${it.title} ${ui.clock(elapsed(it))}" } ?: ""

    companion object {
        private val CALL = Color.rgb(52, 199, 89)
    }
}

class ProgressScene(host: Host, live: Live, private val native: () -> Boolean = { false }) : LiveScene(host, live, LiveKind.Progress) {
    override fun live() = !native() && super.live()
    override val id = "progress"
    override val priority = 30
    override fun ticking() = live.of(LiveKind.Progress).any { it.indeterminate }

    private fun frac(it: LiveItem) = if (it.max > 0) it.progress.toFloat() / it.max else 0f

    override fun drawCompact(c: Canvas, left: RectF, right: RectF, a: Float) {
        val it = item() ?: return
        val s = min(left.height(), ui.dp(24f))
        tmp.set(left.left, left.centerY() - s / 2, left.left + s, left.centerY() + s / 2)
        icon(c, it, tmp, a)
        val r = s / 2 - ui.dp(2f)
        if (it.indeterminate) {
            val spin = (System.currentTimeMillis() % 1200) / 1200f
            ui.stroke.strokeWidth = ui.dp(3f); ui.stroke.color = ui.alpha(ui.ink, a)
            tmp.set(right.right - s, right.centerY() - r, right.right - s + r * 2, right.centerY() + r)
            c.drawArc(tmp, spin * 360f, 90f, false, ui.stroke)
        } else ui.ring(c, right.right - r - ui.dp(2f), right.centerY(), r, ui.dp(3f), frac(it), a)
    }

    override fun drawBubble(c: Canvas, r: RectF, a: Float) {
        val it = item() ?: return
        ui.ring(c, r.centerX(), r.centerY(), r.width() * 0.28f, ui.dp(3f), frac(it), a)
    }

    override fun panelHeight(w: Float) = live.of(LiveKind.Progress).take(3).size * ui.dp(48f) - ui.s8

    override fun drawPanel(c: Canvas, r: RectF, a: Float) {
        var y = r.top
        hits.clear()
        for (it in live.of(LiveKind.Progress).takeLast(3).reversed()) {
            tmp.set(r.left, y, r.left + ui.dp(32f), y + ui.dp(32f))
            icon(c, it, tmp, a)
            val tx = tmp.right + ui.s12
            val pct = if (it.max > 0 && !it.indeterminate) "${(frac(it) * 100).toInt()}%" else ""
            ui.text(c, it.title, ui.label, tx, y + ui.dp(14f), r.right - tx - ui.dp(48f), a)
            if (pct.isNotEmpty()) ui.textEnd(c, pct, ui.small, r.right, y + ui.dp(14f), a)
            val bar = RectF(tx, y + ui.dp(22f), r.right, y + ui.dp(26f))
            ui.fill(c, bar, ui.dp(2f), ui.fillStrong, a)
            if (!it.indeterminate) { bar.right = tx + (r.right - tx) * frac(it); ui.fill(c, bar, ui.dp(2f), ui.ink, a) }
            val row = RectF(r.left, y, r.right, y + ui.dp(40f))
            hits.add(row to { it.intent?.let { p -> host.send(p) }; Unit })
            y += ui.dp(48f)
        }
    }

    override fun describe() = item()?.let { "${it.title} ${(frac(it) * 100).toInt()}%" } ?: ""
}

class NavScene(host: Host, live: Live) : LiveScene(host, live, LiveKind.Nav) {
    override val id = "nav"
    override val priority = 55
    override fun wing() = (ui.compact.measureText(item()?.title ?: "") + ui.dp(12f)).coerceIn(ui.dp(40f), ui.dp(110f))

    override fun drawCompact(c: Canvas, left: RectF, right: RectF, a: Float) {
        val it = item() ?: return
        val s = min(left.height(), ui.dp(24f))
        tmp.set(left.left, left.centerY() - s / 2, left.left + s, left.centerY() + s / 2)
        ui.art(c, it.icon, tmp, a, 0.2f)
        ui.textEnd(c, android.text.TextUtils.ellipsize(it.title, ui.compact, right.width(), android.text.TextUtils.TruncateAt.END), ui.compact, right.right, ui.baseline(ui.compact, right.centerY()), a)
    }

    override fun panelHeight(w: Float) = ui.dp(44f) + ui.s12 + ui.btnH

    override fun drawPanel(c: Canvas, r: RectF, a: Float) {
        val it = item() ?: return
        hits.clear()
        tmp.set(r.left, r.top, r.left + ui.dp(44f), r.top + ui.dp(44f))
        ui.art(c, it.icon, tmp, a, 0.2f)
        val tx = tmp.right + ui.s12
        ui.text(c, it.title, ui.heading, tx, r.top + ui.dp(20f), r.right - tx, a)
        ui.text(c, it.text, ui.body, tx, r.top + ui.dp(38f), r.right - tx, a)
        buttons(c, r, r.top + ui.dp(44f) + ui.s12, listOf(host.ctx.getString(R.string.open_maps) to { it.intent?.let { p -> host.send(p) }; host.collapse() }), a, 0)
    }

    override fun describe() = item()?.let { "${it.title}. ${it.text}" } ?: ""
}

class ClockScene(host: Host, live: Live) : LiveScene(host, live, LiveKind.Clock) {
    override val id = "clock"
    override val priority = 45
    override fun ticking() = true
    override fun wing() = ui.compact.measureText("00:00") + ui.dp(14f)

    override fun drawCompact(c: Canvas, left: RectF, right: RectF, a: Float) {
        val it = item() ?: return
        val s = min(left.height(), ui.dp(22f))
        tmp.set(left.left, left.centerY() - s / 2, left.left + s, left.centerY() + s / 2)
        icon(c, it, tmp, a)
        ui.textEnd(c, ui.clock(elapsed(it)), ui.compact, right.right, ui.baseline(ui.compact, right.centerY()), a)
    }

    override fun panelHeight(w: Float) = 0f
    override fun drawPanel(c: Canvas, r: RectF, a: Float) {}
    override fun describe() = item()?.let { "${it.title} ${ui.clock(elapsed(it))}" } ?: ""
}
