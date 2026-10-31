package io.github.odlaver.tepi

import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZoneOffset

class ClassSlot(
    val title: String,
    val room: String,
    val start: Long,
    val end: Long,
    val online: Boolean = false,
    val url: String? = null,
    val personal: Boolean = false,
    val cancelled: Boolean = false,
    val exam: Boolean = false,
    val source: String = "kulia",
)

class Task(
    val id: String,
    val title: String,
    val course: String,
    val deadline: Long,
    val source: String,
    val url: String?,
    val kind: String = "tugas",
    val personal: Boolean = false,
    val dateOnly: Boolean = false,
    val readOnly: Boolean = false,
)

// Email code login, then PostgREST under RLS; port of src/Kulia.cs
class Kulia(private val vault: Vault) {
    private var access: String? = null
    private var accessUntil = 0L
    private var refresh: String? = null
    private var userId: String? = null
    var email: String? = null
        private set
    private var classLabel: String? = null
    private var classKnown = false

    init {
        vault.get(SESSION)?.let {
            try {
                val o = JSONObject(it)
                refresh = o.optString("refresh").ifEmpty { null }
                email = o.optString("email").ifEmpty { null }
                userId = o.optString("user").ifEmpty { null }
            } catch (_: Exception) { }
        }
    }

    val signedIn get() = refresh != null

    private fun headers(auth: Boolean): Map<String, String> {
        val h = hashMapOf("apikey" to KEY, "Content-Type" to "application/json")
        if (auth && access != null) h["Authorization"] = "Bearer $access"
        return h
    }

    @Synchronized
    fun sendCode(address: String): Int? {
        val r = Net.send("POST", "$URL/auth/v1/otp", headers(false), JSONObject().put("email", address).put("create_user", false).toString())
        return when {
            r.ok -> null
            r.status == 0 -> R.string.k_unreachable
            r.status == 429 -> R.string.k_wait
            r.status == 422 || r.body.contains("signup", true) -> R.string.k_unknown_email
            else -> R.string.k_send_failed
        }
    }

    @Synchronized
    fun verify(address: String, code: String): Int? {
        val r = Net.send("POST", "$URL/auth/v1/verify", headers(false),
            JSONObject().put("type", "email").put("email", address).put("token", code).toString())
        if (r.status == 0) return R.string.k_unreachable
        if (!r.ok) return R.string.k_bad_code
        take(JSONObject(r.body))
        email = address
        persist()
        return null
    }

    @Synchronized
    fun signOut() {
        if (access != null) Net.send("POST", "$URL/auth/v1/logout", headers(true), "{}", 8000)
        access = null; refresh = null; userId = null; email = null; classLabel = null; classKnown = false
        vault.put(SESSION, null)
    }

    private fun take(s: JSONObject) {
        access = s.optString("access_token")
        refresh = s.optString("refresh_token")
        accessUntil = System.currentTimeMillis() + (s.optLong("expires_in", 3600) - 60) * 1000
        s.optJSONObject("user")?.optString("id")?.let { userId = it }
    }

    private fun persist() {
        vault.put(SESSION, JSONObject().put("refresh", refresh ?: "").put("email", email ?: "").put("user", userId ?: "").toString())
    }

    // Refresh tokens rotate: save each one
    private fun ensureAccess(): Int? {
        val rt = refresh ?: return R.string.k_signed_out
        if (access != null && System.currentTimeMillis() < accessUntil) return null
        val r = Net.send("POST", "$URL/auth/v1/token?grant_type=refresh_token", headers(false), JSONObject().put("refresh_token", rt).toString())
        if (r.status == 0) return R.string.k_unreachable
        if (r.status == 400 || r.status == 401) {
            access = null; refresh = null
            vault.put(SESSION, null)
            return R.string.k_expired
        }
        if (!r.ok) return R.string.k_trouble
        take(JSONObject(r.body))
        persist()
        return null
    }

    private fun call(method: String, path: String, body: String? = null, extra: Map<String, String> = emptyMap()): Reply {
        var r = Net.send(method, URL + path, headers(true) + extra, body)
        if (r.status == 401) {
            accessUntil = 0
            if (ensureAccess() == null) r = Net.send(method, URL + path, headers(true) + extra, body)
        }
        return r
    }

    private fun rows(body: String) = try { JSONArray(body) } catch (_: Exception) { JSONArray() }

    private fun ensureClass() {
        if (classKnown || userId == null) return
        val r = call("GET", "/rest/v1/profiles?select=class_label&id=eq.$userId")
        if (!r.ok) return
        val a = rows(r.body)
        if (a.length() > 0) classLabel = a.getJSONObject(0).optString("class_label").ifEmpty { null }
        classKnown = true
    }

    // Next 7 days, the same rows kulia.live/jadwal shows
    @Synchronized
    fun schedule(): Pair<List<ClassSlot>?, Int?> {
        ensureAccess()?.let { return null to it }
        ensureClass()
        val en = call("GET", "/rest/v1/enrollments?select=cohort_course_id&user_id=eq.$userId")
        if (!en.ok) return null to (if (en.status == 0) R.string.k_unreachable else R.string.k_trouble)
        val ids = rows(en.body).let { a -> (0 until a.length()).map { a.getJSONObject(it).optString("cohort_course_id") } }
        var course = JSONArray()
        if (ids.isNotEmpty()) {
            val cr = call("GET", "/rest/v1/course_schedules?select=weekday,starts_at,ends_at,room,kind,mode,meeting_url,class_date,class_label," +
                "cohort_courses!inner(is_active,courses(name,short_name)),schedule_confirmations(class_date,status)" +
                "&cohort_courses.is_active=eq.true&cohort_course_id=in.(" + ids.joinToString(",") + ")")
            if (!cr.ok) return null to R.string.k_trouble
            course = rows(cr.body)
        }
        val pr = call("GET", "/rest/v1/personal_schedules?select=id,title,category,weekday,event_date,starts_at,ends_at,room")
        val personal = if (pr.ok) rows(pr.body) else JSONArray()
        // Invites not yet answered stay off
        val pending = HashSet<String>()
        val mr = call("POST", "/rest/v1/rpc/my_schedule_partners", "{}")
        if (mr.ok) rows(mr.body).let { a ->
            for (i in 0 until a.length()) {
                val m = a.getJSONObject(i)
                if (m.optString("user_id") == userId && m.optString("status") == "pending") pending.add(m.optString("schedule_id"))
            }
        }
        return expand(course, personal, pending) to null
    }

    private fun expand(course: JSONArray, personal: JSONArray, pending: Set<String>): List<ClassSlot> {
        val out = ArrayList<ClassSlot>()
        // Kulia stores WIB wall-clock times
        val today = OffsetDateTime.now(WIB).toLocalDate()
        for (d in 0 until 7) {
            val day = today.plusDays(d.toLong())
            val iso = day.dayOfWeek.value
            val date = day.toString()
            for (i in 0 until course.length()) {
                val r = course.getJSONObject(i)
                val once = r.optString("class_date").takeIf { !r.isNull("class_date") && it.isNotEmpty() }
                if (if (once != null) once != date else r.optInt("weekday") != iso) continue
                val forClass = r.optString("class_label").takeIf { !r.isNull("class_label") && it.isNotEmpty() }
                if (forClass != null && forClass != classLabel) continue
                val c = r.optJSONObject("cohort_courses")?.optJSONObject("courses")
                val name = c?.optString("short_name")?.ifEmpty { null } ?: c?.optString("name")?.ifEmpty { null } ?: "Kuliah"
                var cancelled = false
                var exam = false
                r.optJSONArray("schedule_confirmations")?.let { cf ->
                    for (j in 0 until cf.length()) {
                        val x = cf.getJSONObject(j)
                        if (x.optString("class_date") == date) { cancelled = x.optString("status") == "batal"; exam = x.optString("status") == "ujian" }
                    }
                }
                val online = r.optString("mode") == "online"
                val link = r.optString("meeting_url").takeIf { online && it.startsWith("https://") }
                out.add(ClassSlot(name, r.optString("room").takeIf { !r.isNull("room") }.orEmpty(), at(day, r.optString("starts_at")), at(day, r.optString("ends_at")),
                    online, link, false, cancelled, exam))
            }
            for (i in 0 until personal.length()) {
                val r = personal.getJSONObject(i)
                if (r.optString("id") in pending) continue
                val oneOff = r.optString("category") == "kegiatan"
                if (if (oneOff) r.optString("event_date") != date else r.optInt("weekday") != iso) continue
                out.add(ClassSlot(r.optString("title"), r.optString("room").takeIf { !r.isNull("room") }.orEmpty(),
                    at(day, r.optString("starts_at")), at(day, r.optString("ends_at")), personal = true))
            }
        }
        val now = System.currentTimeMillis()
        return out.filter { it.end >= now }.sortedBy { it.start }
    }

    private fun at(day: LocalDate, hm: String): Long {
        val t = try { LocalTime.parse(hm.take(8)) } catch (_: Exception) { LocalTime.MIDNIGHT }
        return day.atTime(t).toInstant(WIB).toEpochMilli()
    }

    @Synchronized
    fun feed(): Pair<List<Task>?, Int?> {
        ensureAccess()?.let { return null to it }
        ensureClass()
        val r = call("POST", "/rest/v1/rpc/my_feed", "{}")
        if (r.status == 0) return null to R.string.k_unreachable
        if (!r.ok) return null to R.string.k_trouble
        val b = try { JSONObject(r.body) } catch (_: Exception) { return null to R.string.k_trouble }
        val statuses = HashMap<String, String>()
        b.optJSONArray("statuses")?.let { a -> for (i in 0 until a.length()) a.getJSONObject(i).let { statuses[it.optString("task_id")] = it.optString("status") } }
        val list = ArrayList<Pair<Task, String>>()
        b.optJSONArray("tasks")?.let { a ->
            for (i in 0 until a.length()) {
                val t = a.getJSONObject(i)
                val cls = t.optString("class_label").takeIf { !t.isNull("class_label") && it.isNotEmpty() }
                if (cls != null && classLabel != null && cls != classLabel) continue
                val c = t.optJSONObject("cohort_courses")?.optJSONObject("courses")
                val course = c?.optString("short_name")?.ifEmpty { null } ?: c?.optString("name").orEmpty()
                val id = t.optString("id")
                list.add(Task(id, t.optString("title"), course, time(t.optString("deadline_at")), "kulia", "https://kulia.live/tugas/$id", t.optString("kind")) to (statuses[id] ?: "belum"))
            }
        }
        b.optJSONArray("personal")?.let { a ->
            for (i in 0 until a.length()) {
                val t = a.getJSONObject(i)
                val id = t.optString("id")
                list.add(Task(id, t.optString("title"), "Pribadi", time(t.optString("deadline_at")), "kulia", "https://kulia.live/tugas/pribadi/$id", personal = true) to t.optString("status"))
            }
        }
        val now = System.currentTimeMillis()
        return list.filter { (t, st) ->
            st != "selesai" && st != "dilewati" && t.deadline > 0 && (t.kind != "ujian" || t.deadline > now) && t.deadline > now - 14 * 86_400_000L
        }.map { it.first }.sortedBy { it.deadline } to null
    }

    @Synchronized
    fun done(task: Task): Boolean {
        if (ensureAccess() != null || userId == null) return false
        val stamp = OffsetDateTime.now(ZoneOffset.UTC).toString()
        if (task.personal) {
            val r = call("PATCH", "/rest/v1/personal_tasks?id=eq.${task.id}", JSONObject().put("status", "selesai").put("updated_at", stamp).toString(),
                mapOf("Prefer" to "return=representation"))
            return r.ok && rows(r.body).length() > 0
        }
        return call("POST", "/rest/v1/task_progress?on_conflict=user_id,task_id",
            JSONObject().put("user_id", userId).put("task_id", task.id).put("status", "selesai").put("updated_at", stamp).toString(),
            mapOf("Prefer" to "resolution=merge-duplicates,return=minimal")).ok
    }

    private fun time(s: String) = try { OffsetDateTime.parse(s).toInstant().toEpochMilli() } catch (_: Exception) { 0L }

    companion object {
        // Public client config, same as kulia.live
        private const val URL = "https://ptsopuwxpogqgnaazyer.supabase.co"
        private const val KEY = "sb_publishable_LKZS4deN4E3z7zH-EKP2KQ_zQ38JkH_"
        private const val SESSION = "kulia.session"
        private val WIB = ZoneOffset.ofHours(7)
    }
}
