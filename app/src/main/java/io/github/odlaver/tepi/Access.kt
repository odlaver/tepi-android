package io.github.odlaver.tepi

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.accessibility.AccessibilityEvent

// Window above the status bar only; reads nothing
class Access : AccessibilityService() {
    override fun onServiceConnected() {
        instance = this
        Listener.instance?.rehost()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

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
    }
}
