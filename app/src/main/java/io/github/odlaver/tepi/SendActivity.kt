package io.github.odlaver.tepi

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.widget.Toast

// Share sheet: "Send to laptop"; enabled only while paired
class SendActivity : Activity() {
    @Suppress("DEPRECATION")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pc = Listener.instance?.pc
        val i = intent
        val uris: List<Uri> = when (i?.action) {
            Intent.ACTION_SEND -> listOfNotNull(if (Build.VERSION.SDK_INT >= 33) i.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java) else i.getParcelableExtra(Intent.EXTRA_STREAM))
            Intent.ACTION_SEND_MULTIPLE -> (if (Build.VERSION.SDK_INT >= 33) i.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java) else i.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)).orEmpty()
            else -> emptyList()
        }
        if (pc == null || !pc.paired || uris.isEmpty()) {
            Toast.makeText(this, getString(R.string.pc_send_failed), Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        // Open now: the read grant ends with this activity
        val jobs = uris.mapNotNull { u ->
            try {
                var name = "berkas"
                var size = -1L
                contentResolver.query(u, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                    if (c.moveToFirst()) {
                        c.getString(0)?.let { name = it }
                        if (!c.isNull(1)) size = c.getLong(1)
                    }
                }
                contentResolver.openInputStream(u)?.let { Triple(name, size, it) }
            } catch (_: Exception) { null }
        }
        Toast.makeText(this, getString(R.string.pc_sending), Toast.LENGTH_SHORT).show()
        val app = applicationContext
        val main = Handler(Looper.getMainLooper())
        Thread({
            for ((name, size, input) in jobs) {
                val ok = pc.sendFile(name, size, input)
                main.post {
                    val l = Listener.instance
                    if (l != null) l.sentToLaptop(ok, name)
                    else Toast.makeText(app, app.getString(if (ok) R.string.pc_sent else R.string.pc_send_failed) + " · " + name, Toast.LENGTH_SHORT).show()
                }
            }
        }, "pc send").start()
        finish()
    }
}
