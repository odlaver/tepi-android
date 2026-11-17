package io.github.odlaver.tepi

// Apps whose own pop-ups would double Tepi's
class BannerApps : Page() {
    override fun onResume() {
        super.onResume()
        rebuild()
    }

    override fun build() {
        header(getString(R.string.banner_apps))
        val name = Banners.switchName(this)
        note(getString(R.string.banner_apps_intro, name))
        if (!Banners.exact) note(getString(R.string.banner_apps_oem, Oem.name, name))
        val list = Banners.apps(prefs).filter { AppFilter.allowed(prefs, it.pkg) }
        if (list.isEmpty()) { note(getString(R.string.banner_apps_empty)); return }
        val on = list.filter { it.peek }
        val off = list.filter { !it.peek }
        for ((title, apps) in listOf(R.string.banner_still_on to on, R.string.banner_now_off to off)) {
            if (apps.isEmpty()) continue
            label(getString(title))
            val g = group()
            for (app in apps) {
                val detail = getString(if (!Banners.exact) R.string.banner_check else if (app.peek) R.string.banner_on else R.string.banner_off, name)
                row(g, appName(app.pkg), detail, button(getString(R.string.banner_fix), app.peek) { Banners.open(this, app) }, Icon.Bell)
            }
        }
    }

    private fun appName(pkg: String): String = try {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
    } catch (_: Exception) { pkg }
}
