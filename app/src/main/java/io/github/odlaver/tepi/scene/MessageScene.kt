package io.github.odlaver.tepi.scene

import android.app.Notification
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.RectF
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import io.github.odlaver.tepi.R
import kotlin.math.max
import kotlin.math.min

class Message(
    val key: String,
    val title: String,
    val text: String,
    val icon: Bitmap?,
    val intent: PendingIntent?,
    val reply: Notification.Action? = null,
    val actions: List<Notification.Action> = emptyList(),
)

// Incoming messages as a card, stacked
class MessageScene(host: Host) : Scene(host) {
    override val id = "message"
    override val priority = 90

    private val main = Handler(Looper.getMainLooper())
    private val queue = ArrayList<Message>()
    private var replying = false
    private val tmp = RectF()
    private val hitBody = RectF()
    private val hits = ArrayList<Pair<RectF, () -> Unit>>()
    private var layout: StaticLayout? = null
    private var layoutFor: Message? = null

    private val drop = Runnable { next() }

    fun show(m: Message) {
        queue.removeAll { it.key == m.key }
        queue.add(0, m)
        while (queue.size > 5) queue.removeAt(queue.size - 1)
        arm()
        host.refresh()
    }

    private fun arm() {
        main.removeCallbacks(drop)
        if (!replying) main.postDelayed(drop, SHOW_MS)
    }

    private fun next() {
        if (queue.isNotEmpty()) queue.removeAt(0)
        if (queue.isNotEmpty()) arm()
        host.refresh()
    }

    override fun live() = queue.isNotEmpty()
    override fun card() = true
    override fun dismiss() = next()

    override fun drawCompact(c: Canvas, left: RectF, right: RectF, a: Float) {
        val m = queue.firstOrNull() ?: return
        val s = min(left.height(), ui.dp(26f))
        tmp.set(left.left, left.centerY() - s / 2, left.left + s, left.centerY() + s / 2)
        ui.art(c, m.icon, tmp, a, 0.5f)
    }

    override fun panelHeight(w: Float): Float {
        val m = queue.firstOrNull() ?: return 0f
        val textW = w - ui.dp(40f) - ui.s12
        val textH = ui.title.fontSpacing + (if (m.text.isEmpty()) 0f else body(m, textW).height.toFloat())
        var h = max(ui.dp(40f), textH)
        if (buttons(m).isNotEmpty()) h += ui.s16 + ui.dp(40f)
        return h
    }

    private fun buttons(m: Message): List<Pair<String, () -> Unit>> {
        val out = ArrayList<Pair<String, () -> Unit>>()
        m.reply?.let { action -> out.add(host.ctx.getString(R.string.reply) to { reply(m, action) }) }
        for (act in m.actions.take(2)) out.add(act.title.toString() to { fire(act.actionIntent); next() })
        return out
    }

    private fun body(m: Message, width: Float): StaticLayout {
        val w = max(1, width.toInt())
        layout?.let { if (layoutFor === m && it.width == w) return it }
        val p = TextPaint(ui.body)
        return StaticLayout.Builder.obtain(m.text, 0, m.text.length, p, w)
            .setMaxLines(3)
            .setEllipsize(TextUtils.TruncateAt.END)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .build().also { layout = it; layoutFor = m }
    }

    override fun drawPanel(c: Canvas, r: RectF, a: Float) {
        val m = queue.firstOrNull() ?: return
        val icon = ui.dp(40f)
        tmp.set(r.left, r.top, r.left + icon, r.top + icon)
        ui.art(c, m.icon, tmp, a, 0.5f)
        val tx = r.left + icon + ui.s12
        val tw = r.right - tx
        val more = queue.size - 1
        val moreText = if (more > 0) "+$more" else ""
        val moreW = if (more > 0) ui.small.measureText(moreText) + ui.s8 else 0f
        ui.text(c, m.title, ui.title, tx, r.top - ui.title.ascent(), tw - moreW, a)
        if (more > 0) ui.textEnd(c, moreText, ui.small, r.right, r.top - ui.title.ascent(), a)
        var bottom = r.top + ui.title.fontSpacing
        if (m.text.isNotEmpty()) {
            val l = body(m, tw)
            c.save()
            c.translate(tx, bottom)
            l.paint.alpha = (ui.body.alpha * a).toInt()
            l.draw(c)
            c.restore()
            bottom += l.height
        }
        hitBody.set(r.left, r.top, r.right, max(r.top + icon, bottom))
        hits.clear()
        val bs = buttons(m)
        if (bs.isEmpty()) return
        val y = max(r.top + icon, bottom) + ui.s16
        val gap = ui.s8
        val bw = (r.width() - gap * (bs.size - 1)) / bs.size
        bs.forEachIndexed { i, (label, act) ->
            val br = RectF(r.left + i * (bw + gap), y, r.left + i * (bw + gap) + bw, y + ui.dp(40f))
            ui.button(c, br, label, a, primary = i == 0 && m.reply != null)
            hits.add(br to act)
        }
    }

    override fun tap(x: Float, y: Float): Boolean {
        for ((r, act) in hits) if (r.contains(x, y)) { host.haptic(); act(); return true }
        val m = queue.firstOrNull() ?: return false
        if (hitBody.contains(x, y)) {
            m.intent?.let { host.send(it) }
            next()
            return true
        }
        return false
    }

    private fun reply(m: Message, action: Notification.Action) {
        replying = true
        main.removeCallbacks(drop)
        host.input(host.ctx.getString(R.string.reply_to, m.title)) { text ->
            replying = false
            if (text.isNotBlank()) {
                val inputs = action.remoteInputs ?: emptyArray()
                val data = Bundle()
                for (ri in inputs) data.putCharSequence(ri.resultKey, text)
                val intent = Intent()
                RemoteInput.addResultsToIntent(inputs, intent, data)
                try { action.actionIntent.send(host.ctx, 0, intent) } catch (_: Exception) { }
                next()
            } else arm()
        }
    }

    private fun fire(p: PendingIntent?) {
        if (p == null) return
        try { p.send() } catch (_: Exception) { }
    }

    override fun describe(): String {
        val m = queue.firstOrNull() ?: return ""
        return "${m.title}. ${m.text}"
    }

    companion object {
        private const val SHOW_MS = 5500L
    }
}
