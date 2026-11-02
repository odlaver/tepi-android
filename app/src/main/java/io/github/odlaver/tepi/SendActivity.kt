package io.github.odlaver.tepi

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.widget.Toast
import java.io.InputStream

// Share sheet: "Send to laptop"; enabled only while paired
class SendActivity : Activity() {
    @Suppress("DEPRECATION")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val i = intent
        val uris: List<Uri> = when (i?.action) {
            Intent.ACTION_SEND -> listOfNotNull(if (Build.VERSION.SDK_INT >= 33) i.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java) else i.getParcelableExtra(Intent.EXTRA_STREAM))
            Intent.ACTION_SEND_MULTIPLE -> (if (Build.VERSION.SDK_INT >= 33) i.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java) else i.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)).orEmpty()
            else -> emptyList()
        }
        sendUris(this, uris)
        finish()
    }

    companion object {
        // Open now: a read grant can end with the caller
        fun sendUris(ctx: Context, uris: List<Uri>) {
            val pc = Listener.instance?.pc
            if (pc == null || !pc.linked || uris.isEmpty()) {
                Toast.makeText(ctx, ctx.getString(R.string.pc_send_failed), Toast.LENGTH_SHORT).show()
                return
            }
            val jobs = uris.mapNotNull { u ->
                try {
                    var name = "berkas"
                    var size = -1L
                    ctx.contentResolver.query(u, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                        if (c.moveToFirst()) {
                            c.getString(0)?.let { name = it }
                            if (!c.isNull(1)) size = c.getLong(1)
                        }
                    }
                    ctx.contentResolver.openInputStream(u)?.let { Triple(name, size, it) }
                } catch (_: Exception) { null }
            }
            send(ctx, jobs)
        }

        // One file at a time, result toast per file
        fun send(ctx: Context, jobs: List<Triple<String, Long, InputStream>>) {
            val pc = Listener.instance?.pc
            if (pc == null || !pc.linked || jobs.isEmpty()) {
                Toast.makeText(ctx, ctx.getString(R.string.pc_send_failed), Toast.LENGTH_SHORT).show()
                return
            }
            Toast.makeText(ctx, ctx.getString(R.string.pc_sending), Toast.LENGTH_SHORT).show()
            val app = ctx.applicationContext
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
        }
    }
}

// In-app picker: choose files, send to the laptop
class PickSendActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) return
        val pick = Intent(Intent.ACTION_OPEN_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType("*/*")
            .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(pick, PICK)
        } catch (_: Exception) {
            finish()
        }
    }

    @Deprecated("Platform callback")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == PICK && resultCode == RESULT_OK && data != null) {
            val uris = ArrayList<Uri>()
            data.clipData?.let { c -> for (k in 0 until c.itemCount) c.getItemAt(k).uri?.let { uris.add(it) } }
            if (uris.isEmpty()) data.data?.let { uris.add(it) }
            SendActivity.sendUris(this, uris)
        }
        finish()
    }

    companion object {
        private const val PICK = 41
    }
}
