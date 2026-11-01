package io.github.odlaver.tepi

import android.content.ComponentName
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.Executors
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

class AgentAsk(val id: Int, val agent: String, val project: String, val tool: String, val key: String, val note: String, val detail: String, val flags: List<String>, val truncated: Boolean)
class AgentRun(val project: String, val state: String, val activity: String)

// Tepi on the laptop over the LAN; see src/Phone.cs
class Pc(private val context: Context, private val vault: Vault, private val prefs: Prefs, private val changed: () -> Unit) {
    private val main = Handler(Looper.getMainLooper())
    private val rng = SecureRandom()
    @Volatile private var running = false
    private var thread: Thread? = null
    // One sender: pushes keep their order
    private val out = Executors.newSingleThreadExecutor()
    private val files = Executors.newSingleThreadExecutor()
    private val iconsSent = HashSet<String>()
    private val pulled = HashSet<Int>()
    @Volatile private var lastLevel = -1
    @Volatile private var lastCharging = false

    @Volatile var asks: List<AgentAsk> = emptyList()
        private set
    @Volatile var runs: List<AgentRun> = emptyList()
        private set
    @Volatile var online = false
        private set

    // Laptop file arrived: name, saved uri or null
    var onFile: ((String, Uri?) -> Unit)? = null

    val host get() = prefs.str("pc.host", "")
    val name get() = prefs.str("pc.name", "")
    val paired get() = vault.get("pc.key") != null && host.isNotEmpty()
    var sendNotifs: Boolean
        get() = prefs.bool("pc.notifs", true)
        set(v) = prefs.set("pc.notifs", v)
    var sendOtp: Boolean
        get() = prefs.bool("pc.otp", true)
        set(v) = prefs.set("pc.otp", v)

    private fun key() = vault.get("pc.key")?.let { Base64.decode(it, Base64.NO_WRAP) }

    // Same derivation as the laptop
    private fun derive(code: String): ByteArray {
        val spec = PBEKeySpec(code.replace("-", "").uppercase().toCharArray(), "tepi.phone.v1".toByteArray(), 60000, 256)
        return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
    }

    private fun sub(k: ByteArray, label: String): ByteArray {
        val m = Mac.getInstance("HmacSHA256")
        m.init(SecretKeySpec(k, "HmacSHA256"))
        return m.doFinal(label.toByteArray())
    }

    private fun mac(k: ByteArray, text: String): String {
        val m = Mac.getInstance("HmacSHA256")
        m.init(SecretKeySpec(sub(k, "mac"), "HmacSHA256"))
        return m.doFinal(text.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    private fun seal(k: ByteArray, plain: String): String {
        val iv = ByteArray(16).also { rng.nextBytes(it) }
        val c = Cipher.getInstance("AES/CBC/PKCS5Padding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(sub(k, "enc"), "AES"), IvParameterSpec(iv))
        return Base64.encodeToString(iv + c.doFinal(plain.toByteArray()), Base64.NO_WRAP)
    }

    private fun open(k: ByteArray, text: String): String {
        val all = Base64.decode(text, Base64.NO_WRAP)
        val c = Cipher.getInstance("AES/CBC/PKCS5Padding")
        c.init(Cipher.DECRYPT_MODE, SecretKeySpec(sub(k, "enc"), "AES"), IvParameterSpec(all, 0, 16))
        return String(c.doFinal(all, 16, all.size - 16))
    }

    // Signed, sealed POST; null on any failure
    private fun call(k: ByteArray, host: String, path: String, body: JSONObject, timeout: Int = 8000): JSONObject? {
        return try {
            val text = seal(k, body.toString())
            val time = System.currentTimeMillis().toString()
            val nonce = ByteArray(16).also { rng.nextBytes(it) }.joinToString("") { "%02x".format(it) }
            val c = URL("http://$host$path").openConnection() as HttpURLConnection
            c.requestMethod = "POST"
            c.connectTimeout = 5000
            c.readTimeout = timeout
            c.doOutput = true
            c.setRequestProperty("X-Tepi-Time", time)
            c.setRequestProperty("X-Tepi-Nonce", nonce)
            c.setRequestProperty("X-Tepi-Mac", mac(k, "POST\n$path\n$time\n$nonce\n$text"))
            c.outputStream.use { it.write(text.toByteArray()) }
            try {
                if (c.responseCode != 200) return null
                val reply = c.inputStream.use { it.readBytes().toString(Charsets.US_ASCII) }
                val got = c.getHeaderField("X-Tepi-Mac") ?: return null
                if (!MessageDigest.isEqual(got.toByteArray(), mac(k, "reply\n$nonce\n$reply").toByteArray())) return null
                JSONObject(open(k, reply))
            } finally {
                c.disconnect()
            }
        } catch (_: Exception) { null }
    }

    // Laptop must be on the same Wi-Fi; LAN only
    fun pair(hostPort: String, code: String): Boolean {
        val h = hostPort.trim()
        val ip = h.substringBefore(':')
        if (!isLan(ip)) return false
        val k = derive(code)
        val r = call(k, h, "/phone/pair", JSONObject().put("device", Build.MODEL)) ?: return false
        if (!r.optBoolean("ok")) return false
        vault.put("pc.key", Base64.encodeToString(k, Base64.NO_WRAP))
        prefs.set("pc.host", h)
        prefs.set("pc.name", r.optString("pc"))
        start()
        return true
    }

    fun forget() {
        stop()
        vault.put("pc.key", null)
        prefs.set("pc.host", "")
        prefs.set("pc.name", "")
        shareTarget(false)
        synchronized(iconsSent) { iconsSent.clear() }
        asks = emptyList(); runs = emptyList(); online = false
        main.post { changed() }
    }

    // "Send to laptop" only while paired
    private fun shareTarget(on: Boolean) {
        val state = if (on) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        try {
            context.packageManager.setComponentEnabledSetting(ComponentName(context, SendActivity::class.java), state, PackageManager.DONT_KILL_APP)
        } catch (_: Exception) { }
    }

    fun start() {
        if (running || !paired) return
        running = true
        shareTarget(true)
        thread = Thread({ loop() }, "pc").apply { isDaemon = true; start() }
    }

    fun stop() {
        running = false
        thread?.interrupt()
        thread = null
    }

    private fun loop() {
        var since = -1
        var fails = 0
        while (running) {
            val k = key() ?: break
            val r = call(k, host, "/phone/events", JSONObject().put("since", since), 32000)
            if (r == null) {
                fails++
                if (online) { online = false; main.post { changed() } }
                try { Thread.sleep(if (fails < 3) 3000L else 15000L) } catch (_: InterruptedException) { break }
                continue
            }
            // Back in reach: battery again
            if (!online && lastLevel >= 0) send(JSONObject().put("battery", JSONObject().put("level", lastLevel).put("charging", lastCharging)))
            online = true
            fails = 0
            since = r.optInt("v", since)
            val s = r.optJSONObject("s") ?: JSONObject()
            val a = s.optJSONArray("approvals") ?: JSONArray()
            val newAsks = (0 until a.length()).map { i ->
                val o = a.getJSONObject(i)
                val fl = o.optJSONArray("flags")
                AgentAsk(o.optInt("id"), o.optString("agent"), o.optString("project"), o.optString("tool"), o.optString("key"), o.optString("note"),
                    o.optString("detail"), if (fl == null) emptyList() else (0 until fl.length()).map { fl.optString(it) }, o.optBoolean("truncated"))
            }
            val ss = s.optJSONArray("sessions") ?: JSONArray()
            val newRuns = (0 until ss.length()).map { i -> ss.getJSONObject(i).let { AgentRun(it.optString("project"), it.optString("state"), it.optString("activity")) } }
            main.post { asks = newAsks; runs = newRuns; changed() }
            val offers = r.optJSONArray("o") ?: JSONArray()
            for (i in 0 until offers.length()) {
                val o = offers.getJSONObject(i)
                val id = o.optInt("id")
                if (synchronized(pulled) { pulled.add(id) }) files.execute { pull(id, o.optString("name"), o.optLong("size")) }
            }
        }
    }

    fun decide(id: Int, decision: String) {
        Thread {
            val k = key() ?: return@Thread
            call(k, host, "/phone/decide", JSONObject().put("id", id).put("decision", decision))
        }.start()
        asks = asks.filterNot { it.id == id }
        changed()
    }

    // Fire and forget; dropped while away
    private fun send(body: JSONObject) {
        if (!paired) return
        out.execute { key()?.let { call(it, host, "/phone/push", body) } }
    }

    // Every 5%, plug changes, and the low end
    fun battery(level: Int, charging: Boolean) {
        if (level < 0) return
        val moved = lastLevel < 0 || kotlin.math.abs(level - lastLevel) >= 5 || charging != lastCharging || (level <= 20 && level != lastLevel)
        lastLevel = level
        lastCharging = charging
        if (moved && online) send(JSONObject().put("battery", JSONObject().put("level", level).put("charging", charging)))
    }

    fun notif(key: String, pkg: String, app: String, title: String, text: String, icon: Bitmap?) {
        if (!paired || !sendNotifs || !online) return
        val n = JSONObject().put("key", key).put("pkg", pkg).put("app", app).put("title", title.take(120)).put("text", text.take(400))
        // App icon once per app
        if (icon != null && synchronized(iconsSent) { iconsSent.add(pkg) }) {
            val small = Bitmap.createScaledBitmap(icon, 64, 64, true)
            val png = ByteArrayOutputStream().also { small.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
            n.put("icon", Base64.encodeToString(png, Base64.NO_WRAP))
        }
        send(JSONObject().put("notif", n))
    }

    fun removed(key: String) {
        if (paired && sendNotifs && online) send(JSONObject().put("removed", key))
    }

    fun otp(app: String, code: String) {
        if (paired && sendOtp) send(JSONObject().put("otp", JSONObject().put("app", app).put("code", code)))
    }

    // Phone to laptop, in chunks; false on any failure
    fun sendFile(name: String, size: Long, input: java.io.InputStream): Boolean {
        val k = key() ?: run { input.close(); return false }
        try {
            if (size < 0 || size > MAX) { input.close(); return false }
            val id = ByteArray(12).also { rng.nextBytes(it) }.joinToString("") { "%02x".format(it) }
            input.use { s ->
                val buf = ByteArray(CHUNK)
                var off = 0L
                do {
                    var n = 0
                    while (n < CHUNK) { val r = s.read(buf, n, CHUNK - n); if (r <= 0) break; n += r }
                    val body = JSONObject().put("id", id).put("name", name).put("size", size).put("offset", off).put("data", Base64.encodeToString(buf, 0, n, Base64.NO_WRAP))
                    var r: JSONObject? = null
                    for (attempt in 0 until 3) { r = call(k, host, "/phone/put", body, 20000); if (r != null) break }
                    if (r == null || !r.optBoolean("ok") || n == 0) return false
                    off += n
                } while (off < size)
            }
            return true
        } catch (_: Exception) { return false }
    }

    // Laptop to phone: into Download/Tepi
    private fun pull(id: Int, name: String, size: Long) {
        val k = key() ?: return
        if (size > MAX) return
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name.ifEmpty { "berkas" })
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Tepi")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val res = context.contentResolver
        val uri = try { res.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) } catch (_: Exception) { null }
        if (uri == null) { main.post { onFile?.invoke(name, null) }; return }
        var ok = false
        try {
            res.openOutputStream(uri)?.use { os ->
                var off = 0L
                while (true) {
                    var r: JSONObject? = null
                    for (attempt in 0 until 3) { r = call(k, host, "/phone/get", JSONObject().put("id", id).put("offset", off), 20000); if (r != null) break }
                    if (r == null || !r.optBoolean("ok")) break
                    val data = Base64.decode(r.optString("data"), Base64.NO_WRAP)
                    os.write(data)
                    off += data.size
                    if (r.optBoolean("done")) { ok = off == size; break }
                }
            }
        } catch (_: Exception) { ok = false }
        if (ok) {
            res.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
            main.post { onFile?.invoke(name, uri) }
        } else {
            try { res.delete(uri, null, null) } catch (_: Exception) { }
            main.post { onFile?.invoke(name, null) }
        }
    }

    // Private ranges only; never the internet
    private fun isLan(ip: String): Boolean {
        val p = ip.split('.').mapNotNull { it.toIntOrNull() }
        if (p.size != 4) return false
        return p[0] == 10 || (p[0] == 192 && p[1] == 168) || (p[0] == 172 && p[1] in 16..31)
    }

    companion object {
        const val MAX = 500L * 1024 * 1024
        const val CHUNK = 384 * 1024
    }
}
