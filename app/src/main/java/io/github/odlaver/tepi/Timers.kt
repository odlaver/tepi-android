package io.github.odlaver.tepi

import android.os.Handler
import android.os.Looper

// Focus, break, countdown, stopwatch; port of src/Timers.cs
class Timers(private val prefs: Prefs, private val ended: (Kind) -> Unit, private val changed: () -> Unit) {
    enum class Kind { Focus, Break, Countdown, Stopwatch }

    private val main = Handler(Looper.getMainLooper())
    var kind: Kind? = null
        private set
    private var startAt = 0L
    private var endAt = 0L
    var paused = false
        private set
    private var pausedValue = 0L
    var totalMs = 0L
        private set
    val laps = ArrayList<Long>()

    private val ring = Runnable { finish() }

    init {
        restore()
    }

    val active get() = kind != null

    fun focus(minutes: Int) = start(Kind.Focus, minutes * 60_000L)
    fun countdown(minutes: Int) = start(Kind.Countdown, minutes * 60_000L)

    fun stopwatch() {
        stop(notify = false)
        kind = Kind.Stopwatch
        startAt = now()
        paused = false
        laps.clear()
        save()
        changed()
    }

    private fun start(k: Kind, ms: Long) {
        stop(notify = false)
        kind = k
        totalMs = ms
        startAt = now()
        endAt = startAt + ms
        paused = false
        arm()
        save()
        changed()
    }

    // Left for countdowns, elapsed for stopwatch
    fun shownMs(): Long {
        val k = kind ?: return 0
        return if (k == Kind.Stopwatch) elapsed() else remaining()
    }

    fun remaining() = if (paused) pausedValue else (endAt - now()).coerceAtLeast(0)
    fun elapsed() = if (paused) pausedValue else now() - startAt

    fun fraction(): Float {
        val k = kind ?: return 0f
        if (k == Kind.Stopwatch) return (elapsed() % 60_000L) / 60_000f
        return if (totalMs <= 0) 0f else 1f - remaining().toFloat() / totalMs
    }

    fun togglePause() {
        val k = kind ?: return
        if (!paused) {
            pausedValue = if (k == Kind.Stopwatch) elapsed() else remaining()
            paused = true
            main.removeCallbacks(ring)
        } else {
            if (k == Kind.Stopwatch) startAt = now() - pausedValue
            else { endAt = now() + pausedValue; arm() }
            paused = false
        }
        save()
        changed()
    }

    fun plusMinute() {
        val k = kind ?: return
        if (k == Kind.Stopwatch) return
        if (paused) pausedValue += 60_000L else { endAt += 60_000L; arm() }
        totalMs += 60_000L
        save()
        changed()
    }

    fun lap() {
        if (kind != Kind.Stopwatch) return
        laps.add(0, elapsed())
        while (laps.size > 20) laps.removeAt(laps.size - 1)
        changed()
    }

    fun stop(notify: Boolean = true) {
        main.removeCallbacks(ring)
        kind = null
        paused = false
        laps.clear()
        save()
        if (notify) changed()
    }

    private fun arm() {
        main.removeCallbacks(ring)
        main.postDelayed(ring, (endAt - now()).coerceAtLeast(0))
    }

    // Focus rolls into break
    private fun finish() {
        val k = kind ?: return
        if (k == Kind.Focus) {
            val brk = prefs.int("focus.break", 5).coerceIn(1, 30)
            start(Kind.Break, brk * 60_000L)
        } else {
            kind = null
            save()
            changed()
        }
        ended(k)
    }

    private fun now() = System.currentTimeMillis()

    private fun save() {
        prefs.sp.edit()
            .putString("timer.kind", kind?.name ?: "")
            .putLong("timer.start", startAt)
            .putLong("timer.end", endAt)
            .putLong("timer.total", totalMs)
            .putBoolean("timer.paused", paused)
            .putLong("timer.value", pausedValue)
            .apply()
    }

    // Survives a service restart
    private fun restore() {
        val name = prefs.str("timer.kind", "")
        kind = Kind.values().firstOrNull { it.name == name } ?: return
        startAt = prefs.sp.getLong("timer.start", 0)
        endAt = prefs.sp.getLong("timer.end", 0)
        totalMs = prefs.sp.getLong("timer.total", 0)
        paused = prefs.bool("timer.paused", false)
        pausedValue = prefs.sp.getLong("timer.value", 0)
        if (kind != Kind.Stopwatch && !paused) {
            if (endAt <= now()) { kind = null; save() } else arm()
        }
    }
}
