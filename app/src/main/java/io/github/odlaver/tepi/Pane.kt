package io.github.odlaver.tepi

import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import org.json.JSONObject

// Settings pages matching Tepi on Windows
class Pane : Page() {
    private val mode get() = intent.getStringExtra(MODE)

    override fun build() {
        when (mode) {
            GENERAL -> general()
            LOOK -> look()
            CALIBRATE -> calibrate()
            A11Y -> a11y()
            else -> about()
        }
    }

    override fun onResume() {
        super.onResume()
        if (mode == CALIBRATE) Listener.instance?.calibrate(true)
    }

    override fun onPause() {
        if (mode == CALIBRATE) Listener.instance?.calibrate(false)
        super.onPause()
    }

    private fun general() {
        header(getString(R.string.section_general))
        val g = group()
        row(g, getString(R.string.language), getString(R.string.language_sub), icon = Icon.Globe) {
            val i = if (Build.VERSION.SDK_INT >= 33) Intent(Settings.ACTION_APP_LOCALE_SETTINGS, Uri.parse("package:$packageName"))
            else Intent(Settings.ACTION_LOCALE_SETTINGS)
            try { startActivity(i) } catch (_: Exception) { startActivity(Intent(Settings.ACTION_LOCALE_SETTINGS)) }
        }
        row(g, getString(R.string.tour), getString(R.string.tour_sub), icon = Icon.Spark) { startActivity(Intent(this, Tour::class.java)) }
        toggle(g, getString(R.string.update_notify), getString(R.string.update_notify_sub), UpdateCheck.on(prefs), icon = Icon.Bell) {
            prefs.set("update.notify", it)
            if (it && !UpdateCheck.canNotify(this) && Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 43)
        }
        row(g, getString(R.string.version), BuildConfig.VERSION_NAME, button(getString(R.string.update_check), false) { checkUpdate() }, Icon.Download)
        label(getString(R.string.backup))
        val b = group()
        row(b, getString(R.string.export_prefs), getString(R.string.export_prefs_sub), icon = Icon.Share) {
            val i = Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("application/json")
                .putExtra(Intent.EXTRA_TITLE, "tepi-pengaturan.json")
            try { startActivityForResult(i, EXPORT) } catch (_: Exception) { }
        }
        row(b, getString(R.string.import_prefs), getString(R.string.import_prefs_sub), icon = Icon.Download) {
            val i = Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
            try { startActivityForResult(i, IMPORT) } catch (_: Exception) { }
        }
    }

    @Deprecated("Activity result")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data ?: return
        if (resultCode != RESULT_OK) return
        when (requestCode) {
            EXPORT -> work({ exportTo(uri) }) { ok -> toast(getString(if (ok) R.string.export_done else R.string.import_bad)) }
            IMPORT -> work({ importFrom(uri) }) { ok -> toast(getString(if (ok) R.string.import_done else R.string.import_bad)); if (ok) changed() }
        }
    }

    // Never: pairing, tokens, sessions
    private fun shareable(key: String) = !key.startsWith("pc.") && !key.contains("token") && !key.contains("session") && !key.startsWith("kulia")

    private fun exportTo(uri: Uri): Boolean = try {
        val o = JSONObject().put("tepi", 1)
        val v = JSONObject()
        for ((k, x) in prefs.sp.all) if (shareable(k) && x != null && x !is Set<*>) v.put(k, x)
        o.put("prefs", v)
        contentResolver.openOutputStream(uri, "wt")?.use { it.write(o.toString(2).toByteArray()) }
        true
    } catch (_: Exception) { false }

    private fun importFrom(uri: Uri): Boolean = try {
        val text = contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() } ?: ""
        val o = JSONObject(text)
        if (o.optInt("tepi") != 1) false else {
            val v = o.getJSONObject("prefs")
            val e = prefs.sp.edit()
            for (k in v.keys()) {
                if (!shareable(k)) continue
                when (val x = v.get(k)) {
                    is Boolean -> e.putBoolean(k, x)
                    is Int -> e.putInt(k, x)
                    is Long -> e.putLong(k, x)
                    is Double -> e.putFloat(k, x.toFloat())
                    is String -> e.putString(k, x)
                }
            }
            e.apply()
            true
        }
    } catch (_: Exception) { false }

    private fun look() {
        header(getString(R.string.section_look))
        val i = group()
        choice(i, getString(R.string.idle_shape), listOf(
            "pill" to getString(R.string.idle_pill), "ring" to getString(R.string.idle_ring), "hide" to getString(R.string.idle_hide),
        ), prefs.idleShape, Icon.Camera) { prefs.idleShape = it; changed() }
        if (prefs.idleShape == "pill") toggle(i, getString(R.string.idle_eyes), getString(R.string.idle_eyes_sub), prefs.idleEyes, icon = Icon.Eye) { prefs.idleEyes = it; changed() }

        label(getString(R.string.look_island))
        val g = group()
        if (prefs.tint != "custom") choice(g, getString(R.string.theme), listOf(
            "dark" to getString(R.string.theme_dark), "light" to getString(R.string.theme_light), "auto" to getString(R.string.theme_auto),
        ), prefs.theme, Icon.Moon) { prefs.theme = it; looked() }
        choice(g, getString(R.string.island_color), listOf(
            "auto" to getString(R.string.island_color_auto), "custom" to getString(R.string.custom_color),
        ), prefs.tint, Icon.Drop) {
            if (it == "custom") pickColor(getString(R.string.island_color), prefs.tintColor, ISLAND) { c -> prefs.tintColor = c; prefs.tint = "custom"; looked() }
            else { prefs.tint = "auto"; looked() }
        }
        choice(g, getString(R.string.material), listOf(
            "solid" to getString(R.string.material_solid), "liquid" to getString(R.string.material_liquid), "frost" to getString(R.string.material_frost),
        ), prefs.material, Icon.Layers) {
            // Glass needs to let some light through
            if (it == "liquid" && prefs.material != "liquid" && prefs.opacity == 100) prefs.opacity = 80
            prefs.material = it; looked()
        }
        slider(g, getString(R.string.opacity), 60, 100, prefs.opacity, { "$it%" }, Icon.Drop) { prefs.opacity = it; looked() }
        choice(g, getString(R.string.accent), listOf(
            "mono" to getString(R.string.accent_mono), "cover" to getString(R.string.accent_cover), "green" to getString(R.string.accent_green),
            "blue" to getString(R.string.accent_blue), "orange" to getString(R.string.accent_orange), "pink" to getString(R.string.accent_pink),
            "custom" to getString(R.string.custom_color),
        ), prefs.accent, Icon.Music) {
            if (it == "custom") pickColor(getString(R.string.accent), prefs.accentColor, ACCENT) { c -> prefs.accentColor = c; prefs.accent = "custom"; looked() }
            else { prefs.accent = it; looked() }
        }
        if (prefs.material == "solid") toggle(g, getString(R.string.outline), getString(R.string.outline_sub), prefs.outline, icon = Icon.Eye) { prefs.outline = it; looked() }
        note(getString(R.string.opacity_note))

        label(getString(R.string.look_panel))
        val p = group()
        choice(p, getString(R.string.panel_style), listOf(
            "attached" to getString(R.string.panel_attached), "floating" to getString(R.string.panel_floating),
        ), prefs.panelStyle, Icon.Layers) { prefs.panelStyle = it; looked() }
        slider(p, getString(R.string.panel_width), 300, 420, prefs.widthDp, { "$it dp" }, Icon.Chevron) { prefs.widthDp = it; looked() }
        slider(p, getString(R.string.panel_radius), 12, 36, prefs.radiusDp, { "$it dp" }, Icon.Layers) { prefs.radiusDp = it; looked() }
        choice(p, getString(R.string.close_after), listOf(5, 9, 15, 30).map { it to getString(R.string.t_sec_n, it) }, prefs.closeSec, Icon.Timer) { prefs.closeSec = it }

        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(button(getString(R.string.test), icon = Icon.Play) { Listener.instance?.test() })
        row.addView(button(getString(R.string.look_reset), false) {
            val e = prefs.sp.edit()
            for (k in prefs.sp.all.keys) if (k.startsWith("look.")) e.remove(k)
            e.apply()
            changed(); toast(getString(R.string.look_reset_done)); rebuild()
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginStart = dpi(8f) })
        addButton(row)
    }

    // Hue, saturation, brightness; live on the island, Cancel restores
    private fun pickColor(title: String, start: Int, presets: IntArray, apply: (Int) -> Unit) {
        val hsv = FloatArray(3).also { android.graphics.Color.colorToHSV(start, it) }
        val now = { android.graphics.Color.HSVToColor(hsv) }
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dpi(20f), dpi(8f), dpi(20f), 0) }
        val swatch = android.view.View(this)
        box.addView(swatch, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dpi(48f)))
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        box.addView(row, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dpi(44f)).apply { topMargin = dpi(12f) })
        val hue = colorBar(getString(R.string.pick_hue), 360)
        val sat = colorBar(getString(R.string.pick_sat), 100)
        val lit = colorBar(getString(R.string.pick_light), 100)
        for ((t, b) in listOf(R.string.pick_hue to hue, R.string.pick_sat to sat, R.string.pick_light to lit)) {
            box.addView(text(getString(t), 12f, sub).apply { setPadding(0, dpi(12f), 0, dpi(2f)) })
            box.addView(b, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dpi(36f)))
        }
        // Tracks show where each bar leads
        fun paint(fromBars: Boolean) {
            val c = now()
            swatch.background = android.graphics.drawable.GradientDrawable().apply { setColor(c); cornerRadius = dp(14f); setStroke(dpi(1f), withAlpha(ink, 40)) }
            swatch.contentDescription = String.format("#%06X", c and 0xFFFFFF)
            val full = android.graphics.Color.HSVToColor(floatArrayOf(hsv[0], 1f, 1f))
            sat.progressDrawable = track(android.graphics.Color.HSVToColor(floatArrayOf(hsv[0], 0f, hsv[2])), android.graphics.Color.HSVToColor(floatArrayOf(hsv[0], 1f, hsv[2])))
            lit.progressDrawable = track(android.graphics.Color.BLACK, android.graphics.Color.HSVToColor(floatArrayOf(hsv[0], hsv[1], 1f)))
            if (!fromBars) { hue.progress = hsv[0].toInt(); sat.progress = (hsv[1] * 100).toInt(); lit.progress = (hsv[2] * 100).toInt() }
            hue.thumbTintList = android.content.res.ColorStateList.valueOf(full)
            apply(c)
        }
        hue.progressDrawable = track(*IntArray(7) { android.graphics.Color.HSVToColor(floatArrayOf(it * 60f, 1f, 1f)) })
        val moved = object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: android.widget.SeekBar, p: Int, fromUser: Boolean) {
                if (!fromUser) return
                when (s) { hue -> hsv[0] = p.toFloat(); sat -> hsv[1] = p / 100f; else -> hsv[2] = p / 100f }
                paint(true)
            }
            override fun onStartTrackingTouch(s: android.widget.SeekBar) = Unit
            override fun onStopTrackingTouch(s: android.widget.SeekBar) = Unit
        }
        for (b in arrayOf(hue, sat, lit)) b.setOnSeekBarChangeListener(moved)
        // Full-height cell for the tap, small dot inside
        for (c in presets) row.addView(android.widget.FrameLayout(this).apply {
            contentDescription = String.format("#%06X", c and 0xFFFFFF)
            isFocusable = true
            background = ripple(null)
            setOnClickListener { android.graphics.Color.colorToHSV(c, hsv); paint(false) }
            addView(android.view.View(context).apply {
                background = android.graphics.drawable.GradientDrawable().apply { shape = android.graphics.drawable.GradientDrawable.OVAL; setColor(c); setStroke(dpi(1f), withAlpha(ink, 50)) }
            }, android.widget.FrameLayout.LayoutParams(dpi(28f), dpi(28f), android.view.Gravity.CENTER))
        }, LinearLayout.LayoutParams(0, dpi(44f), 1f))
        paint(false)
        AlertDialog.Builder(this).setTitle(title).setView(box)
            .setPositiveButton(R.string.pick_done) { _, _ -> rebuild() }
            .setNegativeButton(R.string.cancel) { _, _ -> apply(start); rebuild() }
            .setOnCancelListener { apply(start); rebuild() }
            .show()
    }

    private fun colorBar(name: String, top: Int) = android.widget.SeekBar(this).apply {
        max = top
        contentDescription = name
        splitTrack = false
        setPadding(dpi(12f), 0, dpi(12f), 0)
    }

    private fun track(vararg colors: Int) = android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.LEFT_RIGHT, colors).apply {
        cornerRadius = dp(6f)
        setSize(0, dpi(12f))
    }

    private fun calibrate() {
        header(getString(R.string.calibrate))
        val lens = hasCutout()
        note(getString(if (lens) R.string.calibrate_note else R.string.calibrate_none))
        val g = group()
        val half = { v: Int -> (if (v > 0) "+" else "") + (if (v % 2 == 0) "${v / 2}" else "${v / 2.0}") + " dp" }
        // Without a lens the island stays centred
        if (lens) slider(g, getString(R.string.cal_x), -Prefs.CAL_X, Prefs.CAL_X, prefs.calX, half, Icon.Chevron) { prefs.calX = it; changed() }
        slider(g, getString(R.string.cal_y), Prefs.CAL_Y_MIN, Prefs.CAL_Y_MAX, prefs.calY, half, Icon.Pin) { prefs.calY = it; changed() }
        if (lens) slider(g, getString(R.string.cal_size), Prefs.CAL_SIZE_MIN, Prefs.CAL_SIZE_MAX, prefs.calSize, half, Icon.Camera) { prefs.calSize = it; changed() }
        addButton(button(getString(R.string.cal_reset), false) {
            prefs.calX = 0; prefs.calY = 0; prefs.calSize = 0; prefs.offsetDp = 0
            changed(); rebuild()
        }, 12f)
    }

    @Suppress("DEPRECATION")
    private fun hasCutout(): Boolean = when {
        Build.VERSION.SDK_INT >= 29 -> windowManager.defaultDisplay.cutout?.boundingRectTop?.isEmpty == false
        Build.VERSION.SDK_INT >= 28 -> window.decorView.rootWindowInsets?.displayCutout != null
        else -> false
    }

    private fun a11y() {
        header(getString(R.string.section_a11y))
        val g = group()
        choice(g, getString(R.string.motion), listOf(
            "spring" to getString(R.string.motion_spring), "smooth" to getString(R.string.motion_smooth), "min" to getString(R.string.motion_min),
        ), prefs.motion, Icon.Spark) { prefs.motion = it; changed() }
        note(getString(R.string.motion_note))
        addButton(button(getString(R.string.test), icon = Icon.Play) { Listener.instance?.test() }, 12f)
    }

    private fun about() {
        header(getString(R.string.section_about))
        val g = group()
        row(g, getString(R.string.version), BuildConfig.VERSION_NAME, button(getString(R.string.check_update), false) { checkUpdate() }, Icon.Info)
        row(g, getString(R.string.whats_new), getString(R.string.whats_new_sub, BuildConfig.VERSION_NAME), icon = Icon.Spark) { whatsNew() }
        row(g, getString(R.string.tour), getString(R.string.tour_sub), icon = Icon.Play) { startActivity(Intent(this, Tour::class.java)) }
        row(g, getString(R.string.reads), getString(R.string.reads_sub), icon = Icon.Shield) { startActivity(Intent(this, Reads::class.java)) }
        row(g, getString(R.string.feedback), getString(R.string.feedback_sub), icon = Icon.Chat) { feedback() }
        row(g, getString(R.string.coffee), getString(R.string.coffee_sub), icon = Icon.Coffee) { web(MainActivity.COFFEE) }
        row(g, getString(R.string.source), "github.com/odlaver/tepi-android", icon = Icon.Code) { web("https://github.com/odlaver/tepi-android") }

        // Links as on the Tepi site
        label(getString(R.string.maker))
        val m = group()
        row(m, getString(R.string.publisher_name), getString(R.string.maker_role), icon = Icon.User)
        row(m, "GitHub", "@odlaver · " + getString(R.string.github_sub), button(getString(R.string.open_profile), false) { web("https://github.com/odlaver") }, Icon.Code)
        row(m, "Instagram", "@odlavr", button(getString(R.string.open_profile), false) { web("https://www.instagram.com/odlavr") }, Icon.User)
        row(m, "LinkedIn", "revalaja", button(getString(R.string.open_profile), false) { web("https://www.linkedin.com/in/revalaja") }, Icon.User)
    }

    private fun web(u: String) = try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(u))) } catch (_: Exception) { }

    private fun checkUpdate() = work({ Update.latest() }) { rel ->
        when {
            rel == null -> toast(getString(R.string.update_none))
            Update.newer(rel.version, BuildConfig.VERSION_NAME) -> AlertDialog.Builder(this)
                .setTitle(getString(R.string.update_found, rel.version))
                .setMessage(R.string.update_sub)
                .setPositiveButton(R.string.download) { _, _ -> web(rel.apk) }
                .setNegativeButton(R.string.cancel, null).show()
            else -> toast(getString(R.string.update_latest))
        }
    }

    private fun whatsNew() {
        val items = resources.getStringArray(R.array.whats_new)
        AlertDialog.Builder(this).setTitle(getString(R.string.whats_new_sub, BuildConfig.VERSION_NAME))
            .setItems(items, null).setPositiveButton(android.R.string.ok, null).show()
    }

    private fun feedback() {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dpi(20f), dpi(8f), dpi(20f), 0) }
        val kinds = RadioGroup(this).apply { orientation = RadioGroup.VERTICAL }
        val labels = listOf(R.string.fb_idea, R.string.fb_problem, R.string.fb_other)
        val ids = labels.map { android.view.View.generateViewId() }
        labels.forEachIndexed { i, s -> kinds.addView(RadioButton(this).apply { id = ids[i]; text = getString(s); setTextColor(ink); minHeight = dpi(44f) }) }
        kinds.check(ids[0])
        val msg = field(getString(R.string.fb_text_hint)).apply {
            setSingleLine(false); minLines = 4; gravity = android.view.Gravity.TOP
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE or android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        }
        val contact = field(getString(R.string.fb_contact_hint))
        val sys = CheckBox(this).apply { text = getString(R.string.fb_system); isChecked = true; setTextColor(ink); minHeight = dpi(44f) }
        val lp = { LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dpi(8f) } }
        box.addView(kinds); box.addView(msg, lp()); box.addView(contact, lp()); box.addView(sys, lp())
        val d = AlertDialog.Builder(this).setTitle(R.string.feedback).setView(box)
            .setPositiveButton(R.string.fb_send, null).setNegativeButton(R.string.cancel, null).create()
        d.setOnShowListener {
            d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val t = msg.text.toString().trim()
                if (t.isEmpty()) { msg.requestFocus(); return@setOnClickListener }
                val k = FORM_KINDS[ids.indexOf(kinds.checkedRadioButtonId).coerceIn(0, 2)]
                val c = contact.text.toString().trim()
                val withSys = sys.isChecked
                it.isEnabled = false
                work({ Feedback.send(k, t, c, withSys) }) { code ->
                    it.isEnabled = true
                    when (code) {
                        0 -> { toast(getString(R.string.fb_sent)); d.dismiss() }
                        -1 -> toast(getString(R.string.fb_failed))
                        else -> toast(getString(R.string.fb_rejected, code))
                    }
                }
            }
        }
        d.show()
    }

    companion object {
        const val MODE = "mode"
        const val GENERAL = "general"
        const val LOOK = "look"
        const val CALIBRATE = "calibrate"
        const val A11Y = "a11y"
        const val ABOUT = "about"
        // Island bodies: near-black, deep tones, two light ones
        private val ISLAND = intArrayOf(
            0xFF000000.toInt(), 0xFF1C1C1E.toInt(), 0xFF101830.toInt(), 0xFF0E281E.toInt(),
            0xFF3C101C.toInt(), 0xFF2A1A40.toInt(), 0xFFF8F8FA.toInt(), 0xFFECE4D6.toInt(),
        )
        private val ACCENT = intArrayOf(
            0xFF30D158.toInt(), 0xFF64AAFF.toInt(), 0xFFFFAA3C.toInt(), 0xFFFF78AA.toInt(),
            0xFFBF5AF2.toInt(), 0xFF40C8E0.toInt(), 0xFFFFD60A.toInt(), 0xFFFF453A.toInt(),
        )
        private const val EXPORT = 41
        private const val IMPORT = 42

        // Same answers as the Windows form
        private val FORM_KINDS = arrayOf("Saran fitur", "Ada masalah", "Lainnya")
    }
}
