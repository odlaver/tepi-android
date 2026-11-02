package io.github.odlaver.tepi

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class ShelfItem(val id: Long, val text: String?, val name: String?, val mime: String?, val file: String?) {
    val isText get() = text != null
}

// Rak: shared text and files, on the phone only
class Shelf(private val context: Context) {
    private val dir = File(context.filesDir, "shelf").apply { mkdirs() }
    private val sp = context.getSharedPreferences("shelf", Context.MODE_PRIVATE)

    fun all(): List<ShelfItem> {
        val arr = try { JSONArray(sp.getString("items", "[]")) } catch (_: Exception) { JSONArray() }
        return (0 until arr.length()).mapNotNull { i ->
            arr.optJSONObject(i)?.let { o ->
                fun opt(k: String) = if (o.has(k)) o.optString(k) else null
                ShelfItem(o.optLong("id"), opt("text"), opt("name"), opt("mime"), opt("file"))
            }
        }
    }

    fun addText(text: String) {
        if (text.isBlank()) return
        val list = all().filterNot { it.text == text }
        save(listOf(ShelfItem(System.currentTimeMillis(), text.take(100_000), null, null, null)) + list)
    }

    // Copied in, so the source app may go away
    fun addFile(uri: Uri): Boolean {
        val cr = context.contentResolver
        var name = uri.lastPathSegment ?: "file"
        var size = -1L
        try {
            cr.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    c.getString(0)?.let { name = it }
                    if (!c.isNull(1)) size = c.getLong(1)
                }
            }
        } catch (_: Exception) { }
        if (size > MAX_BYTES) return false
        val id = System.currentTimeMillis()
        val out = File(dir, id.toString())
        try {
            val input = cr.openInputStream(uri) ?: return false
            input.use { inp ->
                out.outputStream().use { o ->
                    val buf = ByteArray(64 * 1024)
                    var total = 0L
                    while (true) {
                        val n = inp.read(buf)
                        if (n < 0) break
                        total += n
                        if (total > MAX_BYTES) break
                        o.write(buf, 0, n)
                    }
                    if (total > MAX_BYTES) { out.delete(); return false }
                }
            }
        } catch (_: Exception) { out.delete(); return false }
        save(listOf(ShelfItem(id, null, name.replace('/', '_'), cr.getType(uri) ?: "application/octet-stream", out.name)) + all())
        return true
    }

    fun remove(item: ShelfItem) {
        item.file?.let { File(dir, it).delete() }
        save(all().filterNot { it.id == item.id })
    }

    fun copy(item: ShelfItem) {
        val cm = context.getSystemService(ClipboardManager::class.java)
        if (item.isText) cm.setPrimaryClip(ClipData.newPlainText("Tepi", item.text))
        else cm.setPrimaryClip(ClipData.newUri(context.contentResolver, item.name, uri(item)))
    }

    fun openIntent(item: ShelfItem): Intent =
        Intent(Intent.ACTION_VIEW).setDataAndType(uri(item), item.mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)

    fun shareIntent(item: ShelfItem): Intent {
        val send = Intent(Intent.ACTION_SEND)
        if (item.isText) send.setType("text/plain").putExtra(Intent.EXTRA_TEXT, item.text)
        else send.setType(item.mime).putExtra(Intent.EXTRA_STREAM, uri(item)).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return Intent.createChooser(send, null)
    }

    // Stored copy of a file item
    fun file(item: ShelfItem): File? = item.file?.let { File(dir, it) }?.takeIf { it.exists() }

    private fun uri(item: ShelfItem) = Uri.parse("content://${context.packageName}.shelf/${item.file}")

    private fun save(list: List<ShelfItem>) {
        val keep = list.take(MAX_ITEMS)
        for (gone in list.drop(MAX_ITEMS)) gone.file?.let { File(dir, it).delete() }
        val arr = JSONArray()
        for (i in keep) {
            val o = JSONObject().put("id", i.id)
            i.text?.let { o.put("text", it) }
            i.name?.let { o.put("name", it) }
            i.mime?.let { o.put("mime", it) }
            i.file?.let { o.put("file", it) }
            arr.put(o)
        }
        sp.edit().putString("items", arr.toString()).apply()
    }

    companion object {
        const val MAX_BYTES = 25L * 1024 * 1024
        const val MAX_ITEMS = 20
    }
}

// Read-only access to shelf files, by grant only
class ShelfProvider : ContentProvider() {
    override fun onCreate() = true

    private fun file(uri: Uri): File? {
        val name = uri.lastPathSegment ?: return null
        if (!name.all { it.isDigit() }) return null
        return File(File(context!!.filesDir, "shelf"), name).takeIf { it.exists() }
    }

    private fun item(uri: Uri) = Shelf(context!!).all().firstOrNull { it.file == uri.lastPathSegment }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        if (mode != "r") return null
        val f = file(uri) ?: return null
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun getType(uri: Uri) = item(uri)?.mime

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? {
        val f = file(uri) ?: return null
        val c = MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE))
        c.addRow(arrayOf<Any>(item(uri)?.name ?: f.name, f.length()))
        return c
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
}
