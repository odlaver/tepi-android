package io.github.odlaver.tepi.scene

import android.content.Intent
import android.graphics.Canvas
import android.graphics.RectF
import android.net.Uri
import io.github.odlaver.tepi.Agenda
import io.github.odlaver.tepi.Icon
import io.github.odlaver.tepi.R
import java.text.DateFormat
import java.util.Calendar
import java.util.Date

private val hm = DateFormat.getTimeInstance(DateFormat.SHORT)

// "in 25 min", "Tomorrow 08:00", "Mon 13:00"
fun whenText(host: Host, at: Long, dateOnly: Boolean = false): String {
    val now = System.currentTimeMillis()
    val mins = (at - now) / 60_000L
    val c = Calendar.getInstance().apply { timeInMillis = at }
    val today = Calendar.getInstance()
    val days = ((c.apply { set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0) }.timeInMillis -
        today.apply { set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }.timeInMillis) / 86_400_000L).toInt()
    return when {
        mins < 0 && !dateOnly -> host.ctx.getString(R.string.overdue)
        days < 0 -> host.ctx.getString(R.string.overdue)
        mins in 0..59 && !dateOnly -> host.ctx.getString(R.string.in_m, mins)
        days == 0 -> if (dateOnly) host.ctx.getString(R.string.today) else hm.format(Date(at))
        days == 1 -> host.ctx.getString(R.string.tomorrow) + if (dateOnly) "" else " " + hm.format(Date(at))
        else -> java.text.SimpleDateFormat(if (dateOnly) "EEE d MMM" else "EEE HH:mm", java.util.Locale.getDefault()).format(Date(at))
    }
}

class ClassRow(private val host: Host, private val agenda: Agenda) : HomeRow {
    override fun visible() = agenda.nextClass() != null
    override fun height(w: Float) = host.ui.dp(38f)
    override fun draw(c: Canvas, r: RectF, a: Float) {
        val ui = host.ui
        val s = agenda.nextClass() ?: return
        val label = host.ctx.getString(if (s.exam) R.string.next_exam else R.string.next_class)
        ui.text(c, label, ui.body, r.left, r.top + ui.dp(12f), r.width() - ui.dp(90f), a)
        ui.textEnd(c, whenText(host, s.start), ui.body, r.right, r.top + ui.dp(12f), a)
        val room = if (s.room.isNotEmpty()) " · ${s.room}" else if (s.online) " · Online" else ""
        ui.text(c, s.title + room, ui.label, r.left, r.top + ui.dp(30f), r.width(), a)
    }
    override fun tap(x: Float, y: Float): Boolean {
        val s = agenda.nextClass() ?: return false
        val url = s.url ?: if (s.source == "kulia") "https://kulia.live/jadwal" else return false
        host.open(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        host.collapse()
        return true
    }
}

class TaskRow(private val host: Host, private val agenda: Agenda, private val scene: TasksScene) : HomeRow {
    override fun visible() = agenda.tasks.isNotEmpty()
    override fun height(w: Float) = host.ui.dp(12f) + host.ui.dp(4f) + host.ui.dp(18f) * minOf(3, agenda.tasks.size)
    override fun draw(c: Canvas, r: RectF, a: Float) {
        val ui = host.ui
        ui.text(c, host.ctx.getString(R.string.tasks_n, agenda.tasks.size), ui.body, r.left, r.top + ui.dp(12f), r.width(), a)
        var y = r.top + ui.dp(16f)
        for (t in agenda.tasks.take(3)) {
            val due = whenText(host, t.deadline, t.dateOnly)
            ui.text(c, t.title, ui.label, r.left, y + ui.dp(14f), r.width() - ui.dp(96f), a)
            ui.textEnd(c, due, ui.body, r.right, y + ui.dp(14f), a)
            y += ui.dp(18f)
        }
    }
    override fun tap(x: Float, y: Float): Boolean { host.expand(scene); return true }
}

// Task list with done and open
class TasksScene(host: Host, private val agenda: Agenda) : Scene(host) {
    override val id = "tasks"
    private val hits = ArrayList<Pair<RectF, () -> Unit>>()
    private val row get() = ui.dp(44f)

    override fun live() = false
    override fun keepOpen() = true
    override fun drawCompact(c: Canvas, left: RectF, right: RectF, a: Float) {}

    override fun panelHeight(w: Float) = ui.dp(18f) + ui.s8 + maxOf(1, minOf(6, agenda.tasks.size)) * row

    override fun drawPanel(c: Canvas, r: RectF, a: Float) {
        hits.clear()
        ui.text(c, host.ctx.getString(R.string.tasks_n, agenda.tasks.size), ui.label, r.left, ui.baseline(ui.label, r.top + ui.dp(9f)), r.width(), a)
        var y = r.top + ui.dp(18f) + ui.s8
        if (agenda.tasks.isEmpty()) { ui.text(c, host.ctx.getString(R.string.tasks_empty), ui.body, r.left, y + ui.dp(18f), r.width(), a); return }
        for (t in agenda.tasks.take(6)) {
            val box = RectF(r.left, y, r.left + ui.dp(32f), y + ui.dp(32f))
            if (!t.readOnly) {
                ui.fill(c, box, ui.dp(16f), ui.fill, a)
                ui.icon(c, Icon.Check, box.centerX(), box.centerY(), ui.dp(16f), ui.sub, a)
                hits.add(RectF(box) to { agenda.done(t) { host.refresh() } })
            } else ui.icon(c, Icon.Book, box.centerX(), box.centerY(), ui.dp(16f), ui.faint, a)
            val tx = box.right + ui.s12
            ui.text(c, t.title, ui.label, tx, y + ui.dp(14f), r.right - tx - ui.dp(90f), a)
            ui.text(c, t.course, ui.body, tx, y + ui.dp(30f), r.right - tx - ui.dp(90f), a)
            ui.textEnd(c, whenText(host, t.deadline, t.dateOnly), ui.body, r.right, y + ui.dp(14f), a)
            val link = t.url
            if (link != null) hits.add(RectF(tx, y, r.right, y + row - ui.s4) to { host.open(Intent(Intent.ACTION_VIEW, Uri.parse(link))); host.collapse() })
            y += row
        }
    }

    override fun tap(x: Float, y: Float): Boolean {
        for ((r, act) in hits) if (r.contains(x, y)) { host.haptic(); act(); return true }
        return false
    }
}
