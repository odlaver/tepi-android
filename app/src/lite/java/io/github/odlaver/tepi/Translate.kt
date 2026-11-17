package io.github.odlaver.tepi

import android.content.Context
import android.content.Intent
import android.os.Build

// Lite: hand text to the installed translator
object Translator {
    private const val GOOGLE = "com.google.android.apps.translate"

    private fun intents(text: String): List<Intent> {
        val out = ArrayList<Intent>()
        if (Build.VERSION.SDK_INT >= 29) out.add(Intent(Intent.ACTION_TRANSLATE).putExtra(Intent.EXTRA_TEXT, text))
        out.add(Intent(Intent.ACTION_PROCESS_TEXT).setType("text/plain").setPackage(GOOGLE)
            .putExtra(Intent.EXTRA_PROCESS_TEXT, text).putExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, true))
        out.add(Intent(Intent.ACTION_SEND).setType("text/plain").setPackage(GOOGLE).putExtra(Intent.EXTRA_TEXT, text))
        return out
    }

    private fun first(context: Context, text: String): Intent? =
        intents(text).firstOrNull { it.resolveActivity(context.packageManager) != null }

    fun available(context: Context) = first(context, "") != null

    fun offer(context: Context, text: String): Boolean {
        val i = first(context, text) ?: return false
        return try { context.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true } catch (_: Exception) { false }
    }
}
