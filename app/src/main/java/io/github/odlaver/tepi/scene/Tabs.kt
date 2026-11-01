package io.github.odlaver.tepi.scene

import android.content.Intent
import android.graphics.Canvas
import android.graphics.RectF
import android.net.Uri
import io.github.odlaver.tepi.Agenda
import io.github.odlaver.tepi.Icon
import io.github.odlaver.tepi.Notes
import io.github.odlaver.tepi.Prayer
import io.github.odlaver.tepi.R
import io.github.odlaver.tepi.Reminders
import io.github.odlaver.tepi.Shelf
import io.github.odlaver.tepi.ShelfItem
import java.text.DateFormat
import java.util.Calendar
import java.util.Date

// Base for always-present tabs: title, list, one action row
abstract class TabScene(host: Host) : Scene(host) {
    protected val hits = ArrayList<Pair<RectF, () -> Unit>>()
    protected val tmp = RectF()
    protected val rowH get() = ui.dp(40f)

    override fun live() = false
    override fun keepOpen() = true
    override fun tab() = true
    override fun drawCompact(c: Canvas, left: RectF, right: RectF, a: Float) {}

    protected fun head(c: Canvas, r: RectF, icon: Icon, title: String, end: String?, a: Float): Float {
        ui.icon(c, icon, r.left + ui.dp(9f), r.top + ui.dp(9f), ui.dp(16f), ui.sub, a)
        ui.text(c, title, ui.label, r.left + ui.dp(26f), ui.baseline(ui.label, r.top + ui.dp(9f)), r.width() - ui.dp(120f), a)
        if (end != null) ui.textEnd(c, end, ui.body, r.right, ui.baseline(ui.body, r.top + ui.dp(9f)), a)
        return r.top + ui.dp(18f) + ui.s12
    }

    // Two-line row; optional trailing icon button
    protected fun item(c: Canvas, r: RectF, y: Float, title: String, detail: String, a: Float, end: String? = null, endIcon: Icon? = null, onEnd: (() -> Unit)? = null, onTap: (() -> Unit)? = null) {
        val btn = if (endIcon != null) ui.dp(32f) else 0f
        val endW = if (end != null) ui.body.measureText(end) + ui.s8 else 0f
        ui.text(c, title, ui.label, r.left, y + ui.dp(14f), r.width() - btn - endW - ui.s8, a)
        if (detail.isNotEmpty()) ui.text(c, detail, ui.body, r.left, y + ui.dp(30f), r.width() - btn - ui.s8, a)
        if (end != null) ui.textEnd(c, end, ui.body, r.right - btn - (if (btn > 0) ui.s8 else 0f), y + ui.dp(14f), a)
        if (endIcon != null && onEnd != null) {
            val b = RectF(r.right - btn, y + (rowH - btn) / 2 - ui.dp(2f), r.right, y + (rowH + btn) / 2 - ui.dp(2f))
            ui.fill(c, b, btn / 2, ui.fill, a)
            ui.icon(c, endIcon, b.centerX(), b.centerY(), ui.dp(16f), ui.ink, a)
            hits.add(b to onEnd)
        }
        if (onTap != null) hits.add(RectF(r.left, y, r.right - btn - ui.s8, y + rowH - ui.s4) to onTap)
    }

    protected fun empty(c: Canvas, r: RectF, y: Float, text: String, a: Float) =
        ui.text(c, text, ui.body, r.left, y + ui.dp(16f), r.width(), a)

    protected fun actions(c: Canvas, r: RectF, y: Float, list: List<Action>, a: Float) {
        val gap = ui.s8
        val bw = (r.width() - gap * (list.size - 1)) / list.size
        list.forEachIndexed { i, act ->
            val br = RectF(r.left + i * (bw + gap), y, r.left + i * (bw + gap) + bw, y + ui.btnH)
            ui.button(c, br, act.label, a, act.primary, act.icon)
            hits.add(br to { act.run() })
        }
    }

    override fun tap(x: Float, y: Float): Boolean {
        for ((r, act) in hits) if (r.contains(x, y)) { host.haptic(); act(); host.refresh(); return true }
        return false
    }
}

private val hm = DateFormat.getTimeInstance(DateFormat.SHORT)

// Today's classes, then tasks
class AgendaTab(host: Host, private val agenda: Agenda, private val connect: () -> Unit) : TabScene(host) {
    override val id = "agenda"
    override val priority = 30
    override val order = 3

    private fun today() = agenda.slots.filter { val c = Calendar.getInstance(); c.timeInMillis = it.start; c.get(Calendar.DAY_OF_YEAR) == Calendar.getInstance().get(Calendar.DAY_OF_YEAR) }.take(3)

    override fun panelHeight(w: Float): Float {
        if (!agenda.any) return ui.dp(18f) + ui.s12 + ui.dp(36f) + ui.s12 + ui.btnH
        val n = today().size + agenda.tasks.take(4).size
        return ui.dp(18f) + ui.s12 + maxOf(1, n) * rowH
    }

    override fun drawPanel(c: Canvas, r: RectF, a: Float) {
        hits.clear()
        var y = head(c, r, Icon.Book, host.ctx.getString(R.string.tab_agenda), if (agenda.tasks.isNotEmpty()) host.ctx.getString(R.string.tasks_n, agenda.tasks.size) else null, a)
        if (!agenda.any) {
            empty(c, r, y, host.ctx.getString(R.string.agenda_empty), a)
            actions(c, r, y + ui.dp(36f) + ui.s12, listOf(Action(host.ctx.getString(R.string.connect_now), true, Icon.Link) { connect(); host.collapse() }), a)
            return
        }
        val classes = today()
        val tasks = agenda.tasks.take(4)
        if (classes.isEmpty() && tasks.isEmpty()) { empty(c, r, y, host.ctx.getString(R.string.agenda_clear), a); return }
        for (s in classes) {
            val room = if (s.room.isNotEmpty()) s.room else if (s.online) "Online" else ""
            val tag = if (s.cancelled) host.ctx.getString(R.string.cancelled) else if (s.exam) host.ctx.getString(R.string.exam) else ""
            item(c, r, y, s.title, listOf(hm.format(Date(s.start)) + "–" + hm.format(Date(s.end)), room, tag).filter { it.isNotEmpty() }.joinToString(" · "), a, whenText(host, s.start))
            y += rowH
        }
        for (t in tasks) {
            item(c, r, y, t.title, t.course, a, whenText(host, t.deadline, t.dateOnly),
                if (t.readOnly) null else Icon.Check, { agenda.done(t) { host.refresh() } },
                t.url?.let { u -> { host.open(Intent(Intent.ACTION_VIEW, Uri.parse(u))); host.collapse() } })
            y += rowH
        }
    }
}

// Prayer times today and water count
class PrayerTab(host: Host, private val rem: Reminders, private val setup: () -> Unit) : TabScene(host) {
    override val id = "prayer"
    override val priority = 25
    override val order = 4

    override fun panelHeight(w: Float): Float {
        var h = ui.dp(18f) + ui.s12
        h += if (rem.prayOn) ui.dp(52f) else ui.dp(36f) + ui.s12 + ui.btnH
        h += ui.s16 + rowH
        return h
    }

    override fun drawPanel(c: Canvas, r: RectF, a: Float) {
        hits.clear()
        val city = rem.city()
        var y = head(c, r, Icon.Moon, host.ctx.getString(R.string.tab_prayer), if (rem.prayOn) city?.name else null, a)
        if (rem.prayOn && city != null) {
            // Five times in a row, next one lit
            val times = Prayer.forDay(Calendar.getInstance(), city)
            val (next, _) = Prayer.next(city)
            val idx = intArrayOf(0, 2, 3, 4, 5)
            val w = r.width() / idx.size
            idx.forEachIndexed { i, p ->
                val cell = RectF(r.left + i * w + ui.dp(2f), y, r.left + (i + 1) * w - ui.dp(2f), y + ui.dp(52f))
                val on = p == next
                ui.fill(c, cell, ui.dp(12f), if (on) ui.ink else ui.fill, a)
                val ink = if (on) ui.labelDark else ui.label
                ui.centered(c, Prayer.names[p], if (on) ui.labelDark else ui.body, cell.centerX(), cell.top + ui.dp(17f), cell.width() - ui.s8, a)
                ui.centered(c, hm.format(Date(times[p])), ink, cell.centerX(), cell.top + ui.dp(36f), cell.width() - ui.s8, a)
            }
            y += ui.dp(52f)
        } else {
            empty(c, r, y, host.ctx.getString(R.string.prayer_off), a)
            actions(c, r, y + ui.dp(36f) + ui.s12, listOf(Action(host.ctx.getString(R.string.turn_on), true, Icon.Moon) { setup(); host.collapse() }), a)
            y += ui.dp(36f) + ui.s12 + ui.btnH
        }
        y += ui.s16
        item(c, r, y, host.ctx.getString(R.string.n_drink), host.ctx.getString(R.string.n_drink_count, rem.drinkCount(), rem.drinkGoal()), a,
            endIcon = Icon.Plus, onEnd = { rem.drank() })
    }
}

class NotesTab(host: Host, private val notes: Notes, private val add: () -> Unit, private val all: () -> Unit) : TabScene(host) {
    override val id = "notes"
    override val priority = 15
    override val order = 6

    override fun panelHeight(w: Float) = ui.dp(18f) + ui.s12 + maxOf(1, minOf(3, notes.all().size)) * rowH + ui.s8 + ui.btnH

    override fun drawPanel(c: Canvas, r: RectF, a: Float) {
        hits.clear()
        val list = notes.all()
        var y = head(c, r, Icon.Note, host.ctx.getString(R.string.notes), if (list.isNotEmpty()) list.size.toString() else null, a)
        if (list.isEmpty()) { empty(c, r, y, host.ctx.getString(R.string.notes_empty), a); y += rowH }
        for (n in list.take(3)) {
            val firstLine = n.text.lineSequence().first()
            val rest = n.text.removePrefix(firstLine).trim().replace('\n', ' ')
            item(c, r, y, firstLine, rest.ifEmpty { if (n.pinned) host.ctx.getString(R.string.note_pinned) else "" }, a, onTap = { all(); host.collapse() })
            y += rowH
        }
        actions(c, r, y + ui.s8, listOf(
            Action(host.ctx.getString(R.string.note_new), true, Icon.Plus) { add() },
            Action(host.ctx.getString(R.string.see_all), false, Icon.Tasks) { all(); host.collapse() },
        ), a)
    }
}

class ShelfTab(host: Host, private val shelf: Shelf, private val pick: (ShelfItem) -> Unit, private val all: () -> Unit) : TabScene(host) {
    override val id = "shelf"
    override val priority = 10
    override val order = 7

    override fun panelHeight(w: Float): Float {
        val n = shelf.all().size
        return ui.dp(18f) + ui.s12 + (if (n == 0) ui.dp(36f) else minOf(3, n) * rowH) + ui.s8 + ui.btnH
    }

    override fun drawPanel(c: Canvas, r: RectF, a: Float) {
        hits.clear()
        val list = shelf.all()
        var y = head(c, r, Icon.Shelf, host.ctx.getString(R.string.shelf), if (list.isNotEmpty()) list.size.toString() else null, a)
        if (list.isEmpty()) { empty(c, r, y, host.ctx.getString(R.string.shelf_tab_empty), a); y += ui.dp(36f) }
        for (it in list.take(3)) {
            val title = if (it.isText) it.text!!.replace('\n', ' ') else it.name ?: "file"
            item(c, r, y, title, host.ctx.getString(if (it.isText) R.string.shelf_text else R.string.shelf_file), a,
                endIcon = if (it.isText) Icon.Copy else Icon.Open, onEnd = { pick(it) })
            y += rowH
        }
        actions(c, r, y + ui.s8, listOf(Action(host.ctx.getString(R.string.see_all), false, Icon.Tasks) { all(); host.collapse() }), a)
    }
}

// Agent sessions on the laptop
class AgentsTab(host: Host, private val pc: io.github.odlaver.tepi.Pc) : TabScene(host) {
    override val id = "agents"
    override val priority = 12
    override val order = 5
    override fun tab() = pc.paired

    override fun panelHeight(w: Float) = ui.dp(18f) + ui.s12 + maxOf(1, minOf(4, pc.runs.size)) * rowH

    override fun drawPanel(c: Canvas, r: RectF, a: Float) {
        hits.clear()
        var y = head(c, r, Icon.Laptop, pc.name.ifEmpty { host.ctx.getString(R.string.laptop) },
            host.ctx.getString(if (pc.online) R.string.online else R.string.offline), a)
        if (pc.runs.isEmpty()) { empty(c, r, y, host.ctx.getString(R.string.agents_idle), a); return }
        for (s in pc.runs.take(4)) {
            item(c, r, y, s.project, s.activity, a, host.ctx.getString(when (s.state) {
                "working" -> R.string.st_working
                "waiting" -> R.string.st_waiting
                "done" -> R.string.st_done
                else -> R.string.st_ready
            }))
            y += rowH
        }
    }
}
