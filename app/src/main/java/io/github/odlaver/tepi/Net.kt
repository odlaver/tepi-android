package io.github.odlaver.tepi

import java.net.HttpURLConnection
import java.net.URL

class Reply(val status: Int, val body: String) {
    val ok get() = status in 200..299
}

// HTTPS only, capped body
object Net {
    private const val MAX = 5 * 1024 * 1024

    fun send(method: String, url: String, headers: Map<String, String> = emptyMap(), body: String? = null, timeout: Int = 15000): Reply {
        if (!url.startsWith("https://")) return Reply(0, "")
        return try {
            val c = URL(url).openConnection() as HttpURLConnection
            setMethod(c, method)
            c.connectTimeout = timeout
            c.readTimeout = timeout
            c.setRequestProperty("User-Agent", "Tepi Android (github.com/odlaver/tepi-android)")
            for ((k, v) in headers) c.setRequestProperty(k, v)
            if (body != null) {
                c.doOutput = true
                c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            try {
                val code = c.responseCode
                val stream = if (code in 200..299) c.inputStream else c.errorStream
                val text = stream?.use { s ->
                    val out = java.io.ByteArrayOutputStream()
                    val buf = ByteArray(16 * 1024)
                    while (out.size() < MAX) {
                        val n = s.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                    }
                    out.toString("UTF-8")
                } ?: ""
                Reply(code, text)
            } finally {
                c.disconnect()
            }
        } catch (_: Exception) {
            Reply(0, "")
        }
    }

    // HttpURLConnection refuses PATCH; set the field directly
    private fun setMethod(c: HttpURLConnection, method: String) {
        if (method != "PATCH") { c.requestMethod = method; return }
        c.requestMethod = "POST"
        try {
            val f = HttpURLConnection::class.java.getDeclaredField("method")
            f.isAccessible = true
            f.set(c, "PATCH")
        } catch (_: Exception) {
            c.setRequestProperty("X-HTTP-Method-Override", "PATCH")
        }
    }
}
