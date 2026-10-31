package io.github.odlaver.tepi

import android.content.Context
import android.content.SharedPreferences

class Prefs(context: Context) {
    private val sp: SharedPreferences = context.getSharedPreferences("tepi", Context.MODE_PRIVATE)

    var lyrics: Boolean
        get() = sp.getBoolean("lyrics", true)
        set(v) = sp.edit().putBoolean("lyrics", v).apply()

    var lyricsOnline: Boolean
        get() = sp.getBoolean("lyrics.online", true)
        set(v) = sp.edit().putBoolean("lyrics.online", v).apply()

    var notifs: Boolean
        get() = sp.getBoolean("notifs", true)
        set(v) = sp.edit().putBoolean("notifs", v).apply()

    var notifContent: Boolean
        get() = sp.getBoolean("notifs.content", true)
        set(v) = sp.edit().putBoolean("notifs.content", v).apply()

    // Island shift in dp, down positive
    var offsetDp: Int
        get() = sp.getInt("offset", 0)
        set(v) = sp.edit().putInt("offset", v).apply()

    companion object {
        const val OFFSET_MIN = -8
        const val OFFSET_MAX = 24
    }
}
