package io.github.odlaver.tepi

import android.app.Notification
import android.os.SystemClock
import android.service.notification.StatusBarNotification

// One alert per message, however it arrives
class Dedupe(private val windowMs: Long = 120_000L) {
    private val sigs = LinkedHashMap<String, Long>()
    private val convTime = LinkedHashMap<String, Long>()
    private val keys = HashMap<String, Long>()

    // False when this is a repeat
    fun fresh(sbn: StatusBarNotification, sender: String, text: String, conv: String): Boolean {
        val n = sbn.notification
        val fam = family(sbn.packageName)
        val now = SystemClock.uptimeMillis()
        // Silent update of a known key
        val known = keys.containsKey(sbn.key)
        keys[sbn.key] = now
        if (keys.size > 400) keys.keys.take(100).toList().forEach { keys.remove(it) }
        if (known && n.flags and Notification.FLAG_ONLY_ALERT_ONCE != 0) return false
        // History re-delivered: last message not newer
        val at = lastTime(n)
        if (at > 0) {
            val c = "$fam|${norm(conv)}"
            val seen = convTime[c] ?: 0L
            if (at <= seen) return false
            convTime[c] = at
            while (convTime.size > 300) convTime.remove(convTime.keys.first())
        }
        val sig = "$fam|${norm(sender)}|${norm(text)}"
        sigs.entries.removeAll { now - it.value > windowMs }
        if (sigs.containsKey(sig)) return false
        sigs[sig] = now
        return true
    }

    fun clear() { sigs.clear(); convTime.clear(); keys.clear() }

    companion object {
        // Clients sharing one account
        private val FAMILIES = mapOf(
            "org.telegram.messenger.web" to "org.telegram.messenger",
            "org.telegram.plus" to "org.telegram.messenger",
            "org.thunderdog.challegram" to "org.telegram.messenger",
            "com.google.android.apps.messaging" to "sms",
            "com.android.mms" to "sms",
            "com.samsung.android.messaging" to "sms",
        )

        fun family(pkg: String) = FAMILIES[pkg] ?: pkg

        // Case, spacing, invisible marks ignored
        fun norm(s: String): String = s.replace(Regex("[\\u200B-\\u200F\\u2060\\uFEFF]"), "")
            .replace(Regex("\\s+"), " ").trim().lowercase()

        @Suppress("DEPRECATION")
        fun lastTime(n: Notification): Long {
            val b = n.extras.getParcelableArray(Notification.EXTRA_MESSAGES)?.lastOrNull() as? android.os.Bundle ?: return 0L
            return b.getLong("time", 0L)
        }
    }
}
