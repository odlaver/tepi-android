package io.github.odlaver.tepi.scene

import android.graphics.Canvas
import android.graphics.RectF
import io.github.odlaver.tepi.R
import java.text.DateFormat
import java.util.Calendar
import java.util.Date
import kotlin.math.max

// A block on Home, fed by a feature
interface HomeRow {
    fun visible(): Boolean
    // Hidden on the lock screen
    fun personal() = false
    fun height(w: Float): Float
    fun draw(c: Canvas, r: RectF, a: Float)
    fun tap(x: Float, y: Float) = false
}

class Action(val label: String, val primary: Boolean = false, val icon: io.github.odlaver.tepi.Icon? = null, val personal: Boolean = false, val run: () -> Unit)

// Opened on demand: camera tap, tile, shortcut
class HomeScene(host: Host, private val actions: () -> List<Action>) : Scene(host) {
    override val id = "home"
    val rows = ArrayList<HomeRow>()
    private val placed = ArrayList<HomeRow>()
    private val boxes = ArrayList<RectF>()
    private val hitActs = ArrayList<Action>()
    private val hitBoxes = ArrayList<RectF>()
    private val tmp = RectF()
    private val time = DateFormat.getTimeInstance(DateFormat.SHORT)
    private val date = java.text.SimpleDateFormat("EEEE, d MMMM", java.util.Locale.getDefault())

    // Text that changes once a minute
    private var minute = -1L
    private var timeText = ""
    private var dateText = ""
    private var greetText = ""

    // Building actions makes lambdas; twice a second is plenty
    private var acts: List<Action> = emptyList()
    private var actsAt = -1L

    private fun acts(): List<Action> {
        val now = android.os.SystemClock.uptimeMillis()
        if (actsAt < 0 || now - actsAt > 500) { acts = actions(); actsAt = now }
        return acts
    }

    override fun live() = false
    override fun ticking() = true

    override fun drawCompact(c: Canvas, left: RectF, right: RectF, a: Float) {}

    private fun header() = ui.dp(52f)

    override fun panelHeight(w: Float): Float {
        actsAt = -1
        var h = header()
        for (row in rows) if (shows(row)) h += ui.s8 + row.height(w - ui.s24) + ui.s20
        if (acts().isNotEmpty()) h += ui.s12 + ui.btnH
        return h
    }

    private fun box(list: ArrayList<RectF>, i: Int): RectF {
        while (list.size <= i) list.add(RectF())
        return list[i]
    }

    private fun shows(row: HomeRow) = row.visible() && !(host.locked && row.personal())

    private fun greeting(): String {
        val h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        return host.ctx.getString(when {
            h < 4 -> R.string.greet_night
            h < 11 -> R.string.greet_morning
            h < 15 -> R.string.greet_day
            h < 18 -> R.string.greet_afternoon
            else -> R.string.greet_night
        })
    }

    override fun drawPanel(c: Canvas, r: RectF, a: Float) {
        val m = System.currentTimeMillis() / 60_000L
        if (m != minute) {
            minute = m
            val now = Date()
            timeText = time.format(now); dateText = date.format(now); greetText = greeting()
        }
        ui.text(c, greetText, ui.body, r.left, r.top + ui.dp(12f), r.width(), a)
        ui.text(c, timeText, ui.big, r.left, r.top + ui.dp(44f), r.width() / 2, a)
        ui.textEnd(c, dateText, ui.body, r.right, r.top + ui.dp(44f), a)
        var y = r.top + header()
        placed.clear()
        for (row in rows) {
            if (!shows(row)) continue
            y += ui.s8
            val h = row.height(r.width() - ui.s24) + ui.s20
            val box = box(boxes, placed.size).apply { set(r.left, y, r.right, y + h) }
            ui.fill(c, box, ui.radiusRow, ui.fill, a)
            tmp.set(box.left + ui.s12, box.top + ui.dp(10f), box.right - ui.s12, box.bottom - ui.dp(10f))
            row.draw(c, tmp, a)
            placed.add(row)
            y += h
        }
        hitActs.clear()
        val acts = acts()
        if (acts.isEmpty()) return
        y += ui.s12
        val gap = ui.s8
        val bw = (r.width() - gap * (acts.size - 1)) / acts.size
        for (i in acts.indices) {
            val act = acts[i]
            val br = box(hitBoxes, i).apply { set(r.left + i * (bw + gap), y, r.left + i * (bw + gap) + bw, y + ui.btnH) }
            ui.button(c, br, act.label, a, act.primary, act.icon)
            hitActs.add(act)
        }
    }

    override fun tap(x: Float, y: Float): Boolean {
        for (i in hitActs.indices) if (hitBoxes[i].contains(x, y)) { host.haptic(); hitActs[i].run(); actsAt = -1; return true }
        for (i in placed.indices) if (boxes[i].contains(x, y)) return placed[i].tap(x, y)
        return false
    }
}

// Song on Home: art, title, play
class MusicRow(private val host: Host, private val music: MusicScene, private val media: io.github.odlaver.tepi.Media) : HomeRow {
    private val ui get() = host.ui
    private val tmp = RectF()
    private val hitPlay = RectF()
    private val hitInfo = RectF()

    override fun visible() = music.present()
    override fun height(w: Float) = ui.dp(38f)

    override fun draw(c: Canvas, r: RectF, a: Float) {
        val tr = media.track ?: return
        val s = r.height()
        tmp.set(r.left, r.top, r.left + s, r.bottom)
        ui.art(c, tr.art, tmp, a)
        val tx = r.left + s + ui.s12
        val btn = ui.dp(38f)
        val tw = r.right - btn - ui.s8 - tx
        ui.text(c, tr.title, ui.label, tx, r.top + ui.dp(15f), tw, a)
        ui.text(c, tr.artist, ui.body, tx, r.top + ui.dp(32f), tw, a)
        hitPlay.set(r.right - btn, r.centerY() - btn / 2, r.right, r.centerY() + btn / 2)
        hitInfo.set(r.left, r.top, hitPlay.left, r.bottom)
        ui.icon(c, if (media.playing) io.github.odlaver.tepi.Icon.Pause else io.github.odlaver.tepi.Icon.Play, hitPlay.centerX(), hitPlay.centerY(), ui.dp(22f), ui.ink, a)
    }

    override fun tap(x: Float, y: Float): Boolean {
        if (hitPlay.contains(x, y)) { media.toggle(); host.haptic(); return true }
        if (hitInfo.contains(x, y)) { host.expand(music); return true }
        return false
    }
}

// Grid of quick actions from a long press
class MenuScene(host: Host, private val items: () -> List<Action>) : Scene(host) {
    override val id = "menu"
    private val hits = ArrayList<Pair<RectF, Action>>()

    override fun live() = false
    override fun drawCompact(c: Canvas, left: RectF, right: RectF, a: Float) {}

    private val rowH get() = ui.dp(40f)

    // Fresh when opened, then reused while drawing
    private var list: List<Action> = emptyList()

    override fun panelHeight(w: Float): Float {
        list = items()
        val n = list.size
        val rows = (n + 1) / 2
        return max(0, rows) * rowH + max(0, rows - 1) * ui.s8
    }

    override fun drawPanel(c: Canvas, r: RectF, a: Float) {
        hits.clear()
        val list = list
        val gap = ui.s8
        val bw = (r.width() - gap) / 2
        list.forEachIndexed { i, act ->
            val col = i % 2
            val row = i / 2
            val x = r.left + col * (bw + gap)
            val y = r.top + row * (rowH + gap)
            val full = i == list.size - 1 && list.size % 2 == 1
            val br = RectF(x, y, if (full) r.right else x + bw, y + rowH)
            ui.button(c, br, act.label, a, act.primary, act.icon)
            hits.add(br to act)
        }
    }

    override fun tap(x: Float, y: Float): Boolean {
        for ((r, act) in hits) if (r.contains(x, y)) { host.haptic(); host.collapse(); act.run(); return true }
        return false
    }
}
