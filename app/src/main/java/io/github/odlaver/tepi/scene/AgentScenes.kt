package io.github.odlaver.tepi.scene

import android.content.Intent
import android.graphics.Canvas
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import io.github.odlaver.tepi.Confirm
import io.github.odlaver.tepi.Icon
import io.github.odlaver.tepi.Pc
import io.github.odlaver.tepi.R
import kotlin.math.max

// Permission request from an agent on the laptop
class AgentScene(host: Host, private val pc: Pc) : Scene(host) {
    override val id = "agent"
    override val priority = 100
    private val hits = ArrayList<Pair<RectF, () -> Unit>>()
    private val tmp = RectF()
    private val mono by lazy { TextPaint(ui.bodyInk).apply { typeface = Typeface.MONOSPACE; textSize = ui.sp(11f) } }

    override fun live() = pc.asks.isNotEmpty()
    override fun card() = true

    override fun drawCompact(c: Canvas, left: RectF, right: RectF, a: Float) {}

    private fun body(w: Float): StaticLayout {
        val ask = pc.asks.first()
        val text = ask.key.ifEmpty { ask.detail }
        return StaticLayout.Builder.obtain(text, 0, text.length, mono, max(1, (w - ui.s24).toInt()))
            .setMaxLines(4).setEllipsize(TextUtils.TruncateAt.END).setAlignment(Layout.Alignment.ALIGN_NORMAL).build()
    }

    override fun panelHeight(w: Float): Float {
        if (pc.asks.isEmpty()) return 0f
        val flags = if (pc.asks.first().flags.isNotEmpty()) ui.dp(16f) else 0f
        return ui.dp(36f) + ui.s12 + body(w).height + ui.s20 + flags + ui.s12 + ui.btnH
    }

    override fun drawPanel(c: Canvas, r: RectF, a: Float) {
        val ask = pc.asks.firstOrNull() ?: return
        hits.clear()
        tmp.set(r.left, r.top, r.left + ui.dp(36f), r.top + ui.dp(36f))
        ui.fill(c, tmp, ui.dp(18f), ui.fill, a)
        ui.icon(c, Icon.Laptop, tmp.centerX(), tmp.centerY(), ui.dp(18f), ui.ink, a)
        val tx = tmp.right + ui.s12
        val more = if (pc.asks.size > 1) "  +${pc.asks.size - 1}" else ""
        ui.text(c, host.ctx.getString(R.string.agent_asks, ask.agent, ask.tool) + more, ui.title, tx, r.top + ui.dp(15f), r.right - tx, a)
        ui.text(c, listOf(ask.project, ask.note).filter { it.isNotEmpty() }.joinToString(" · "), ui.body, tx, r.top + ui.dp(32f), r.right - tx, a)
        var y = r.top + ui.dp(36f) + ui.s12
        val l = body(r.width())
        val box = RectF(r.left, y, r.right, y + l.height + ui.s20)
        ui.fill(c, box, ui.dp(10f), ui.fill, a)
        c.save()
        c.translate(r.left + ui.s12, y + ui.dp(10f))
        l.paint.alpha = (255 * a).toInt()
        l.draw(c)
        c.restore()
        y = box.bottom
        if (ask.flags.isNotEmpty()) { ui.text(c, ask.flags.joinToString(" · "), ui.small, r.left, y + ui.dp(13f), r.width(), a); y += ui.dp(16f) }
        y += ui.s12
        val gap = ui.s8
        val bw = (r.width() - gap) / 2
        val deny = RectF(r.left, y, r.left + bw, y + ui.btnH)
        val allow = RectF(deny.right + gap, y, r.right, y + ui.btnH)
        ui.button(c, deny, host.ctx.getString(R.string.deny), a, false, Icon.Close)
        hits.add(deny to { pc.decide(ask.id, "deny") })
        if (ask.truncated) ui.button(c, allow, host.ctx.getString(R.string.allow_on_pc), a, false, Icon.Laptop)
        else {
            ui.button(c, allow, host.ctx.getString(R.string.allow), a, true, Icon.Check)
            hits.add(allow to {
                host.open(Intent(host.ctx, Confirm::class.java).putExtra(Confirm.ID, ask.id).putExtra(Confirm.WHAT, ask.key))
            })
        }
    }

    override fun tap(x: Float, y: Float): Boolean {
        for ((r, act) in hits) if (r.contains(x, y)) { host.haptic(); act(); return true }
        return false
    }

    // Swipe up: answer at the terminal instead
    override fun dismiss() {}

    override fun describe(): String {
        val ask = pc.asks.firstOrNull() ?: return ""
        return host.ctx.getString(R.string.agent_asks, ask.agent, ask.tool) + ". " + ask.key
    }
}

// Agent sessions on Home
class AgentRow(private val host: Host, private val pc: Pc) : HomeRow {
    override fun personal() = true
    override fun visible() = pc.paired && pc.runs.isNotEmpty()
    override fun height(w: Float) = host.ui.dp(38f)
    override fun draw(c: Canvas, r: RectF, a: Float) {
        val ui = host.ui
        val busy = pc.runs.firstOrNull { it.state == "working" || it.state == "waiting" } ?: pc.runs.first()
        ui.text(c, host.ctx.getString(R.string.agents_n, pc.runs.size) + if (!pc.online) " · " + host.ctx.getString(R.string.offline) else "", ui.body, r.left, r.top + ui.dp(12f), r.width(), a)
        ui.text(c, busy.project + if (busy.activity.isNotEmpty()) " · " + busy.activity else "", ui.label, r.left, r.top + ui.dp(30f), r.width(), a)
    }
}
