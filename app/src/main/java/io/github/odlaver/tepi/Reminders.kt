package io.github.odlaver.tepi

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

enum class Nudge { Drink, Eyes, Stretch }

// Prayer and wellbeing nudges; port of src/Reminders.cs
class Reminders(private val context: Context, private val prefs: Prefs, private val prayAlert: (Int, Long, Boolean) -> Unit, private val nudge: (Nudge) -> Unit) {
    private val main = Handler(Looper.getMainLooper())
    private val power = context.getSystemService(PowerManager::class.java)
    private var warned = -1
    private var announced = -1
    private var prayDay = -1
    private var prayCity: City? = null
    private var times: LongArray? = null
    private var lastDrink = System.currentTimeMillis()
    private var lastEyes = System.currentTimeMillis()
    private var lastStretch = System.currentTimeMillis()
    private var snoozeUntil = 0L
    private var offSince = 0L
    var quiet: () -> Boolean = { false }

    // One-off reminders from Suruh Tepi
    var due: (String, Long) -> Unit = { _, _ -> }
    private val onceRun = Runnable { check() }

    private val tick = object : Runnable {
        override fun run() {
            check()
            main.postDelayed(this, if (Power.low(prefs)) 60_000L else 30_000L)
        }
    }

    fun start() { main.removeCallbacks(tick); main.post(tick); armOnce() }
    fun stop() {
        main.removeCallbacks(tick); main.removeCallbacks(onceRun)
        Wake.set(context, Wake.ONCE, 0); armPray(0)
    }

    // Screen lit: catch up now
    fun wake() { main.removeCallbacks(onceRun); main.post(onceRun) }

    // From an alarm: run before the wakelock drops
    fun now() = check()

    fun once(at: Long, text: String) {
        val list = onces().toMutableList()
        list.add(at to text)
        saveOnces(list.sortedBy { it.first }.take(50))
        armOnce()
    }

    fun onces(): List<Pair<Long, String>> {
        val arr = try { org.json.JSONArray(prefs.str("once", "[]")) } catch (_: Exception) { org.json.JSONArray() }
        return (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i)?.let { it.optLong("at") to it.optString("t") } }
    }

    private fun saveOnces(list: List<Pair<Long, String>>) {
        val arr = org.json.JSONArray()
        for ((at, t) in list) arr.put(org.json.JSONObject().put("at", at).put("t", t))
        prefs.set("once", arr.toString())
    }

    // Exact wake for the next one
    private fun armOnce() {
        main.removeCallbacks(onceRun)
        val next = onces().minOfOrNull { it.first }
        Wake.set(context, Wake.ONCE, next ?: 0)
        if (next == null) return
        main.postDelayed(onceRun, (next - System.currentTimeMillis()).coerceAtLeast(0) + 50)
    }

    private fun fireOnces(now: Long) {
        val all = onces()
        val (ready, later) = all.partition { it.first <= now }
        if (ready.isEmpty()) return
        saveOnces(later)
        for ((at, t) in ready) due(t, at)
        armOnce()
    }

    val prayOn get() = prefs.bool("pray", false) && city() != null
    fun city() = Prayer.city(prefs.str("pray.city", ""))

    fun snooze(minutes: Int) { snoozeUntil = System.currentTimeMillis() + minutes * 60_000L }

    fun drank() {
        val key = today()
        val n = if (prefs.str("drink.day", "") == key) prefs.int("drink.count", 0) else 0
        prefs.set("drink.day", key)
        prefs.set("drink.count", n + 1)
        lastDrink = System.currentTimeMillis()
    }

    fun drinkCount() = if (prefs.str("drink.day", "") == today()) prefs.int("drink.count", 0) else 0
    fun drinkGoal() = prefs.int("drink.goal", 8).coerceIn(1, 20)

    private fun today() = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

    private fun check() {
        val now = System.currentTimeMillis()
        fireOnces(now)
        prayers(now)
        // Nudges need someone looking; five minutes away resets them
        if (!power.isInteractive) { if (offSince == 0L) offSince = now; return }
        if (offSince > 0 && now - offSince > 300_000L) { lastEyes = now; lastStretch = now }
        offSince = 0
        if (now < snoozeUntil || quiet()) return
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        if (prefs.bool("drink", false) && hour in 7..21 && drinkCount() < drinkGoal() &&
            now - lastDrink >= prefs.int("drink.every", 60).coerceAtLeast(15) * 60_000L) {
            lastDrink = now; nudge(Nudge.Drink); return
        }
        if (prefs.bool("eyes", false) && now - lastEyes >= 20 * 60_000L) { lastEyes = now; nudge(Nudge.Eyes); return }
        if (prefs.bool("stretch", false) && now - lastStretch >= prefs.int("stretch.every", 60).coerceAtLeast(30) * 60_000L) {
            lastStretch = now; nudge(Nudge.Stretch)
        }
    }

    private fun prayers(now: Long) {
        val c = city()
        if (c == null || !prefs.bool("pray", false)) { armPray(0); return }
        val cal = Calendar.getInstance()
        val day = cal.get(Calendar.DAY_OF_YEAR)
        if (day != prayDay) { prayDay = day; warned = -1; announced = -1; times = null }
        // Same day, same city: same times
        if (times == null || c !== prayCity) { prayCity = c; times = Prayer.forDay(cal, c) }
        val times = times ?: return
        val before = prefs.int("pray.before", 10).coerceAtLeast(0) * 60_000L
        var next = Long.MAX_VALUE
        for (i in 0 until 6) {
            if (i == 1) continue
            if (before > 0 && warned < i && now >= times[i] - before && now < times[i]) {
                warned = i; prayAlert(i, times[i], false)
            }
            if (announced < i && now >= times[i] && now < times[i] + 600_000L) {
                announced = i; warned = i; prayAlert(i, times[i], true)
            }
            if (before > 0 && times[i] - before > now) next = minOf(next, times[i] - before)
            if (times[i] > now) next = minOf(next, times[i])
        }
        // Past Isya: wake after midnight for tomorrow
        if (next == Long.MAX_VALUE) next = (cal.clone() as Calendar).apply {
            add(Calendar.DAY_OF_YEAR, 1); set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 1); set(Calendar.SECOND, 0)
        }.timeInMillis
        armPray(next)
    }

    // Checks run every 30 s; the alarm only on change
    private var prayAt = -1L
    private fun armPray(at: Long) {
        if (at == prayAt) return
        prayAt = at
        Wake.set(context, Wake.PRAY, at)
    }
}
