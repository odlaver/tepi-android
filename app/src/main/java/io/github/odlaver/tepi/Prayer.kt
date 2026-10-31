package io.github.odlaver.tepi

import java.util.Calendar
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.tan

class City(val name: String, val lat: Double, val lng: Double, val tz: Int)

// Prayer times computed locally, port of src/Reminders.cs
object Prayer {
    val names = arrayOf("Subuh", "Terbit", "Dzuhur", "Ashar", "Maghrib", "Isya")

    val cities = listOf(
        City("Banda Aceh", 5.5483, 95.3238, 7), City("Medan", 3.5952, 98.6722, 7), City("Padang", -0.9471, 100.4172, 7),
        City("Pekanbaru", 0.5071, 101.4478, 7), City("Palembang", -2.9761, 104.7754, 7), City("Bandar Lampung", -5.4292, 105.261, 7),
        City("Serang", -6.12, 106.1503, 7), City("Tangerang", -6.1783, 106.6319, 7), City("Jakarta", -6.2088, 106.8456, 7),
        City("Depok", -6.4025, 106.7942, 7), City("Bogor", -6.595, 106.8166, 7), City("Bekasi", -6.2383, 106.9756, 7),
        City("Bandung", -6.9175, 107.6191, 7), City("Semarang", -6.9667, 110.4167, 7), City("Yogyakarta", -7.7956, 110.3695, 7),
        City("Surakarta", -7.5755, 110.8243, 7), City("Surabaya", -7.2575, 112.7521, 7), City("Malang", -7.9666, 112.6326, 7),
        City("Pontianak", -0.0263, 109.3425, 7), City("Banjarmasin", -3.3186, 114.5944, 8), City("Balikpapan", -1.2379, 116.8529, 8),
        City("Samarinda", -0.5022, 117.1536, 8), City("Denpasar", -8.6705, 115.2126, 8), City("Mataram", -8.5833, 116.1167, 8),
        City("Makassar", -5.1477, 119.4327, 8), City("Manado", 1.4748, 124.8421, 8), City("Kupang", -10.1772, 123.607, 8),
        City("Ambon", -3.6954, 128.1814, 9), City("Jayapura", -2.5916, 140.669, 9),
    )

    fun city(name: String) = cities.firstOrNull { it.name == name }

    // Kemenag: Fajr 20°, Isha 18°, Asr 1x; epoch millis
    fun forDay(day: Calendar, c: City): LongArray {
        val y = day.get(Calendar.YEAR)
        val m = day.get(Calendar.MONTH) + 1
        val d = day.get(Calendar.DAY_OF_MONTH)
        val jd = julian(y, m, d) - c.lng / (15 * 24.0)
        val hours = doubleArrayOf(
            angleTime(jd, 20.0, 5 / 24.0, true, c.lat),
            angleTime(jd, 0.833, 6 / 24.0, true, c.lat),
            midDay(jd, 12 / 24.0),
            asrTime(jd, 1.0, 13 / 24.0, c.lat),
            angleTime(jd, 0.833, 18 / 24.0, false, c.lat),
            angleTime(jd, 18.0, 18 / 24.0, false, c.lat),
        )
        val safety = intArrayOf(2, -2, 2, 2, 2, 2)
        // Midnight of that day in the city's own zone
        val utcMidnight = Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC")).apply {
            clear(); set(y, m - 1, d, 0, 0, 0)
        }.timeInMillis - c.tz * 3_600_000L
        return LongArray(6) { i ->
            val h = hours[i] + c.tz - c.lng / 15
            var t = utcMidnight + (h * 3_600_000).toLong() + safety[i] * 60_000L
            // Round up, like printed schedules
            val rem = t % 60_000L
            if (rem != 0L) t += 60_000L - rem
            t
        }
    }

    // Next alerted prayer index and time; skips Terbit
    fun next(c: City, nowMs: Long = System.currentTimeMillis()): Pair<Int, Long> {
        val today = Calendar.getInstance()
        val times = forDay(today, c)
        for (i in 0 until 6) if (i != 1 && times[i] > nowMs) return i to times[i]
        val tomorrow = (today.clone() as Calendar).apply { add(Calendar.DAY_OF_MONTH, 1) }
        return 0 to forDay(tomorrow, c)[0]
    }

    private fun julian(yy: Int, mm: Int, d: Int): Double {
        var y = yy
        var m = mm
        if (m <= 2) { y -= 1; m += 12 }
        val a = floor(y / 100.0)
        val b = 2 - a + floor(a / 4)
        return floor(365.25 * (y + 4716)) + floor(30.6001 * (m + 1)) + d + b - 1524.5
    }

    private fun rad(d: Double) = d * Math.PI / 180
    private fun deg(r: Double) = r * 180 / Math.PI
    private fun fix(a: Double, b: Double): Double {
        val x = a - b * floor(a / b)
        return if (x < 0) x + b else x
    }

    // Declination and equation of time
    private fun sun(jd: Double): Pair<Double, Double> {
        val d = jd - 2451545.0
        val g = fix(357.529 + 0.98560028 * d, 360.0)
        val q = fix(280.459 + 0.98564736 * d, 360.0)
        val l = fix(q + 1.915 * sin(rad(g)) + 0.020 * sin(rad(2 * g)), 360.0)
        val e = 23.439 - 0.00000036 * d
        val ra = deg(atan2(cos(rad(e)) * sin(rad(l)), cos(rad(l)))) / 15
        val eqt = q / 15 - fix(ra, 24.0)
        val decl = deg(asin(sin(rad(e)) * sin(rad(l))))
        return decl to eqt
    }

    private fun midDay(jd: Double, t: Double) = fix(12 - sun(jd + t).second, 24.0)

    private fun angleTime(jd: Double, angle: Double, t: Double, before: Boolean, lat: Double): Double {
        val decl = sun(jd + t).first
        val noon = midDay(jd, t)
        val c = (-sin(rad(angle)) - sin(rad(decl)) * sin(rad(lat))) / (cos(rad(decl)) * cos(rad(lat)))
        val span = deg(acos(c.coerceIn(-1.0, 1.0))) / 15
        return noon + if (before) -span else span
    }

    private fun asrTime(jd: Double, factor: Double, t: Double, lat: Double): Double {
        val decl = sun(jd + t).first
        val angle = -deg(atan(1 / (factor + tan(rad(abs(lat - decl))))))
        return angleTime(jd, angle, t, false, lat)
    }
}
