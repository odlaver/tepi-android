package io.github.odlaver.tepi

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.Normalizer
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.roundToInt

class LyricLine(val atMs: Long, val text: String)

// Synced lines; empty when none found
class SongLyrics(val key: String, val lines: List<LyricLine>) {
    // Last line at or before pos
    fun indexAt(posMs: Long): Int {
        var lo = 0
        var hi = lines.size - 1
        var found = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (lines[mid].atMs <= posMs) { found = mid; lo = mid + 1 } else hi = mid - 1
        }
        return found
    }
}

// LRCLIB first, NetEase fallback, disk cache
class Lyrics(private val cacheDir: File) {
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val failedAt = HashMap<String, Long>()
    private var backoffMs = 10_000L

    fun load(rawTitle: String, rawArtist: String, durationMs: Long, online: Boolean, done: (SongLyrics) -> Unit) {
        val (title, artist) = clean(rawTitle, rawArtist)
        val key = "$artist|$title".lowercase()
        worker.execute {
            val lines = try {
                fetch(key, title, artist, (durationMs / 1000.0).roundToInt(), online)
            } catch (e: Exception) {
                Log.w(TAG, "lyrics '$artist - $title': ${e.message}")
                failedAt[key] = SystemClock.elapsedRealtime()
                backoffMs = (backoffMs * 2).coerceAtMost(120_000L)
                emptyList()
            }
            main.post { done(SongLyrics(key, lines)) }
        }
    }

    private fun fetch(key: String, title: String, artist: String, dur: Int, online: Boolean): List<LyricLine> {
        val file = File(cacheDir, hash(key) + ".lrc")
        if (file.exists()) return parse(file.readText())
        if (!online || title.isEmpty()) return emptyList()
        val failed = failedAt[key]
        if (failed != null && SystemClock.elapsedRealtime() - failed < backoffMs) return emptyList()

        var lrc: String? = null
        var down: Exception? = null
        val q = "track_name=" + enc(title) + if (artist.isNotEmpty()) "&artist_name=" + enc(artist) else ""
        try {
            if (artist.isNotEmpty()) {
                val exact = ask("https://lrclib.net/api/get?$q&duration=$dur")
                if (exact is JSONObject) lrc = synced(exact)
            }
            // Spotify suffixes and artist lists miss
            val short = "track_name=" + enc(base(title)) + if (artist.isNotEmpty()) "&artist_name=" + enc(lead(artist)) else ""
            for (url in listOf("https://lrclib.net/api/search?$q", "https://lrclib.net/api/search?$short", "https://lrclib.net/api/search?q=" + enc("$artist $title".trim()))) {
                if (lrc != null) break
                lrc = best(ask(url) as? JSONArray, dur)
            }
        } catch (e: Exception) {
            down = e
        }
        // LRCLIB busy or unsynced: NetEase
        if (lrc == null) {
            try {
                lrc = netEase(title, artist, dur)
            } catch (e: Exception) {
                if (down != null) throw Exception("${down.message}; ${e.message}")
            }
        }
        if (lrc == null && down != null) throw down
        backoffMs = 10_000L
        if (lrc == null) return emptyList()
        cacheDir.mkdirs()
        file.writeText(lrc)
        return parse(lrc)
    }

    private fun ask(url: String): Any? {
        val (status, body) = get(url, longArrayOf(1000))
        if (status == 404 || status == 400) return null
        if (status !in 200..299) throw Exception("LRCLIB $status")
        val t = body.trim()
        return if (t.startsWith("[")) JSONArray(t) else JSONObject(t)
    }

    // Overload bursts pass in seconds: retry
    private fun get(url: String, retryMs: LongArray): Pair<Int, String> {
        var r = send(url)
        var i = 0
        while (i < retryMs.size && (r.first == 0 || r.first == 429 || r.first >= 500)) {
            Thread.sleep(retryMs[i++])
            r = send(url)
        }
        return r
    }

    private fun send(url: String): Pair<Int, String> {
        return try {
            val c = URL(url).openConnection() as HttpURLConnection
            c.connectTimeout = 9000
            c.readTimeout = 9000
            c.instanceFollowRedirects = false
            c.setRequestProperty("User-Agent", "Tepi Android (github.com/odlaver/tepi-android)")
            try {
                val code = c.responseCode
                val stream = if (code in 200..299) c.inputStream else c.errorStream
                val body = stream?.use { s -> s.readBytes().take(MAX_BODY).toByteArray().toString(Charsets.UTF_8) } ?: ""
                code to body
            } finally {
                c.disconnect()
            }
        } catch (e: Exception) {
            0 to ""
        }
    }

    // Same song: artist, title, length agree
    private fun netEase(title: String, artist: String, dur: Int): String? {
        if (artist.isEmpty()) return null
        val retry = longArrayOf(1000, 2500)
        var r = get("https://music.163.com/api/search/get?s=" + enc(lead(artist) + " " + base(title)) + "&type=1&limit=10", retry)
        if (r.first !in 200..299) throw Exception("NetEase ${r.first}")
        val songs = JSONObject(r.second).optJSONObject("result")?.optJSONArray("songs") ?: return null
        val want = bare(title)
        val by = norm(artist)
        if (want.isEmpty() || by.isEmpty()) return null
        var bestGap = Double.MAX_VALUE
        var id = 0L
        for (i in 0 until songs.length()) {
            val s = songs.optJSONObject(i) ?: continue
            val gap = abs(s.optDouble("duration", 0.0) / 1000 - dur)
            if (gap > 8 || bare(s.optString("name")) != want) continue
            val artists = s.optJSONArray("artists") ?: continue
            var same = false
            for (j in 0 until artists.length()) {
                val n = norm(artists.optJSONObject(j)?.optString("name") ?: "")
                if (n.isNotEmpty() && (by.contains(n) || n.contains(by))) same = true
            }
            if (same && gap < bestGap) { bestGap = gap; id = s.optLong("id") }
        }
        if (id <= 0) return null
        r = get("https://music.163.com/api/song/lyric?id=$id&lv=1", retry)
        if (r.first !in 200..299) throw Exception("NetEase ${r.first}")
        val lrc = JSONObject(r.second).optJSONObject("lrc")?.optString("lyric") ?: return null
        if (lrc.contains("纯音乐")) return null
        val sb = StringBuilder()
        for (raw in lrc.replace("\r", "").split('\n')) {
            val m = STAMP.find(raw) ?: continue
            if (CREDIT.containsMatchIn(raw.substring(m.range.last + 1).trim())) continue
            sb.append(raw).append('\n')
        }
        val out = sb.toString()
        return if (parse(out).isNotEmpty()) out else null
    }

    private fun synced(item: JSONObject): String? {
        val s = item.optString("syncedLyrics", "")
        return if (item.isNull("syncedLyrics") || s.isBlank()) null else s
    }

    // Closest duration wins; other edits drift
    private fun best(items: JSONArray?, dur: Int): String? {
        if (items == null) return null
        var best: String? = null
        var bestScore = Double.MAX_VALUE
        for (i in 0 until items.length()) {
            val item = items.optJSONObject(i) ?: continue
            val lrc = synced(item) ?: continue
            val score = abs(item.optDouble("duration", dur.toDouble()) - dur)
            if (score > 8) continue
            if (score < bestScore) { bestScore = score; best = lrc }
        }
        return best
    }

    companion object {
        private const val TAG = "Tepi"
        private const val MAX_BODY = 2 * 1024 * 1024
        private val STAMP = Regex("""\[(\d+):(\d+(?:[.:]\d+)?)]""")
        private val CREDIT = Regex("""^(作词|作曲|编曲|制作人|监制|混音|母带|录音|和声|吉他|贝斯|鼓|键盘|弦乐|出品|发行|词|曲)\s*[:：]""")
        private val NOISE = Regex("""official|video|audio|lyric|lirik|\bmv\b|m/v|\bhd\b|\b4k\b|visuali[sz]er|remaster|explicit|live session""", RegexOption.IGNORE_CASE)

        private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

        fun parse(lrc: String): List<LyricLine> {
            val lines = ArrayList<LyricLine>()
            for (raw in lrc.split('\n')) {
                val ms = STAMP.findAll(raw).toList()
                if (ms.isEmpty()) continue
                val text = raw.substring(ms.last().range.last + 1).trim()
                for (m in ms) {
                    val min = m.groupValues[1].toDoubleOrNull() ?: continue
                    val sec = m.groupValues[2].replace(':', '.').toDoubleOrNull() ?: continue
                    lines.add(LyricLine(((min * 60 + sec) * 1000).toLong(), text))
                }
            }
            lines.sortBy { it.atMs }
            return lines
        }

        // Title without (feat.), [Remastered], " - Live"
        private fun base(s: String) = s.replace(Regex("""\s*[(\[][^)\]]*[)\]]"""), "").substringBefore(" - ").trim()

        private fun bare(s: String) = norm(base(s))

        // First name of "A, B & C"
        private fun lead(a: String) = a.split(Regex("""\s*(,|&|\sx\s|\sfeat\.?\s)\s*""", RegexOption.IGNORE_CASE))[0].trim()

        // Case, accents, punctuation aside
        private fun norm(s: String): String {
            val sb = StringBuilder()
            for (c in Normalizer.normalize(s, Normalizer.Form.NFD)) if (c.isLetterOrDigit()) sb.append(c.lowercaseChar())
            return sb.toString()
        }

        // YouTube-style titles into artist + title
        fun clean(rawTitle: String, rawArtist: String): Pair<String, String> {
            var t = rawTitle
            var a = rawArtist.trim()
            t = Regex("""\s*[(\[【]([^)\]】]*)[)\]】]""").replace(t) { m -> if (NOISE.containsMatchIn(m.groupValues[1])) "" else m.value }
            t = t.replace(Regex("""\s+\|.*$"""), "")
            val channel = a.isEmpty() || Regex("""(?i)(vevo|\s-\s*topic|official|records|music|channel|\btv)$|^official""").containsMatchIn(a)
            val dash = Regex("""^(.+?)\s+[-–—]\s+(.+)$""").find(t)
            if (dash != null && (channel || dash.groupValues[1].contains(a, ignoreCase = true))) {
                a = dash.groupValues[1].trim()
                t = dash.groupValues[2].trim()
            }
            a = a.replace(Regex("""(?i)\s*-\s*topic$|vevo$|^official\s*"""), "")
            t = t.replace(Regex("""(?i)\s*[(\[](ft\.?|feat\.?|featuring)\s[^)\]]*[)\]]"""), "")
            t = t.replace(Regex("""(?i)\s+(ft\.?|feat\.?|featuring)\s+.*$"""), "")
            return t.trim().trim('"', '\'', '-', ' ') to a.trim()
        }

        private fun hash(s: String): String {
            var h = -3750763034362895579L
            for (c in s) h = (h xor c.code.toLong()) * 1099511628211L
            return java.lang.Long.toHexString(h).padStart(16, '0')
        }
    }
}
