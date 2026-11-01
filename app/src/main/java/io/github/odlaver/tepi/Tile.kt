package io.github.odlaver.tepi

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.content.ComponentName
import android.content.Context
import android.service.quicksettings.Tile as QsTile
import android.service.quicksettings.TileService

// Quick Settings tile: off turns Tepi on, on opens Home
class Tile : TileService() {
    override fun onStartListening() {
        val on = Prefs(this).enabled
        qsTile?.apply {
            state = if (on) QsTile.STATE_ACTIVE else QsTile.STATE_INACTIVE
            updateTile()
        }
    }

    @SuppressLint("StartActivityAndCollapseDeprecated")
    @Suppress("DEPRECATION")
    override fun onClick() {
        val prefs = Prefs(this)
        if (!prefs.enabled) {
            prefs.enabled = true
            Listener.instance?.applyEnabled()
            onStartListening()
            return
        }
        val i = Intent(this, Open::class.java).putExtra(Open.EXTRA, Open.HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= 34) startActivityAndCollapse(PendingIntent.getActivity(this, 0, i, PendingIntent.FLAG_IMMUTABLE))
        else startActivityAndCollapse(i)
    }

    companion object {
        fun refresh(c: Context) {
            try { requestListeningState(c, ComponentName(c, Tile::class.java)) } catch (_: Exception) { }
        }
    }
}
