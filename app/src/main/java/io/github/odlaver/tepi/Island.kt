package io.github.odlaver.tepi

import android.annotation.SuppressLint
import android.app.ActivityOptions
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.util.DisplayMetrics
import android.util.TypedValue
import android.view.Choreographer
import android.view.Display
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

class Message(val key: String, val title: String, val text: String, val icon: Bitmap?, val intent: PendingIntent?)

// Overlay around the front camera
@SuppressLint("ViewConstructor")
class Island(context: Context, private val probeCtx: Context?, private val screen: Display, private val type: Int, private val media: Media, private val prefs: Prefs, private val lyrics: Lyrics) : View(context) {
    private enum class Mode { Hidden, Music, Panel, Message }

    private val wm = context.getSystemService(WindowManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private val d = resources.displayMetrics.density
    private fun dp(v: Float) = v * d
    private fun sp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, resources.displayMetrics)

    // Tone.Dark from Tepi Windows
    private val ink = Color.rgb(245, 245, 247)
    private val sub = Color.argb(158, 245, 245, 247)
    private val faint = Color.argb(92, 245, 245, 247)
    private val fill = Color.argb(28, 255, 255, 255)

    private val baseFlags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
        WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
        WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
    private val lp = WindowManager.LayoutParams(
        1, 1, type,
        baseFlags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE, PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        title = "Tepi"
        layoutInDisplayCutoutMode = if (Build.VERSION.SDK_INT >= 30) WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        else WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        if (Build.VERSION.SDK_INT >= 30) fitInsetsTypes = 0
    }
    private var attached = false

    // Geometry, screen px
    private var screenW = 0f
    private val hole = RectF()
    private val pill = RectF()
    private val l = Spring(0f, 0.3f)
    private val t = Spring(0f, 0.3f)
    private val r = Spring(0f, 0.3f)
    private val b = Spring(0f, 0.3f)
    private val rad = Spring(0f, 0.3f)
    // Two tiers: neck by the camera, body below
    private val neck = Spring(0f, 0.3f)
    private val split = Spring(0f, 0.3f)
    private val radB = Spring(0f, 0.3f)
    private var statusBottom = 0f
    private var compactW = 0f
    private val contentA = Spring(0f, 0.004f, 520f, 46f)
    private val playLevel = Spring(0f, 0.004f, 160f, 26f)
    private val lyricT = Spring(1f, 0.004f, 260f, 32f)
    private val now = RectF()
    private val target = RectF()

    // State
    private var wanted = Mode.Hidden
    private var shown = Mode.Hidden
    private var panelOpen = false
    private var message: Message? = null
    private var dismissedKey: String? = null
    private var pausedAt = 0L
    private var wasPlaying = false
    private var fullscreen = false
    private var song: SongLyrics? = null
    private var songKey: String? = null
    private var lyricIdx = -1
    private var lyricPrev = ""
    private var lastTouch = 0L

    // Paints
    private val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK }
    private val solid = Paint(Paint.ANTI_ALIAS_FLAG)
    private val img = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val titleP = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = ink; textSize = sp(15f); typeface = Typeface.DEFAULT_BOLD }
    private val subP = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = sub; textSize = sp(13f) }
    private val timeP = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = faint; textSize = sp(11f); fontFeatureSettings = "tnum" }
    private val lyricP = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = ink; textSize = sp(13.5f); typeface = Typeface.DEFAULT_BOLD }
    private val shape = Path()
    private val tierA = Path()
    private val tierB = Path()
    private val box = RectF()
    private val glyph = Path()
    private val tmp = RectF()
    private val loc = IntArray(2)
    private val shaderM = Matrix()

    // Hit areas in panel
    private val hitPrev = RectF()
    private val hitPlay = RectF()
    private val hitNext = RectF()
    private val hitBar = RectF()
    private val hitInfo = RectF()

    fun attach() {
        if (attached) return
        measureScreen()
        l.snap(hole.left); t.snap(hole.top); r.snap(hole.right); b.snap(hole.bottom); rad.snap(hole.height() / 2)
        neck.snap(hole.width()); split.snap(hole.top); radB.snap(hole.height() / 2)
        place(hole, false)
        wm.addView(this, lp)
        attached = true
        if (probeCtx != null && Settings.canDrawOverlays(context)) {
            val p = WindowManager.LayoutParams(1, 1, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE, PixelFormat.TRANSLUCENT)
            p.gravity = Gravity.TOP or Gravity.START
            try { probeCtx.getSystemService(WindowManager::class.java).addView(probe, p); probing = true } catch (_: Exception) { }
        }
        refresh()
    }

    fun detach() {
        if (!attached) return
        main.removeCallbacksAndMessages(null)
        Choreographer.getInstance().removeFrameCallback(frame)
        ticking = false
        wm.removeView(this)
        if (probing) { try { probeCtx?.getSystemService(WindowManager::class.java)?.removeView(probe) } catch (_: Exception) { }; probing = false }
        attached = false
    }

    fun show(m: Message) {
        message = m
        panelOpen = false
        main.removeCallbacks(dropMessage)
        main.postDelayed(dropMessage, 4500)
        refresh()
    }

    private val dropMessage = Runnable { message = null; refresh() }
    private val closePanel = Runnable {
        if (panelOpen && SystemClock.uptimeMillis() - lastTouch >= 7900) { panelOpen = false; refresh() }
    }
    private val recheck = Runnable { refresh() }

    // Media or prefs changed
    fun mediaChanged() {
        val tr = media.track
        if (tr == null || tr.key != songKey) {
            song = null
            lyricIdx = -1
            songKey = tr?.key
            if (tr != null && prefs.lyrics) {
                val k = tr.key
                lyrics.load(tr.title, tr.artist, tr.durationMs, prefs.lyricsOnline) { got ->
                    if (songKey == k) { song = got; refresh() }
                }
            }
        }
        if (tr?.key != dismissedKey) dismissedKey = null
        if (media.playing) {
            pausedAt = 0
            wasPlaying = true
        } else if (wasPlaying) {
            pausedAt = SystemClock.uptimeMillis()
            wasPlaying = false
        }
        refresh()
    }

    fun prefsChanged() {
        songKey = null
        measureScreen()
        mediaChanged()
    }

    override fun onConfigurationChanged(newConfig: Configuration?) {
        super.onConfigurationChanged(newConfig)
        measureScreen()
        refresh()
    }

    override fun onApplyWindowInsets(insets: WindowInsets): WindowInsets {
        if (type == WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY) sense(insets)
        return super.onApplyWindowInsets(insets)
    }

    // Status bar gone: a fullscreen app is up
    private fun sense(insets: WindowInsets) {
        if (Build.VERSION.SDK_INT < 30) return
        val full = !insets.isVisible(WindowInsets.Type.statusBars())
        if (full != fullscreen) { fullscreen = full; refresh() }
    }

    // Accessibility windows never see the status bar
    private val probe = object : View(probeCtx ?: context) {
        override fun onApplyWindowInsets(insets: WindowInsets): WindowInsets {
            sense(insets)
            return super.onApplyWindowInsets(insets)
        }
    }
    private var probing = false

    @Suppress("DEPRECATION")
    private fun measureScreen() {
        val m = DisplayMetrics()
        screen.getRealMetrics(m)
        screenW = m.widthPixels.toFloat()
        val cut = screen.cutout
        val top = cut?.boundingRectTop?.takeIf { !it.isEmpty }
        if (top != null) {
            hole.set(top)
            // Path is tighter than the bounding box
            if (Build.VERSION.SDK_INT >= 31) cut?.cutoutPath?.let { p ->
                val bounds = RectF()
                p.computeBounds(bounds, true)
                if (bounds.intersect(hole)) hole.set(bounds)
            }
            // Bar down from the edge: a round hole inside
            if (hole.top <= 1f && hole.height() > hole.width() * 1.4f) {
                val r = hole.width() / 2
                val cy = hole.height() / 2
                hole.set(hole.centerX() - r, cy - r, hole.centerX() + r, cy + r)
            }
        } else {
            hole.set(screenW / 2 - dp(10f), dp(10f), screenW / 2 + dp(10f), dp(30f))
        }
        hole.offset(0f, dp(prefs.offsetDp.toFloat()))
        val pad = dp(5f)
        pill.set(hole.left - pad, max(0f, hole.top - pad), hole.right + pad, hole.bottom + pad)
        if (pill.height() < dp(30f)) pill.bottom = pill.top + dp(30f)
        val bar = resources.getIdentifier("status_bar_height", "dimen", "android").let { if (it != 0) resources.getDimensionPixelSize(it) else 0 }
        statusBottom = max(pill.bottom, (cut?.safeInsetTop?.takeIf { it > 0 } ?: bar).toFloat())
        compactW = pill.width() + max(pill.height() * 1.1f, dp(30f)) * 2
    }

    private val landscape get() = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    private fun pick(): Mode {
        if (landscape || fullscreen) return Mode.Hidden
        if (message != null) return Mode.Message
        val tr = media.track ?: return Mode.Hidden
        if (panelOpen) return Mode.Panel
        if (tr.key == dismissedKey) return Mode.Hidden
        if (media.playing) return Mode.Music
        val idle = SystemClock.uptimeMillis() - pausedAt
        if (pausedAt > 0 && idle < PAUSED_MS) {
            main.removeCallbacks(recheck)
            main.postDelayed(recheck, PAUSED_MS - idle + 50)
            return Mode.Music
        }
        return Mode.Hidden
    }

    // Recompute target, start motion
    private fun refresh() {
        if (!attached) return
        wanted = pick()
        if (wanted != Mode.Panel) panelOpen = false
        layoutFor(wanted, target)
        val tiered = wanted == Mode.Panel || wanted == Mode.Message || (wanted == Mode.Music && target.bottom > pill.bottom + 1f)
        val nk = if (tiered) min(compactW, target.width()) else target.width()
        val sy = if (tiered) statusBottom else target.top
        if (target != springTarget() || nk != neck.target || sy != split.target) {
            l.target = target.left; t.target = target.top; r.target = target.right; b.target = target.bottom
            neck.target = nk
            split.target = sy
            rad.target = if (wanted == Mode.Hidden) hole.height() / 2 else pill.height() / 2
            radB.target = when (wanted) {
                Mode.Panel, Mode.Message -> dp(28f)
                Mode.Music -> if (tiered) dp(18f) else rad.target
                Mode.Hidden -> rad.target
            }
            // Grow first; shrink once settled
            val union = RectF(target)
            union.union(l.value, t.value, r.value, b.value)
            place(union, wanted != Mode.Hidden)
        }
        if (wanted != shown) contentA.target = 0f
        kick()
    }

    private fun springTarget() = RectF(l.target, t.target, r.target, b.target)

    private fun layoutFor(mode: Mode, out: RectF) {
        val cx = hole.centerX()
        val wide = min(screenW - dp(16f), dp(420f))
        val below = statusBottom + dp(12f)
        when (mode) {
            Mode.Hidden -> out.set(hole)
            Mode.Music -> {
                var w = compactW
                var bottom = pill.bottom
                val line = currentLine()
                if (line != null) {
                    w = (lyricP.measureText(line) + dp(36f)).coerceIn(w, wide)
                    bottom = statusBottom + dp(30f)
                }
                out.set(cx - w / 2, pill.top, cx + w / 2, bottom)
            }
            Mode.Panel -> {
                var h = below - pill.top + dp(56f) + dp(16f) + dp(30f) + dp(56f) + dp(14f)
                if (song?.lines?.isNotEmpty() == true) h += dp(52f)
                out.set(cx - wide / 2, pill.top, cx + wide / 2, pill.top + h)
            }
            Mode.Message -> {
                val m = message
                val textH = if (m == null) 0f else messageLayout(m, wide - dp(36f + 40f + 12f)).height.toFloat() + titleP.fontSpacing
                out.set(cx - wide / 2, pill.top, cx + wide / 2, below + max(dp(40f), textH) + dp(16f))
            }
        }
        // Keep on screen
        val m = dp(8f)
        if (out.left < m) out.offset(m - out.left, 0f)
        if (out.right > screenW - m) out.offset(screenW - m - out.right, 0f)
    }

    // Window covers the moving shape only
    private fun place(rect: RectF, touchable: Boolean) {
        val x = rect.left.toInt() - 2
        val y = max(0, rect.top.toInt() - 2)
        lp.x = x
        lp.y = y
        lp.width = max(1, rect.right.toInt() + 2 - x)
        lp.height = max(1, rect.bottom.toInt() + 2 - y)
        lp.flags = baseFlags or if (touchable) 0 else WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        if (attached) wm.updateViewLayout(this, lp)
    }

    // Frame loop
    private var ticking = false
    private var lastNs = 0L
    private var frameNo = 0
    private val frame = Choreographer.FrameCallback { ns -> tick(ns) }

    private fun kick() {
        if (ticking) return
        ticking = true
        lastNs = 0
        Choreographer.getInstance().postFrameCallback(frame)
    }

    private fun tick(ns: Long) {
        val dt = if (lastNs == 0L) 1f / 60f else ((ns - lastNs) / 1e9f).coerceIn(0f, 1f / 20f)
        lastNs = ns
        var moving = false
        for (s in arrayOf(l, t, r, b, rad, neck, split, radB)) moving = s.step(dt) or moving
        moving = contentA.step(dt) or moving
        if (contentA.value <= 0.05f && contentA.target == 0f) {
            shown = wanted
            contentA.target = if (shown == Mode.Hidden) 0f else 1f
            if (shown == Mode.Hidden) contentA.snap(0f)
        }
        playLevel.target = if (media.playing) 1f else 0f
        moving = playLevel.step(dt) or moving
        moving = lyricT.step(dt) or moving
        if (updateLyric()) refresh()

        val live = shown != Mode.Hidden && media.playing && (shown == Mode.Music || shown == Mode.Panel)
        // Bars and progress at half rate
        if (moving || !live || frameNo++ % 2 == 0) invalidate()
        if (moving || live) {
            Choreographer.getInstance().postFrameCallback(frame)
        } else {
            ticking = false
            settle()
        }
    }

    private fun settle() {
        if (wanted == Mode.Hidden && shown == Mode.Hidden) place(hole, false)
        else place(target, true)
    }

    private fun currentLine(): String? {
        if (!prefs.lyrics) return null
        val s = song ?: return null
        if (lyricIdx < 0 || lyricIdx >= s.lines.size) return null
        return s.lines[lyricIdx].text.takeIf { it.isNotEmpty() }
    }

    // True when the line changed
    private fun updateLyric(): Boolean {
        val s = song ?: return false
        if (s.lines.isEmpty()) return false
        val idx = s.indexAt(media.positionMs() + 150)
        if (idx == lyricIdx) return false
        lyricPrev = currentLine() ?: ""
        lyricIdx = idx
        lyricT.snap(0f)
        lyricT.target = 1f
        return true
    }

    override fun onDraw(canvas: Canvas) {
        now.set(l.value, t.value, r.value, b.value)
        if (shown == Mode.Hidden && wanted == Mode.Hidden && !ticking) return
        getLocationOnScreen(loc)
        canvas.save()
        canvas.translate(-loc[0].toFloat(), -loc[1].toFloat())
        buildShape(shape)
        canvas.drawPath(shape, bg)
        canvas.clipPath(shape)
        val a = contentA.value.coerceIn(0f, 1f)
        if (a > 0.01f) when (shown) {
            Mode.Music -> drawMusic(canvas, a)
            Mode.Panel -> drawPanel(canvas, a)
            Mode.Message -> drawMessage(canvas, a)
            Mode.Hidden -> Unit
        }
        canvas.restore()
    }

    // Neck and body joined by concave fillets
    private fun buildShape(out: Path) {
        out.reset()
        val w = now.width()
        val cx = now.centerX()
        val nk = neck.value.coerceIn(0f, w)
        val sy = split.value.coerceIn(now.top, now.bottom)
        if (w - nk < 1f || sy - now.top < 1f || now.bottom - sy < 1f) {
            val r0 = min(rad.value, min(w, now.height()) / 2)
            out.addRoundRect(now, r0, r0, Path.Direction.CW)
            return
        }
        val rt = min(rad.value, nk / 2)
        val rb = min(radB.value, min(w, now.bottom - sy) / 2)
        tierA.reset()
        box.set(cx - nk / 2, now.top, cx + nk / 2, min(now.bottom, sy + rt))
        tierA.addRoundRect(box, rt, rt, Path.Direction.CW)
        tierB.reset()
        box.set(now.left, sy, now.right, now.bottom)
        tierB.addRoundRect(box, rb, rb, Path.Direction.CW)
        out.op(tierA, tierB, Path.Op.UNION)
        val f = min(dp(12f), min((w - nk) / 2 - rb, sy - now.top))
        if (f < 1f) return
        for (side in intArrayOf(-1, 1)) {
            val x = cx + side * nk / 2
            tierA.reset()
            tierA.addRect(min(x, x + side * f), sy - f, max(x, x + side * f), sy, Path.Direction.CW)
            tierB.reset()
            tierB.addCircle(x + side * f, sy - f, f, Path.Direction.CW)
            tierA.op(tierB, Path.Op.DIFFERENCE)
            out.op(tierA, Path.Op.UNION)
        }
    }

    private fun drawMusic(c: Canvas, a: Float) {
        val tr = media.track ?: return
        val h = pill.height()
        val s = min(h - dp(12f), dp(26f))
        val inset = (h - s) / 2
        val nk = min(neck.value, now.width())
        val left = now.centerX() - nk / 2
        tmp.set(left + inset, pill.top + inset, left + inset + s, pill.top + inset + s)
        drawArt(c, tr.art, tmp, a)
        drawBars(c, left + nk - inset - s / 2, pill.centerY(), s * 0.8f, a)
        val line = currentLine() ?: return
        val rowA = ((now.bottom - statusBottom) / dp(30f)).coerceIn(0f, 1f) * a
        if (rowA < 0.02f) return
        val avail = now.width() - dp(28f)
        val y = statusBottom + dp(15f)
        val k = lyricT.value.coerceIn(0f, 1f)
        val shift = dp(10f)
        if (k < 1f && lyricPrev.isNotEmpty()) drawCentered(c, lyricPrev, lyricP, now.centerX(), y - shift * k, avail, rowA * (1 - k))
        drawCentered(c, line, lyricP, now.centerX(), y + shift * (1 - k), avail, rowA * k)
    }

    private fun drawPanel(c: Canvas, a: Float) {
        val tr = media.track ?: return
        val left = now.left + dp(18f)
        val right = now.right - dp(18f)
        val top = statusBottom + dp(12f)
        val art = dp(56f)
        tmp.set(left, top, left + art, top + art)
        drawArt(c, tr.art, tmp, a)
        val tx = left + art + dp(14f)
        val tw = right - tx
        hitInfo.set(left, top, right, top + art)
        drawText(c, tr.title, titleP, tx, top + dp(24f), tw, a)
        if (tr.artist.isNotEmpty()) drawText(c, tr.artist, subP, tx, top + dp(44f), tw, a)

        // Progress
        val barY = top + art + dp(18f)
        val dur = tr.durationMs
        val pos = media.positionMs()
        solid.color = fill
        solid.alpha = (solid.alpha * a).toInt()
        tmp.set(left, barY, right, barY + dp(4f))
        c.drawRoundRect(tmp, dp(2f), dp(2f), solid)
        if (dur > 0) {
            solid.color = sub
            solid.alpha = (solid.alpha * a).toInt()
            tmp.right = left + (right - left) * (pos.toFloat() / dur).coerceIn(0f, 1f)
            c.drawRoundRect(tmp, dp(2f), dp(2f), solid)
            drawText(c, clock(pos), timeP, left, barY + dp(20f), dp(60f), a)
            val total = clock(dur)
            drawText(c, total, timeP, right - timeP.measureText(total), barY + dp(20f), dp(60f), a)
        }
        hitBar.set(left, barY - dp(14f), right, barY + dp(18f))

        // Controls
        val cy = barY + dp(30f) + dp(28f)
        val cx = now.centerX()
        val gap = dp(72f)
        val hit = dp(28f)
        hitPrev.set(cx - gap - hit, cy - hit, cx - gap + hit, cy + hit)
        hitPlay.set(cx - hit, cy - hit, cx + hit, cy + hit)
        hitNext.set(cx + gap - hit, cy - hit, cx + gap + hit, cy + hit)
        solid.color = ink
        solid.alpha = (255 * a).toInt()
        skipGlyph(c, cx - gap, cy, dp(20f), back = true)
        if (media.playing) pauseGlyph(c, cx, cy, dp(26f)) else playGlyph(c, cx, cy, dp(26f))
        skipGlyph(c, cx + gap, cy, dp(20f), back = false)

        // Lyrics: current, then next
        val lines = song?.lines ?: return
        if (lines.isEmpty()) return
        val ly = cy + dp(28f) + dp(22f)
        val cur = currentLine() ?: "♪"
        drawCentered(c, cur, lyricP, cx, ly, right - left, a)
        val next = lines.getOrNull(lyricIdx + 1)?.text
        if (!next.isNullOrEmpty()) drawCentered(c, next, subP, cx, ly + dp(22f), right - left, a)
    }

    private fun drawMessage(c: Canvas, a: Float) {
        val m = message ?: return
        val left = now.left + dp(18f)
        val right = now.right - dp(18f)
        val top = statusBottom + dp(12f)
        val icon = dp(40f)
        tmp.set(left, top, left + icon, top + icon)
        drawArt(c, m.icon, tmp, a)
        val tx = left + icon + dp(12f)
        val tw = min(screenW - dp(16f), dp(420f)) - dp(36f + 40f + 12f)
        drawText(c, m.title, titleP, tx, top - titleP.ascent(), tw, a)
        if (m.text.isEmpty()) return
        val layout = messageLayout(m, tw)
        c.save()
        c.translate(tx, top + titleP.fontSpacing)
        layout.paint.alpha = (sub.ushr(24) * a).toInt()
        layout.draw(c)
        c.restore()
    }

    private var msgLayout: StaticLayout? = null
    private var msgFor: Message? = null

    private fun messageLayout(m: Message, width: Float): StaticLayout {
        val w = max(1, width.toInt())
        msgLayout?.let { if (msgFor === m && it.width == w) return it }
        val p = TextPaint(subP)
        val made = StaticLayout.Builder.obtain(m.text, 0, m.text.length, p, w)
            .setMaxLines(2)
            .setEllipsize(TextUtils.TruncateAt.END)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .build()
        msgLayout = made
        msgFor = m
        return made
    }

    private fun drawArt(c: Canvas, art: Bitmap?, at: RectF, a: Float) {
        val radius = at.width() * 0.24f
        if (art == null) {
            solid.color = fill
            solid.alpha = (solid.alpha * a).toInt()
            c.drawRoundRect(at, radius, radius, solid)
            solid.color = sub
            solid.alpha = (solid.alpha * a).toInt()
            playGlyph(c, at.centerX(), at.centerY(), at.width() * 0.36f)
            return
        }
        val shader = BitmapShader(art, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        val k = max(at.width() / art.width, at.height() / art.height)
        shaderM.setScale(k, k)
        shaderM.postTranslate(at.centerX() - art.width * k / 2, at.centerY() - art.height * k / 2)
        shader.setLocalMatrix(shaderM)
        img.shader = shader
        img.alpha = (255 * a).toInt()
        c.drawRoundRect(at, radius, radius, img)
        img.shader = null
    }

    // Decorative bars: follow play state, not audio
    private fun drawBars(c: Canvas, cx: Float, cy: Float, maxH: Float, a: Float) {
        val w = dp(2.6f)
        val gap = dp(2.4f)
        val total = w * 4 + gap * 3
        val secs = SystemClock.uptimeMillis() / 1000f
        val level = playLevel.value.coerceIn(0f, 1f)
        solid.color = ink
        solid.alpha = (255 * a).toInt()
        for (i in 0 until 4) {
            val wave = 0.5f + 0.25f * sin(secs * (5.1f + i * 1.7f) + i * 1.3f) + 0.25f * sin(secs * (8.3f - i) + i * 2.1f)
            val h = maxH * (0.2f + 0.8f * wave * level).coerceIn(0.2f, 1f)
            val x = cx - total / 2 + i * (w + gap)
            tmp.set(x, cy - h / 2, x + w, cy + h / 2)
            c.drawRoundRect(tmp, w / 2, w / 2, solid)
        }
    }

    private fun drawText(c: Canvas, text: String, p: TextPaint, x: Float, baseline: Float, width: Float, a: Float) {
        val s = TextUtils.ellipsize(text, p, width, TextUtils.TruncateAt.END)
        val base = p.alpha
        p.alpha = (base * a).toInt()
        c.drawText(s, 0, s.length, x, baseline, p)
        p.alpha = base
    }

    // Vertically centred on y
    private fun drawCentered(c: Canvas, text: String, p: TextPaint, cx: Float, y: Float, width: Float, a: Float) {
        val s = TextUtils.ellipsize(text, p, width, TextUtils.TruncateAt.END)
        val w = p.measureText(s, 0, s.length)
        val base = p.alpha
        p.alpha = (base * a).toInt()
        c.drawText(s, 0, s.length, cx - w / 2, y - (p.ascent() + p.descent()) / 2, p)
        p.alpha = base
    }

    private fun playGlyph(c: Canvas, cx: Float, cy: Float, size: Float) {
        glyph.reset()
        val h = size * 0.5f
        glyph.moveTo(cx - h * 0.7f, cy - h)
        glyph.lineTo(cx + h, cy)
        glyph.lineTo(cx - h * 0.7f, cy + h)
        glyph.close()
        c.drawPath(glyph, solid)
    }

    private fun pauseGlyph(c: Canvas, cx: Float, cy: Float, size: Float) {
        val w = size * 0.26f
        val h = size * 0.5f
        val g = size * 0.14f
        tmp.set(cx - g - w, cy - h, cx - g, cy + h)
        c.drawRoundRect(tmp, w * 0.3f, w * 0.3f, solid)
        tmp.set(cx + g, cy - h, cx + g + w, cy + h)
        c.drawRoundRect(tmp, w * 0.3f, w * 0.3f, solid)
    }

    private fun skipGlyph(c: Canvas, cx: Float, cy: Float, size: Float, back: Boolean) {
        val h = size * 0.42f
        val dir = if (back) -1f else 1f
        glyph.reset()
        glyph.moveTo(cx - dir * h, cy - h)
        glyph.lineTo(cx + dir * h * 0.6f, cy)
        glyph.lineTo(cx - dir * h, cy + h)
        glyph.close()
        c.drawPath(glyph, solid)
        val bx = cx + dir * h * 0.7f
        val bw = size * 0.11f
        tmp.set(min(bx, bx + dir * bw), cy - h, max(bx, bx + dir * bw), cy + h)
        c.drawRoundRect(tmp, bw / 2, bw / 2, solid)
    }

    private fun clock(ms: Long): String {
        val s = ms / 1000
        return "%d:%02d".format(s / 60, s % 60)
    }

    // Touch
    private var downX = 0f
    private var downY = 0f
    private var pressed = false
    private var swiped = false
    private val longPress = Runnable {
        if (pressed && !swiped) {
            pressed = false
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            open(Intent(context, MainActivity::class.java))
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        lastTouch = SystemClock.uptimeMillis()
        when (e.actionMasked) {
            MotionEvent.ACTION_OUTSIDE -> {
                if (panelOpen) { panelOpen = false; refresh() }
                return false
            }
            MotionEvent.ACTION_DOWN -> {
                if (shown == Mode.Hidden || !now.contains(e.rawX, e.rawY)) return false
                downX = e.rawX
                downY = e.rawY
                pressed = true
                swiped = false
                main.postDelayed(longPress, 550)
            }
            MotionEvent.ACTION_MOVE -> {
                if (pressed && !swiped && downY - e.rawY > dp(24f)) {
                    swiped = true
                    main.removeCallbacks(longPress)
                    swipeUp()
                }
            }
            MotionEvent.ACTION_UP -> {
                main.removeCallbacks(longPress)
                if (pressed && !swiped && hypot(e.rawX - downX, e.rawY - downY) < dp(12f)) tap(e.rawX, e.rawY)
                pressed = false
            }
            MotionEvent.ACTION_CANCEL -> {
                main.removeCallbacks(longPress)
                pressed = false
            }
        }
        return true
    }

    private fun swipeUp() {
        when (shown) {
            Mode.Panel -> panelOpen = false
            Mode.Message -> message = null
            Mode.Music -> dismissedKey = media.track?.key
            Mode.Hidden -> Unit
        }
        refresh()
    }

    private fun tap(x: Float, y: Float) {
        when (shown) {
            Mode.Music -> {
                panelOpen = true
                main.removeCallbacks(closePanel)
                main.postDelayed(closePanel, 8000)
            }
            Mode.Panel -> {
                main.removeCallbacks(closePanel)
                main.postDelayed(closePanel, 8000)
                when {
                    hitPlay.contains(x, y) -> media.toggle()
                    hitPrev.contains(x, y) -> media.prev()
                    hitNext.contains(x, y) -> media.next()
                    hitBar.contains(x, y) -> media.track?.durationMs?.takeIf { it > 0 }?.let { d ->
                        media.seek((d * ((x - hitBar.left) / hitBar.width()).coerceIn(0f, 1f)).toLong())
                    }
                    hitInfo.contains(x, y) -> media.track?.pkg?.let { pkg ->
                        context.packageManager.getLaunchIntentForPackage(pkg)?.let { open(it); panelOpen = false }
                    }
                    else -> return
                }
                performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
            }
            Mode.Message -> {
                message?.intent?.let { send(it) }
                message = null
            }
            Mode.Hidden -> Unit
        }
        refresh()
    }

    private fun open(i: Intent) {
        try {
            context.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Exception) { }
    }

    @Suppress("DEPRECATION")
    private fun send(p: PendingIntent) {
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                val o = ActivityOptions.makeBasic().setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
                p.send(context, 0, null, null, null, null, o.toBundle())
            } else p.send()
        } catch (_: Exception) { }
    }

    companion object {
        private const val PAUSED_MS = 20_000L
    }
}
