package io.github.odlaver.tepi

import android.widget.LinearLayout
import java.text.DateFormat
import java.util.Date

// Notes or shelf, listed with their actions
class Items : Page() {
    private val fmt = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)

    override fun build() {
        if (intent.getStringExtra(MODE) == SHELF) shelf() else notes()
    }

    private fun notes() {
        val notes = Notes(this)
        header(getString(R.string.notes))
        addButton(button(getString(R.string.note_new)) { Listener.instance?.command("note"); finish() }, 8f)
        val list = notes.all()
        if (list.isEmpty()) { note(getString(R.string.notes_empty)); return }
        label(getString(R.string.notes_count, list.size))
        val g = group()
        for (n in list) {
            val box = row(g, n.text, (if (n.pinned) getString(R.string.note_pinned) + " · " else "") + fmt.format(Date(n.at)))
            actions(box, listOf(
                getString(if (n.pinned) R.string.unpin else R.string.pin) to { notes.pin(n); rebuild() },
                getString(R.string.delete) to { notes.remove(n); rebuild() },
            ))
        }
    }

    private fun shelf() {
        val shelf = Shelf(this)
        header(getString(R.string.shelf))
        note(getString(R.string.shelf_how))
        val list = shelf.all()
        if (list.isEmpty()) { note(getString(R.string.shelf_empty)); return }
        label(getString(R.string.shelf_count, list.size))
        val g = group()
        for (it in list) {
            val title = if (it.isText) it.text!!.take(200) else it.name ?: "file"
            val box = row(g, title, fmt.format(Date(it.id)))
            val acts = ArrayList<Pair<String, () -> Unit>>()
            acts.add(getString(R.string.copy) to { shelf.copy(it) })
            if (!it.isText) acts.add(getString(R.string.open) to { try { startActivity(shelf.openIntent(it)) } catch (_: Exception) { } })
            acts.add(getString(R.string.share) to { startActivity(shelf.shareIntent(it)) })
            acts.add(getString(R.string.delete) to { shelf.remove(it); rebuild() })
            actions(box, acts)
        }
    }

    // Small action buttons under a row's text
    private fun actions(box: LinearLayout, acts: List<Pair<String, () -> Unit>>) {
        val texts = box.getChildAt(0) as LinearLayout
        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dpi(10f), 0, 0) }
        acts.forEachIndexed { i, (label, act) ->
            bar.addView(button(label, false, act), LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                if (i > 0) marginStart = dpi(8f)
            })
        }
        texts.addView(bar)
    }

    companion object {
        const val MODE = "mode"
        const val NOTES = "notes"
        const val SHELF = "shelf"
    }
}
