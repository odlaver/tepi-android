package io.github.odlaver.tepi

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

// Focusable card under the island for typing
@SuppressLint("ViewConstructor")
class Input(context: Context, private val type: Int, private val ui: Ui) : LinearLayout(context) {
    private val wm = context.getSystemService(WindowManager::class.java)
    private var shown = false
    private var done: ((String) -> Unit)? = null
    private val hint = TextView(context)
    private val field = EditText(context)

    init {
        orientation = VERTICAL
        val p = ui.s16.toInt()
        setPadding(ui.pad.toInt(), p, ui.pad.toInt(), p)
        background = GradientDrawable().apply { setColor(Color.BLACK); cornerRadius = ui.radiusPanel }
        hint.setTextColor(ui.sub)
        hint.textSize = 13f
        addView(hint)
        field.setTextColor(ui.ink)
        field.setHintTextColor(ui.faint)
        field.textSize = 16f
        field.background = GradientDrawable().apply { setColor(ui.fill); cornerRadius = ui.radiusButton }
        field.setPadding(p, ui.s12.toInt(), p, ui.s12.toInt())
        field.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        field.maxLines = 4
        field.imeOptions = EditorInfo.IME_ACTION_SEND
        field.setOnEditorActionListener { _, id, _ -> if (id == EditorInfo.IME_ACTION_SEND) { finish(true); true } else false }
        addView(field, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = ui.s8.toInt() })
        val row = LinearLayout(context).apply { orientation = HORIZONTAL; gravity = Gravity.END }
        row.addView(button(context.getString(R.string.cancel), false) { finish(false) })
        row.addView(button(context.getString(R.string.send), true) { finish(true) },
            LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = ui.s8.toInt() })
        addView(row, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = ui.s12.toInt() })
    }

    private fun button(label: String, primary: Boolean, onClick: () -> Unit) = TextView(context).apply {
        text = label
        textSize = 14f
        typeface = android.graphics.Typeface.DEFAULT_BOLD
        setTextColor(if (primary) Color.BLACK else ui.ink)
        gravity = Gravity.CENTER
        minHeight = ui.dp(44f).toInt()
        minWidth = ui.dp(88f).toInt()
        setPadding(ui.s16.toInt(), 0, ui.s16.toInt(), 0)
        background = GradientDrawable().apply { setColor(if (primary) ui.ink else ui.fill); cornerRadius = ui.radiusButton }
        isFocusable = true
        setOnClickListener { onClick() }
    }

    fun show(hintText: String, initial: String, x: Int, y: Int, width: Int, onDone: (String) -> Unit) {
        if (shown) finish(false)
        done = onDone
        hint.text = hintText
        field.setText(initial)
        field.setSelection(initial.length)
        val lp = WindowManager.LayoutParams(
            width, WindowManager.LayoutParams.WRAP_CONTENT, type,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            this.x = x
            this.y = y
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE or WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING
            title = "Tepi input"
        }
        wm.addView(this, lp)
        shown = true
        field.requestFocus()
        field.postDelayed({
            context.getSystemService(InputMethodManager::class.java).showSoftInput(field, InputMethodManager.SHOW_IMPLICIT)
        }, 120)
    }

    private fun finish(send: Boolean) {
        if (!shown) return
        val text = if (send) field.text.toString().trim() else ""
        context.getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(field.windowToken, 0)
        try { wm.removeView(this) } catch (_: Exception) { }
        shown = false
        val cb = done
        done = null
        cb?.invoke(text)
    }

    fun close() = finish(false)

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) { finish(false); return true }
        return super.dispatchKeyEvent(event)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_OUTSIDE) { finish(false); return true }
        return super.onTouchEvent(event)
    }
}
