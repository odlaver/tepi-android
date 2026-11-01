package io.github.odlaver.tepi

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

// Deny tapped on the system island
class Answer : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getIntExtra(ID, -1)
        val what = intent.getStringExtra(WHAT) ?: return
        if (id < 0 || what != "deny") return
        Listener.instance?.pc?.decide(id, what)
    }

    companion object {
        const val ID = "id"
        const val WHAT = "what"
    }
}
