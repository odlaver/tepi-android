package io.github.odlaver.tepi.scene

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.RectF
import io.github.odlaver.tepi.Ui

// What scenes may ask of the island
interface Host {
    val ui: Ui
    val ctx: Context
    fun refresh()
    fun expand(scene: Scene)
    fun collapse()
    fun open(i: Intent)
    fun send(p: PendingIntent)
    fun haptic()
    fun input(hint: String, initial: String = "", done: (String) -> Unit)
}

// One activity the island can show
abstract class Scene(protected val host: Host) {
    protected val ui get() = host.ui

    abstract val id: String
    open val priority = 0

    // Position among swipe tabs, low first
    open val order = 50

    // Shown without being opened
    abstract fun live(): Boolean

    // Auto-opened card instead of compact
    open fun card() = false

    // Panel stays open while not live
    open fun keepOpen() = false

    // Shown as a swipe page when the island is open
    open fun tab() = live() && !card()

    // Compact: zones beside the camera
    open fun wing() = 0f
    abstract fun drawCompact(c: Canvas, left: RectF, right: RectF, a: Float)

    // Optional row below the status bar
    open fun rowWidth() = 0f
    open fun drawRow(c: Canvas, r: RectF, a: Float) {}

    // Small circle when second in line
    open fun drawBubble(c: Canvas, r: RectF, a: Float) {}

    abstract fun panelHeight(w: Float): Float
    abstract fun drawPanel(c: Canvas, r: RectF, a: Float)

    // True when handled
    open fun tap(x: Float, y: Float) = false
    open fun tapCompact() = false

    open fun dismiss() {}

    // Needs frames while shown
    open fun ticking() = false

    // Per-frame animation; true while moving
    open fun step(dt: Float) = false

    open fun describe() = ""
}
