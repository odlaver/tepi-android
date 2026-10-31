package io.github.odlaver.tepi.scene

import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.Canvas
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import io.github.odlaver.tepi.Icon
import io.github.odlaver.tepi.R
import kotlin.math.max

// Translation result as a card
class TranslateScene(host: Host) : Scene(host) {
    override val id = "translate"
    override val priority = 88
    private val main = Handler(Looper.getMainLooper())
    private var source = ""
    private var result: String? = null
    private var pair = ""
    private val hits = ArrayList<Pair<RectF, () -> Unit>>()
    private val drop = Runnable { result = null; host.refresh() }

    fun show(src: String, out: String, langs: String) {
        source = src; result = out; pair = langs
        main.removeCallbacks(drop)
        main.postDelayed(drop, 25_000)
        host.refresh()
    }

    override fun live() = result != null
    override fun card() = true
    override fun dismiss() { result = null }
    override fun drawCompact(c: Canvas, left: RectF, right: RectF, a: Float) {}

    private fun body(w: Float): StaticLayout {
        val t = result.orEmpty()
        return StaticLayout.Builder.obtain(t, 0, t.length, TextPaint(ui.bodyInk).apply { textSize = ui.sp(13f) }, max(1, w.toInt()))
            .setMaxLines(8).setEllipsize(TextUtils.TruncateAt.END).setAlignment(Layout.Alignment.ALIGN_NORMAL).build()
    }

    override fun panelHeight(w: Float) = if (result == null) 0f else ui.dp(18f) + ui.s8 + body(w).height + ui.s12 + ui.btnH

    override fun drawPanel(c: Canvas, r: RectF, a: Float) {
        val out = result ?: return
        hits.clear()
        ui.icon(c, Icon.Globe, r.left + ui.dp(9f), r.top + ui.dp(9f), ui.dp(16f), ui.sub, a)
        ui.text(c, "$pair · ${source.replace('\n', ' ')}", ui.body, r.left + ui.dp(24f), r.top + ui.dp(13f), r.width() - ui.dp(24f), a)
        val l = body(r.width())
        c.save()
        c.translate(r.left, r.top + ui.dp(18f) + ui.s8)
        l.paint.alpha = (255 * a).toInt()
        l.draw(c)
        c.restore()
        val y = r.top + ui.dp(18f) + ui.s8 + l.height + ui.s12
        val gap = ui.s8
        val bw = (r.width() - gap) / 2
        val copy = RectF(r.left, y, r.left + bw, y + ui.btnH)
        val close = RectF(copy.right + gap, y, r.right, y + ui.btnH)
        ui.button(c, copy, host.ctx.getString(R.string.copy), a, true, Icon.Copy)
        ui.button(c, close, host.ctx.getString(R.string.close), a, false, Icon.Close)
        hits.add(copy to {
            host.ctx.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Tepi", out))
            result = null
        })
        hits.add(close to { result = null })
    }

    override fun tap(x: Float, y: Float): Boolean {
        for ((r, act) in hits) if (r.contains(x, y)) { host.haptic(); act(); host.refresh(); return true }
        return false
    }

    override fun describe() = result.orEmpty()
}
