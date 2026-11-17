package io.github.odlaver.tepi

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

class Note(val text: String, val at: Long, val pinned: Boolean)

// Quick notes, kept on the phone only
class Notes(context: Context) {
    private val sp = context.getSharedPreferences("notes", Context.MODE_PRIVATE)

    // Island draws this every frame; parse only on change
    // Prefs hand back the same String until written
    private var raw: String? = null
    private var parsed: List<Note> = emptyList()

    fun all(): List<Note> {
        val now = sp.getString("list", "[]")
        if (now === raw) return parsed
        val arr = try { JSONArray(now) } catch (_: Exception) { JSONArray() }
        parsed = (0 until arr.length()).mapNotNull { i ->
            arr.optJSONObject(i)?.let { Note(it.optString("t"), it.optLong("at"), it.optBoolean("pin")) }
        }.sortedWith(compareByDescending<Note> { it.pinned }.thenByDescending { it.at })
        raw = now
        return parsed
    }

    fun add(text: String) {
        if (text.isBlank()) return
        save(all() + Note(text.trim(), System.currentTimeMillis(), false))
    }

    fun remove(n: Note) = save(all().filterNot { it.at == n.at })

    fun pin(n: Note) = save(all().map { if (it.at == n.at) Note(it.text, it.at, !it.pinned) else it })

    private fun save(list: List<Note>) {
        val arr = JSONArray()
        for (n in list.take(200)) arr.put(JSONObject().put("t", n.text).put("at", n.at).put("pin", n.pinned))
        sp.edit().putString("list", arr.toString()).apply()
    }
}
