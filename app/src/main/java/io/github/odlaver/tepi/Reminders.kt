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
    private var lastDrink = System.currentTimeMillis()
    private var lastEyes = System.currentTimeMillis()
    private var lastStretch = System.currentTimeMillis()
    private var snoozeUntil = 0L
    private var offSince = 0L
    var quiet: () -> Boolean = { false }

    private val tick = object : Runnable {
        override fun run() {
            check()
            main.postDelayed(this, 30_000L)
        }
    }

    fun start() { main.removeCallbacks(tick); main.post(tick) }
    fun stop() = main.removeCallbacks(tick)

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
        // Away five minutes resets eye and stretch timers
        if (!power.isInteractive) { if (offSince == 0L) offSince = now; return }
        if (offSince > 0 && now - offSince > 300_000L) { lastEyes = now; lastStretch = now }
        offSince = 0
        prayers(now)
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
        val c = city() ?: return
        if (!prefs.bool("pray", false)) return
        val cal = Calendar.getInstance()
        val day = cal.get(Calendar.DAY_OF_YEAR)
        if (day != prayDay) { prayDay = day; warned = -1; announced = -1 }
        val times = Prayer.forDay(cal, c)
        val before = prefs.int("pray.before", 10).coerceAtLeast(0) * 60_000L
        for (i in 0 until 6) {
            if (i == 1) continue
            if (before > 0 && warned < i && now >= times[i] - before && now < times[i]) {
                warned = i; prayAlert(i, times[i], false)
            }
            if (announced < i && now >= times[i] && now < times[i] + 600_000L) {
                announced = i; warned = i; prayAlert(i, times[i], true)
            }
        }
    }
}
