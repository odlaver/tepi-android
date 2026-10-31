package io.github.odlaver.tepi

import android.app.Activity
import android.os.Bundle

// Invisible hop from tile and shortcuts to the island
class Open : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val what = intent?.getStringExtra(EXTRA) ?: intent?.data?.host ?: HOME
        val l = Listener.instance
        if (l == null || !l.command(what)) startActivity(android.content.Intent(this, MainActivity::class.java))
        finish()
        overridePendingTransition(0, 0)
    }

    companion object {
        const val EXTRA = "what"
        const val HOME = "home"
    }
}
