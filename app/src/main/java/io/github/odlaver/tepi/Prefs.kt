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

    // Master switch: island and every feature
    var enabled: Boolean
        get() = bool("enabled", true)
        set(v) = set("enabled", v)

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

    // Look: idle shape, motion, material
    var idleShape: String
        get() = str("look.idle", "pill")
        set(v) = set("look.idle", v)

    var idleEyes: Boolean
        get() = bool("look.eyes", false)
        set(v) = set("look.eyes", v)

    var motion: String
        get() = str("look.motion", "spring")
        set(v) = set("look.motion", v)

    var material: String
        get() = str("look.material", "solid")
        set(v) = set("look.material", v)

    // Percent, 60 to 100
    var opacity: Int
        get() = int("look.opacity", 100).coerceIn(60, 100)
        set(v) = set("look.opacity", v)

    var theme: String
        get() = str("look.theme", "dark")
        set(v) = set("look.theme", v)

    var accent: String
        get() = str("look.accent", "mono")
        set(v) = set("look.accent", v)

    // Free colours: accent and island body
    var accentColor: Int
        get() = int("look.accent.color", android.graphics.Color.rgb(100, 170, 255))
        set(v) = set("look.accent.color", v)

    var tint: String
        get() = str("look.tint", "auto")
        set(v) = set("look.tint", v)

    var tintColor: Int
        get() = int("look.tint.color", android.graphics.Color.rgb(28, 28, 30))
        set(v) = set("look.tint.color", v)

    var outline: Boolean
        get() = bool("look.outline", false)
        set(v) = set("look.outline", v)

    var radiusDp: Int
        get() = int("look.radius", 24)
        set(v) = set("look.radius", v)

    var widthDp: Int
        get() = int("look.width", 380)
        set(v) = set("look.width", v)

    var panelStyle: String
        get() = str("look.panel", "attached")
        set(v) = set("look.panel", v)

    // Follow the camera, or screen centre
    var anchor: String
        get() = str("look.anchor", "camera")
        set(v) = set("look.anchor", v)

    // Calibration, half-dp steps
    var calX: Int
        get() = int("cal.x", 0)
        set(v) = set("cal.x", v)

    var calY: Int
        get() = if (sp.contains("cal.y")) int("cal.y", 0) else offsetDp * 2
        set(v) = set("cal.y", v)

    var calSize: Int
        get() = int("cal.size", 0)
        set(v) = set("cal.size", v)

    var closeSec: Int
        get() = int("look.close", 9)
        set(v) = set("look.close", v)

    var hideLandscape: Boolean
        get() = bool("hide.landscape", false)
        set(v) = set("hide.landscape", v)

    var hideFullscreen: Boolean
        get() = bool("hide.fullscreen", true)
        set(v) = set("hide.fullscreen", v)

    // Low-power: auto, on, off
    var lowPower: String
        get() = str("power.low", Power.AUTO)
        set(v) = set("power.low", v)

    // Lyrics unless low-power trims them
    val lyricsActive get() = lyrics && !Power.low(this)

    companion object {
        const val OFFSET_MIN = -8
        const val OFFSET_MAX = 24
        const val CAL_X = 80
        const val CAL_Y_MIN = -16
        const val CAL_Y_MAX = 48
        const val CAL_SIZE_MIN = -12
        const val CAL_SIZE_MAX = 24
    }
}
