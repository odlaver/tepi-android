package io.github.odlaver.tepi

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

// Todoist: overdue, today, next 21 days
class Todoist(private val token: String) {
    fun tasks(): List<Task>? {
        val out = ArrayList<Task>()
        var cursor: String? = null
        var pages = 0
        do {
            val q = java.net.URLEncoder.encode("overdue | today | 21 days", "UTF-8")
            val url = "https://api.todoist.com/api/v1/tasks/filter?query=$q&limit=200" + (cursor?.let { "&cursor=$it" } ?: "")
            val r = Net.send("GET", url, mapOf("Authorization" to "Bearer $token"))
            if (!r.ok) return null
            val o = JSONObject(r.body)
            val arr = o.optJSONArray("results") ?: JSONArray()
            for (i in 0 until arr.length()) {
                val t = arr.getJSONObject(i)
                val due = t.optJSONObject("due") ?: t.optJSONObject("deadline") ?: continue
                val dt = due.optString("datetime").ifEmpty { null }
                val d = due.optString("date")
                val at = dt?.let { parseTime(it) } ?: parseDate(d) ?: continue
                out.add(Task(t.optString("id"), t.optString("content"), "Todoist", at, "todoist",
                    "https://app.todoist.com/app/task/${t.optString("id")}", dateOnly = dt == null))
            }
            cursor = o.optString("next_cursor").takeIf { !o.isNull("next_cursor") && it.isNotEmpty() }
        } while (cursor != null && ++pages < 5)
        return out
    }

    fun close(task: Task) = Net.send("POST", "https://api.todoist.com/api/v1/tasks/${task.id}/close", mapOf("Authorization" to "Bearer $token")).ok
}

// Notion: one data source with a date column
class Notion(private val token: String) {
    private fun h() = mapOf("Authorization" to "Bearer $token", "Notion-Version" to VERSION, "Content-Type" to "application/json")

    // Data sources the integration can see
    fun sources(): List<Pair<String, String>>? {
        val r = Net.send("POST", "https://api.notion.com/v1/search", h(), JSONObject().put("filter", JSONObject().put("property", "object").put("value", "data_source")).toString())
        if (!r.ok) return null
        val arr = JSONObject(r.body).optJSONArray("results") ?: return emptyList()
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            val title = o.optJSONArray("title")?.let { t -> (0 until t.length()).joinToString("") { t.getJSONObject(it).optString("plain_text") } }.orEmpty()
            o.optString("id") to title.ifEmpty { "Untitled" }
        }
    }

    fun tasks(source: String): List<Task>? {
        val schema = Net.send("GET", "https://api.notion.com/v1/data_sources/$source", h())
        if (!schema.ok) return null
        val props = JSONObject(schema.body).optJSONObject("properties") ?: return null
        var dateProp: String? = null
        var doneProp: String? = null
        var doneType = ""
        var titleProp = "Name"
        for (k in props.keys()) {
            when (props.getJSONObject(k).optString("type")) {
                "date" -> if (dateProp == null) dateProp = k
                "checkbox" -> if (doneProp == null) { doneProp = k; doneType = "checkbox" }
                "status" -> if (doneProp == null) { doneProp = k; doneType = "status" }
                "title" -> titleProp = k
            }
        }
        val dp = dateProp ?: return emptyList()
        val from = LocalDate.now().minusDays(14).toString()
        val to = LocalDate.now().plusDays(21).toString()
        val body = JSONObject()
            .put("filter", JSONObject().put("and", JSONArray()
                .put(JSONObject().put("property", dp).put("date", JSONObject().put("on_or_after", from)))
                .put(JSONObject().put("property", dp).put("date", JSONObject().put("on_or_before", to)))))
            .put("sorts", JSONArray().put(JSONObject().put("property", dp).put("direction", "ascending")))
            .put("page_size", 100)
        val r = Net.send("POST", "https://api.notion.com/v1/data_sources/$source/query", h(), body.toString())
        if (!r.ok) return null
        val arr = JSONObject(r.body).optJSONArray("results") ?: return emptyList()
        val out = ArrayList<Task>()
        for (i in 0 until arr.length()) {
            val page = arr.getJSONObject(i)
            val p = page.optJSONObject("properties") ?: continue
            if (doneProp != null) {
                val d = p.optJSONObject(doneProp)
                val done = if (doneType == "checkbox") d?.optBoolean("checkbox") == true
                else d?.optJSONObject("status")?.optString("name")?.let { it.equals("Done", true) || it.equals("Selesai", true) || it.equals("Complete", true) } == true
                if (done) continue
            }
            val start = p.optJSONObject(dp)?.optJSONObject("date")?.optString("start") ?: continue
            val at = if (start.length > 10) parseTime(start) else parseDate(start)
            val title = p.optJSONObject(titleProp)?.optJSONArray("title")?.let { t -> (0 until t.length()).joinToString("") { t.getJSONObject(it).optString("plain_text") } }.orEmpty()
            out.add(Task(page.optString("id"), title.ifEmpty { "Untitled" }, "Notion", at ?: continue, "notion", page.optString("url"),
                dateOnly = start.length <= 10, readOnly = doneType != "checkbox"))
        }
        return out
    }

    fun done(task: Task, source: String): Boolean {
        val schema = Net.send("GET", "https://api.notion.com/v1/data_sources/$source", h())
        if (!schema.ok) return false
        val props = JSONObject(schema.body).optJSONObject("properties") ?: return false
        val box = props.keys().asSequence().firstOrNull { props.getJSONObject(it).optString("type") == "checkbox" } ?: return false
        return Net.send("PATCH", "https://api.notion.com/v1/pages/${task.id}", h(),
            JSONObject().put("properties", JSONObject().put(box, JSONObject().put("checkbox", true))).toString()).ok
    }

    companion object {
        private const val VERSION = "2026-03-11"
    }
}

// iCal feed: events and to-dos in a window, weekly/daily repeats
object Ical {
    fun fetch(url: String): Pair<List<ClassSlot>, List<Task>>? {
        val u = if (url.startsWith("webcal://")) "https://" + url.removePrefix("webcal://") else url
        val r = Net.send("GET", u, mapOf("Accept" to "text/calendar"))
        if (!r.ok) return null
        return parse(r.body)
    }

    fun parse(text: String): Pair<List<ClassSlot>, List<Task>> {
        val lines = unfold(text)
        val events = ArrayList<ClassSlot>()
        val todos = ArrayList<Task>()
        var cur: HashMap<String, Pair<String, String>>? = null
        val now = System.currentTimeMillis()
        val until = now + 8 * 86_400_000L
        for (raw in lines) {
            when {
                raw == "BEGIN:VEVENT" || raw == "BEGIN:VTODO" -> cur = HashMap()
                raw == "END:VEVENT" && cur != null -> {
                    val c = cur!!
                    val start = time(c["DTSTART"]) ?: run { cur = null; null }
                    if (start != null) {
                        val end = time(c["DTEND"]) ?: (start + 3_600_000L)
                        val len = end - start
                        val title = unescape(c["SUMMARY"]?.second ?: "")
                        val room = unescape(c["LOCATION"]?.second ?: "")
                        for (s in repeats(start, c["RRULE"]?.second, now - len, until)) events.add(ClassSlot(title, room, s, s + len, source = "ics"))
                    }
                    cur = null
                }
                raw == "END:VTODO" && cur != null -> {
                    val c = cur!!
                    val status = c["STATUS"]?.second ?: ""
                    val due = time(c["DUE"])
                    if (due != null && status != "COMPLETED" && status != "CANCELLED") todos.add(Task(c["UID"]?.second ?: due.toString(), unescape(c["SUMMARY"]?.second ?: ""), "iCal", due, "ics", null,
                        dateOnly = c["DUE"]?.first?.contains("VALUE=DATE") == true, readOnly = true))
                    cur = null
                }
                cur != null -> {
                    val colon = raw.indexOf(':')
                    if (colon > 0) {
                        val head = raw.substring(0, colon)
                        val name = head.substringBefore(';').uppercase()
                        cur!![name] = head to raw.substring(colon + 1)
                    }
                }
            }
        }
        return events.filter { it.end >= now && it.start <= until }.sortedBy { it.start } to todos
    }

    private fun unfold(t: String): List<String> {
        val out = ArrayList<String>()
        for (l in t.replace("\r\n", "\n").split('\n')) {
            if ((l.startsWith(" ") || l.startsWith("\t")) && out.isNotEmpty()) out[out.size - 1] = out.last() + l.substring(1)
            else out.add(l)
        }
        return out
    }

    private fun unescape(s: String) = s.replace("\\n", " ").replace("\\,", ",").replace("\\;", ";").replace("\\\\", "\\")

    // Value with TZID, Z, or floating local time
    private fun time(v: Pair<String, String>?): Long? {
        if (v == null) return null
        val (head, value) = v
        return try {
            when {
                value.length == 8 -> LocalDate.parse(value, DateTimeFormatter.BASIC_ISO_DATE).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
                value.endsWith("Z") -> LocalDateTime.parse(value.dropLast(1), FMT).toInstant(ZoneOffset.UTC).toEpochMilli()
                else -> {
                    val tz = Regex("TZID=([^;:]+)").find(head)?.groupValues?.get(1)
                    val zone = try { tz?.let { ZoneId.of(it) } } catch (_: Exception) { null } ?: ZoneId.systemDefault()
                    LocalDateTime.parse(value, FMT).atZone(zone).toInstant().toEpochMilli()
                }
            }
        } catch (_: Exception) { null }
    }

    // DAILY or WEEKLY with INTERVAL, COUNT, UNTIL, BYDAY
    private fun repeats(start: Long, rule: String?, from: Long, until: Long): List<Long> {
        if (rule == null) return listOf(start)
        val p = rule.split(';').associate { it.substringBefore('=') to it.substringAfter('=') }
        val freq = p["FREQ"] ?: return listOf(start)
        val step = (p["INTERVAL"]?.toIntOrNull() ?: 1).coerceAtLeast(1)
        val count = p["COUNT"]?.toIntOrNull() ?: Int.MAX_VALUE
        val end = p["UNTIL"]?.let { time("" to it) } ?: Long.MAX_VALUE
        val zone = ZoneId.systemDefault()
        val base = Instant.ofEpochMilli(start).atZone(zone)
        val days = p["BYDAY"]?.split(',')?.mapNotNull { DAYS[it.takeLast(2)] }?.toSet()
        val out = ArrayList<Long>()
        var n = 0
        var d = 0L
        while (n < count && d < 800) {
            val day = base.plusDays(d)
            val t = day.toInstant().toEpochMilli()
            if (t > end || t > until) break
            val ok = when (freq) {
                "DAILY" -> d % step == 0L
                "WEEKLY" -> (d / 7) % step == 0L && (days?.contains(day.dayOfWeek.value) ?: (d % 7 == 0L))
                else -> d == 0L
            }
            if (ok) { n++; if (t >= from) out.add(t) }
            if (freq != "DAILY" && freq != "WEEKLY") break
            d++
        }
        return out
    }

    private val FMT = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss")
    private val DAYS = mapOf("MO" to 1, "TU" to 2, "WE" to 3, "TH" to 4, "FR" to 5, "SA" to 6, "SU" to 7)
}

private fun parseTime(s: String): Long? = try { java.time.OffsetDateTime.parse(s).toInstant().toEpochMilli() } catch (_: Exception) {
    try { LocalDateTime.parse(s.take(19)).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli() } catch (_: Exception) { null }
}

// Date-only: end of that day
private fun parseDate(s: String): Long? = try { LocalDate.parse(s.take(10)).atTime(23, 59).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli() } catch (_: Exception) { null }
