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
    val app: String = "",
    val appIcon: Bitmap? = null,
    val at: Long = System.currentTimeMillis(),
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
        val s = min(left.height(), ui.dp(22f))
        tmp.set(left.left, left.centerY() - s / 2, left.left + s, left.centerY() + s / 2)
        ui.art(c, m.icon, tmp, a, 0.5f)
    }

    override fun panelHeight(w: Float): Float {
        val m = queue.firstOrNull() ?: return 0f
        val textW = w - ui.dp(40f) - ui.s12
        val textH = ui.dp(14f) + ui.title.fontSpacing + (if (m.text.isEmpty()) 0f else body(m, textW).height.toFloat())
        var h = max(ui.dp(40f), textH)
        if (buttons(m).isNotEmpty()) h += ui.s12 + ui.dp(32f)
        return h
    }

    private fun buttons(m: Message): List<Pair<String, () -> Unit>> {
        val out = ArrayList<Pair<String, () -> Unit>>()
        m.reply?.let { action -> out.add(host.ctx.getString(R.string.reply) to { reply(m, action) }) }
        for (act in m.actions.take(2)) out.add(act.title.toString() to { fire(act.actionIntent); next() })
        return out
    }

    // "now", "3 min", "09:41"
    private fun ago(at: Long): String {
        val mins = (System.currentTimeMillis() - at) / 60_000L
        return when {
            mins < 1 -> host.ctx.getString(R.string.just_now)
            mins < 60 -> host.ctx.getString(R.string.mins_ago, mins)
            else -> java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(java.util.Date(at))
        }
    }

    private fun body(m: Message, width: Float): StaticLayout {
        val w = max(1, width.toInt())
        layout?.let { if (layoutFor === m && it.width == w) return it }
        val p = TextPaint(ui.body)
        return StaticLayout.Builder.obtain(m.text, 0, m.text.length, p, w)
            .setMaxLines(2)
            .setEllipsize(TextUtils.TruncateAt.END)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .build().also { layout = it; layoutFor = m }
    }

    override fun drawPanel(c: Canvas, r: RectF, a: Float) {
        val m = queue.firstOrNull() ?: return
        val icon = ui.dp(40f)
        tmp.set(r.left, r.top, r.left + icon, r.top + icon)
        ui.art(c, m.icon, tmp, a, 0.5f)
        // App badge on the avatar, only when it is a person
        if (m.appIcon != null && m.appIcon !== m.icon) {
            val bs = ui.dp(16f)
            val bx = tmp.right - bs + ui.dp(3f)
            val by = tmp.bottom - bs + ui.dp(3f)
            ui.dot(c, bx + bs / 2, by + bs / 2, bs / 2 + ui.dp(2f), android.graphics.Color.BLACK, a)
            tmp.set(bx, by, bx + bs, by + bs)
            ui.art(c, m.appIcon, tmp, a, 0.5f)
        }
        val tx = r.left + icon + ui.s12
        val tw = r.right - tx
        // App and time above, count of others on the right
        val more = queue.size - 1
        val meta = listOf(m.app, ago(m.at)).filter { it.isNotEmpty() }.joinToString(" · ")
        val moreText = if (more > 0) host.ctx.getString(R.string.n_more, more) else ""
        val moreW = if (more > 0) ui.small.measureText(moreText) + ui.s8 else 0f
        ui.text(c, meta, ui.small, tx, r.top + ui.dp(10f), tw - moreW, a)
        if (more > 0) ui.textEnd(c, moreText, ui.small, r.right, r.top + ui.dp(10f), a)
        val top = r.top + ui.dp(14f)
        ui.text(c, m.title, ui.title, tx, top - ui.title.ascent(), tw, a)
        var bottom = top + ui.title.fontSpacing
        if (m.text.isNotEmpty()) {
            val l = body(m, tw)
            c.save()
            c.translate(tx, bottom)
            l.paint.alpha = (255 * a).toInt()
            l.paint.color = ui.ink
            l.draw(c)
            c.restore()
            bottom += l.height
        }
        hitBody.set(r.left, r.top, r.right, max(r.top + icon, bottom))
        hits.clear()
        val bs = buttons(m)
        if (bs.isEmpty()) return
        // Compact chips under the text, sized to their labels
        val y = max(r.top + icon, bottom) + ui.s12
        val h = ui.dp(32f)
        var x = tx
        bs.forEachIndexed { i, (label, act) ->
            val isReply = i == 0 && m.reply != null
            val p = if (isReply) ui.labelDark else ui.label
            val w = p.measureText(label) + ui.s24 + if (isReply) ui.iconS + ui.dp(6f) else 0f
            if (x + w > r.right) return@forEachIndexed
            val br = RectF(x, y, x + w, y + h)
            ui.button(c, br, label, a, primary = isReply, icon = if (isReply) io.github.odlaver.tepi.Icon.Reply else null)
            hits.add(br to act)
            x += w + ui.s8
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

    // From the system island's Reply button
    fun replyTo(m: Message) { m.reply?.let { reply(m, it) } }

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
