package io.github.odlaver.tepi

import org.json.JSONArray

class Release(val version: String, val apk: String, val page: String)

// Newest android-v* release with an APK
object Update {
    fun latest(): Release? {
        val r = Net.send("GET", "https://api.github.com/repos/odlaver/tepi/releases?per_page=30", mapOf("Accept" to "application/vnd.github+json"))
        if (!r.ok) return null
        val arr = try { JSONArray(r.body) } catch (_: Exception) { return null }
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val tag = o.optString("tag_name")
            if (!tag.startsWith("android-v") || o.optBoolean("draft")) continue
            val assets = o.optJSONArray("assets") ?: continue
            for (j in 0 until assets.length()) {
                val a = assets.getJSONObject(j)
                if (a.optString("name").endsWith(".apk")) return Release(tag.removePrefix("android-v"), a.optString("browser_download_url"), o.optString("html_url"))
            }
        }
        return null
    }

    // 0.2.10 > 0.2.9
    fun newer(a: String, b: String): Boolean {
        val x = a.split('.').map { it.toIntOrNull() ?: 0 }
        val y = b.split('.').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(x.size, y.size)) {
            val p = x.getOrElse(i) { 0 }
            val q = y.getOrElse(i) { 0 }
            if (p != q) return p > q
        }
        return false
    }
}
