package io.github.odlaver.tepi

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

class Note(val text: String, val at: Long, val pinned: Boolean)

// Quick notes, kept on the phone only
class Notes(context: Context) {
    private val sp = context.getSharedPreferences("notes", Context.MODE_PRIVATE)

    fun all(): List<Note> {
        val arr = try { JSONArray(sp.getString("list", "[]")) } catch (_: Exception) { JSONArray() }
        return (0 until arr.length()).mapNotNull { i ->
            arr.optJSONObject(i)?.let { Note(it.optString("t"), it.optLong("at"), it.optBoolean("pin")) }
        }.sortedWith(compareByDescending<Note> { it.pinned }.thenByDescending { it.at })
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
