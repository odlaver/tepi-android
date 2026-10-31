package io.github.odlaver.tepi.scene

import android.graphics.Canvas
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import io.github.odlaver.tepi.Lyrics
import io.github.odlaver.tepi.Media
import io.github.odlaver.tepi.Prefs
import io.github.odlaver.tepi.R
import io.github.odlaver.tepi.SongLyrics
import io.github.odlaver.tepi.Spring
import kotlin.math.min
import kotlin.math.sin

class MusicScene(host: Host, private val media: Media, private val prefs: Prefs, private val lyrics: Lyrics) : Scene(host) {
    override val id = "music"
    override val priority = 40

    private val main = Handler(Looper.getMainLooper())
    private var song: SongLyrics? = null
    private var songKey: String? = null
    private var lyricIdx = -1
    private var lyricPrev = ""
    private val lyricT = Spring(1f, 0.004f, 260f, 32f)
    private val playLevel = Spring(0f, 0.004f, 160f, 26f)
    private var wasPlaying = false
    private var pausedAt = 0L
    private var dismissedKey: String? = null
    private val tmp = RectF()
    private val hitPrev = RectF()
    private val hitPlay = RectF()
    private val hitNext = RectF()
    private val hitBar = RectF()
    private val hitInfo = RectF()

    val playing get() = media.playing
    val track get() = media.track

    fun changed() {
        val tr = media.track
        if (tr == null || tr.key != songKey) {
            song = null
            lyricIdx = -1
            songKey = tr?.key
            if (tr != null && prefs.lyrics) {
                val k = tr.key
                lyrics.load(tr.title, tr.artist, tr.durationMs, prefs.lyricsOnline) { got ->
                    if (songKey == k) { song = got; host.refresh() }
                }
            }
        }
        if (tr?.key != dismissedKey) dismissedKey = null
        if (media.playing) {
            pausedAt = 0
            wasPlaying = true
        } else if (wasPlaying) {
            pausedAt = SystemClock.uptimeMillis()
            wasPlaying = false
            main.postDelayed({ host.refresh() }, PAUSED_MS + 50)
        }
        host.refresh()
    }

    fun reload() {
        songKey = null
        changed()
    }

    override fun live(): Boolean {
        val tr = media.track ?: return false
        if (tr.key == dismissedKey || !prefs.music) return false
        return media.playing || (pausedAt > 0 && SystemClock.uptimeMillis() - pausedAt < PAUSED_MS)
    }

    // Has a track at all, for Home
    fun present() = media.track != null

    override fun dismiss() { dismissedKey = media.track?.key }

    override fun ticking() = media.playing

    override fun step(dt: Float): Boolean {
        playLevel.target = if (media.playing) 1f else 0f
        var moving = playLevel.step(dt)
        moving = lyricT.step(dt) or moving
        if (updateLyric()) host.refresh()
        return moving
    }

    private fun currentLine(): String? {
        if (!prefs.lyrics) return null
        val s = song ?: return null
        if (lyricIdx < 0 || lyricIdx >= s.lines.size) return null
        return s.lines[lyricIdx].text.takeIf { it.isNotEmpty() }
    }

    private fun updateLyric(): Boolean {
        val s = song ?: return false
        if (s.lines.isEmpty()) return false
        val idx = s.indexAt(media.positionMs() + 150)
        if (idx == lyricIdx) return false
        lyricPrev = currentLine() ?: ""
        lyricIdx = idx
        lyricT.snap(0f)
        lyricT.target = 1f
        return true
    }

    override fun drawCompact(c: Canvas, left: RectF, right: RectF, a: Float) {
        val tr = media.track ?: return
        val s = min(left.height(), ui.dp(26f))
        tmp.set(left.left, left.centerY() - s / 2, left.left + s, left.centerY() + s / 2)
        ui.art(c, tr.art, tmp, a)
        bars(c, right.right - s / 2, right.centerY(), s * 0.8f, a)
    }

    override fun rowWidth(): Float {
        val line = currentLine() ?: return 0f
        return ui.lyric.measureText(line) + ui.dp(40f)
    }

    override fun drawRow(c: Canvas, r: RectF, a: Float) {
        val line = currentLine() ?: return
        val k = lyricT.value.coerceIn(0f, 1f)
        val shift = ui.dp(10f)
        val w = r.width() - ui.s24
        if (k < 1f && lyricPrev.isNotEmpty()) ui.centered(c, lyricPrev, ui.lyric, r.centerX(), r.centerY() - shift * k, w, a * (1 - k))
        ui.centered(c, line, ui.lyric, r.centerX(), r.centerY() + shift * (1 - k), w, a * k)
    }

    override fun drawBubble(c: Canvas, r: RectF, a: Float) {
        val tr = media.track ?: return
        tmp.set(r)
        tmp.inset(r.width() * 0.18f, r.height() * 0.18f)
        ui.art(c, tr.art, tmp, a, 0.5f)
    }

    private fun hasLyrics() = prefs.lyrics && song?.lines?.isNotEmpty() == true

    override fun panelHeight(w: Float): Float {
        var h = ui.dp(56f) + ui.dp(22f) + ui.dp(4f) + ui.dp(22f) + ui.dp(8f) + ui.dp(56f)
        if (hasLyrics()) h += ui.dp(14f) + ui.dp(44f)
        return h
    }

    override fun drawPanel(c: Canvas, r: RectF, a: Float) {
        val tr = media.track ?: return
        val art = ui.dp(56f)
        tmp.set(r.left, r.top, r.left + art, r.top + art)
        ui.art(c, tr.art, tmp, a)
        val tx = r.left + art + ui.s16
        hitInfo.set(r.left, r.top, r.right, r.top + art)
        ui.text(c, tr.title, ui.title, tx, r.top + ui.dp(24f), r.right - tx, a)
        if (tr.artist.isNotEmpty()) ui.text(c, tr.artist, ui.body, tx, r.top + ui.dp(44f), r.right - tx, a)

        val barY = r.top + art + ui.dp(22f)
        val dur = tr.durationMs
        val pos = media.positionMs()
        tmp.set(r.left, barY, r.right, barY + ui.dp(4f))
        ui.fill(c, tmp, ui.dp(2f), ui.fillStrong, a)
        if (dur > 0) {
            tmp.right = r.left + r.width() * (pos.toFloat() / dur).coerceIn(0f, 1f)
            ui.fill(c, tmp, ui.dp(2f), ui.sub, a)
            ui.text(c, ui.clock(pos), ui.small, r.left, barY + ui.dp(22f), ui.dp(60f), a)
            ui.textEnd(c, ui.clock(dur), ui.small, r.right, barY + ui.dp(22f), a)
        }
        hitBar.set(r.left, barY - ui.s16, r.right, barY + ui.s16)

        val cy = barY + ui.dp(4f) + ui.dp(22f) + ui.dp(8f) + ui.dp(28f)
        val cx = r.centerX()
        val gap = ui.dp(76f)
        val hit = ui.dp(28f)
        hitPrev.set(cx - gap - hit, cy - hit, cx - gap + hit, cy + hit)
        hitPlay.set(cx - hit, cy - hit, cx + hit, cy + hit)
        hitNext.set(cx + gap - hit, cy - hit, cx + gap + hit, cy + hit)
        ui.solid.color = ui.alpha(ui.ink, a)
        ui.skip(c, cx - gap, cy, ui.dp(20f), back = true)
        if (media.playing) ui.pause(c, cx, cy, ui.dp(26f)) else ui.play(c, cx, cy, ui.dp(26f))
        ui.solid.color = ui.alpha(ui.ink, a)
        ui.skip(c, cx + gap, cy, ui.dp(20f), back = false)

        if (!hasLyrics()) return
        val lines = song?.lines ?: return
        val ly = cy + ui.dp(28f) + ui.dp(14f) + ui.dp(11f)
        ui.centered(c, currentLine() ?: "♪", ui.lyric, cx, ly, r.width(), a)
        val next = lines.getOrNull(lyricIdx + 1)?.text
        if (!next.isNullOrEmpty()) ui.centered(c, next, ui.body, cx, ly + ui.dp(22f), r.width(), a)
    }

    override fun tap(x: Float, y: Float): Boolean {
        when {
            hitPlay.contains(x, y) -> media.toggle()
            hitPrev.contains(x, y) -> media.prev()
            hitNext.contains(x, y) -> media.next()
            hitBar.contains(x, y) -> media.track?.durationMs?.takeIf { it > 0 }?.let { d ->
                media.seek((d * ((x - hitBar.left) / hitBar.width()).coerceIn(0f, 1f)).toLong())
            }
            hitInfo.contains(x, y) -> media.track?.pkg?.let { pkg ->
                host.ctx.packageManager.getLaunchIntentForPackage(pkg)?.let { host.open(it); host.collapse() }
            }
            else -> return false
        }
        host.haptic()
        return true
    }

    // Decorative bars: follow play state, not audio
    private fun bars(c: Canvas, cx: Float, cy: Float, maxH: Float, a: Float) {
        val w = ui.dp(2.6f)
        val gap = ui.dp(2.4f)
        val total = w * 4 + gap * 3
        val secs = SystemClock.uptimeMillis() / 1000f
        val level = playLevel.value.coerceIn(0f, 1f)
        for (i in 0 until 4) {
            val wave = 0.5f + 0.25f * sin(secs * (5.1f + i * 1.7f) + i * 1.3f) + 0.25f * sin(secs * (8.3f - i) + i * 2.1f)
            val h = maxH * (0.2f + 0.8f * wave * level).coerceIn(0.2f, 1f)
            val x = cx - total / 2 + i * (w + gap)
            tmp.set(x, cy - h / 2, x + w, cy + h / 2)
            ui.fill(c, tmp, w / 2, ui.ink, a)
        }
    }

    override fun describe(): String {
        val tr = media.track ?: return ""
        return host.ctx.getString(R.string.cd_music, tr.title, tr.artist)
    }

    companion object {
        private const val PAUSED_MS = 20_000L
    }
}
