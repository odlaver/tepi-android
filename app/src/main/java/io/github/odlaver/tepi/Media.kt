package io.github.odlaver.tepi

import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock

class Track(
    val title: String,
    val artist: String,
    val durationMs: Long,
    val art: Bitmap?,
    val pkg: String,
) {
    val key get() = "$pkg|$artist|$title|${durationMs / 1000}"
}

// Follows the session that is playing
class Media(context: Context, private val listener: ComponentName, private val changed: () -> Unit) {
    private val msm = context.getSystemService(MediaSessionManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private val watched = HashMap<MediaController, MediaController.Callback>()
    private var current: MediaController? = null
    private var artFor: MediaMetadata? = null
    private var artCache: Bitmap? = null

    var track: Track? = null
        private set
    var playing = false
        private set

    private val sessions = MediaSessionManager.OnActiveSessionsChangedListener { list -> watch(list ?: emptyList()) }

    fun start() {
        try {
            msm.addOnActiveSessionsChangedListener(sessions, listener, main)
            watch(msm.getActiveSessions(listener))
        } catch (_: SecurityException) {
            // Notification access revoked meanwhile
        }
    }

    fun stop() {
        try { msm.removeOnActiveSessionsChangedListener(sessions) } catch (_: Exception) { }
        for ((c, cb) in watched) c.unregisterCallback(cb)
        watched.clear()
        current = null
        track = null
        playing = false
    }

    private fun watch(list: List<MediaController>) {
        val tokens = list.map { it.sessionToken }.toSet()
        val gone = watched.keys.filter { it.sessionToken !in tokens }
        for (c in gone) c.unregisterCallback(watched.remove(c)!!)
        for (c in list) {
            if (watched.keys.any { it.sessionToken == c.sessionToken }) continue
            val cb = object : MediaController.Callback() {
                override fun onPlaybackStateChanged(state: PlaybackState?) = pick()
                override fun onMetadataChanged(metadata: MediaMetadata?) = pick()
                override fun onSessionDestroyed() = pick()
            }
            c.registerCallback(cb, main)
            watched[c] = cb
        }
        pick()
    }

    // Playing session wins, else the newest
    private fun pick() {
        val all = watched.keys.toList()
        val chosen = all.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
            ?: current?.takeIf { c -> all.any { it.sessionToken == c.sessionToken } }
            ?: all.firstOrNull()
        current = chosen
        val meta = chosen?.metadata
        playing = chosen?.playbackState?.state == PlaybackState.STATE_PLAYING
        track = if (chosen == null || meta == null) null else {
            val title = meta.getString(MediaMetadata.METADATA_KEY_TITLE) ?: meta.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE) ?: ""
            val artist = meta.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: meta.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST) ?: meta.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE) ?: ""
            if (title.isEmpty()) null else Track(title, artist, meta.getLong(MediaMetadata.METADATA_KEY_DURATION), art(meta), chosen.packageName)
        }
        changed()
    }

    // Downscaled once per metadata
    private fun art(meta: MediaMetadata): Bitmap? {
        if (meta === artFor) return artCache
        artFor = meta
        val src = meta.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: meta.getBitmap(MediaMetadata.METADATA_KEY_ART)
            ?: meta.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
        artCache = src?.let {
            val side = 192
            if (it.width <= side && it.height <= side) it
            else {
                val k = side.toFloat() / maxOf(it.width, it.height)
                Bitmap.createScaledBitmap(it, (it.width * k).toInt().coerceAtLeast(1), (it.height * k).toInt().coerceAtLeast(1), true)
            }
        }
        return artCache
    }

    fun positionMs(): Long {
        val s = current?.playbackState ?: return 0
        var p = s.position
        if (s.state == PlaybackState.STATE_PLAYING && s.lastPositionUpdateTime > 0) {
            p += ((SystemClock.elapsedRealtime() - s.lastPositionUpdateTime) * s.playbackSpeed).toLong()
        }
        val d = track?.durationMs ?: 0
        return if (d > 0) p.coerceIn(0, d) else p.coerceAtLeast(0)
    }

    fun toggle() {
        val c = current?.transportControls ?: return
        if (playing) c.pause() else c.play()
    }

    fun next() { current?.transportControls?.skipToNext() }

    fun prev() { current?.transportControls?.skipToPrevious() }

    fun seek(ms: Long) { current?.transportControls?.seekTo(ms) }
}
