package io.github.odlaver.tepi

import android.annotation.SuppressLint
import android.app.ActivityOptions
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.DisplayMetrics
import android.view.Choreographer
import android.view.Display
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import io.github.odlaver.tepi.scene.Action
import io.github.odlaver.tepi.scene.HomeRow
import io.github.odlaver.tepi.scene.HomeScene
import io.github.odlaver.tepi.scene.Host
import io.github.odlaver.tepi.scene.MenuScene
import io.github.odlaver.tepi.scene.Message
import io.github.odlaver.tepi.scene.MessageScene
import io.github.odlaver.tepi.scene.MusicRow
import io.github.odlaver.tepi.scene.MusicScene
import io.github.odlaver.tepi.scene.Scene
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

// Overlay around the front camera; hosts scenes
@SuppressLint("ViewConstructor")
class Island(
    context: Context,
    private val probeCtx: Context?,
    private val screen: Display,
    private val type: Int,
    media: Media,
    private val prefs: Prefs,
    lyrics: Lyrics,
) : View(context), Host {
    private enum class Kind { Hidden, Compact, Panel }

    override val ui = Ui(context)
    override val ctx: Context get() = context
    private val wm = context.getSystemService(WindowManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private val touchable get() = type == WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY

    // Scenes
    val music = MusicScene(this, media, prefs, lyrics)
    val messages = MessageScene(this)
    val home = HomeScene(this) { homeActions() }
    private val menu = MenuScene(this) { menuItems() }
    private val scenes = arrayListOf<Scene>(messages, music)
    val menuExtras = ArrayList<() -> Action?>()
    val homeExtras = ArrayList<() -> Action?>()

    private val baseFlags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
        WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
        WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
    private val lp = WindowManager.LayoutParams(
        1, 1, type, baseFlags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE, PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        title = "Tepi"
        layoutInDisplayCutoutMode = if (Build.VERSION.SDK_INT >= 30) WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        else WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        if (Build.VERSION.SDK_INT >= 30) fitInsetsTypes = 0
    }
    private var attached = false
    private var input: Input? = null

    // Geometry, screen px
    private var screenW = 0f
    private val hole = RectF()
    private val pill = RectF()
    private var statusBottom = 0f
    private var wingBase = 0f
    private val l = Spring(0f, 0.3f)
    private val t = Spring(0f, 0.3f)
    private val r = Spring(0f, 0.3f)
    private val b = Spring(0f, 0.3f)
    private val rad = Spring(0f, 0.3f)
    private val neck = Spring(0f, 0.3f)
    private val split = Spring(0f, 0.3f)
    private val radB = Spring(0f, 0.3f)
    private val bubbleK = Spring(0f, 0.004f, 420f, 30f)
    private val contentA = Spring(0f, 0.004f, 520f, 46f)
    private val now = RectF()
    private val target = RectF()
    private val bubble = RectF()

    // State
    private var kind = Kind.Hidden
    private var primary: Scene? = null
    private var second: Scene? = null
    private var opened: Scene? = null
    private var shownKey = ""
    private var wantedKey = ""
    private var shownScene: Scene? = null
    private var shownKind = Kind.Hidden
    private var fullscreen = false
    private var hiddenUntil = 0L
    private var lastTouch = 0L

    private val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK }
    private val shape = Path()
    private val tierA = Path()
    private val tierB = Path()
    private val box = RectF()
    private val loc = IntArray(2)
    private val zoneL = RectF()
    private val zoneR = RectF()
    private val content = RectF()

    init {
        home.rows.add(MusicRow(this, music, media))
    }

    fun register(scene: Scene, homeRow: HomeRow? = null) {
        scenes.add(scene)
        if (homeRow != null) home.rows.add(homeRow)
    }

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
        input?.close()
        main.removeCallbacksAndMessages(null)
        Choreographer.getInstance().removeFrameCallback(frame)
        ticking = false
        wm.removeView(this)
        if (probing) { try { probeCtx?.getSystemService(WindowManager::class.java)?.removeView(probe) } catch (_: Exception) { }; probing = false }
        attached = false
    }

    fun show(m: Message) {
        if (opened != null && opened !== messages) opened = null
        messages.show(m)
    }

    fun mediaChanged() = music.changed()

    fun prefsChanged() {
        measureScreen()
        music.reload()
    }

    fun openHome() = expand(home)

    // Host
    override fun expand(scene: Scene) {
        opened = scene
        lastTouch = SystemClock.uptimeMillis()
        armClose()
        refresh()
    }

    override fun collapse() {
        if (opened == null) return
        opened = null
        refresh()
    }

    override fun open(i: Intent) {
        try { context.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) { }
    }

    @Suppress("DEPRECATION")
    override fun send(p: PendingIntent) {
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                val o = ActivityOptions.makeBasic().setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
                p.send(context, 0, null, null, null, null, o.toBundle())
            } else p.send()
        } catch (_: Exception) { }
    }

    override fun haptic() {
        performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
    }

    override fun input(hint: String, initial: String, done: (String) -> Unit) {
        val card = input ?: Input(context, type, ui).also { input = it }
        val w = min(screenW - ui.edge * 2, ui.panelMax)
        main.removeCallbacks(closePanel)
        card.show(hint, initial, (hole.centerX() - w / 2).toInt(), (statusBottom + ui.s8).toInt(), w.toInt()) { text ->
            armClose()
            done(text)
        }
    }

    private val closePanel = Runnable {
        if (opened != null && SystemClock.uptimeMillis() - lastTouch >= CLOSE_MS - 100) { opened = null; refresh() }
    }

    private fun armClose() {
        main.removeCallbacks(closePanel)
        main.postDelayed(closePanel, CLOSE_MS)
    }

    private fun homeActions(): List<Action> {
        val out = ArrayList<Action>()
        for (f in homeExtras) f()?.let { out.add(it) }
        out.add(Action(context.getString(R.string.settings), icon = Icon.Gear) { open(Intent(context, MainActivity::class.java)); collapse() })
        return out.take(3)
    }

    private fun menuItems(): List<Action> {
        val out = ArrayList<Action>()
        for (f in menuExtras) f()?.let { out.add(it) }
        out.add(Action(context.getString(R.string.home), icon = Icon.Home) { main.post { openHome() } })
        if (hiddenUntil > SystemClock.uptimeMillis()) out.add(Action(context.getString(R.string.unhide), icon = Icon.Eye) { hiddenUntil = 0; refresh() })
        else out.add(Action(context.getString(R.string.hide_hour), icon = Icon.Hide) { hiddenUntil = SystemClock.uptimeMillis() + 3_600_000L; refresh() })
        out.add(Action(context.getString(R.string.settings), icon = Icon.Gear) { open(Intent(context, MainActivity::class.java)) })
        return out
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
                val rr = hole.width() / 2
                val cy = hole.height() / 2
                hole.set(hole.centerX() - rr, cy - rr, hole.centerX() + rr, cy + rr)
            }
        } else {
            hole.set(screenW / 2 - ui.dp(10f), ui.dp(10f), screenW / 2 + ui.dp(10f), ui.dp(30f))
        }
        hole.offset(0f, ui.dp(prefs.offsetDp.toFloat()))
        val pad = ui.dp(5f)
        pill.set(hole.left - pad, max(0f, hole.top - pad), hole.right + pad, hole.bottom + pad)
        if (pill.height() < ui.dp(30f)) pill.bottom = pill.top + ui.dp(30f)
        val bar = resources.getIdentifier("status_bar_height", "dimen", "android").let { if (it != 0) resources.getDimensionPixelSize(it) else 0 }
        statusBottom = max(pill.bottom, (cut?.safeInsetTop?.takeIf { it > 0 } ?: bar).toFloat())
        wingBase = max(pill.height() * 1.1f, ui.dp(30f))
    }

    private val landscape get() = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    private val wide get() = min(screenW - ui.edge * 2, ui.panelMax)

    private fun choose() {
        val o = opened
        if (o != null && o !== home && o !== menu && !o.live() && !o.keepOpen()) opened = null
        val live = scenes.filter { it.live() }.sortedByDescending { it.priority }
        val quiet = landscape || fullscreen || hiddenUntil > SystemClock.uptimeMillis()
        val shown = if (quiet) live.filter { it.priority >= URGENT } else live
        val op = opened
        when {
            op != null -> { kind = Kind.Panel; primary = op; second = null }
            shown.firstOrNull()?.card() == true -> { kind = Kind.Panel; primary = shown.first(); second = null }
            shown.isNotEmpty() -> {
                kind = Kind.Compact
                primary = shown[0]
                second = shown.getOrNull(1)?.takeIf { !it.card() }
            }
            else -> { kind = Kind.Hidden; primary = null; second = null }
        }
    }

    private fun compactWidth(s: Scene?) = pill.width() + max(s?.wing() ?: 0f, wingBase) * 2

    // Recompute target, start motion
    override fun refresh() {
        if (!attached) return
        choose()
        val cx = hole.centerX()
        var nk: Float
        var sy: Float
        var rt = pill.height() / 2
        var rb = rt
        when (kind) {
            Kind.Hidden -> {
                target.set(hole); nk = hole.width(); sy = hole.top; rt = hole.height() / 2; rb = rt
            }
            Kind.Compact -> {
                val w = compactWidth(primary)
                val row = primary?.rowWidth() ?: 0f
                if (row > 0f) {
                    val bw = row.coerceIn(w, wide)
                    target.set(cx - bw / 2, pill.top, cx + bw / 2, statusBottom + ui.dp(32f))
                    nk = w; sy = statusBottom; rb = ui.dp(18f)
                } else {
                    target.set(cx - w / 2, pill.top, cx + w / 2, pill.bottom)
                    nk = w; sy = pill.top
                }
            }
            Kind.Panel -> {
                val w = wide
                val h = primary?.panelHeight(w - ui.pad * 2) ?: 0f
                target.set(cx - w / 2, pill.top, cx + w / 2, statusBottom + ui.s16 + h + ui.pad)
                nk = min(compactWidth(if (primary?.card() == true) null else primary), w)
                sy = statusBottom; rb = ui.radiusPanel
            }
        }
        // Keep clear of the screen edges
        val m = ui.edge
        if (target.left < m) target.offset(m - target.left, 0f)
        if (target.right > screenW - m) target.offset(screenW - m - target.right, 0f)

        l.target = target.left; t.target = target.top; r.target = target.right; b.target = target.bottom
        neck.target = nk; split.target = sy; rad.target = rt; radB.target = rb
        bubbleK.target = if (second != null) 1f else 0f
        val d = pill.height()
        bubble.set(target.right + ui.s8, pill.top, target.right + ui.s8 + d, pill.bottom)

        wantedKey = "$kind:${primary?.id}"
        if (wantedKey != shownKey) contentA.target = 0f
        contentDescription = primary?.describe()

        // Grow first; shrink once settled
        val union = RectF(target)
        union.union(l.value, t.value, r.value, b.value)
        if (second != null || bubbleK.value > 0.01f) union.union(bubble)
        place(union, kind != Kind.Hidden || hotspot())
        kick()
    }

    private fun hotspot() = touchable && prefs.cameraTap

    // Window covers the moving shape only
    private fun place(rect: RectF, touch: Boolean) {
        val grow = if (kind == Kind.Hidden && touch) ui.s8 else 0f
        val x = (rect.left - grow).toInt() - 2
        val y = max(0, (rect.top - grow).toInt() - 2)
        lp.x = x
        lp.y = y
        lp.width = max(1, (rect.right + grow).toInt() + 2 - x)
        lp.height = max(1, (rect.bottom + grow).toInt() + 2 - y)
        lp.flags = baseFlags or if (touch) 0 else WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
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
        for (s in arrayOf(l, t, r, b, rad, neck, split, radB, bubbleK)) moving = s.step(dt) or moving
        moving = contentA.step(dt) or moving
        if (contentA.value <= 0.05f && contentA.target == 0f) {
            shownKey = wantedKey
            shownScene = primary
            shownKind = kind
            contentA.target = if (kind == Kind.Hidden) 0f else 1f
            if (kind == Kind.Hidden) contentA.snap(0f)
        }
        for (s in scenes) moving = s.step(dt) or moving
        val op = opened
        if (op != null && op !in scenes) moving = op.step(dt) or moving

        val live = kind != Kind.Hidden && (primary?.ticking() == true || second?.ticking() == true)
        // Live content at half rate
        if (moving || !live || frameNo++ % 2 == 0) invalidate()
        if (moving || live) Choreographer.getInstance().postFrameCallback(frame)
        else {
            ticking = false
            settle()
        }
    }

    private fun settle() {
        if (kind == Kind.Hidden) place(if (hotspot()) pill else hole, hotspot())
        else {
            val rect = RectF(target)
            if (second != null) rect.union(bubble)
            place(rect, true)
        }
    }

    override fun onDraw(canvas: Canvas) {
        now.set(l.value, t.value, r.value, b.value)
        if (kind == Kind.Hidden && shownKind == Kind.Hidden && !ticking) return
        getLocationOnScreen(loc)
        canvas.save()
        canvas.translate(-loc[0].toFloat(), -loc[1].toFloat())
        buildShape(shape)
        val k = bubbleK.value.coerceIn(0f, 1f)
        if (k > 0.01f) shape.addCircle(bubble.centerX(), bubble.centerY(), pill.height() / 2 * k, Path.Direction.CW)
        canvas.drawPath(shape, bg)
        canvas.clipPath(shape)
        val a = contentA.value.coerceIn(0f, 1f)
        val s = shownScene
        if (a > 0.01f && s != null) when (shownKind) {
            Kind.Compact -> {
                zones(now)
                s.drawCompact(canvas, zoneL, zoneR, a)
                if (now.bottom > pill.bottom + 1f) {
                    val rowA = ((now.bottom - statusBottom) / ui.dp(32f)).coerceIn(0f, 1f) * a
                    box.set(now.left, statusBottom, now.right, now.bottom)
                    if (rowA > 0.02f) s.drawRow(canvas, box, rowA)
                }
            }
            Kind.Panel -> {
                content.set(target.left + ui.pad, statusBottom + ui.s16, target.right - ui.pad, target.bottom - ui.pad)
                s.drawPanel(canvas, content, a)
            }
            Kind.Hidden -> Unit
        }
        val sec = second
        if (k > 0.05f && sec != null && shownKind == Kind.Compact) {
            box.set(bubble)
            sec.drawBubble(canvas, box, k * a)
        }
        canvas.restore()
    }

    // Left and right of the camera inside the neck
    private fun zones(at: RectF) {
        val nk = min(neck.value, at.width())
        val left = at.centerX() - nk / 2
        val inset = max(ui.dp(7f), (pill.height() - ui.dp(26f)) / 2)
        zoneL.set(left + inset, pill.top + inset, hole.left - ui.s8, pill.bottom - inset)
        zoneR.set(hole.right + ui.s8, pill.top + inset, left + nk - inset, pill.bottom - inset)
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
        val f = min(ui.dp(12f), min((w - nk) / 2 - rb, sy - now.top))
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

    // Touch
    private var downX = 0f
    private var downY = 0f
    private var pressed = false
    private var acted = false
    private val longPress = Runnable {
        if (pressed && !acted) {
            acted = true
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            expand(menu)
        }
    }

    private fun inside(x: Float, y: Float): Boolean {
        if (kind == Kind.Hidden) {
            box.set(pill); box.inset(-ui.s8, -ui.s8)
            return hotspot() && box.contains(x, y)
        }
        if (now.contains(x, y)) return true
        return second != null && bubble.contains(x, y)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        lastTouch = SystemClock.uptimeMillis()
        when (e.actionMasked) {
            MotionEvent.ACTION_OUTSIDE -> {
                if (opened != null) collapse()
                return false
            }
            MotionEvent.ACTION_DOWN -> {
                if (!inside(e.rawX, e.rawY)) return false
                downX = e.rawX
                downY = e.rawY
                pressed = true
                acted = false
                main.postDelayed(longPress, 500)
            }
            MotionEvent.ACTION_MOVE -> {
                if (!pressed || acted) return true
                val dx = e.rawX - downX
                val dy = e.rawY - downY
                val slop = ui.dp(24f)
                when {
                    dy > slop && abs(dy) > abs(dx) -> { acted = true; main.removeCallbacks(longPress); swipeDown() }
                    dy < -slop && abs(dy) > abs(dx) -> { acted = true; main.removeCallbacks(longPress); swipeUp() }
                    abs(dx) > ui.dp(48f) && kind == Kind.Panel -> { acted = true; main.removeCallbacks(longPress); page(if (dx < 0) 1 else -1) }
                }
            }
            MotionEvent.ACTION_UP -> {
                main.removeCallbacks(longPress)
                if (pressed && !acted && hypot(e.rawX - downX, e.rawY - downY) < ui.dp(12f)) tap(e.rawX, e.rawY)
                pressed = false
            }
            MotionEvent.ACTION_CANCEL -> {
                main.removeCallbacks(longPress)
                pressed = false
            }
        }
        return true
    }

    private fun swipeDown() {
        when (kind) {
            Kind.Hidden -> openHome()
            Kind.Compact -> primary?.let { expand(it) }
            Kind.Panel -> Unit
        }
    }

    private fun swipeUp() {
        when (kind) {
            Kind.Panel -> if (opened != null) collapse() else primary?.dismiss()
            Kind.Compact -> primary?.dismiss()
            Kind.Hidden -> Unit
        }
        refresh()
    }

    // Next or previous live scene, Home last
    private fun page(dir: Int) {
        val pages = scenes.filter { it.live() && !it.card() }.sortedByDescending { it.priority } + home
        val cur = pages.indexOf(primary)
        val next = pages[((if (cur < 0) 0 else cur) + dir + pages.size) % pages.size]
        haptic()
        expand(next)
    }

    private fun tap(x: Float, y: Float) {
        when (kind) {
            Kind.Hidden -> openHome()
            Kind.Compact -> {
                val sec = second
                if (sec != null && bubble.contains(x, y)) expand(sec)
                else primary?.let { if (!it.tapCompact()) expand(it) }
            }
            Kind.Panel -> {
                // Neck around the camera toggles closed
                if (y < statusBottom) { if (opened != null) collapse() else primary?.dismiss() }
                else { armClose(); primary?.tap(x, y) }
            }
        }
        refresh()
    }

    companion object {
        private const val CLOSE_MS = 9000L
        const val URGENT = 95
    }
}
