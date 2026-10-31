package io.github.odlaver.tepi

import android.content.Context
import android.content.SharedPreferences

class Prefs(context: Context) {
    val sp: SharedPreferences = context.getSharedPreferences("tepi", Context.MODE_PRIVATE)

    fun bool(key: String, def: Boolean) = sp.getBoolean(key, def)
    fun set(key: String, v: Boolean) = sp.edit().putBoolean(key, v).apply()
    fun int(key: String, def: Int) = sp.getInt(key, def)
    fun set(key: String, v: Int) = sp.edit().putInt(key, v).apply()
    fun str(key: String, def: String) = sp.getString(key, def) ?: def
    fun set(key: String, v: String) = sp.edit().putString(key, v).apply()

    var music: Boolean
        get() = bool("music", true)
        set(v) = set("music", v)

    var lyrics: Boolean
        get() = bool("lyrics", true)
        set(v) = set("lyrics", v)

    var lyricsOnline: Boolean
        get() = bool("lyrics.online", true)
        set(v) = set("lyrics.online", v)

    var notifs: Boolean
        get() = bool("notifs", true)
        set(v) = set("notifs", v)

    var notifContent: Boolean
        get() = bool("notifs.content", true)
        set(v) = set("notifs.content", v)

    // Tap the camera to open Home
    var cameraTap: Boolean
        get() = bool("camera.tap", true)
        set(v) = set("camera.tap", v)

    // Island shift in dp, down positive
    var offsetDp: Int
        get() = int("offset", 0)
        set(v) = set("offset", v)

    companion object {
        const val OFFSET_MIN = -8
        const val OFFSET_MAX = 24
    }
}
