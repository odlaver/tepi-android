package io.github.odlaver.tepi

import android.content.Intent
import android.text.Editable
import android.text.TextWatcher
import android.widget.LinearLayout

// Which apps' notifications Tepi may read
class Apps : Page() {
    private var query = ""
    private var list: LinearLayout? = null

    override fun build() {
        header(getString(R.string.apps))
        note(getString(R.string.apps_sub))
        val search = field(getString(R.string.search), value = query)
        search.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) { query = s.toString(); fill() }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
        })
        col.addView(search, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dpi(12f) })
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(list)
        fill()
    }

    private fun fill() {
        val box = list ?: return
        box.removeAllViews()
        val pm = packageManager
        val seen = AppFilter.seen(prefs)
        val apps = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
            .map { it.activityInfo.packageName to it.loadLabel(pm).toString() }
            .filter { it.first != packageName }
            .distinctBy { it.first }
            .filter { query.isBlank() || it.second.contains(query, true) }
            .sortedBy { it.second.lowercase() }
        val (recent, rest) = apps.partition { it.first in seen }
        for ((title, group) in listOf(R.string.apps_recent to recent, R.string.apps_all to rest)) {
            if (group.isEmpty()) continue
            box.addView(text(getString(title), 12f, sub).apply { setPadding(dpi(4f), dpi(20f), 0, dpi(6f)) })
            val g = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = android.graphics.drawable.GradientDrawable().apply { setColor(fill); cornerRadius = dp(14f) }
            }
            box.addView(g)
            for ((pkg, label) in group) {
                val sw = toggle(g, label, null, AppFilter.allowed(prefs, pkg)) { AppFilter.set(prefs, pkg, it) }
                try {
                    val icon = pm.getApplicationIcon(pkg)
                    icon.setBounds(0, 0, dpi(30f), dpi(30f))
                    sw.setCompoundDrawablesRelative(icon, null, null, null)
                    sw.compoundDrawablePadding = dpi(12f)
                } catch (_: Exception) { }
            }
        }
    }
}

// Blocked set plus apps seen posting notifications
object AppFilter {
    fun allowed(p: Prefs, pkg: String) = pkg !in p.sp.getStringSet("apps.blocked", emptySet())!!

    fun set(p: Prefs, pkg: String, on: Boolean) {
        val s = HashSet(p.sp.getStringSet("apps.blocked", emptySet())!!)
        if (on) s.remove(pkg) else s.add(pkg)
        p.sp.edit().putStringSet("apps.blocked", s).apply()
    }

    fun seen(p: Prefs): Set<String> = p.sp.getStringSet("apps.seen", emptySet())!!

    fun saw(p: Prefs, pkg: String) {
        val s = seen(p)
        if (pkg in s) return
        p.sp.edit().putStringSet("apps.seen", HashSet(s).apply { add(pkg) }).apply()
    }
}
