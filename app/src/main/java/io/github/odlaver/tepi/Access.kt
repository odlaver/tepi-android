package io.github.odlaver.tepi

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.widget.Toast

// Window above the status bar; hears only bank app launches
class Access : AccessibilityService() {
    override fun onServiceConnected() {
        instance = this
        Prefs(this).set(PAUSED, false)
        // Events from bank apps only, window changes only
        serviceInfo = serviceInfo.apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
            packageNames = BANKS
        }
        Listener.instance?.rehost()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val pkg = event?.packageName?.toString() ?: return
        if (pkg in BANKS) stepAside(pkg)
    }

    // Banks refuse to run beside accessibility
    fun stepAside(pkg: String?) {
        Prefs(this).set(PAUSED, true)
        val name = pkg?.let { try { packageManager.getApplicationLabel(packageManager.getApplicationInfo(it, 0)).toString() } catch (_: Exception) { null } }
        Toast.makeText(this, if (name != null) getString(R.string.bank_stepped, name) else getString(R.string.bank_stepped_any), Toast.LENGTH_LONG).show()
        disableSelf()
    }

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: Intent?): Boolean {
        gone()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        gone()
        super.onDestroy()
    }

    private fun gone() {
        if (instance !== this) return
        instance = null
        Listener.instance?.rehost()
    }

    companion object {
        var instance: Access? = null
            private set

        const val PAUSED = "bank.paused"

        // Indonesian banking apps known to block accessibility
        val BANKS = arrayOf(
            "com.bca", "com.bcadigital.blu", "id.bni.wondr", "src.com.bni", "id.co.bri.brimo",
            "id.bmri.livin", "com.jago.digitalBanking", "id.co.cimbniaga.mobile.android", "com.btpn.dc",
        )

        fun paused(c: Context) = instance == null && Prefs(c).bool(PAUSED, false)

        // Tepi's own switch when the system offers it
        fun openSettings(c: Context) {
            val me = ComponentName(c, Access::class.java).flattenToString()
            val direct = Intent("android.settings.ACCESSIBILITY_DETAILS_SETTINGS").putExtra(Intent.EXTRA_COMPONENT_NAME, me)
            for (i in listOf(direct, Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))) {
                try { c.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); return } catch (_: Exception) { }
            }
        }
    }
}
