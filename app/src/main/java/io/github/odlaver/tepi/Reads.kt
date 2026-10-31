package io.github.odlaver.tepi

import android.content.Intent
import android.net.Uri

// What each permission lets Tepi read, and where data goes
class Reads : Page() {
    override fun build() {
        header(getString(R.string.reads))
        note(getString(R.string.reads_intro))
        for ((title, body, file) in listOf(
            Triple(R.string.perm_listener, R.string.reads_listener, "Listener.kt"),
            Triple(R.string.perm_access, R.string.reads_access, "Access.kt"),
            Triple(R.string.perm_overlay, R.string.reads_overlay, "Island.kt"),
            Triple(R.string.lyrics_online, R.string.reads_lyrics, "Lyrics.kt"),
            Triple(R.string.shelf, R.string.reads_shelf, "Shelf.kt"),
        )) {
            label(getString(title))
            val g = group()
            row(g, getString(body), getString(R.string.reads_code, file)) {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("$SRC/$file")))
            }
        }
        label(getString(R.string.reads_never_title))
        val g = group()
        row(g, getString(R.string.reads_never))
    }

    companion object {
        private const val SRC = "https://github.com/odlaver/tepi/blob/android/android/app/src/main/java/io/github/odlaver/tepi"
    }
}
