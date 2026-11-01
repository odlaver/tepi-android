package io.github.odlaver.tepi

import android.app.AlertDialog
import android.os.Build
import android.content.Intent
import android.net.Uri
import android.widget.LinearLayout

// One settings category per page
class Section : Page() {
    private val agenda get() = Listener.instance?.agenda
    private var kuliaEmail = ""
    private var kuliaSent = false

    override fun build() {
        when (intent.getStringExtra(MODE)) {
            ISLAND -> island()
            ACTIVITIES -> activities()
            PRODUCTIVITY -> productivity()
            REMINDERS -> reminders()
            CONNECT -> connect()
            else -> about()
        }
    }

    private fun a11y() = Access.instance != null

    private fun island() {
        header(getString(R.string.section_island))
        val g = group()
        toggle(g, getString(R.string.camera_tap), getString(if (a11y()) R.string.camera_tap_sub else R.string.camera_tap_off), prefs.cameraTap, a11y(), Icon.Camera) {
            prefs.cameraTap = it; changed()
        }
        toggle(g, getString(R.string.lockscreen), getString(R.string.lockscreen_sub), prefs.bool("lockscreen", true), icon = Icon.Shield) {
            prefs.set("lockscreen", it); changed()
        }
        slider(g, getString(R.string.offset), Prefs.OFFSET_MIN, Prefs.OFFSET_MAX, prefs.offsetDp, { (if (it > 0) "+$it" else "$it") + " dp" }, Icon.Pin) {
            prefs.offsetDp = it
            changed()
            Listener.instance?.test()
        }
        label(getString(R.string.gestures))
        val h = group()
        for ((t, d, i) in listOf(
            Triple(R.string.g_tap, R.string.g_tap_sub, Icon.Hand),
            Triple(R.string.g_down, R.string.g_down_sub, Icon.Download),
            Triple(R.string.g_up, R.string.g_up_sub, Icon.Close),
            Triple(R.string.g_hold, R.string.g_hold_sub, Icon.Spark),
            Triple(R.string.g_side, R.string.g_side_sub, Icon.Chevron),
            Triple(R.string.triggers, R.string.triggers_sub, Icon.Info),
        )) row(h, getString(t), getString(d), icon = i)
        addButton(button(getString(R.string.test), icon = Icon.Play) { Listener.instance?.test() }, 12f)
    }

    private fun activities() {
        header(getString(R.string.section_activities))
        val g = group()
        toggle(g, getString(R.string.music), getString(R.string.music_sub), prefs.music, icon = Icon.Music) { prefs.music = it; changed() }
        toggle(g, getString(R.string.lyrics), getString(R.string.lyrics_sub), prefs.lyrics, icon = Icon.Lyrics) { prefs.lyrics = it; changed() }
        toggle(g, getString(R.string.lyrics_online), getString(R.string.lyrics_online_sub), prefs.lyricsOnline, icon = Icon.Globe) { prefs.lyricsOnline = it; changed() }
        label(getString(R.string.messages))
        val m = group()
        row(m, getString(R.string.apps), getString(R.string.apps_row_sub), icon = Icon.Layers) { startActivity(Intent(this, Apps::class.java)) }
        toggle(m, getString(R.string.notifs), getString(R.string.notifs_sub), prefs.notifs, icon = Icon.Chat) { prefs.notifs = it }
        toggle(m, getString(R.string.notif_content), getString(R.string.notif_content_sub), prefs.notifContent, icon = Icon.Eye) { prefs.notifContent = it }
        label(getString(R.string.system))
        val s = group()
        for ((key, title, detail, icon) in listOf(
            Quad("act.charge", R.string.act_charge, R.string.act_charge_sub, Icon.Bolt),
            Quad("act.call", R.string.act_call, R.string.act_call_sub, Icon.Phone),
            Quad("act.progress", R.string.act_progress, R.string.act_progress_sub, Icon.Download),
            Quad("act.nav", R.string.act_nav, R.string.act_nav_sub, Icon.Nav),
            Quad("act.ringer", R.string.act_ringer, R.string.act_ringer_sub, Icon.Bell),
            Quad("act.audio", R.string.act_audio, R.string.act_audio_sub, Icon.Headphones),
            Quad("act.privacy", R.string.act_privacy, R.string.act_privacy_sub, Icon.Shield),
        )) toggle(s, getString(title), getString(detail), prefs.bool(key, true), icon = icon) { prefs.set(key, it); changed() }
    }

    private fun productivity() {
        header(getString(R.string.section_productivity))
        val g = group()
        choice(g, getString(R.string.focus_break), listOf(5, 10, 15, 20).map { it to getString(R.string.t_min_n, it) }, prefs.int("focus.break", 5), Icon.Timer) { prefs.set("focus.break", it) }
        row(g, getString(R.string.notes), getString(R.string.notes_sub), icon = Icon.Note) { startActivity(Intent(this, Items::class.java).putExtra(Items.MODE, Items.NOTES)) }
        row(g, getString(R.string.shelf), getString(R.string.shelf_sub), icon = Icon.Shelf) { startActivity(Intent(this, Items::class.java).putExtra(Items.MODE, Items.SHELF)) }
    }

    private fun reminders() {
        header(getString(R.string.section_reminders))
        val r = group()
        toggle(r, getString(R.string.pray), getString(R.string.pray_sub), prefs.bool("pray", false), icon = Icon.Moon) { prefs.set("pray", it); rebuild() }
        if (prefs.bool("pray", false)) {
            choice(r, getString(R.string.pray_city), Prayer.cities.map { it.name to it.name }, prefs.str("pray.city", ""), Icon.Pin) { prefs.set("pray.city", it) }
            choice(r, getString(R.string.pray_before), listOf(0, 5, 10, 15).map { it to if (it == 0) getString(R.string.off) else getString(R.string.t_min_n, it) },
                prefs.int("pray.before", 10), Icon.Bell) { prefs.set("pray.before", it) }
        }
        val w = group().also { (it.layoutParams as? LinearLayout.LayoutParams)?.topMargin = dpi(12f) }
        toggle(w, getString(R.string.drink), getString(R.string.drink_sub), prefs.bool("drink", false), icon = Icon.Drop) { prefs.set("drink", it); rebuild() }
        if (prefs.bool("drink", false)) {
            choice(w, getString(R.string.every), listOf(30, 45, 60, 90).map { it to getString(R.string.t_min_n, it) }, prefs.int("drink.every", 60), Icon.Timer) { prefs.set("drink.every", it) }
            choice(w, getString(R.string.drink_goal), listOf(6, 8, 10, 12).map { it to getString(R.string.glasses, it) }, prefs.int("drink.goal", 8), Icon.Check) { prefs.set("drink.goal", it) }
        }
        toggle(w, getString(R.string.eyes), getString(R.string.eyes_sub), prefs.bool("eyes", false), icon = Icon.Eye) { prefs.set("eyes", it) }
        toggle(w, getString(R.string.stretch), getString(R.string.stretch_sub), prefs.bool("stretch", false), icon = Icon.Stretch) { prefs.set("stretch", it); rebuild() }
        if (prefs.bool("stretch", false)) {
            choice(w, getString(R.string.every), listOf(30, 60, 90).map { it to getString(R.string.t_min_n, it) }, prefs.int("stretch.every", 60), Icon.Timer) { prefs.set("stretch.every", it) }
        }
        val c = group().also { (it.layoutParams as? LinearLayout.LayoutParams)?.topMargin = dpi(12f) }
        toggle(c, getString(R.string.class_remind), getString(R.string.class_remind_sub), prefs.bool("class.remind", true), icon = Icon.Book) { prefs.set("class.remind", it) }
    }

    private fun connect() {
        header(getString(R.string.section_connect))
        val ag = agenda
        if (ag == null) { note(getString(R.string.need_running)); return }
        note(getString(R.string.connect_intro))

        // Kulia: email code, like kulia.live/masuk
        label("Kulia")
        val k = group()
        if (ag.kulia.signedIn) {
            row(k, ag.kulia.email ?: "Kulia", getString(R.string.kulia_in), button(getString(R.string.sign_out), false) {
                work({ ag.kulia.signOut() }) { ag.refresh(); rebuild() }
            }, Icon.Book)
        } else if (!kuliaSent) {
            row(k, getString(R.string.kulia_title), getString(R.string.kulia_sub), icon = Icon.Book)
            val email = field(getString(R.string.email), value = kuliaEmail)
            email.inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
            form(k, email, button(getString(R.string.send_code)) {
                val addr = email.text.toString().trim()
                if (!addr.contains('@')) return@button
                kuliaEmail = addr
                work({ ag.kulia.sendCode(addr) }) { err -> if (err != null) toast(getString(err)) else { kuliaSent = true; rebuild() } }
            })
        } else {
            row(k, getString(R.string.code_sent, kuliaEmail), null, icon = Icon.Book)
            val code = field(getString(R.string.code))
            code.inputType = android.text.InputType.TYPE_CLASS_NUMBER
            form(k, code, button(getString(R.string.sign_in)) {
                val c = code.text.toString().trim()
                if (c.length < 6) return@button
                work({ ag.kulia.verify(kuliaEmail, c) }) { err -> if (err != null) toast(getString(err)) else { kuliaSent = false; ag.refresh(); rebuild() } }
            })
        }

        // Todoist token
        label("Todoist")
        val t = group()
        if (ag.todoistToken != null) row(t, getString(R.string.connected), getString(R.string.todoist_sub), button(getString(R.string.disconnect), false) {
            ag.vault.put("todoist", null); ag.refresh(); rebuild()
        }, Icon.Tasks)
        else {
            row(t, getString(R.string.todoist_title), getString(R.string.todoist_how), icon = Icon.Tasks)
            val f = field(getString(R.string.token), secret = true)
            form(t, f, button(getString(R.string.save)) {
                val tok = f.text.toString().trim()
                if (tok.isEmpty()) return@button
                work({ Todoist(tok).tasks() != null }) { ok -> if (ok) { ag.vault.put("todoist", tok); ag.refresh(); rebuild() } else toast(getString(R.string.token_bad)) }
            })
        }

        // Notion token and data source
        label("Notion")
        val n = group()
        val ntok = ag.notionToken
        if (ntok != null) {
            val src = prefs.str("notion.source.name", "")
            row(n, if (src.isNotEmpty()) src else getString(R.string.notion_pick), getString(R.string.notion_sub), button(getString(R.string.choose), false) {
                work({ Notion(ntok).sources() }) { list ->
                    if (list.isNullOrEmpty()) { toast(getString(R.string.notion_none)); return@work }
                    AlertDialog.Builder(this).setTitle(R.string.notion_pick).setItems(list.map { it.second }.toTypedArray()) { _, i ->
                        prefs.set("notion.source", list[i].first); prefs.set("notion.source.name", list[i].second); ag.refresh(); rebuild()
                    }.show()
                }
            }, Icon.Note)
            row(n, getString(R.string.disconnect), null, icon = Icon.Close) {
                ag.vault.put("notion", null); prefs.set("notion.source", ""); prefs.set("notion.source.name", ""); ag.refresh(); rebuild()
            }
        } else {
            row(n, getString(R.string.notion_title), getString(R.string.notion_how), icon = Icon.Note)
            val f = field(getString(R.string.token), secret = true)
            form(n, f, button(getString(R.string.save)) {
                val tok = f.text.toString().trim()
                if (tok.isEmpty()) return@button
                work({ Notion(tok).sources() }) { list -> if (list != null) { ag.vault.put("notion", tok); rebuild() } else toast(getString(R.string.token_bad)) }
            })
        }

        // iCal feeds
        label(getString(R.string.calendar))
        val c = group()
        for (u in ag.icsUrls) row(c, u.substringAfter("://").take(40), null, button(getString(R.string.delete), false) {
            ag.vault.put("ics", ag.icsUrls.filter { it != u }.joinToString("\n")); ag.refresh(); rebuild()
        }, Icon.Globe)
        if (ag.icsUrls.size < 12) {
            val f = field(getString(R.string.ics_hint))
            form(c, f, button(getString(R.string.add)) {
                val u = f.text.toString().trim()
                if (!u.startsWith("https://") && !u.startsWith("webcal://")) { toast(getString(R.string.ics_bad)); return@button }
                work({ Ical.fetch(u) != null }) { ok -> if (ok) { ag.vault.put("ics", (ag.icsUrls + u).joinToString("\n")); ag.refresh(); rebuild() } else toast(getString(R.string.ics_bad)) }
            })
        }
        laptop()
        note(getString(R.string.connect_privacy))
    }

    // Tepi on Windows: approve agents from here
    private fun laptop() {
        val pc = Listener.instance?.pc ?: return
        label(getString(R.string.laptop))
        val g = group()
        if (pc.paired) {
            row(g, getString(R.string.pc_connected, pc.name.ifEmpty { pc.host }), getString(if (pc.online) R.string.pc_online else R.string.pc_offline, pc.host),
                button(getString(R.string.disconnect), false) { pc.forget(); rebuild() }, Icon.Laptop)
            toggle(g, getString(R.string.pc_notifs), getString(R.string.pc_notifs_sub), pc.sendNotifs, icon = Icon.Chat) { pc.sendNotifs = it }
            toggle(g, getString(R.string.pc_otp), getString(R.string.pc_otp_sub), pc.sendOtp, icon = Icon.Shield) { pc.sendOtp = it }
            return
        }
        row(g, getString(R.string.pc_title), getString(R.string.pc_how), icon = Icon.Laptop)
        val host = field(getString(R.string.pc_address))
        val code = field(getString(R.string.pc_code))
        code.inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
        form(g, host, code, button(getString(R.string.pc_pair)) {
            val h = host.text.toString().trim()
            val c = code.text.toString().trim()
            if (!h.contains(':') || c.replace("-", "").length != 12) { toast(getString(R.string.pc_bad)); return@button }
            work({ pc.pair(h, c) }) { ok -> if (ok) rebuild() else toast(getString(R.string.pc_failed)) }
        })
    }

    private fun about() {
        header(getString(R.string.section_about))
        val g = group()
        row(g, getString(R.string.reads), getString(R.string.reads_sub), icon = Icon.Shield) { startActivity(Intent(this, Reads::class.java)) }
        row(g, getString(R.string.publisher), getString(R.string.publisher_name), icon = Icon.User)
        row(g, getString(R.string.version), BuildConfig.VERSION_NAME, button(getString(R.string.check_update), false) {
            work({ Update.latest() }) { rel ->
                when {
                    rel == null -> toast(getString(R.string.update_none))
                    Update.newer(rel.version, BuildConfig.VERSION_NAME) -> AlertDialog.Builder(this)
                        .setTitle(getString(R.string.update_found, rel.version))
                        .setMessage(R.string.update_sub)
                        .setPositiveButton(R.string.download) { _, _ -> startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(rel.apk))) }
                        .setNegativeButton(R.string.cancel, null).show()
                    else -> toast(getString(R.string.update_latest))
                }
            }
        }, Icon.Info)
        row(g, getString(R.string.source), "github.com/odlaver/tepi-android", icon = Icon.Code) {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/odlaver/tepi-android")))
        }
        row(g, getString(R.string.coffee), getString(R.string.coffee_sub), icon = Icon.Coffee) {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(MainActivity.COFFEE)))
        }
    }

    companion object {
        const val MODE = "mode"
        const val ISLAND = "island"
        const val ACTIVITIES = "activities"
        const val PRODUCTIVITY = "productivity"
        const val REMINDERS = "reminders"
        const val CONNECT = "connect"
        const val ABOUT = "about"
    }
}
