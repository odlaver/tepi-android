package io.github.odlaver.tepi

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast

// Share sheet and text selection: into the shelf
class ShareActivity : Activity() {
    @Suppress("DEPRECATION")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val shelf = Shelf(this)
        val i = intent
        var ok = false
        var tooBig = false
        when (i?.action) {
            Intent.ACTION_PROCESS_TEXT -> i.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString()?.let { shelf.addText(it); ok = true }
            Intent.ACTION_SEND -> {
                val stream: Uri? = if (Build.VERSION.SDK_INT >= 33) i.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java) else i.getParcelableExtra(Intent.EXTRA_STREAM)
                if (stream != null) { ok = shelf.addFile(stream); tooBig = !ok }
                else i.getStringExtra(Intent.EXTRA_TEXT)?.let { shelf.addText(it); ok = true }
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                val list: List<Uri>? = if (Build.VERSION.SDK_INT >= 33) i.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java) else i.getParcelableArrayListExtra(Intent.EXTRA_STREAM)
                list?.forEach { if (shelf.addFile(it)) ok = true else tooBig = true }
            }
        }
        val msg = getString(when {
            ok -> R.string.shelf_saved
            tooBig -> R.string.shelf_too_big
            else -> R.string.shelf_failed
        })
        if (!Listener.shelved(msg)) Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        finish()
    }
}
