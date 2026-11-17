package io.github.odlaver.tepi

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.service.notification.NotificationListenerService.Ranking
import org.json.JSONObject

// Who shows the pop-up: Tepi or Android
object Banners {
    const val SYSTEM = "system"
    const val TEPI = "tepi"

    fun owner(p: Prefs) = p.str("banner.owner", SYSTEM)
    fun systemOwns(p: Prefs) = owner(p) != TEPI

    // Android will pop this up itself
    fun systemPeeks(r: Ranking?, locked: Boolean): Boolean {
        if (r == null) return true
        // Lock screen lists it anyway
        if (locked) return true
        if (r.importance < NotificationManager.IMPORTANCE_HIGH) return false
        if (r.isAmbient) return false
        if (!r.matchesInterruptionFilter()) return false
        @Suppress("DEPRECATION")
        if (r.suppressedVisualEffects and NotificationManager.Policy.SUPPRESSED_EFFECT_PEEK != 0) return false
        return true
    }

    // Last seen pop-up state per app
    fun note(p: Prefs, pkg: String, r: Ranking?) {
        if (r == null) return
        val all = load(p)
        val ch = r.channel?.id.orEmpty()
        val peek = r.importance >= NotificationManager.IMPORTANCE_HIGH
        val old = all.optJSONObject(pkg)
        if (old != null && old.optString("ch") == ch && old.optBoolean("peek") == peek) return
        all.put(pkg, JSONObject().put("ch", ch).put("peek", peek).put("at", System.currentTimeMillis()))
        // Newest 40 apps only
        if (all.length() > 40) {
            val oldest = all.keys().asSequence().minByOrNull { all.optJSONObject(it)?.optLong("at") ?: 0L }
            if (oldest != null) all.remove(oldest)
        }
        p.set("banner.apps", all.toString())
    }

    class App(val pkg: String, val channel: String, val peek: Boolean, val at: Long)

    fun apps(p: Prefs): List<App> {
        val all = load(p)
        return all.keys().asSequence().mapNotNull { k ->
            all.optJSONObject(k)?.let { App(k, it.optString("ch"), it.optBoolean("peek"), it.optLong("at")) }
        }.sortedByDescending { it.at }.toList()
    }

    private fun load(p: Prefs) = try { JSONObject(p.str("banner.apps", "{}")) } catch (_: Exception) { JSONObject() }

    // Channel page first, app page second
    fun open(c: Context, app: App) {
        val tries = ArrayList<Intent>()
        if (app.channel.isNotEmpty()) tries.add(Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, app.pkg).putExtra(Settings.EXTRA_CHANNEL_ID, app.channel))
        tries.add(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, app.pkg))
        tries.add(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:${app.pkg}")))
        for (i in tries) {
            try { c.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); return } catch (_: Exception) { }
        }
    }

    // Brand name of the pop-up switch
    fun switchName(c: Context): String = c.getString(when (Oem.family) {
        Oem.Family.Xiaomi -> R.string.popup_xiaomi
        Oem.Family.Oppo -> R.string.popup_oppo
        Oem.Family.Samsung -> R.string.popup_samsung
        Oem.Family.Vivo -> R.string.popup_vivo
        else -> R.string.popup_android
    })

    // Hidden importance on brand skins
    val exact get() = Oem.family !in setOf(Oem.Family.Xiaomi, Oem.Family.Oppo, Oem.Family.Vivo)
}
