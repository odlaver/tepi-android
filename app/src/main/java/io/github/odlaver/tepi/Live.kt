package io.github.odlaver.tepi

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.service.notification.StatusBarNotification

enum class LiveKind { Incoming, Call, Progress, Nav, Clock }

class LiveItem(
    val key: String,
    val kind: LiveKind,
    val pkg: String,
    val title: String,
    val text: String,
    val icon: Bitmap?,
    val progress: Int,
    val max: Int,
    val indeterminate: Boolean,
    val whenMs: Long,
    val countDown: Boolean,
    val intent: PendingIntent?,
    val actions: List<Notification.Action>,
)

// Ongoing notifications worth an island activity
class Live(private val context: Context, private val prefs: Prefs, private val iconOf: (Notification, String) -> Bitmap?, private val changed: () -> Unit) {
    private val items = LinkedHashMap<String, LiveItem>()

    fun of(kind: LiveKind) = items.values.filter { it.kind == kind }

    fun posted(sbn: StatusBarNotification) {
        val item = parse(sbn)
        val had = items.remove(sbn.key) != null
        if (item != null) items[sbn.key] = item
        if (item != null || had) changed()
    }

    fun removed(key: String) {
        if (items.remove(key) != null) changed()
    }

    fun clear() = items.clear()

    private fun parse(sbn: StatusBarNotification): LiveItem? {
        val n = sbn.notification
        val x = n.extras
        if (sbn.packageName == context.packageName || x.containsKey(Notification.EXTRA_MEDIA_SESSION)) return null
        val title = x.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
        val text = (x.getCharSequence(Notification.EXTRA_TEXT) ?: x.getCharSequence(Notification.EXTRA_SUB_TEXT))?.toString()?.trim().orEmpty()
        val chrono = x.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER)
        val max = x.getInt(Notification.EXTRA_PROGRESS_MAX, 0)
        val indet = x.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE)
        val kind = when {
            n.category == Notification.CATEGORY_CALL -> {
                if (!prefs.bool("act.call", true)) return null
                val type = if (Build.VERSION.SDK_INT >= 31) x.getInt(Notification.EXTRA_CALL_TYPE, 0) else 0
                // CallStyle: 1 incoming, 2 ongoing, 3 screening
                when {
                    type == 1 || type == 3 -> LiveKind.Incoming
                    type == 2 || chrono -> LiveKind.Call
                    sbn.isOngoing -> LiveKind.Incoming
                    else -> return null
                }
            }
            n.category == Notification.CATEGORY_NAVIGATION -> if (prefs.bool("act.nav", true) && sbn.isOngoing) LiveKind.Nav else return null
            chrono && sbn.isOngoing && (n.category == Notification.CATEGORY_STOPWATCH || n.category == Notification.CATEGORY_ALARM || "clock" in sbn.packageName) ->
                if (prefs.bool("act.progress", true)) LiveKind.Clock else return null
            sbn.isOngoing && (max > 0 || indet) && n.category != Notification.CATEGORY_SERVICE ->
                if (prefs.bool("act.progress", true)) LiveKind.Progress else return null
            else -> return null
        }
        if (kind == LiveKind.Progress && max > 0 && x.getInt(Notification.EXTRA_PROGRESS, 0) >= max) return null
        val countDown = if (Build.VERSION.SDK_INT >= 24) x.getBoolean(Notification.EXTRA_CHRONOMETER_COUNT_DOWN) else false
        return LiveItem(
            sbn.key, kind, sbn.packageName,
            title.ifEmpty { label(sbn.packageName) }, text,
            iconOf(n, sbn.packageName),
            x.getInt(Notification.EXTRA_PROGRESS, 0), max, indet,
            n.`when`, countDown, n.contentIntent,
            n.actions?.filter { it.actionIntent != null }.orEmpty(),
        )
    }

    private fun label(pkg: String) = try {
        context.packageManager.getApplicationLabel(context.packageManager.getApplicationInfo(pkg, 0)).toString()
    } catch (_: Exception) { pkg }
}
