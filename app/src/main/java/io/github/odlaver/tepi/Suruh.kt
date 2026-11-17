package io.github.odlaver.tepi

import android.content.Context
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Calendar
import java.util.Locale
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.round

// Suruh Tepi, offline; port of src/Suruh.cs
class Suruh(private val c: Context, private val timers: Timers, private val notes: Notes, private val reminders: Reminders) {

    // Result line, or null when not understood
    fun run(raw: String): String? {
        val text = raw.trim().trimEnd('.', '!', '?')
        val low = text.lowercase(Locale.ROOT)
        if (low.isEmpty()) return null

        if (Regex("^(stop|hentikan|matikan|batalkan)\\s+(timer|stopwatch|fokus)$").matches(low)) {
            if (!timers.active) return c.getString(R.string.s_no_timer)
            timers.stop(); return c.getString(R.string.s_timer_stopped)
        }
        if (Regex("^(mulai\\s+)?stopwatch$").matches(low)) { timers.stopwatch(); return c.getString(R.string.s_stopwatch) }

        Regex("^(mulai\\s+)?(fokus|focus|pomodoro)(\\s+(\\d{1,3})\\s*(menit|mnt|min|minutes?|m)?)?$").matchEntire(low)?.let { m ->
            val min = m.groupValues[4].toIntOrNull() ?: 25
            if (min !in 1..300) return null
            timers.focus(min); return c.getString(R.string.s_focus, min)
        }

        val tm = Regex("^(?:(?:set(?:el)?|pasang|mulai)\\s+)?(?:timer|hitung mundur|countdown)\\s+(.+)$").matchEntire(low)
            ?: Regex("^(.+?)\\s+(?:timer|hitung mundur)$").matchEntire(low)
        if (tm != null) {
            val ms = span(tm.groupValues[1])
            if (ms != null) {
                if (ms < 5_000L || ms > 10 * 3_600_000L) return null
                timers.countdownMs(ms); return c.getString(R.string.s_timer, spanText(ms))
            }
        }

        Regex("^(catat|catatan|note|tulis)\\s*:?\\s+(.+)$", RegexOption.IGNORE_CASE).matchEntire(text)?.let { m ->
            notes.add(m.groupValues[2].trim()); return c.getString(R.string.s_noted)
        }

        Regex("^(ingetin|ingatin|ingatkan|ingetkan|pengingat|reminder|remind me( to)?)\\b\\s*(aku|saya|gue|gw|me)?\\s*(untuk|buat|to)?\\s*(.*)$", RegexOption.IGNORE_CASE).matchEntire(text)?.let { m ->
            val (at, w) = whenOf(m.groupValues[5]) ?: return null
            val what = (w.ifEmpty { c.getString(R.string.s_reminder) }).replaceFirstChar { it.uppercase() }
            reminders.once(at, what)
            return said(at, what)
        }

        convert(low)?.let { return it }
        calc(low)?.let { return "= " + fmt(it) }
        return null
    }

    private fun said(at: Long, what: String): String {
        val cal = Calendar.getInstance().apply { timeInMillis = at }
        val today = Calendar.getInstance()
        val day = when {
            sameDay(cal, today) -> ""
            sameDay(cal, today.apply { add(Calendar.DAY_OF_YEAR, 1) }) -> c.getString(R.string.s_tomorrow)
            else -> java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM).format(java.util.Date(at)) + " "
        }
        val time = java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(java.util.Date(at))
        return c.getString(R.string.s_reminded, day, time, what)
    }

    private fun sameDay(a: Calendar, b: Calendar) = a.get(Calendar.YEAR) == b.get(Calendar.YEAR) && a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR)

    private fun spanText(ms: Long): String {
        val s = ms / 1000
        val h = s / 3600
        val m = (s % 3600) / 60
        val sec = s % 60
        return listOfNotNull(
            if (h > 0) c.getString(R.string.s_h, h) else null,
            if (m > 0) c.getString(R.string.s_m, m) else null,
            if (sec > 0) c.getString(R.string.s_s, sec) else null,
        ).joinToString(" ")
    }

    companion object {
        private val CLOCK = Regex("^(\\d{1,2}):(\\d{2})(?::(\\d{2}))?$")
        private val PART = Regex("(\\d{1,4})\\s*(jam|j|hours?|hrs?|h|menit|mnt|minutes?|mins?|m|detik|dtk|seconds?|secs?|s)(?![a-z])")
        private val PARTS = Regex("^(?:\\s*\\d{1,4}\\s*(?:jam|j|hours?|hrs?|h|menit|mnt|minutes?|mins?|m|detik|dtk|seconds?|secs?|s)(?![a-z])\\s*(?:dan|and|,)?)+$")

        // "5:30", "1 jam 30 menit", "1j30m"
        fun span(raw: String): Long? {
            val s = raw.trim()
            CLOCK.matchEntire(s)?.let { m ->
                val a = m.groupValues[1].toLong()
                val b = m.groupValues[2].toLong()
                if (b > 59) return null
                if (m.groupValues[3].isNotEmpty()) {
                    val sec = m.groupValues[3].toLong()
                    if (sec > 59) return null
                    return ((a * 60 + b) * 60 + sec) * 1000
                }
                return (a * 60 + b) * 1000
            }
            if (!PARTS.matches(s)) return null
            var ms = 0L
            for (m in PART.findAll(s)) {
                val v = m.groupValues[1].toLong()
                ms += when (unit(m.groupValues[2])) { 'h' -> v * 3_600_000L; 's' -> v * 1000L; else -> v * 60_000L }
            }
            return if (ms > 0) ms else null
        }

        private fun unit(u: String) = when {
            u.startsWith("j") || u.startsWith("h") -> 'h'
            u.startsWith("d") || u.startsWith("s") -> 's'
            else -> 'm'
        }

        private val RELATIVE = Regex("\\b(dalam|in)?\\s*(\\d{1,3})\\s*(menit|mnt|min|minutes?|jam|hours?|detik|dtk|seconds?)\\s*(lagi|later|dari sekarang|from now)?\\b", RegexOption.IGNORE_CASE)
        private val AT_CLOCK = Regex("\\b(besok|tomorrow|lusa)?\\s*(nanti\\s+)?(jam|pukul|at)\\s*(\\d{1,2})(?:[.:](\\d{2}))?\\s*(pagi|siang|sore|malam|am|pm)?\\b", RegexOption.IGNORE_CASE)

        // "15 menit lagi", "besok jam 7 pagi"
        fun whenOf(rest: String, now: Long = System.currentTimeMillis()): Pair<Long, String>? {
            RELATIVE.find(rest)?.let { m ->
                val v = m.groupValues[2].toLong()
                if (v <= 0) return null
                val at = when (unit(m.groupValues[3].lowercase(Locale.ROOT))) {
                    'h' -> now + v * 3_600_000L
                    's' -> now + maxOf(30L, v) * 1000L
                    else -> now + v * 60_000L
                }
                return at to tidy(rest.removeRange(m.range))
            }
            val m = AT_CLOCK.find(rest) ?: return null
            var h = m.groupValues[4].toInt()
            val min = m.groupValues[5].toIntOrNull() ?: 0
            val part = m.groupValues[6].lowercase(Locale.ROOT)
            if (h > 23 || min > 59) return null
            if ((part == "sore" || part == "malam" || part == "pm") && h < 12) h += 12
            else if (part == "siang" && h < 11) h += 12
            else if ((part == "pagi" || part == "am") && h == 12) h = 0
            val day = m.groupValues[1].lowercase(Locale.ROOT)
            val cal = Calendar.getInstance().apply {
                timeInMillis = now
                set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
                add(Calendar.DAY_OF_YEAR, if (day == "lusa") 2 else if (day.isNotEmpty()) 1 else 0)
            }
            val base = cal.timeInMillis
            var at = base + h * 3_600_000L + min * 60_000L
            // "jam 3" after 3 a.m. means afternoon
            if (day.isEmpty() && at <= now) {
                val pm = base + (h + 12) * 3_600_000L + min * 60_000L
                at = if (part.isEmpty() && h < 12 && pm > now) pm else at + 86_400_000L
            }
            return at to tidy(rest.removeRange(m.range))
        }

        private fun tidy(s: String): String {
            var t = s.replace(Regex("\\s{2,}"), " ").trim().trim(',', '.', ':', ';')
            t = t.replace(Regex("^(untuk|buat|to|bahwa)\\s+", RegexOption.IGNORE_CASE), "")
            return t.trim()
        }

        private class Unit(val kind: Char, val size: Double)

        private val UNITS: Map<String, Unit> = HashMap<String, Unit>().apply {
            fun add(k: Char, size: Double, names: String) { for (n in names.split(' ')) put(n, Unit(k, size)) }
            add('L', 0.001, "mm milimeter millimeter millimeters")
            add('L', 0.01, "cm sentimeter centimeter centimeters")
            add('L', 1.0, "m meter meters metre metres")
            add('L', 1000.0, "km kilometer kilometers")
            add('L', 0.0254, "in inch inches inci")
            add('L', 0.3048, "ft foot feet kaki")
            add('L', 0.9144, "yd yard yards")
            add('L', 1609.344, "mi mil mile miles")
            add('M', 0.001, "mg miligram milligram")
            add('M', 1.0, "g gr gram grams")
            add('M', 100.0, "ons")
            add('M', 1000.0, "kg kilo kilogram kilograms")
            add('M', 28.349523125, "oz ounce ounces")
            add('M', 453.59237, "lb lbs pound pounds")
            add('V', 0.001, "ml mililiter milliliter")
            add('V', 1.0, "l liter litre liters litres")
            add('V', 3.785411784, "gal galon gallon gallons")
            add('D', 1.0, "byte bytes")
            add('D', 1024.0, "kb")
            add('D', 1048576.0, "mb")
            add('D', 1073741824.0, "gb")
            add('D', 1099511627776.0, "tb")
            add('T', 1.0, "detik dtk sec secs second seconds")
            add('T', 60.0, "menit mnt min mins minute minutes")
            add('T', 3600.0, "jam hour hours")
            add('T', 86400.0, "hari day days")
            add('T', 604800.0, "minggu week weeks")
            add('S', 1.0, "m/s mps")
            add('S', 1000.0 / 3600, "km/j km/jam km/h kmh kph")
            add('S', 1609.344 / 3600, "mph")
            add('C', 0.0, "c °c celsius")
            add('C', 1.0, "f °f fahrenheit")
            add('C', 2.0, "k kelvin")
        }

        private val CONVERT = Regex("^(?:berapa|konversi|convert|ubah)?\\s*(-?[\\d.,]+)\\s*([a-z°/]+)\\s+(?:ke|to|in|into|jadi|dalam)\\s+([a-z°/]+)$")

        // "5 km ke mil", "72 f ke c"
        fun convert(low: String): String? {
            val m = CONVERT.matchEntire(low.trim()) ?: return null
            val from = UNITS[m.groupValues[2]] ?: return null
            val to = UNITS[m.groupValues[3]] ?: return null
            if (from.kind != to.kind) return null
            var v = num(m.groupValues[1].trimStart('-')) ?: return null
            if (m.groupValues[1].startsWith("-")) v = -v
            val r = if (from.kind == 'C') {
                val cel = when (from.size) { 0.0 -> v; 1.0 -> (v - 32) * 5 / 9; else -> v - 273.15 }
                when (to.size) { 0.0 -> cel; 1.0 -> cel * 9 / 5 + 32; else -> cel + 273.15 }
            } else v * from.size / to.size
            val k = if (abs(r) >= 100) 100.0 else 10000.0
            return "= " + fmt(round(r * k) / k) + " " + m.groupValues[3]
        }

        // "15% dari 240.000", "12 x 3,5"
        fun calc(low: String): Double? {
            var s = low.replace(Regex("^(berapa|hitung|hitungin|calc|what is|what's)\\s+"), "")
            s = s.replace("berapa", "").replace("=", "").trim()
            Regex("^([\\d.,]+)\\s*(%|persen|percent)\\s*(dari|of)\\s*([\\d.,]+)$").matchEntire(s)?.let { p ->
                val a = num(p.groupValues[1]) ?: return null
                val b = num(p.groupValues[4]) ?: return null
                return a / 100 * b
            }
            if (!Regex("^[\\d.,\\s+\\-*/x×÷:()^%]+$").matches(s) || !Regex("\\d\\s*[+\\-*/x×÷:^]\\s*[\\d(]").containsMatchIn(s)) return null
            val t = Regex("[\\d.,]+|[+\\-*/x×÷:()^%]").findAll(s).map { it.value }.toList()
            return try {
                val p = Parser(t)
                val r = p.sum()
                if (p.i == t.size && !r.isNaN() && !r.isInfinite()) r else null
            } catch (_: Exception) { null }
        }

        private class Parser(val t: List<String>) {
            var i = 0
            fun sum(): Double {
                var v = product()
                while (i < t.size && (t[i] == "+" || t[i] == "-")) { val add = t[i++] == "+"; val r = product(); v = if (add) v + r else v - r }
                return v
            }
            fun product(): Double {
                var v = power()
                while (i < t.size && t[i] in setOf("*", "x", "×", "/", "÷", ":")) {
                    val op = t[i++]
                    val r = power()
                    v = if (op == "*" || op == "x" || op == "×") v * r else v / r
                }
                return v
            }
            fun power(): Double {
                var v = atom()
                if (i < t.size && t[i] == "^") { i++; v = v.pow(power()) }
                return v
            }
            fun atom(): Double {
                if (i >= t.size) throw IllegalArgumentException()
                if (t[i] == "-") { i++; return -atom() }
                if (t[i] == "(") {
                    i++
                    val v = sum()
                    if (i >= t.size || t[i] != ")") throw IllegalArgumentException()
                    i++
                    return percent(v)
                }
                val n = num(t[i]) ?: throw IllegalArgumentException()
                i++
                return percent(n)
            }
            fun percent(v: Double): Double {
                if (i < t.size && t[i] == "%") { i++; return v / 100 }
                return v
            }
        }

        // 240.000 and 2,5 the Indonesian way
        fun num(raw: String): Double? {
            var s = raw.trim()
            s = when {
                Regex("^\\d{1,3}(\\.\\d{3})+(,\\d+)?$").matches(s) -> s.replace(".", "").replace(',', '.')
                Regex("^\\d{1,3}(,\\d{3})+(\\.\\d+)?$").matches(s) -> s.replace(",", "")
                else -> s.replace(',', '.')
            }
            return s.toDoubleOrNull()
        }

        fun fmt(v: Double): String {
            val sym = DecimalFormatSymbols(Locale.getDefault())
            return if (abs(v - round(v)) < 1e-9) DecimalFormat("#,##0", sym).format(round(v))
            else DecimalFormat("#,##0.####", sym).format(v)
        }
    }
}
