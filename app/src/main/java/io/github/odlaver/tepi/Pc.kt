package io.github.odlaver.tepi

import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

class AgentAsk(val id: Int, val agent: String, val project: String, val tool: String, val key: String, val note: String, val detail: String, val flags: List<String>, val truncated: Boolean)
class AgentRun(val project: String, val state: String, val activity: String)

// Tepi on the laptop over the LAN; see src/Phone.cs
class Pc(private val vault: Vault, private val prefs: Prefs, private val changed: () -> Unit) {
    private val main = Handler(Looper.getMainLooper())
    private val rng = SecureRandom()
    @Volatile private var running = false
    private var thread: Thread? = null

    @Volatile var asks: List<AgentAsk> = emptyList()
        private set
    @Volatile var runs: List<AgentRun> = emptyList()
        private set
    @Volatile var online = false
        private set

    val host get() = prefs.str("pc.host", "")
    val name get() = prefs.str("pc.name", "")
    val paired get() = vault.get("pc.key") != null && host.isNotEmpty()

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
        asks = emptyList(); runs = emptyList(); online = false
        main.post { changed() }
    }

    fun start() {
        if (running || !paired) return
        running = true
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
            main.post { asks = newAsks; runs = newRuns; online = true; changed() }
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

    // Private ranges only; never the internet
    private fun isLan(ip: String): Boolean {
        val p = ip.split('.').mapNotNull { it.toIntOrNull() }
        if (p.size != 4) return false
        return p[0] == 10 || (p[0] == 192 && p[1] == 168) || (p[0] == 172 && p[1] in 16..31)
    }
}
