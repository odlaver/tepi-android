package io.github.odlaver.tepi

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.Build
import android.provider.Settings
import io.github.d4viddf.hyperisland_kit.HyperAction
import io.github.d4viddf.hyperisland_kit.HyperIslandNotification
import io.github.d4viddf.hyperisland_kit.HyperPicture
import io.github.d4viddf.hyperisland_kit.models.ImageTextInfoLeft
import io.github.d4viddf.hyperisland_kit.models.ImageTextInfoRight
import io.github.d4viddf.hyperisland_kit.models.PicInfo
import io.github.d4viddf.hyperisland_kit.models.TextInfo
import io.github.d4viddf.hyperisland_kit.models.TimerInfo
import io.github.odlaver.tepi.scene.Message

// HyperOS Super Island through focus notifications
object Native {
    private const val CHANNEL = "island"
    const val TIMER_ID = 7001
    const val ALERT_ID = 7002
    const val MESSAGE_ID = 7003
    const val ASK_ID = 7004
    const val LYRIC_ID = 7005

    // Experiment: current lyric line as its own island
    fun lyric(c: Context, track: Track, line: String) {
        val b = HyperIslandNotification.Builder(c, "tepi_lyric", line)
            .addPicture(HyperPicture("art", track.art ?: glyph(Icon.Music)))
            .addPicture(HyperPicture("blank", clear()))
            .setShowNotification(false)
            .setEnableFloat(false)
            .setIslandConfig(priority = 1, dismissible = true)
            .setBaseInfo(title = line, content = listOf(track.title, track.artist).filter { it.isNotEmpty() }.joinToString(" · "), pictureKey = "art")
            .setBigIslandInfo(
                left = ImageTextInfoLeft(1, PicInfo(1, "art"), TextInfo("", "")),
                right = ImageTextInfoRight(1, PicInfo(1, "blank"), TextInfo(line, null)),
            )
            .setSmallIsland("art")
        post(c, LYRIC_ID, b, line, track.title, true, null)
    }
    private const val PROGRESS_BASE = 7100

    fun progressId(key: String) = PROGRESS_BASE + (key.hashCode() and 0x3f)

    private fun hop(c: Context, what: String, req: Int): PendingIntent =
        PendingIntent.getActivity(c, req, Intent(c, Open::class.java).putExtra(Open.EXTRA, what).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    // Island needs to know how to fire it
    private fun kind(p: PendingIntent) = when {
        Build.VERSION.SDK_INT < 31 -> 2
        p.isActivity -> 1
        p.isService -> 3
        else -> 2
    }

    private fun clear(): Bitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)

    // Avatar left, name and text right; Reply opens Tepi's input
    fun message(c: Context, m: Message) {
        val avatar = m.icon ?: glyph(Icon.Chat)
        val b = HyperIslandNotification.Builder(c, "tepi_msg", m.title)
            .addPicture(HyperPicture("who", avatar))
            .addPicture(HyperPicture("blank", clear()))
            .setEnableFloat(true)
            .setIslandFirstFloat(true)
            .setShowNotification(false)
            .setReopen(true)
            .setIslandConfig(timeout = 8000, dismissible = true, expandedTimeMs = 5000)
            .setBaseInfo(type = 2, title = m.title, content = m.text.ifEmpty { m.app })
            .setIconTextInfo(picKey = "who", title = m.title, content = m.text.ifEmpty { m.app })
            .setBigIslandInfo(
                left = ImageTextInfoLeft(1, PicInfo(1, "who"), TextInfo("", "")),
                right = ImageTextInfoRight(1, PicInfo(1, "blank"), TextInfo(m.title, m.text)),
            )
            .setSmallIsland("who")
        val acts = ArrayList<HyperAction>()
        if (m.reply != null) acts.add(HyperAction("reply", c.getString(R.string.reply), hop(c, "reply:" + m.key, m.key.hashCode()), 1, titleColor = "#FFFFFF"))
        for ((i, a) in m.actions.take(2).withIndex()) acts.add(HyperAction("act$i", a.title, a.actionIntent, kind(a.actionIntent), titleColor = "#FFFFFF"))
        if (acts.isNotEmpty()) {
            b.setTextButtons(*acts.toTypedArray())
            acts.forEach { b.addHiddenAction(it) }
        }
        post(c, MESSAGE_ID, b, m.title, m.text, false, m.intent)
    }

    // Download or upload ring
    fun progress(c: Context, it: LiveItem) {
        val pct = if (it.max > 0) (it.progress * 100 / it.max).coerceIn(0, 100) else 0
        val b = HyperIslandNotification.Builder(c, "tepi_progress", it.title)
            .addPicture(HyperPicture("app", it.icon ?: glyph(Icon.Download)))
            .setIslandConfig(priority = 1, dismissible = true)
            .setShowNotification(false)
            .setBaseInfo(title = it.title, content = if (it.indeterminate) it.text.ifEmpty { " " } else "$pct%", pictureKey = "app")
            .setProgressBar(pct)
            .setBigIslandProgressCircle("app", "", pct)
            .setSmallIslandCircularProgress("app", pct)
        post(c, progressId(it.key), b, it.title, "$pct%", true, it.intent)
    }

    // Permission from an agent on the laptop
    fun ask(c: Context, ask: AgentAsk) {
        val title = c.getString(R.string.agent_asks, ask.agent, ask.tool)
        val body = ask.key.ifEmpty { ask.detail }.take(300)
        val deny = PendingIntent.getBroadcast(c, ask.id, Intent(c, Answer::class.java).putExtra(Answer.ID, ask.id).putExtra(Answer.WHAT, "deny"),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val allow = PendingIntent.getActivity(c, ask.id, Intent(c, Confirm::class.java).putExtra(Confirm.ID, ask.id).putExtra(Confirm.WHAT, ask.key)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val b = HyperIslandNotification.Builder(c, "tepi_agent", title)
            .addPicture(HyperPicture("pc", glyph(Icon.Laptop)))
            .setEnableFloat(true)
            .setIslandFirstFloat(true)
            .setIslandConfig(priority = 3, dismissible = false, expandedTimeMs = 15000)
            .setBaseInfo(title = title, content = body, subTitle = ask.project, pictureKey = "pc")
            .setBigIslandInfo(left = ImageTextInfoLeft(1, PicInfo(1, "pc"), TextInfo(ask.agent, ask.tool)))
            .setSmallIsland("pc")
        val acts = ArrayList<HyperAction>()
        acts.add(HyperAction("deny", c.getString(R.string.deny), deny, 2, titleColor = "#FFFFFF"))
        if (!ask.truncated) acts.add(HyperAction("allow", c.getString(R.string.allow), allow, 1, bgColor = "#FFFFFF", titleColor = "#000000"))
        b.setTextButtons(*acts.toTypedArray())
        acts.forEach { b.addHiddenAction(it) }
        post(c, ASK_ID, b, title, body, true, null)
    }

    // Phone has the system island at all
    @SuppressLint("PrivateApi")
    fun hasIsland(): Boolean {
        if (!Build.MANUFACTURER.equals("Xiaomi", true)) return false
        return try {
            Class.forName("android.os.SystemProperties").getDeclaredMethod("getBoolean", String::class.java, Boolean::class.java)
                .invoke(null, "persist.sys.feature.island", false) as Boolean
        } catch (_: Exception) { false }
    }

    fun protocol(c: Context) = try { Settings.System.getInt(c.contentResolver, "notification_focus_protocol", 0) } catch (_: Exception) { 0 }

    fun canPost(c: Context) = c.getSystemService(NotificationManager::class.java).areNotificationsEnabled()

    // "Featured notifications" switch for Tepi
    fun featured(c: Context) = HyperIslandNotification.isSupported(c)

    // Checked often while drawing; cache a few seconds
    private var readyAt = 0L
    private var readyVal = false

    fun ready(c: Context): Boolean {
        val now = android.os.SystemClock.uptimeMillis()
        if (now - readyAt > 5000) { readyVal = hasIsland() && protocol(c) >= 3 && canPost(c) && featured(c); readyAt = now }
        return readyVal
    }

    fun on(c: Context, prefs: Prefs) = prefs.bool("engine.native", true) && ready(c)

    private fun channel(c: Context) {
        val nm = c.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) != null) return
        nm.createNotificationChannel(NotificationChannel(CHANNEL, c.getString(R.string.channel_island), NotificationManager.IMPORTANCE_HIGH).apply {
            setSound(null, null)
            enableVibration(false)
            setShowBadge(false)
        })
    }

    // White glyph on transparent, for island pictures
    private fun glyph(icon: Icon, color: Int = Color.WHITE): Bitmap {
        val s = 96
        val b = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
        Icons.draw(Canvas(b), icon, s / 2f, s / 2f, s * 0.8f, color)
        return b
    }

    private fun open(c: Context): PendingIntent =
        PendingIntent.getActivity(c, 0, Intent(c, Open::class.java).putExtra(Open.EXTRA, "timer").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE)

    private fun post(c: Context, id: Int, island: HyperIslandNotification, title: String, text: String, ongoing: Boolean, tap: PendingIntent? = null) {
        channel(c)
        val n = Notification.Builder(c, CHANNEL)
            .setSmallIcon(R.drawable.ic_mark)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(ongoing)
            .setOnlyAlertOnce(true)
            .setContentIntent(tap ?: open(c))
            .setCategory(if (ongoing) Notification.CATEGORY_STOPWATCH else Notification.CATEGORY_REMINDER)
            .addExtras(island.buildResourceBundle())
            .build()
        n.extras.putString("miui.focus.param", island.buildJsonParam())
        try { c.getSystemService(NotificationManager::class.java).notify(id, n) } catch (_: SecurityException) { }
    }

    fun cancel(c: Context, id: Int) = c.getSystemService(NotificationManager::class.java).cancel(id)

    // Running timer: countdown or count-up in the big island
    fun timer(c: Context, t: Timers) {
        val k = t.kind ?: return cancel(c, TIMER_ID)
        val label = c.getString(when (k) {
            Timers.Kind.Focus -> R.string.t_focus
            Timers.Kind.Break -> R.string.t_break
            Timers.Kind.Countdown -> R.string.t_timer
            Timers.Kind.Stopwatch -> R.string.t_stopwatch
        })
        val now = System.currentTimeMillis()
        val b = HyperIslandNotification.Builder(c, "tepi_timer", label)
            .addPicture(HyperPicture("timer", glyph(Icon.Timer)))
            .setIslandConfig(priority = 2, dismissible = false)
            .setSmallIsland("timer")
        if (t.paused) {
            val shown = c.getString(R.string.t_paused) + " · " + Ui(c).clock(t.shownMs())
            b.setBaseInfo(title = label, content = shown, pictureKey = "timer")
            post(c, TIMER_ID, b, label, shown, true)
            return
        }
        if (k == Timers.Kind.Stopwatch) {
            val start = now - t.elapsed()
            b.setHighlightInfo(title = label, picKey = "timer", timer = TimerInfo(1, start, start, now))
            b.setBigIslandCountUp(start, "timer")
        } else {
            val end = now + t.remaining()
            b.setHighlightInfo(title = label, picKey = "timer", timer = TimerInfo(-1, end, t.totalMs, now))
            b.setBigIslandCountdown(end, "timer")
        }
        post(c, TIMER_ID, b, label, "", true)
    }

    // Short card: prayer, class, nudge, timer end
    fun alert(c: Context, title: String, body: String, icon: Icon, ms: Long) {
        val b = HyperIslandNotification.Builder(c, "tepi_alert", title)
            .addPicture(HyperPicture("alert", glyph(icon)))
            .setBaseInfo(title = title, content = body.ifEmpty { " " }, pictureKey = "alert")
            .setIslandConfig(priority = 2, timeout = ms.toInt(), dismissible = true, expandedTimeMs = ms.toInt())
            .setSmallIsland("alert")
            .setEnableFloat(true)
            .setTimeout(ms)
        post(c, ALERT_ID, b, title, body, false)
    }
}
