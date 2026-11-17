package io.github.odlaver.tepi

import android.os.Build
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale

// Suggestions go to the Windows Google Form
object Feedback {
    private const val FORM = "https://docs.google.com/forms/d/e/1FAIpQLSdDsBrxC6LIIlq4Itbab9WwW416oyCjpZDQu5JySwDmBdVrUg/formResponse"
    private const val KIND = "entry.467859773"
    private const val TEXT = "entry.617759107"
    private const val CONTACT = "entry.263767280"
    private const val VERSION = "entry.1329022008"
    private const val SYSTEM = "entry.1141987323"

    // 0 sent, -1 offline, else HTTP status
    fun send(kind: String, text: String, contact: String, withSystem: Boolean): Int {
        val lang = if (Locale.getDefault().language == "in" || Locale.getDefault().language == "id") "id" else "en"
        val fields = listOf(
            KIND to kind, TEXT to text, CONTACT to contact,
            VERSION to if (withSystem) "Android ${BuildConfig.VERSION_NAME} · $lang" else "",
            SYSTEM to if (withSystem) "Android ${Build.VERSION.RELEASE} · ${Build.MANUFACTURER} ${Build.MODEL}" else "",
        )
        val body = fields.filter { it.second.isNotEmpty() }
            .joinToString("&") { URLEncoder.encode(it.first, "UTF-8") + "=" + URLEncoder.encode(it.second, "UTF-8") }
        return try {
            val c = URL(FORM).openConnection() as HttpURLConnection
            c.requestMethod = "POST"
            c.connectTimeout = 15000
            c.readTimeout = 20000
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            c.outputStream.use { it.write(body.toByteArray()) }
            val code = c.responseCode
            c.disconnect()
            if (code in 200..399) 0 else code
        } catch (_: Exception) { -1 }
    }
}
