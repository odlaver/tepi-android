package io.github.odlaver.tepi

// One-time codes in any notification: SMS, email, banks
object Otp {
    private val words = Regex("(?i)(otp|kode|code|c[oó]digo|verifikasi|verification|verify|token|passcode|one[- ]?time|2fa|autentikasi|authentication|aktivasi|activation)")
    // 4-8 digits, or 3+3 split; not part of a longer number
    private val digits = Regex("(?<![\\d+])(?<!\\d[.,:/])(\\d{3}[- ]\\d{3}|\\d{4,8})(?!\\d|[.,:/]\\d|%)")
    private val money = Regex("(?i)(rp\\.?|idr|usd|\\$|€)\\s?[\\d.,]+")
    private val phone = Regex("(\\+?62|0)8\\d{2}[- ]?\\d{3,4}[- ]?\\d{3,4}")

    fun find(text: String): String? {
        if (!words.containsMatchIn(text)) return null
        // Money and phone numbers are never codes
        val clean = phone.replace(money.replace(text, " "), " ")
        val keys = words.findAll(clean).map { it.range.first }.toList()
        if (keys.isEmpty()) return null
        var best: String? = null
        var bestGap = Int.MAX_VALUE
        for (m in digits.findAll(clean)) {
            val code = m.value.replace("-", "").replace(" ", "")
            if (code.length !in 4..8) continue
            val gap = keys.minOf { kotlin.math.abs(it - m.range.first) }
            // A year unless right by the keyword
            if (code.length == 4 && (code.startsWith("19") || code.startsWith("20")) && gap > 12) continue
            if (gap <= 90 && gap < bestGap) { best = code; bestGap = gap }
        }
        return best
    }
}
