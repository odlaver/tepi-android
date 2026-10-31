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
import io.github.odlaver.tepi.scene.Action
import io.github.odlaver.tepi.scene.Alert
import io.github.odlaver.tepi.scene.AlertScene
import io.github.odlaver.tepi.scene.BriefScene
import io.github.odlaver.tepi.scene.CallScene
import io.github.odlaver.tepi.scene.ClockScene
import io.github.odlaver.tepi.scene.IncomingScene
import io.github.odlaver.tepi.scene.NavScene
import io.github.odlaver.tepi.scene.PrivacyScene
import io.github.odlaver.tepi.scene.ProgressScene
import io.github.odlaver.tepi.scene.Glyph
import io.github.odlaver.tepi.scene.Message
import io.github.odlaver.tepi.scene.NoteRow
import io.github.odlaver.tepi.scene.PrayerRow
import io.github.odlaver.tepi.scene.ShelfRow
import io.github.odlaver.tepi.scene.TimerRow
import io.github.odlaver.tepi.scene.TimerScene

// Hosts the island: bound by Android while access is on
class Listener : NotificationListenerService() {
    private var island: Island? = null
    private var media: Media? = null
    private lateinit var prefs: Prefs
    private lateinit var lyrics: Lyrics
    private val recent = HashMap<String, Long>()
    lateinit var timers: Timers
    lateinit var reminders: Reminders
    lateinit var notes: Notes
    lateinit var shelf: Shelf
    private var alerts: AlertScene? = null
    lateinit var live: Live
    private lateinit var watch: Watch
    private var briefs: BriefScene? = null
    private var privacyScene: PrivacyScene? = null
    private var timerScene: TimerScene? = null

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        lyrics = Lyrics(java.io.File(cacheDir, "lyrics"))
        notes = Notes(this)
        shelf = Shelf(this)
        timers = Timers(prefs, ended = { timerEnded(it) }, changed = { island?.refresh() })
        reminders = Reminders(this, prefs, prayAlert = { i, at, now -> prayAlert(i, at, now) }, nudge = { nudge(it) })
        live = Live(this, prefs, { n, pkg -> n.getLargeIcon()?.loadDrawable(this)?.let { bitmap(it) } ?: appIcon(pkg) }) { island?.refresh() }
        watch = Watch(this, prefs, brief = { k, t -> briefs?.show(k, t) }, lowBattery = { lowBattery(it) }, privacy = { cam, mic ->
            privacyScene?.let { it.cam = cam; it.mic = mic }
            island?.refresh()
        })
    }

    override fun onListenerConnected() {
        instance = this
        connected = true
        media = Media(this, ComponentName(this, Listener::class.java)) { island?.mediaChanged() }.also { it.start() }
        reminders.start()
        watch.start()
        try { activeNotifications?.forEach { live.posted(it) } } catch (_: Exception) { }
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
        reminders.stop()
        watch.stop()
        live.clear()
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
                wire(it)
                it.attach()
                it.mediaChanged()
            } catch (_: Exception) {
                island = null
            }
        }
    }

    // Features into a fresh island
    private fun wire(isl: Island) {
        val al = AlertScene(isl)
        val ts = TimerScene(isl, timers)
        alerts = al
        timerScene = ts
        isl.register(al)
        isl.register(ts)
        val br = BriefScene(isl)
        val pv = PrivacyScene(isl)
        briefs = br
        privacyScene = pv
        isl.register(br)
        isl.register(pv)
        isl.register(IncomingScene(isl, live))
        isl.register(CallScene(isl, live))
        isl.register(NavScene(isl, live))
        isl.register(ClockScene(isl, live))
        isl.register(ProgressScene(isl, live))
        isl.home.rows.add(0, TimerRow(isl, timers, ts))
        isl.home.rows.add(1, PrayerRow(isl, reminders))
        isl.home.rows.add(NoteRow(isl, notes) { open(Items::class.java, Items.NOTES) })
        isl.home.rows.add(ShelfRow(isl, shelf) { picked(it) })
        isl.homeExtras.add { if (!timers.active) Action(getString(R.string.t_focus_n, 25), primary = true, icon = Icon.Timer) { timers.focus(25) } else null }
        isl.homeExtras.add { Action(getString(R.string.note_new), icon = Icon.Note) { newNote() } }
        isl.menuExtras.add { Action(getString(R.string.t_timer), icon = Icon.Timer) { isl.expand(ts) } }
        isl.menuExtras.add { Action(getString(R.string.note_new), icon = Icon.Note) { newNote() } }
    }

    private fun open(cls: Class<*>, mode: String) {
        startActivity(android.content.Intent(this, cls).putExtra(Items.MODE, mode).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun newNote() {
        island?.input(getString(R.string.note_hint)) { text ->
            if (text.isNotBlank()) { notes.add(text); alert(Alert("note", getString(R.string.note_saved), text, Glyph.Check, ms = 2500)) }
        }
    }

    private fun picked(item: ShelfItem) {
        if (item.isText) {
            shelf.copy(item)
            alert(Alert("shelf", getString(R.string.copied), item.text!!.take(80), Glyph.Check, ms = 2500))
        } else island?.open(shelf.openIntent(item))
    }

    private fun alert(a: Alert) {
        ensureIsland()
        alerts?.show(a)
    }

    private fun lowBattery(level: Int) {
        alert(Alert("battery", getString(R.string.low_battery, level), getString(R.string.low_battery_body), Glyph.Bolt, ms = 6000))
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        live.removed(sbn.key)
    }

    private fun timerEnded(k: Timers.Kind) {
        Bell.ring(this)
        val (title, body) = when (k) {
            Timers.Kind.Focus -> getString(R.string.t_focus_done) to getString(R.string.t_break_now, prefs.int("focus.break", 5))
            Timers.Kind.Break -> getString(R.string.t_break_done) to getString(R.string.t_again)
            else -> getString(R.string.t_done) to ""
        }
        val acts = if (k == Timers.Kind.Break) listOf(Action(getString(R.string.t_focus_n, 25), primary = true, icon = Icon.Timer) { timers.focus(25) }) else emptyList()
        alert(Alert("timer", title, body, Glyph.Check, acts, 8000))
    }

    private fun prayAlert(i: Int, at: Long, now: Boolean) {
        if (now) Bell.ring(this)
        val name = Prayer.names[i]
        val time = java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(java.util.Date(at))
        val title = if (now) getString(R.string.pray_now, name) else getString(R.string.pray_soon, name, prefs.int("pray.before", 10))
        alert(Alert("pray", title, "${reminders.city()?.name ?: ""} · $time", Glyph.Moon, ms = if (now) 10_000 else 7000))
    }

    private fun nudge(n: Nudge) {
        val later = Action(getString(R.string.later)) { reminders.snooze(15) }
        when (n) {
            Nudge.Drink -> alert(Alert("nudge", getString(R.string.n_drink), getString(R.string.n_drink_count, reminders.drinkCount(), reminders.drinkGoal()), Glyph.Drop,
                listOf(Action(getString(R.string.n_drank), primary = true, icon = Icon.Check) { reminders.drank() }, later), 12_000))
            Nudge.Eyes -> alert(Alert("nudge", getString(R.string.n_eyes), getString(R.string.n_eyes_body), Glyph.Eye, listOf(later), 20_000))
            Nudge.Stretch -> alert(Alert("nudge", getString(R.string.n_stretch), getString(R.string.n_stretch_body), Glyph.Stretch, listOf(later), 12_000))
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
            "focus" -> timers.focus(25)
            "timer" -> timerScene?.let { isl.expand(it) }
            "note" -> newNote()
            else -> return false
        }
        return true
    }

    override fun onNotificationPosted(sbn: StatusBarNotification, rankingMap: RankingMap?) {
        live.posted(sbn)
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

        // Shelf saved: tell through the island
        fun shelved(msg: String): Boolean {
            val l = instance ?: return false
            if (l.island == null) return false
            l.alert(Alert("shelf", msg, "", Glyph.Check, ms = 2500))
            return true
        }
        var connected = false
            private set

        fun hasAccess(context: Context): Boolean =
            context.getSystemService(NotificationManager::class.java).isNotificationListenerAccessGranted(ComponentName(context, Listener::class.java))
    }
}
