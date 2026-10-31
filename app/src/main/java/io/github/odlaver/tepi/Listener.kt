package io.github.odlaver.tepi

import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.view.Display
import android.view.WindowManager
import io.github.odlaver.tepi.scene.Message

// Hosts the island: bound by Android while access is on
class Listener : NotificationListenerService() {
    private var island: Island? = null
    private var media: Media? = null
    private lateinit var prefs: Prefs
    private lateinit var lyrics: Lyrics
    private val recent = HashMap<String, Long>()

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        lyrics = Lyrics(java.io.File(cacheDir, "lyrics"))
    }

    override fun onListenerConnected() {
        instance = this
        connected = true
        media = Media(this, ComponentName(this, Listener::class.java)) { island?.mediaChanged() }.also { it.start() }
        ensureIsland()
    }

    override fun onListenerDisconnected() {
        connected = false
        teardown()
    }

    override fun onDestroy() {
        teardown()
        super.onDestroy()
    }

    private fun teardown() {
        island?.detach()
        island = null
        media?.stop()
        media = null
        if (instance === this) instance = null
    }

    // Overlay access may arrive later
    fun ensureIsland() {
        val m = media ?: return
        val access = Access.instance
        if (island != null || (access == null && !Settings.canDrawOverlays(this))) return
        val display = getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY) ?: return
        // Accessibility window sits above the status bar
        val type = if (access != null) WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY else WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        val ctx = access ?: if (Build.VERSION.SDK_INT >= 30) createWindowContext(display, type, null) else this
        val probe = if (access != null && Settings.canDrawOverlays(this)) {
            if (Build.VERSION.SDK_INT >= 30) createWindowContext(display, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null) else this
        } else null
        island = Island(ctx, probe, display, type, m, prefs, lyrics).also {
            try {
                it.attach()
                it.mediaChanged()
            } catch (_: Exception) {
                island = null
            }
        }
    }

    // Accessibility came or went
    fun rehost() {
        island?.detach()
        island = null
        ensureIsland()
    }

    fun prefsChanged() {
        island?.prefsChanged()
    }

    fun test() {
        ensureIsland()
        island?.show(Message("tepi.test", getString(R.string.test_title), getString(R.string.test_text), appIcon(packageName), null))
    }

    // From tile, shortcuts, notifications
    fun command(what: String): Boolean {
        ensureIsland()
        val isl = island ?: return false
        when (what) {
            Open.HOME -> isl.openHome()
            else -> return false
        }
        return true
    }

    override fun onNotificationPosted(sbn: StatusBarNotification, rankingMap: RankingMap?) {
        val isl = island ?: return
        if (!prefs.notifs) return
        val n = sbn.notification
        if (sbn.packageName == packageName || sbn.isOngoing || !isMessage(n)) return
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        if (rankingMap != null) {
            val rank = Ranking()
            if (rankingMap.getRanking(sbn.key, rank) && rank.importance < NotificationManager.IMPORTANCE_DEFAULT) return
        }
        val x = n.extras
        val title = (x.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE) ?: x.getCharSequence(Notification.EXTRA_TITLE))?.toString()?.trim().orEmpty()
        val text = lastMessage(n) ?: x.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim().orEmpty()
        if (title.isEmpty() && text.isEmpty()) return

        // Same text again: an update, not news
        val sig = sbn.key + "|" + title + "|" + text
        val nowMs = SystemClock.uptimeMillis()
        recent.entries.removeAll { nowMs - it.value > 60_000 }
        if (recent.containsKey(sig)) return
        recent[sig] = nowMs

        val locked = getSystemService(KeyguardManager::class.java).isKeyguardLocked
        val reveal = prefs.notifContent && !locked
        val app = appLabel(sbn.packageName)
        val icon = n.getLargeIcon()?.loadDrawable(this)?.let { bitmap(it) } ?: appIcon(sbn.packageName)
        val reply = if (reveal) n.actions?.firstOrNull { a -> a.remoteInputs?.any { it.allowFreeFormInput } == true } else null
        val others = if (reveal) n.actions?.filter { it !== reply && it.remoteInputs.isNullOrEmpty() && it.actionIntent != null }?.take(2).orEmpty() else emptyList()
        val m = if (reveal) Message(sbn.key, title.ifEmpty { app }, text, icon, n.contentIntent, reply, others)
        else Message(sbn.key, app, getString(R.string.new_message), appIcon(sbn.packageName), n.contentIntent)
        isl.show(m)
    }

    private fun isMessage(n: Notification): Boolean {
        if (n.extras.containsKey(Notification.EXTRA_MEDIA_SESSION)) return false
        if (n.category == Notification.CATEGORY_MESSAGE || n.category == Notification.CATEGORY_EMAIL) return true
        if (n.extras.containsKey(Notification.EXTRA_MESSAGES)) return true
        return n.actions?.any { it.remoteInputs?.isNotEmpty() == true } == true
    }

    // MessagingStyle: newest message wins
    private fun lastMessage(n: Notification): String? {
        val arr = n.extras.getParcelableArray(Notification.EXTRA_MESSAGES) ?: return null
        val last = arr.lastOrNull() as? android.os.Bundle ?: return null
        return last.getCharSequence("text")?.toString()?.trim()?.takeIf { it.isNotEmpty() }
    }

    private fun appLabel(pkg: String): String = try {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
    } catch (_: Exception) { pkg }

    private fun appIcon(pkg: String): Bitmap? = try {
        bitmap(packageManager.getApplicationIcon(pkg))
    } catch (_: Exception) { null }

    private fun bitmap(d: Drawable): Bitmap {
        if (d is BitmapDrawable && d.bitmap != null) return d.bitmap
        val side = (48 * resources.displayMetrics.density).toInt()
        val bmp = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        d.setBounds(0, 0, side, side)
        d.draw(c)
        return bmp
    }

    companion object {
        var instance: Listener? = null
            private set
        var connected = false
            private set

        fun hasAccess(context: Context): Boolean =
            context.getSystemService(NotificationManager::class.java).isNotificationListenerAccessGranted(ComponentName(context, Listener::class.java))
    }
}
