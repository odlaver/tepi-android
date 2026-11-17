package io.github.odlaver.tepi

import android.annotation.SuppressLint
import android.app.ActivityOptions
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Shader
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.hardware.display.DisplayManager
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
import android.view.Surface
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
    private val keyguard = context.getSystemService(android.app.KeyguardManager::class.java)
    override val locked get() = keyguard.isKeyguardLocked

    // Lock and unlock change what may be shown
    private val lockWatch = object : android.content.BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            dark = i.action == Intent.ACTION_SCREEN_OFF
            if (locked && opened?.personal == true) opened = null
            refresh()
        }
    }
    private var dark = false
    private var listening = false
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
        gravity = Gravity.TOP or Gravity.LEFT
        title = "Tepi"
        if (Build.VERSION.SDK_INT >= 30) layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        else if (Build.VERSION.SDK_INT >= 28) layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
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
    private val bodyA = Spring(1f, 0.004f, 700f, 53f)
    private val gap = Spring(0f, 0.3f)
    private val lid = Spring(1f, 0.01f, 900f, 50f)
    private val shaped = arrayOf(l, t, r, b, rad, neck, split, radB, gap)
    private val springs = arrayOf(l, t, r, b, rad, neck, split, radB, bubbleK, gap)
    private val shapeKey = FloatArray(shaped.size + 1) { Float.NaN }
    private val rest = RectF()
    private val sysHole = RectF()

    // Vertical edges wait for the horizontal ones
    private var pendingAt = 0L
    private var pendingTune: Tune? = null
    private val pendingT = FloatArray(2)
    private var opening = true
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
    private var silenced = false
    private var hiddenUntil = 0L

    // Camera on the current top edge
    private var docked = true
    private var turned = false
    private var wideScreen = false
    private var rotation = -1

    // Phone on its side, not a landscape tablet
    private val sideways get() = turned && wideScreen
    private var lastTouch = 0L

    private val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK }
    private val sheen = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val unit = Matrix()
    private var material = ""

    // Calibration page is open
    var calibrating = false
        set(v) { field = v; refresh() }

    // Battery for the camera ring
    private var level = -1
    private var charging = false
    private var batteryOn = false
    private val battery = object : android.content.BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            val l = i.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1) * 100 / max(1, i.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, 100))
            val ch = i.getIntExtra(android.os.BatteryManager.EXTRA_PLUGGED, 0) != 0
            if (l == level && ch == charging) return
            level = l; charging = ch
            if (kind == Kind.Hidden) invalidate()
        }
    }
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
        restyle()
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
        try {
            val f = android.content.IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON); addAction(Intent.ACTION_SCREEN_OFF); addAction(Intent.ACTION_USER_PRESENT)
            }
            if (Build.VERSION.SDK_INT >= 33) context.applicationContext.registerReceiver(lockWatch, f, Context.RECEIVER_NOT_EXPORTED)
            else context.applicationContext.registerReceiver(lockWatch, f)
            listening = true
        } catch (_: Exception) { }
        watchBattery()
        if (probeCtx != null && Settings.canDrawOverlays(context)) {
            val p = WindowManager.LayoutParams(1, 1, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE, PixelFormat.TRANSLUCENT)
            p.gravity = Gravity.TOP or Gravity.LEFT
            try { probeCtx.getSystemService(WindowManager::class.java).addView(probe, p); probing = true } catch (_: Exception) { }
        }
        if (Build.VERSION.SDK_INT < 30) watchSysUi(true)
        rotation = screen.rotation
        try { displays.registerDisplayListener(turnWatch, main) } catch (_: Exception) { }
        refresh()
    }

    // A 180° turn changes no configuration
    private val displays = context.getSystemService(DisplayManager::class.java)
    private val turnWatch = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(id: Int) = Unit
        override fun onDisplayRemoved(id: Int) = Unit
        override fun onDisplayChanged(id: Int) {
            // Refresh-rate switches land here too
            if (id != screen.displayId || !attached || screen.rotation == rotation) return
            rotation = screen.rotation
            measureScreen()
            refresh()
        }
    }

    // Android 8 to 10 have no inset visibility
    @Suppress("DEPRECATION")
    private val sysUi = View.OnSystemUiVisibilityChangeListener { v -> full(v and View.SYSTEM_UI_FLAG_FULLSCREEN != 0) }

    @Suppress("DEPRECATION")
    private fun watchSysUi(on: Boolean) {
        val l = if (on) sysUi else null
        if (type == WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY) setOnSystemUiVisibilityChangeListener(l)
        if (probing) probe.setOnSystemUiVisibilityChangeListener(l)
    }

    fun detach() {
        if (!attached) return
        input?.close()
        main.removeCallbacksAndMessages(null)
        Choreographer.getInstance().removeFrameCallback(frame)
        ticking = false
        try { displays.unregisterDisplayListener(turnWatch) } catch (_: Exception) { }
        if (Build.VERSION.SDK_INT < 30) watchSysUi(false)
        wm.removeView(this)
        if (listening) { try { context.applicationContext.unregisterReceiver(lockWatch) } catch (_: Exception) { }; listening = false }
        if (probing) { try { probeCtx?.getSystemService(WindowManager::class.java)?.removeView(probe) } catch (_: Exception) { }; probing = false }
        main.removeCallbacks(blink)
        unwatchBattery()
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
        restyle()
        if (attached) watchBattery()
        refresh()
    }

    // Ring only: battery broadcasts otherwise unneeded
    private fun watchBattery() {
        val want = prefs.idleShape == "ring"
        if (want == batteryOn) return
        if (!want) { unwatchBattery(); return }
        try {
            context.applicationContext.registerReceiver(battery, android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED))?.let { battery.onReceive(context, it) }
            batteryOn = true
        } catch (_: Exception) { }
    }

    private fun unwatchBattery() {
        if (!batteryOn) return
        try { context.applicationContext.unregisterReceiver(battery) } catch (_: Exception) { }
        batteryOn = false
    }

    // Theme, material, and accent into Ui
    override fun restyle() {
        val night = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        val dark = when (prefs.theme) { "light" -> false; "auto" -> night; else -> true }
        material = prefs.material
        val base = when {
            material == "frost" && dark -> Color.rgb(46, 46, 50)
            material == "frost" -> Color.rgb(226, 226, 230)
            dark -> Color.BLACK
            else -> Color.rgb(248, 248, 250)
        }
        ui.restyle(dark, base, Color.WHITE, prefs.widthDp, prefs.radiusDp)
        val acc = when (prefs.accent) {
            "cover" -> ui.coverAccent() ?: ui.ink
            "green" -> if (dark) Color.rgb(48, 209, 88) else Color.rgb(36, 138, 61)
            "blue" -> if (dark) Color.rgb(100, 170, 255) else Color.rgb(0, 88, 208)
            "orange" -> if (dark) Color.rgb(255, 170, 60) else Color.rgb(178, 80, 0)
            "pink" -> if (dark) Color.rgb(255, 120, 170) else Color.rgb(196, 30, 100)
            else -> ui.ink
        }
        ui.accent = acc
        bg.color = base
        bg.alpha = prefs.opacity * 255 / 100
        // Glass highlight in unit space
        val hi = if (dark) Color.argb(46, 255, 255, 255) else Color.argb(150, 255, 255, 255)
        sheen.shader = if (material == "liquid") LinearGradient(0f, 0f, 0f, 1f, intArrayOf(hi, Color.TRANSPARENT), floatArrayOf(0f, 0.45f), Shader.TileMode.CLAMP) else null
        rim.strokeWidth = ui.dp(1f)
        rim.shader = when {
            material == "liquid" -> LinearGradient(0f, 0f, 0f, 1f, intArrayOf(if (dark) Color.argb(90, 255, 255, 255) else Color.argb(200, 255, 255, 255),
                if (dark) Color.argb(14, 255, 255, 255) else Color.argb(30, 0, 0, 0)), null, Shader.TileMode.CLAMP)
            else -> null
        }
        rim.color = if (dark) Color.argb(if (material == "frost") 40 else 46, 255, 255, 255) else Color.argb(36, 0, 0, 0)
        invalidate()
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
        val x = (hole.centerX() - w / 2).coerceIn(ui.edge, max(ui.edge, screenW - ui.edge - w))
        card.show(hint, initial, x.toInt(), (statusBottom + ui.s8).toInt(), w.toInt()) { text ->
            armClose()
            done(text)
        }
    }

    private val closePanel = Runnable {
        if (opened != null && SystemClock.uptimeMillis() - lastTouch >= closeMs - 100) { opened = null; refresh() }
    }

    private val closeMs get() = prefs.closeSec * 1000L

    private fun armClose() {
        main.removeCallbacks(closePanel)
        main.postDelayed(closePanel, closeMs)
    }

    private fun homeActions(): List<Action> {
        val out = ArrayList<Action>()
        for (f in homeExtras) f()?.let { out.add(it) }
        out.add(Action(context.getString(R.string.settings), icon = Icon.Gear, personal = true) { open(Intent(context, MainActivity::class.java)); collapse() })
        return out.filter { !(locked && it.personal) }.take(3)
    }

    private fun menuItems(): List<Action> {
        val out = ArrayList<Action>()
        for (f in menuExtras) f()?.let { out.add(it) }
        out.add(Action(context.getString(R.string.home), icon = Icon.Home) { main.post { openHome() } })
        if (hiddenUntil > SystemClock.uptimeMillis()) out.add(Action(context.getString(R.string.unhide), icon = Icon.Eye) { hiddenUntil = 0; refresh() })
        else out.add(Action(context.getString(R.string.hide_hour), icon = Icon.Hide) { hiddenUntil = SystemClock.uptimeMillis() + 3_600_000L; refresh() })
        out.add(Action(context.getString(R.string.settings), icon = Icon.Gear, personal = true) { open(Intent(context, MainActivity::class.java)) })
        return out.filter { !(locked && it.personal) }
    }

    override fun onConfigurationChanged(newConfig: Configuration?) {
        super.onConfigurationChanged(newConfig)
        rotation = screen.rotation
        measureScreen()
        restyle()
        refresh()
    }

    override fun onApplyWindowInsets(insets: WindowInsets): WindowInsets {
        if (type == WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY) sense(insets)
        // Android 9 knows the cutout only now
        if (Build.VERSION.SDK_INT == 28 && sysHole.isEmpty && insets.displayCutout != null) { measureScreen(); main.post { refresh() } }
        return super.onApplyWindowInsets(insets)
    }

    // Status bar gone: a fullscreen app is up
    private fun sense(insets: WindowInsets) {
        if (Build.VERSION.SDK_INT < 30) return
        full(!insets.isVisible(WindowInsets.Type.statusBars()))
    }

    // Hide at once; return only once the bar stays
    private fun full(on: Boolean) {
        main.removeCallbacks(back)
        if (!on) { if (fullscreen) main.postDelayed(back, BACK_MS); return }
        if (fullscreen) return
        fullscreen = true
        if (prefs.hideFullscreen) { opened = null; input?.close() }
        refresh()
    }

    private val back = Runnable { fullscreen = false; refresh() }

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
        shapeKey[0] = Float.NaN
        val m = DisplayMetrics()
        screen.getRealMetrics(m)
        screenW = m.widthPixels.toFloat()
        wideScreen = m.widthPixels > m.heightPixels
        turned = screen.rotation != Surface.ROTATION_0
        val found = cameraRect()
        docked = found != null
        if (found != null) {
            hole.set(found)
            // Bar down from the edge: a round hole inside
            if (hole.top <= 1f && hole.height() > hole.width() * 1.4f) {
                val rr = hole.width() / 2
                val cy = hole.height() / 2
                hole.set(hole.centerX() - rr, cy - rr, hole.centerX() + rr, cy + rr)
            }
            sysHole.set(hole)
            if (prefs.anchor == "center") hole.offset(screenW / 2 - hole.centerX(), 0f)
            // Calibration in half-dp steps, real lens only
            val grow = ui.dp(prefs.calSize / 4f)
            hole.inset(-grow, -grow)
            hole.offset(ui.dp(prefs.calX / 2f), ui.dp(prefs.calY / 2f))
        } else {
            // Floating: dead centre, height still adjustable
            hole.set(screenW / 2 - ui.dp(10f), ui.dp(10f), screenW / 2 + ui.dp(10f), ui.dp(30f))
            if (!turned) hole.offset(0f, ui.dp(prefs.calY / 2f))
            sysHole.setEmpty()
        }
        val pad = ui.dp(5f)
        pill.set(hole.left - pad, max(0f, hole.top - pad), hole.right + pad, hole.bottom + pad)
        if (pill.height() < ui.dp(30f)) pill.bottom = pill.top + ui.dp(30f)
        val bar = resources.getIdentifier("status_bar_height", "dimen", "android").let { if (it != 0) resources.getDimensionPixelSize(it) else 0 }
        val safe = if (Build.VERSION.SDK_INT >= 29) screen.cutout?.safeInsetTop ?: 0 else 0
        statusBottom = max(pill.bottom, (if (safe > 0) safe else bar).toFloat())
        wingBase = max(pill.height() * 1.3f, ui.dp(36f))
        // Idle pill hugs the camera
        val rw = pill.height() * if (prefs.idleEyes) 3.4f else 2.2f
        rest.set(hole.centerX() - rw / 2, pill.top, hole.centerX() + rw / 2, pill.bottom)
        if (rest.left < ui.dp(4f)) rest.offset(ui.dp(4f) - rest.left, 0f)
        if (rest.right > screenW - ui.dp(4f)) rest.offset(screenW - ui.dp(4f) - rest.right, 0f)
    }

    // Cutout Android assigns to the top edge
    // A corner lens turned sideways belongs to a side edge
    private fun cameraRect(): RectF? {
        if (Build.VERSION.SDK_INT < 28) return null
        val cut = (if (Build.VERSION.SDK_INT >= 29) screen.cutout else rootWindowInsets?.displayCutout) ?: return null
        val out = RectF()
        if (Build.VERSION.SDK_INT >= 29) out.set(RectF(cut.boundingRectTop))
        else if (cut.safeInsetTop > 0) for (r in cut.boundingRects) if (!r.isEmpty && r.top <= 0) out.union(RectF(r))
        if (out.isEmpty) return null
        if (Build.VERSION.SDK_INT >= 31) cut.cutoutPath?.let { p ->
            val b = RectF()
            p.computeBounds(b, true)
            if (b.intersect(out)) out.set(b)
        }
        return out
    }

    private val wide get() = min(screenW - ui.edge * 2, ui.panelMax)

    private fun choose() {
        val o = opened
        if (o != null && o !== home && o !== menu && !o.live() && !o.keepOpen()) opened = null
        val lock = locked
        val live = scenes.filter { it.live() && !(lock && it.personal) }.sortedByDescending { it.priority }
        // The fullscreen probe goes dark under the lock screen; ignore it there
        val quiet = (sideways && prefs.hideLandscape) || (fullscreen && !lock && prefs.hideFullscreen) || hiddenUntil > SystemClock.uptimeMillis() || (lock && !prefs.bool("lockscreen", true))
        silenced = quiet
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

    // No lens to clear when floating
    private fun compactWidth(s: Scene?) = (if (docked) pill.width() else ui.s12) + max(s?.wing() ?: 0f, wingBase) * 2

    // Open bouncy, close settled
    private fun tunes(open: Boolean): Tune = when {
        systemStill() -> SNAP
        minimal -> MIN
        prefs.motion == "smooth" -> if (open) SMOOTH_OPEN else CLOSE
        else -> if (open) OPEN else CLOSE
    }

    // Minim, or low-power mode
    private val minimal get() = prefs.motion == "min" || Power.low(prefs)

    // Animations off in system settings
    private fun systemStill() = !android.animation.ValueAnimator.areAnimatorsEnabled()

    // Silenced or sideways: nothing at rest
    // Floating has no lens to ring
    private val idleShape get() = when {
        calibrating -> "pill"
        silenced -> "hide"
        docked -> prefs.idleShape
        sideways || prefs.idleShape != "pill" -> "hide"
        else -> "pill"
    }
    private val floating get() = prefs.panelStyle == "floating"

    // Recompute target, start motion
    override fun refresh() {
        if (!attached) return
        choose()
        val cx = hole.centerX()
        var nk: Float
        var sy: Float
        var rt = pill.height() / 2
        var rb = rt
        var gp = 0f
        when (kind) {
            Kind.Hidden -> {
                if (idleShape == "pill") { target.set(rest); nk = rest.width(); sy = rest.top }
                else { target.set(hole); nk = hole.width(); sy = hole.top; rt = hole.height() / 2; rb = rt }
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
                val dots = if (opened != null && pages().size > 1) ui.dp(18f) else 0f
                if (floating) gp = ui.s8
                target.set(cx - w / 2, pill.top, cx + w / 2, statusBottom + gp + ui.s16 + h + ui.pad + dots)
                nk = if (floating) rest.width() else min(compactWidth(if (primary?.card() == true) null else primary), w)
                sy = statusBottom; rb = ui.radiusPanel
            }
        }
        // Keep clear of the screen edges
        val m = ui.edge
        if (target.left < m) target.offset(m - target.left, 0f)
        if (target.right > screenW - m) target.offset(screenW - m - target.right, 0f)

        // Growing or shrinking decides the feel
        val area = (r.target - l.target) * (b.target - t.target)
        val open = target.width() * target.height() >= area - 1f
        val tune = tunes(open)
        opening = open
        val lag = if (tune === SNAP || tune === MIN) 0L else 40L
        l.to(target.left, tune); r.to(target.right, tune)
        neck.to(nk, tune); rad.to(rt, tune); radB.to(rb, tune); gap.to(gp, tune)
        if (open && lag > 0) {
            // Width leads, height follows
            pendingT[0] = target.top; pendingT[1] = target.bottom
            pendingTune = tune
            pendingAt = SystemClock.uptimeMillis() + lag
            split.to(sy, tune)
        } else {
            pendingTune = null
            t.to(target.top, tune); b.to(target.bottom, tune); split.to(sy, tune)
        }
        if (tune === SNAP) for (sp in shaped) sp.snap(sp.target)
        bubbleK.target = if (second != null) 1f else 0f
        val d = pill.height()
        bubble.set(target.right + ui.s8, pill.top, target.right + ui.s8 + d, pill.bottom)

        wantedKey = "$kind:${primary?.id}"
        if (wantedKey != shownKey) contentA.to(0f, FADE_OUT)
        if (kind != Kind.Hidden || idleShape == "pill") bodyA.to(1f, FADE_IN)
        contentDescription = primary?.describe()
        armBlink()

        // Grow first; shrink once settled
        val union = RectF(target)
        union.union(l.value, t.value, r.value, b.value)
        if (second != null || bubbleK.value > 0.01f) union.union(bubble)
        if (kind == Kind.Hidden && idleShape == "ring") union.union(ringBox())
        // Parked and still parked: leave the window alone
        val resting = kind == Kind.Hidden && idleShape == "hide" && !hotspot() && bodyA.value < 0.01f &&
            abs(l.value - target.left) < 0.5f && abs(r.value - target.right) < 0.5f && abs(t.value - target.top) < 0.5f && abs(b.value - target.bottom) < 0.5f
        if (resting) park() else place(union, kind != Kind.Hidden || hotspot())
        kick()
    }

    // Shape close enough to its target
    private fun grown(): Float {
        val w = r.value - l.value
        val h = b.value - t.value
        val tw = max(1f, target.width())
        val th = max(1f, target.height())
        return min(min(w, tw) / max(w, tw), min(h, th) / max(h, th))
    }

    private fun ringBox() = RectF(hole).apply { inset(-ui.dp(7f), -ui.dp(7f)) }

    // Blink now and then while idle
    private val blink = Runnable {
        if (kind == Kind.Hidden && idleShape == "pill" && prefs.idleEyes && isShown) {
            lid.to(0.12f, BLINK)
            main.postDelayed({ lid.to(1f, BLINK); kick() }, 110)
            kick()
        }
        armBlink()
    }

    private fun armBlink() {
        main.removeCallbacks(blink)
        if (kind == Kind.Hidden && idleShape == "pill" && prefs.idleEyes && !minimal) main.postDelayed(blink, 4000L + (Math.random() * 3000).toLong())
    }

    // Silenced: no invisible tap zone over games
    private fun hotspot() = prefs.cameraTap && !silenced && (idleShape == "pill" || (touchable && docked))

    // Window covers the moving shape only
    private fun place(rect: RectF, touch: Boolean) {
        val grow = if (kind == Kind.Hidden && touch) ui.s8 else 0f
        val x = (rect.left - grow).toInt() - 2
        val y = max(0, (rect.top - grow).toInt() - 2)
        window(x, y, max(1, (rect.right + grow).toInt() + 2 - x), max(1, (rect.bottom + grow).toInt() + 2 - y), touch)
    }

    // Untouchable overlays still block touches on Android 12
    // Nothing drawn: one pixel in the corner
    private fun park() = window(0, 0, 1, 1, false)

    // Each update is a call into the system
    private fun window(x: Int, y: Int, w: Int, h: Int, touch: Boolean) {
        val f = baseFlags or if (touch) 0 else WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        if (lp.x == x && lp.y == y && lp.width == w && lp.height == h && lp.flags == f) return
        lp.x = x; lp.y = y; lp.width = w; lp.height = h; lp.flags = f
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
        // Screen off: resume from refresh on wake
        if (dark) { ticking = false; return }
        val dt = if (lastNs == 0L) 1f / 60f else ((ns - lastNs) / 1e9f).coerceIn(0f, 1f / 20f)
        lastNs = ns
        var moving = false
        pendingTune?.let { tu ->
            if (SystemClock.uptimeMillis() >= pendingAt) { t.to(pendingT[0], tu); b.to(pendingT[1], tu); pendingTune = null }
            moving = true
        }
        for (s in springs) moving = s.step(dt) or moving
        moving = contentA.step(dt) or moving
        if (contentA.value <= 0.05f && contentA.target == 0f && shownKey != wantedKey) {
            shownKey = wantedKey
            shownScene = primary
            shownKind = kind
            if (kind == Kind.Hidden) contentA.snap(0f)
        }
        // Content waits for the shape
        if (shownKey == wantedKey && kind != Kind.Hidden && contentA.target == 0f && grown() > 0.6f) contentA.to(1f, FADE_IN)
        // Hidden: fade out at lens size, no pop
        if (kind == Kind.Hidden && idleShape != "pill") bodyA.to(if (r.value - l.value < hole.width() * 1.25f) 0f else 1f, FADE_LENS)
        moving = bodyA.step(dt) or moving
        moving = lid.step(dt) or moving
        for (s in scenes) moving = s.step(dt) or moving
        val op = opened
        if (op != null && op !in scenes) moving = op.step(dt) or moving

        val live = kind != Kind.Hidden && (primary?.ticking() == true || second?.ticking() == true)
        // Live content at half rate, quarter when saving
        if (moving || !live || frameNo++ % (if (minimal) 4 else 2) == 0) invalidate()
        if (moving || live) Choreographer.getInstance().postFrameCallback(frame)
        else {
            ticking = false
            settle()
        }
    }

    private fun settle() {
        if (kind == Kind.Hidden) when (idleShape) {
            "pill" -> place(rest, hotspot())
            "ring" -> place(if (hotspot()) RectF(pill).apply { union(ringBox()) } else ringBox(), hotspot())
            else -> if (hotspot()) place(pill, true) else park()
        }
        else {
            val rect = RectF(target)
            if (second != null) rect.union(bubble)
            place(rect, true)
        }
    }

    override fun onDraw(canvas: Canvas) {
        now.set(l.value, t.value, r.value, b.value)
        val ring = kind == Kind.Hidden && idleShape == "ring"
        val body = bodyA.value.coerceIn(0f, 1f)
        if (kind == Kind.Hidden && shownKind == Kind.Hidden && !ticking && body < 0.01f && !ring && !calibrating) return
        getLocationOnScreen(loc)
        canvas.save()
        canvas.translate(-loc[0].toFloat(), -loc[1].toFloat())
        val k = bubbleK.value.coerceIn(0f, 1f)
        reshape(k)
        if (body > 0.01f) paintBody(canvas, body)
        if (ring) drawRing(canvas, 1f - body)
        canvas.clipPath(shape)
        if (kind == Kind.Hidden && idleShape == "pill" && prefs.idleEyes && !calibrating) drawEyes(canvas, body)
        val a = contentA.value.coerceIn(0f, 1f)
        val s = shownScene
        if (a > 0.01f && s != null && shownKind != Kind.Hidden) {
            canvas.save()
            // Content settles in from slightly small
            if (!minimal) { val sc = 0.94f + 0.06f * a; canvas.scale(sc, sc, now.centerX(), now.top) }
            when (shownKind) {
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
                    val list = if (opened != null) pages() else emptyList()
                    val dots = if (list.size > 1) ui.dp(18f) else 0f
                    val g = gap.value
                    content.set(target.left + ui.pad, statusBottom + g + ui.s16, target.right - ui.pad, target.bottom - ui.pad - dots)
                    s.drawPanel(canvas, content, a)
                    // Page dots: which tab, and that there are more
                    if (list.size > 1) {
                        val r = ui.dp(3f)
                        val gapX = ui.dp(10f)
                        val y = target.bottom - ui.pad / 2 - dots / 2
                        var x = target.centerX() - (list.size - 1) * gapX / 2
                        for (p in list) {
                            ui.dot(canvas, x, y, r, if (p === s) ui.ink else ui.faint, a)
                            x += gapX
                        }
                    }
                }
                Kind.Hidden -> Unit
            }
            canvas.restore()
        }
        val sec = second
        if (k > 0.05f && sec != null && shownKind == Kind.Compact) {
            box.set(bubble)
            sec.drawBubble(canvas, box, k * a)
        }
        canvas.restore()
        if (calibrating) drawGuide(canvas)
    }

    // Fill, glass sheen, and rim
    private fun paintBody(c: Canvas, a: Float) {
        val base = bg.alpha
        bg.alpha = (base * a).toInt()
        c.drawPath(shape, bg)
        bg.alpha = base
        if (sheen.shader != null) {
            unit.setScale(1f, max(1f, now.height()))
            unit.postTranslate(0f, now.top)
            sheen.shader.setLocalMatrix(unit)
            sheen.alpha = (255 * a).toInt()
            c.drawPath(shape, sheen)
        }
        // Floating: no camera hole to blend into
        if (material == "liquid" || material == "frost" || prefs.outline || !docked) {
            rim.shader?.let { unit.setScale(1f, max(1f, now.height())); unit.postTranslate(0f, now.top); it.setLocalMatrix(unit) }
            rim.alpha = (255 * a).toInt()
            c.drawPath(shape, rim)
        }
    }

    // Battery ring around the lens
    private fun drawRing(c: Canvas, a: Float) {
        if (a < 0.01f) return
        val rr = hole.width() / 2 + ui.dp(4f)
        val w = ui.dp(3f)
        ui.stroke.strokeWidth = w
        ui.stroke.color = ui.alpha(Color.BLACK, a * 0.55f)
        c.drawCircle(hole.centerX(), hole.centerY(), rr, ui.stroke)
        val frac = if (level < 0) 0f else level / 100f
        ui.stroke.color = ui.alpha(when {
            charging -> Color.rgb(52, 199, 89)
            level in 0..15 -> Color.rgb(255, 69, 58)
            else -> Color.WHITE
        }, a)
        box.set(hole.centerX() - rr, hole.centerY() - rr, hole.centerX() + rr, hole.centerY() + rr)
        c.drawArc(box, -90f, 360f * frac, false, ui.stroke)
    }

    // Tepi's eyes beside the camera
    private fun drawEyes(c: Canvas, a: Float) {
        val h = rest.height() * 0.42f * lid.value.coerceIn(0.1f, 1f)
        val w = rest.height() * 0.22f
        val y = rest.centerY()
        val off = hole.width() / 2 + (rest.width() / 2 - hole.width() / 2) * 0.55f
        for (x in floatArrayOf(hole.centerX() - off, hole.centerX() + off)) {
            box.set(x - w / 2, y - h / 2, x + w / 2, y + h / 2)
            ui.fill(c, box, w / 2, ui.ink, a)
        }
    }

    // Where the system says the camera is
    private fun drawGuide(c: Canvas) {
        getLocationOnScreen(loc)
        c.save()
        c.translate(-loc[0].toFloat(), -loc[1].toFloat())
        ui.stroke.strokeWidth = ui.dp(1.5f)
        ui.stroke.color = ui.accent
        c.drawOval(hole, ui.stroke)
        if (!sysHole.isEmpty) {
            ui.stroke.strokeWidth = ui.dp(1f)
            ui.stroke.color = Color.argb(200, 255, 69, 58)
            c.drawRect(sysHole, ui.stroke)
        }
        c.restore()
    }

    // Left and right of the camera inside the neck
    private fun zones(at: RectF) {
        val nk = min(neck.value, at.width())
        val left = at.centerX() - nk / 2
        // Keep content off the rounded ends
        val inset = max(ui.dp(7f), (pill.height() - ui.dp(22f)) / 2)
        val side = max(inset, pill.height() * 0.42f)
        val gapL = if (docked) hole.left - ui.s12 else hole.centerX() - ui.s4
        val gapR = if (docked) hole.right + ui.s12 else hole.centerX() + ui.s4
        zoneL.set(left + side, pill.top + inset, gapL, pill.bottom - inset)
        zoneR.set(gapR, pill.top + inset, left + nk - side, pill.bottom - inset)
    }

    // Path.op is costly; still shapes reuse the last path
    private fun reshape(k: Float) {
        var same = shapeKey[shaped.size] == k
        for (i in shaped.indices) if (shapeKey[i] != shaped[i].value) { shapeKey[i] = shaped[i].value; same = false }
        if (same) return
        shapeKey[shaped.size] = k
        buildShape(shape)
        if (k > 0.01f) shape.addCircle(bubble.centerX(), bubble.centerY(), pill.height() / 2 * k, Path.Direction.CW)
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
        val g = gap.value
        if (g > 0.5f) {
            // Floating card under a separate pill
            box.set(cx - nk / 2, now.top, cx + nk / 2, min(sy, now.top + pill.height()))
            out.addRoundRect(box, rt, rt, Path.Direction.CW)
            box.set(now.left, sy + g, now.right, now.bottom)
            out.addRoundRect(box, rb, rb, Path.Direction.CW)
            return
        }
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
            box.set(if (idleShape == "pill") rest else pill); box.inset(-ui.s8, -ui.s8)
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
    // Home, then features that have something to show
    private fun pages(): List<Scene> = listOf(home) + scenes.filter { it.tab() && !(locked && it.personal) }.sortedBy { it.order }

    private fun page(dir: Int) {
        val list = pages()
        if (list.size < 2) return
        val cur = list.indexOf(primary).coerceAtLeast(0)
        val next = cur + dir
        if (next < 0 || next >= list.size) return
        haptic()
        expand(list[next])
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
        const val URGENT = 95

        // Swiping the bar down mid-game must not flash
        private const val BACK_MS = 400L
        private val OPEN = Tune(300f, 25f)
        private val SMOOTH_OPEN = Tune(260f, 32.2f)
        private val CLOSE = Tune(420f, 41f)
        private val MIN = Tune(1600f, 80f)
        private val SNAP = Tune(1600f, 80f)
        private val FADE_IN = Tune(420f, 41f)
        private val FADE_OUT = Tune(900f, 60f)
        private val FADE_LENS = Tune(700f, 53f)
        private val BLINK = Tune(2200f, 90f)
    }
}
