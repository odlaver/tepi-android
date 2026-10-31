package io.github.odlaver.tepi

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.util.concurrent.Executors

// Classes and tasks from every source, refreshed in the background
class Agenda(context: Context, private val prefs: Prefs, private val changed: () -> Unit, private val classSoon: (ClassSlot) -> Unit) {
    val vault = Vault(context)
    val kulia = Kulia(vault)
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val warned = HashSet<String>()

    @Volatile var slots: List<ClassSlot> = emptyList()
        private set
    @Volatile var tasks: List<Task> = emptyList()
        private set
    @Volatile var error: Int? = null
        private set

    private val loop = object : Runnable {
        override fun run() {
            refresh()
            main.postDelayed(this, if (error != null) 120_000L else 600_000L)
        }
    }

    private val minute = object : Runnable {
        override fun run() {
            soon()
            main.postDelayed(this, 60_000L)
        }
    }

    fun start() { main.removeCallbacks(loop); main.removeCallbacks(minute); main.post(loop); main.postDelayed(minute, 5000) }
    fun stop() { main.removeCallbacks(loop); main.removeCallbacks(minute) }

    val todoistToken get() = vault.get("todoist")
    val notionToken get() = vault.get("notion")
    val icsUrls get() = vault.get("ics")?.split('\n')?.filter { it.isNotBlank() }.orEmpty()
    val any get() = kulia.signedIn || todoistToken != null || (notionToken != null && prefs.str("notion.source", "").isNotEmpty()) || icsUrls.isNotEmpty()

    fun refresh() {
        worker.execute {
            val s = ArrayList<ClassSlot>()
            val t = ArrayList<Task>()
            var err: Int? = null
            if (kulia.signedIn) {
                val (sch, e1) = kulia.schedule()
                sch?.let { s.addAll(it) }
                val (feed, e2) = kulia.feed()
                feed?.let { t.addAll(it) }
                err = e1 ?: e2
            }
            todoistToken?.let { tok -> Todoist(tok).tasks()?.let { t.addAll(it) } ?: run { err = err ?: R.string.src_failed } }
            val ns = prefs.str("notion.source", "")
            if (ns.isNotEmpty()) notionToken?.let { tok -> Notion(tok).tasks(ns)?.let { t.addAll(it) } ?: run { err = err ?: R.string.src_failed } }
            for (u in icsUrls) Ical.fetch(u)?.let { (ev, td) -> s.addAll(ev); t.addAll(td) } ?: run { err = err ?: R.string.src_failed }
            val now = System.currentTimeMillis()
            val slotsOut = s.filter { it.end >= now }.sortedBy { it.start }
            val tasksOut = t.filter { it.deadline > now - 14 * 86_400_000L }.sortedBy { it.deadline }
            main.post {
                slots = slotsOut
                tasks = tasksOut
                error = err
                changed()
            }
        }
    }

    fun nextClass(): ClassSlot? {
        val now = System.currentTimeMillis()
        return slots.firstOrNull { !it.cancelled && it.end > now }
    }

    fun done(task: Task, ok: (Boolean) -> Unit) {
        worker.execute {
            val res = when (task.source) {
                "kulia" -> kulia.done(task)
                "todoist" -> todoistToken?.let { Todoist(it).close(task) } ?: false
                "notion" -> notionToken?.let { Notion(it).done(task, prefs.str("notion.source", "")) } ?: false
                else -> false
            }
            main.post {
                if (res) { tasks = tasks.filterNot { it.id == task.id && it.source == task.source }; changed() }
                ok(res)
            }
        }
    }

    // Class in 15 minutes: one alert each
    private fun soon() {
        if (!prefs.bool("class.remind", true)) return
        val now = System.currentTimeMillis()
        for (s in slots) {
            if (s.cancelled) continue
            val key = "${s.title}@${s.start}"
            if (s.start - now in 0..15 * 60_000L && warned.add(key)) classSoon(s)
        }
    }
}
