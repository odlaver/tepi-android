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
import io.github.odlaver.tepi.scene.AgentRow
import io.github.odlaver.tepi.scene.AgentScene
import io.github.odlaver.tepi.scene.AlertScene
import io.github.odlaver.tepi.scene.BriefScene
import io.github.odlaver.tepi.scene.CallScene
import io.github.odlaver.tepi.scene.ClassRow
import io.github.odlaver.tepi.scene.TaskRow
import io.github.odlaver.tepi.scene.TasksScene
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
    private var wasLow = false
    private val shown = Dedupe()
    private val sentPc = Dedupe()
    private val codes = HashMap<String, Long>()
    private val forwarded = HashSet<String>()
    lateinit var timers: Timers
    lateinit var reminders: Reminders
    lateinit var notes: Notes
    private lateinit var suruh: Suruh
    lateinit var shelf: Shelf
    private var alerts: AlertScene? = null
    lateinit var live: Live
    lateinit var agenda: Agenda
    lateinit var pc: Pc
    private lateinit var watch: Watch
    private var briefs: BriefScene? = null
    private var privacyScene: PrivacyScene? = null
    private var timerScene: TimerScene? = null
    private var translateScene: io.github.odlaver.tepi.scene.TranslateScene? = null

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        Power.start(this) {
            // Only a real flip reloads
            val now = Power.low(prefs)
            if (now != wasLow) { wasLow = now; island?.prefsChanged() }
            if (Power.screenOn) { reminders.wake(); checkUpdate() }
        }
        wasLow = Power.low(prefs)
        // Leftovers from the old HyperOS island mode
        try { getSystemService(NotificationManager::class.java).cancelAll() } catch (_: Exception) { }
        lyrics = Lyrics(java.io.File(cacheDir, "lyrics"))
        notes = Notes(this)
        shelf = Shelf(this)
        timers = Timers(prefs, ended = { timerEnded(it) }, changed = { island?.refresh() }, alarm = { Wake.set(this, Wake.TIMER, it) })
        reminders = Reminders(this, prefs, prayAlert = { i, at, now -> prayAlert(i, at, now) }, nudge = { nudge(it) })
        reminders.due = { text, at -> onceDue(text, at) }
        suruh = Suruh(this, timers, notes, reminders)
        agenda = Agenda(this, prefs, changed = { island?.refresh() }, classSoon = { classSoon(it) })
        pc = Pc(this, agenda.vault, prefs) { island?.refresh() }
        pc.onFile = { name, uri -> fromLaptop(name, uri) }
        live = Live(this, prefs, { n, pkg -> n.getLargeIcon()?.loadDrawable(this)?.let { bitmap(it) } ?: appIcon(pkg) }) { island?.refresh() }
        watch = Watch(this, prefs, brief = { k, t -> briefs?.show(k, t) }, lowBattery = { lowBattery(it) }, battery = { l, c -> pc.battery(l, c) }, privacy = { cam, mic ->
            privacyScene?.let { it.cam = cam; it.mic = mic }
            island?.refresh()
        })
    }

    override fun onListenerConnected() {
        instance = this
        connected = true
        media = Media(this, ComponentName(this, Listener::class.java)) { island?.mediaChanged() }.also { it.small = { Power.low(prefs) }; it.start() }
        applyEnabled()
        checkUpdate()
    }

    // New version: island card plus notification
    private fun checkUpdate() = UpdateCheck.maybe(this, prefs) { rel ->
        ensureIsland()
        island?.show(Message("tepi.update", getString(R.string.update_found, rel.version), getString(R.string.update_tap), appIcon(packageName), UpdateCheck.open(this, rel)))
    }

    // Start or pause everything after the master switch
    fun applyEnabled() {
        if (prefs.enabled) {
            reminders.start()
            watch.start()
            agenda.start()
            pc.start()
            try { activeNotifications?.forEach { live.posted(it) } } catch (_: Exception) { }
            ensureIsland()
        } else {
            island?.detach()
            island = null
            reminders.stop()
            watch.stop()
            agenda.stop()
            pc.stop()
            live.clear()
        }
        Tile.refresh(this)
    }

    override fun onListenerDisconnected() {
        connected = false
        teardown()
    }

    override fun onDestroy() {
        Power.stop(this)
        teardown()
        super.onDestroy()
    }

    private fun teardown() {
        island?.detach()
        island = null
        reminders.stop()
        watch.stop()
        agenda.stop()
        pc.stop()
        live.clear()
        media?.stop()
        media = null
        if (instance === this) instance = null
    }

    // Overlay access may arrive later
    fun ensureIsland() {
        if (!prefs.enabled) return
        val m = media ?: return
        val access = Access.instance
        if (island != null || (access == null && !Settings.canDrawOverlays(this))) return
        val display = getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY) ?: return
        // Accessibility window sits above the status bar
        val type = if (access != null) WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY else WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        val ctx = access ?: if (Build.VERSION.SDK_INT >= 31) createWindowContext(display, type, null) else this
        val probe = if (access != null && Settings.canDrawOverlays(this)) {
            if (Build.VERSION.SDK_INT >= 31) createWindowContext(display, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null) else this
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
        isl.register(AgentScene(isl, pc))
        translateScene = io.github.odlaver.tepi.scene.TranslateScene(isl).also { isl.register(it) }
        isl.home.rows.add(AgentRow(isl, pc))
        isl.register(IncomingScene(isl, live))
        isl.register(CallScene(isl, live))
        isl.register(NavScene(isl, live))
        isl.register(ClockScene(isl, live))
        isl.register(ProgressScene(isl, live))
        isl.register(io.github.odlaver.tepi.scene.PromotedScene(isl, live))
        isl.home.rows.add(0, TimerRow(isl, timers, ts))
        isl.home.rows.add(1, ClassRow(isl, agenda))
        isl.home.rows.add(2, PrayerRow(isl, reminders))
        // Fixed tabs for every main feature
        val agendaTab = io.github.odlaver.tepi.scene.AgendaTab(isl, agenda) { section(Section.CONNECT) }
        isl.register(agendaTab)
        isl.register(io.github.odlaver.tepi.scene.PrayerTab(isl, reminders) { section(Section.REMINDERS) })
        isl.register(io.github.odlaver.tepi.scene.NotesTab(isl, notes, { newNote() }) { open(Items::class.java, Items.NOTES) })
        isl.register(io.github.odlaver.tepi.scene.ShelfTab(isl, shelf, { picked(it) }) { open(Items::class.java, Items.SHELF) })
        isl.register(io.github.odlaver.tepi.scene.AgentsTab(isl, pc))
        isl.home.rows.add(TaskRow(isl, agenda, agendaTab))
        isl.home.rows.add(NoteRow(isl, notes) { open(Items::class.java, Items.NOTES) })
        isl.home.rows.add(ShelfRow(isl, shelf) { picked(it) })
        isl.homeExtras.add { if (Access.paused(this)) Action(getString(R.string.perm_access), primary = true, icon = Icon.Hand) { Access.openSettings(this) } else null }
        isl.homeExtras.add { if (!timers.active) Action(getString(R.string.t_focus_n, 25), primary = true, icon = Icon.Timer) { timers.focus(25) } else null }
        isl.homeExtras.add { Action(getString(R.string.note_new), icon = Icon.Note, personal = true) { newNote() } }
        isl.menuExtras.add { Action(getString(R.string.t_timer), icon = Icon.Timer) { isl.expand(ts) } }
        isl.menuExtras.add { Access.instance?.let { a -> Action(getString(R.string.bank_mode), icon = Icon.Shield) { a.stepAside(null) } } }
        isl.menuExtras.add { Action(getString(R.string.note_new), icon = Icon.Note, personal = true) { newNote() } }
        isl.menuExtras.add { Action(getString(R.string.suruh), icon = Icon.Spark) { ask() } }
    }

    // Suruh Tepi: typed, offline
    private fun ask() {
        island?.input(getString(R.string.suruh_hint)) { text ->
            if (text.isBlank()) return@input
            val done = suruh.run(text)
            if (done != null) alert(Alert("suruh", done, "", Glyph.Check, ms = 4000))
            else alert(Alert("suruh", getString(R.string.suruh_unknown), getString(R.string.suruh_try), Glyph.Bell, ms = 6000))
        }
    }

    private fun onceDue(text: String, at: Long) {
        Bell.ring(this)
        val time = java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(java.util.Date(at))
        alert(Alert("once", text, time, Glyph.Bell, listOf(Action(getString(R.string.close), primary = true, icon = Icon.Check) { }), 20_000))
    }

    private fun section(mode: String) {
        startActivity(android.content.Intent(this, Section::class.java).putExtra(Section.MODE, mode).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
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
        // Island is gone by the time the screen lights
        if (!Power.screenOn && a.key in LATER) Bell.note(this, a.title, a.body)
    }

    // Alarm fired while asleep
    fun woke() {
        timers.due()
        reminders.now()
    }

    private fun classSoon(s: ClassSlot) {
        val time = java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(java.util.Date(s.start))
        val where = listOf(time, s.room.ifEmpty { if (s.online) "Online" else "" }).filter { it.isNotEmpty() }.joinToString(" · ")
        val acts = s.url?.let { u -> listOf(Action(getString(R.string.join), primary = true, icon = Icon.Open) {
            island?.open(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(u)))
        }) } ?: emptyList()
        alert(Alert("class", getString(if (s.exam) R.string.exam_soon else R.string.class_soon, s.title), where, Glyph.Book, acts, 10_000))
    }

    private fun lowBattery(level: Int) {
        alert(Alert("battery", getString(R.string.low_battery, level), getString(R.string.low_battery_body), Glyph.Bolt, ms = 6000))
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        live.removed(sbn.key)
        if (forwarded.remove(sbn.key)) pc.removed(sbn.key)
    }

    // Laptop sent a file; it sits in Download/Tepi
    private fun fromLaptop(name: String, uri: android.net.Uri?) {
        if (uri == null) { alert(Alert("pc", getString(R.string.pc_file_failed), name, Glyph.Bell, ms = 4000)); return }
        val open = Action(getString(R.string.open), primary = true, icon = Icon.Open) {
            val type = contentResolver.getType(uri) ?: "*/*"
            island?.open(android.content.Intent(android.content.Intent.ACTION_VIEW).setDataAndType(uri, type).addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION))
        }
        alert(Alert("pc", getString(R.string.pc_file_in), name, Glyph.Check, listOf(open), 8000))
    }

    // Send to laptop finished
    fun sentToLaptop(ok: Boolean, name: String) {
        alert(Alert("pc", getString(if (ok) R.string.pc_sent else R.string.pc_send_failed), name, if (ok) Glyph.Check else Glyph.Bell, ms = 4000))
    }

    // Codes from any app; never the notification itself
    private fun otpFrom(sbn: StatusBarNotification, island: Boolean): String? {
        val x = sbn.notification.extras
        val parts = listOf(x.getCharSequence(Notification.EXTRA_TITLE), x.getCharSequence(Notification.EXTRA_TEXT), x.getCharSequence(Notification.EXTRA_BIG_TEXT),
            lastBundle(sbn.notification)?.getCharSequence("text"), sbn.notification.tickerText).mapNotNull { it?.toString() }.distinct()
        val code = Otp.find(parts.joinToString("\n")) ?: return null
        val now = SystemClock.uptimeMillis()
        codes.entries.removeAll { now - it.value > 120_000 }
        if (codes.containsKey(code)) return code
        codes[code] = now
        val app = appLabel(sbn.packageName)
        if (pc.linked && pc.sendOtp) pc.otp(app, code)
        if (island && prefs.notifs) offerCode(app, code)
        return code
    }

    // Never on the lock screen, never when content is hidden
    private fun offerCode(app: String, code: String) {
        val locked = getSystemService(KeyguardManager::class.java).isKeyguardLocked
        if (!prefs.bool("otp.island", true) || !prefs.notifContent || locked) return
        val copy = Action(getString(R.string.copy), primary = true, icon = Icon.Copy) {
            val cm = getSystemService(android.content.ClipboardManager::class.java)
            val clip = android.content.ClipData.newPlainText(app, code)
            // Keep the code out of keyboard clipboard history
            clip.description.extras = android.os.PersistableBundle().apply { putBoolean("android.content.extra.IS_SENSITIVE", true) }
            try { cm.setPrimaryClip(clip) } catch (_: Exception) { }
        }
        alert(Alert("otp", getString(R.string.otp_code, code), app, Glyph.Check, listOf(copy), 20_000))
    }

    // Messages to the laptop's Pesan
    private fun forward(sbn: StatusBarNotification, rankingMap: RankingMap?) {
        val n = sbn.notification
        if (sbn.isOngoing || !isMessage(n) || n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        if (rankingMap != null) {
            val rank = Ranking()
            if (rankingMap.getRanking(sbn.key, rank) && rank.importance < NotificationManager.IMPORTANCE_DEFAULT) return
        }
        if (mine(n)) return
        val (title, text) = chat(n)
        if (title.isEmpty() && text.isEmpty()) return
        if (!sentPc.fresh(sbn, title, text, conv(n, title))) return
        val app = appLabel(sbn.packageName)
        if (forwarded.size > 500) forwarded.clear()
        forwarded.add(sbn.key)
        if (prefs.notifContent) pc.notif(sbn.key, sbn.packageName, app, title.ifEmpty { app }, text, appIcon(sbn.packageName))
        else pc.notif(sbn.key, sbn.packageName, app, app, getString(R.string.new_message), appIcon(sbn.packageName))
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

    // Calibration page shows the guide
    fun calibrate(on: Boolean) { island?.calibrating = on }

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
            "ask" -> ask()
            else -> return false
        }
        return true
    }

    override fun onNotificationPosted(sbn: StatusBarNotification, rankingMap: RankingMap?) {
        if (sbn.packageName != packageName) AppFilter.saw(prefs, sbn.packageName)
        val own = sbn.packageName == packageName
        val allowed = AppFilter.allowed(prefs, sbn.packageName)
        val code = if (!own) otpFrom(sbn, allowed) else null
        // User turned this app off
        if (!allowed) return
        live.posted(sbn)
        if (!own && (code == null || !pc.sendOtp) && pc.linked && pc.online) forward(sbn, rankingMap)
        val isl = island ?: return
        if (!prefs.notifs) return
        val n = sbn.notification
        if (sbn.packageName == packageName || sbn.isOngoing || !isMessage(n)) return
        // The code card already covers this message
        if (code != null && prefs.bool("otp.island", true) && prefs.notifContent && !getSystemService(KeyguardManager::class.java).isKeyguardLocked) return
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        val rank = rankingMap?.let { m -> Ranking().takeIf { m.getRanking(sbn.key, it) } }
        if (rank != null && rank.importance < NotificationManager.IMPORTANCE_DEFAULT) return
        // Our own reply echoed back
        if (mine(n)) return
        val (title, text) = chat(n)
        if (title.isEmpty() && text.isEmpty()) return
        // Repeats, updates, history: one alert
        if (!shown.fresh(sbn, title, text, conv(n, title))) return

        val locked = getSystemService(KeyguardManager::class.java).isKeyguardLocked
        Banners.note(prefs, sbn.packageName, rank)
        // Android pops it up, or Do Not Disturb holds it: stay small
        val dnd = rank != null && !rank.matchesInterruptionFilter()
        val quiet = dnd || (Banners.systemOwns(prefs) && Banners.systemPeeks(rank, locked))
        val reveal = prefs.notifContent && !locked
        val app = appLabel(sbn.packageName)
        val badge = appIcon(sbn.packageName)
        val icon = n.getLargeIcon()?.loadDrawable(this)?.let { bitmap(it) } ?: badge
        val reply = if (reveal) n.actions?.firstOrNull { a -> a.remoteInputs?.any { it.allowFreeFormInput } == true } else null
        val others = if (reveal) n.actions?.filter { it !== reply && it.remoteInputs.isNullOrEmpty() && it.actionIntent != null }?.take(2).orEmpty() else emptyList()
        val built = if (reveal) Message(sbn.key, title.ifEmpty { app }, text, icon, n.contentIntent, reply, others, app, badge, sbn.postTime, quiet)
        else Message(sbn.key, app, getString(R.string.new_message), badge, n.contentIntent, app = app, appIcon = badge, at = sbn.postTime, quiet = quiet)
        isl.show(built)
    }

    private fun isMessage(n: Notification): Boolean {
        if (n.extras.containsKey(Notification.EXTRA_MEDIA_SESSION)) return false
        if (n.category == Notification.CATEGORY_MESSAGE || n.category == Notification.CATEGORY_EMAIL) return true
        if (n.extras.containsKey(Notification.EXTRA_MESSAGES)) return true
        return n.actions?.any { it.remoteInputs?.isNotEmpty() == true } == true
    }

    // Last message sent by the user
    @Suppress("DEPRECATION")
    private fun mine(n: Notification): Boolean {
        val last = lastBundle(n) ?: return false
        val sender = senderOf(last)
        val person = if (Build.VERSION.SDK_INT >= 28) n.extras.getParcelable<android.app.Person>(Notification.EXTRA_MESSAGING_PERSON)?.name else null
        val self = (person ?: n.extras.getCharSequence(Notification.EXTRA_SELF_DISPLAY_NAME))?.toString()?.trim()
        if (self.isNullOrEmpty()) return false
        return sender == null || sender == self
    }

    // Chat identity for history checks
    private fun conv(n: Notification, title: String): String =
        n.extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)?.toString()?.trim()?.takeIf { it.isNotEmpty() } ?: title

    // MessagingStyle: newest message wins
    // Chat apps put the account in EXTRA_TITLE; the sender is in the message
    private fun chat(n: Notification): Pair<String, String> {
        val x = n.extras
        val conv = x.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)?.toString()?.trim()?.takeIf { it.isNotEmpty() }
        val last = lastBundle(n)
        val sender = last?.let { senderOf(it) }
        // Android 8 has no group flag
        val group = (if (Build.VERSION.SDK_INT >= 28) x.getBoolean(Notification.EXTRA_IS_GROUP_CONVERSATION) else x.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE) != null) && conv != null
        val body = last?.getCharSequence("text")?.toString()?.trim()?.takeIf { it.isNotEmpty() }
            ?: x.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim().orEmpty()
        val title = (if (group) conv else sender ?: conv ?: x.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim()).orEmpty()
        return title to (if (group && sender != null) "$sender: $body" else body)
    }

    @Suppress("DEPRECATION")
    private fun lastBundle(n: Notification): android.os.Bundle? =
        n.extras.getParcelableArray(Notification.EXTRA_MESSAGES)?.lastOrNull() as? android.os.Bundle

    // Person name first, legacy sender string second
    @Suppress("DEPRECATION")
    private fun senderOf(b: android.os.Bundle): String? {
        val person = if (Build.VERSION.SDK_INT >= 28) b.getParcelable<android.app.Person>("sender_person")?.name else null
        return (person ?: b.getCharSequence("sender"))?.toString()?.trim()?.takeIf { it.isNotEmpty() }
    }

    private fun appLabel(pkg: String): String = try {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
    } catch (_: Exception) { pkg }

    // Progress updates arrive many times a second
    private val icons = android.util.LruCache<String, Bitmap>(24)

    private fun appIcon(pkg: String): Bitmap? = icons.get(pkg) ?: try {
        bitmap(packageManager.getApplicationIcon(pkg)).also { icons.put(pkg, it) }
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

        private val LATER = setOf("timer", "pray", "once")

        // Translation result into the island
        fun translated(src: String, out: String, pair: String): Boolean {
            val l = instance ?: return false
            l.ensureIsland()
            val t = l.translateScene ?: return false
            t.show(src, out, pair)
            return true
        }

        // Shelf saved: tell through the island
        fun shelved(msg: String): Boolean {
            val l = instance ?: return false
            if (l.island == null) return false
            l.alert(Alert("shelf", msg, "", Glyph.Check, ms = 2500))
            return true
        }
        var connected = false
            private set

        fun hasAccess(context: Context): Boolean {
            val me = ComponentName(context, Listener::class.java)
            if (Build.VERSION.SDK_INT >= 27) return context.getSystemService(NotificationManager::class.java).isNotificationListenerAccessGranted(me)
            // Android 8.0: read the setting
            val list = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners").orEmpty()
            return list.split(':').any { ComponentName.unflattenFromString(it) == me }
        }
    }
}
