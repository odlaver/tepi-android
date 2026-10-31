package io.github.odlaver.tepi

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable

enum class Icon {
    Play, Pause, Next, Prev, Stop, Plus, Check, Close, Timer, Note, Gear, Home, Hide, Copy, Share, Open, Reply,
    Flag, Music, Lyrics, Bell, Moon, Drop, Eye, Stretch, Bolt, Phone, Headphones, Download, Nav, Camera, Shield,
    Book, Tasks, Shelf, Info, Code, User, Battery, Layers, Hand, Power, Chat, Link, Pin, Trash, Laptop, Globe
}

// Line icons on a 24-unit grid, drawn with the Ink stroke
object Icons {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val path = Path()
    private val r = RectF()

    fun draw(c: Canvas, icon: Icon, cx: Float, cy: Float, size: Float, color: Int) {
        val k = size / 24f
        c.save()
        c.translate(cx - 12 * k, cy - 12 * k)
        c.scale(k, k)
        p.color = color
        p.strokeWidth = 2f
        p.style = Paint.Style.STROKE
        shape(c, icon)
        c.restore()
    }

    private fun fill() { p.style = Paint.Style.FILL }
    private fun line() { p.style = Paint.Style.STROKE }
    private fun ln(c: Canvas, x1: Float, y1: Float, x2: Float, y2: Float) = c.drawLine(x1, y1, x2, y2, p)
    private fun rr(c: Canvas, l: Float, t: Float, rt: Float, b: Float, rad: Float) { r.set(l, t, rt, b); c.drawRoundRect(r, rad, rad, p) }
    private fun poly(c: Canvas, vararg xy: Float, close: Boolean = false) {
        path.reset()
        path.moveTo(xy[0], xy[1])
        var i = 2
        while (i < xy.size) { path.lineTo(xy[i], xy[i + 1]); i += 2 }
        if (close) path.close()
        c.drawPath(path, p)
    }

    private fun shape(c: Canvas, i: Icon) {
        when (i) {
            Icon.Play -> { fill(); poly(c, 7f, 4.5f, 19f, 12f, 7f, 19.5f, close = true) }
            Icon.Pause -> { fill(); rr(c, 6f, 4.5f, 10f, 19.5f, 1.2f); rr(c, 14f, 4.5f, 18f, 19.5f, 1.2f) }
            Icon.Next -> { fill(); poly(c, 5f, 5f, 15f, 12f, 5f, 19f, close = true); rr(c, 16.5f, 5f, 19f, 19f, 1f) }
            Icon.Prev -> { fill(); poly(c, 19f, 5f, 9f, 12f, 19f, 19f, close = true); rr(c, 5f, 5f, 7.5f, 19f, 1f) }
            Icon.Stop -> { fill(); rr(c, 6f, 6f, 18f, 18f, 2.5f) }
            Icon.Plus -> { ln(c, 12f, 5f, 12f, 19f); ln(c, 5f, 12f, 19f, 12f) }
            Icon.Check -> poly(c, 5f, 12.5f, 10f, 17.5f, 19f, 7f)
            Icon.Close -> { ln(c, 6f, 6f, 18f, 18f); ln(c, 18f, 6f, 6f, 18f) }
            Icon.Timer -> { c.drawCircle(12f, 13.5f, 7.5f, p); ln(c, 12f, 13.5f, 12f, 9.5f); ln(c, 10f, 3f, 14f, 3f); ln(c, 18.5f, 6f, 19.8f, 4.8f) }
            Icon.Note -> { poly(c, 15f, 4f, 20f, 9f, 9f, 20f, 4f, 20f, 4f, 15f, close = true); ln(c, 13f, 6f, 18f, 11f) }
            Icon.Gear -> {
                c.drawCircle(12f, 12f, 3f, p)
                for (a in 0 until 8) {
                    val ang = Math.toRadians(a * 45.0)
                    ln(c, 12f + 6.2f * Math.cos(ang).toFloat(), 12f + 6.2f * Math.sin(ang).toFloat(), 12f + 8.6f * Math.cos(ang).toFloat(), 12f + 8.6f * Math.sin(ang).toFloat())
                }
                c.drawCircle(12f, 12f, 6.2f, p)
            }
            Icon.Home -> { poly(c, 4f, 11f, 12f, 4f, 20f, 11f); poly(c, 6f, 9.5f, 6f, 20f, 18f, 20f, 18f, 9.5f); rr(c, 10f, 14f, 14f, 20f, 1f) }
            Icon.Hide -> { path.reset(); path.moveTo(3f, 12f); path.quadTo(12f, 3f, 21f, 12f); path.quadTo(12f, 21f, 3f, 12f); c.drawPath(path, p); ln(c, 4f, 4f, 20f, 20f) }
            Icon.Eye -> { path.reset(); path.moveTo(3f, 12f); path.quadTo(12f, 3f, 21f, 12f); path.quadTo(12f, 21f, 3f, 12f); c.drawPath(path, p); c.drawCircle(12f, 12f, 2.8f, p) }
            Icon.Copy -> { rr(c, 8f, 8f, 19f, 19f, 2f); poly(c, 5f, 15f, 5f, 5f, 15f, 5f) }
            Icon.Share -> { poly(c, 12f, 15f, 12f, 3.5f); poly(c, 7.5f, 8f, 12f, 3.5f, 16.5f, 8f); poly(c, 5f, 12f, 5f, 20f, 19f, 20f, 19f, 12f) }
            Icon.Open -> { poly(c, 13f, 4f, 20f, 4f, 20f, 11f); ln(c, 20f, 4f, 11f, 13f); poly(c, 17f, 14f, 17f, 20f, 4f, 20f, 4f, 7f, 10f, 7f) }
            Icon.Reply -> { poly(c, 9f, 5f, 3.5f, 10.5f, 9f, 16f); path.reset(); path.moveTo(3.5f, 10.5f); path.lineTo(13f, 10.5f); path.quadTo(20.5f, 10.5f, 20.5f, 19f); c.drawPath(path, p) }
            Icon.Flag -> { ln(c, 6f, 21f, 6f, 4f); poly(c, 6f, 4.5f, 18f, 4.5f, 15f, 9f, 18f, 13.5f, 6f, 13.5f) }
            Icon.Music -> { ln(c, 9f, 17f, 9f, 5f); ln(c, 9f, 5f, 19f, 3.5f); ln(c, 19f, 3.5f, 19f, 15f); fill(); c.drawCircle(6.5f, 17.5f, 2.8f, p); c.drawCircle(16.5f, 15.5f, 2.8f, p) }
            Icon.Lyrics -> { ln(c, 4f, 7f, 20f, 7f); ln(c, 4f, 12f, 16f, 12f); ln(c, 4f, 17f, 12f, 17f) }
            Icon.Bell -> { path.reset(); path.moveTo(5f, 17f); path.lineTo(6.5f, 15f); path.lineTo(6.5f, 10.5f); path.quadTo(6.5f, 4.5f, 12f, 4.5f); path.quadTo(17.5f, 4.5f, 17.5f, 10.5f); path.lineTo(17.5f, 15f); path.lineTo(19f, 17f); path.close(); c.drawPath(path, p); ln(c, 10f, 20.5f, 14f, 20.5f) }
            Icon.Moon -> { path.reset(); path.moveTo(15f, 3.5f); path.cubicTo(8f, 4f, 5f, 12f, 9f, 17f); path.cubicTo(12f, 20.5f, 18f, 20.5f, 20.5f, 15.5f); path.cubicTo(14.5f, 17f, 10.5f, 10f, 15f, 3.5f); c.drawPath(path, p) }
            Icon.Drop -> { path.reset(); path.moveTo(12f, 3.5f); path.cubicTo(17f, 9f, 18.5f, 12f, 18.5f, 14.5f); path.cubicTo(18.5f, 18.5f, 15.5f, 20.5f, 12f, 20.5f); path.cubicTo(8.5f, 20.5f, 5.5f, 18.5f, 5.5f, 14.5f); path.cubicTo(5.5f, 12f, 7f, 9f, 12f, 3.5f); c.drawPath(path, p) }
            Icon.Stretch -> { c.drawCircle(12f, 4.8f, 1.8f, p); poly(c, 4f, 9f, 12f, 10f, 20f, 9f); poly(c, 12f, 10f, 12f, 14.5f); poly(c, 8f, 21f, 12f, 14.5f, 16f, 21f) }
            Icon.Bolt -> { fill(); poly(c, 13.5f, 2.5f, 5f, 13.5f, 11f, 13.5f, 10f, 21.5f, 19f, 10f, 13f, 10f, close = true) }
            Icon.Phone -> { path.reset(); path.moveTo(5f, 4f); path.lineTo(9f, 4f); path.lineTo(10.5f, 8.5f); path.lineTo(8.3f, 10f); path.quadTo(10f, 14f, 14f, 15.7f); path.lineTo(15.5f, 13.5f); path.lineTo(20f, 15f); path.lineTo(20f, 19f); path.quadTo(20f, 20.5f, 18f, 20.5f); path.quadTo(4.5f, 19.5f, 3.5f, 6f); path.quadTo(3.5f, 4f, 5f, 4f); path.close(); c.drawPath(path, p) }
            Icon.Headphones -> { path.reset(); path.moveTo(4f, 15f); path.lineTo(4f, 12f); path.quadTo(4f, 4f, 12f, 4f); path.quadTo(20f, 4f, 20f, 12f); path.lineTo(20f, 15f); c.drawPath(path, p); rr(c, 3.5f, 14f, 8f, 20.5f, 1.5f); rr(c, 16f, 14f, 20.5f, 20.5f, 1.5f) }
            Icon.Download -> { ln(c, 12f, 4f, 12f, 15f); poly(c, 7.5f, 10.5f, 12f, 15f, 16.5f, 10.5f); ln(c, 5f, 20f, 19f, 20f) }
            Icon.Nav -> { fill(); poly(c, 12f, 3f, 19.5f, 20f, 12f, 16f, 4.5f, 20f, close = true) }
            Icon.Camera -> { rr(c, 3f, 7f, 21f, 19f, 2.5f); poly(c, 8.5f, 7f, 10f, 4.5f, 14f, 4.5f, 15.5f, 7f); c.drawCircle(12f, 13f, 3.3f, p) }
            Icon.Shield -> { path.reset(); path.moveTo(12f, 3f); path.lineTo(19.5f, 6f); path.lineTo(19.5f, 11.5f); path.quadTo(19.5f, 17.5f, 12f, 21f); path.quadTo(4.5f, 17.5f, 4.5f, 11.5f); path.lineTo(4.5f, 6f); path.close(); c.drawPath(path, p); poly(c, 9f, 12f, 11.2f, 14.2f, 15f, 10f) }
            Icon.Book -> { poly(c, 12f, 6.5f, 12f, 20f); path.reset(); path.moveTo(12f, 6.5f); path.quadTo(8f, 4f, 3.5f, 5f); path.lineTo(3.5f, 18.5f); path.quadTo(8f, 17.5f, 12f, 20f); path.quadTo(16f, 17.5f, 20.5f, 18.5f); path.lineTo(20.5f, 5f); path.quadTo(16f, 4f, 12f, 6.5f); c.drawPath(path, p) }
            Icon.Tasks -> { poly(c, 4f, 7f, 5.5f, 8.5f, 8.5f, 5.5f); ln(c, 11.5f, 7f, 20f, 7f); poly(c, 4f, 15f, 5.5f, 16.5f, 8.5f, 13.5f); ln(c, 11.5f, 15f, 20f, 15f) }
            Icon.Shelf -> { poly(c, 3.5f, 13f, 7f, 5f, 17f, 5f, 20.5f, 13f); rr(c, 3.5f, 13f, 20.5f, 19.5f, 2f); poly(c, 3.5f, 13f, 9f, 13f, 10f, 15.5f, 14f, 15.5f, 15f, 13f, 20.5f, 13f) }
            Icon.Info -> { c.drawCircle(12f, 12f, 8.5f, p); ln(c, 12f, 11f, 12f, 16.5f); fill(); c.drawCircle(12f, 7.8f, 1.2f, p) }
            Icon.Code -> { poly(c, 8f, 7f, 3f, 12f, 8f, 17f); poly(c, 16f, 7f, 21f, 12f, 16f, 17f); ln(c, 13.5f, 5f, 10.5f, 19f) }
            Icon.User -> { c.drawCircle(12f, 8.5f, 3.8f, p); path.reset(); path.moveTo(4.5f, 20f); path.quadTo(5.5f, 14f, 12f, 14f); path.quadTo(18.5f, 14f, 19.5f, 20f); c.drawPath(path, p) }
            Icon.Battery -> { rr(c, 3f, 7.5f, 18.5f, 16.5f, 2f); ln(c, 21f, 10.5f, 21f, 13.5f); fill(); rr(c, 5.5f, 10f, 11f, 14f, 0.8f) }
            Icon.Layers -> { poly(c, 12f, 4f, 21f, 9f, 12f, 14f, 3f, 9f, close = true); poly(c, 3f, 13.5f, 12f, 18.5f, 21f, 13.5f) }
            Icon.Hand -> { path.reset(); path.moveTo(8f, 12f); path.lineTo(8f, 5.5f); path.quadTo(8f, 4f, 9.5f, 4f); path.quadTo(11f, 4f, 11f, 5.5f); path.lineTo(11f, 11f); path.lineTo(11f, 4.5f); path.quadTo(11f, 3f, 12.5f, 3f); path.quadTo(14f, 3f, 14f, 4.5f); path.lineTo(14f, 11f); path.lineTo(14f, 6f); path.quadTo(14f, 4.5f, 15.5f, 4.5f); path.quadTo(17f, 4.5f, 17f, 6f); path.lineTo(17f, 14f); path.quadTo(17f, 20.5f, 11.5f, 20.5f); path.quadTo(8f, 20.5f, 5.5f, 16f); path.lineTo(4.5f, 13f); path.quadTo(4.5f, 11f, 6.5f, 11.5f); path.lineTo(8f, 13.5f); c.drawPath(path, p) }
            Icon.Power -> { ln(c, 12f, 3.5f, 12f, 11.5f); r.set(4.5f, 5.5f, 19.5f, 20.5f); c.drawArc(r, -55f, 290f, false, p) }
            Icon.Chat -> { path.reset(); path.moveTo(4f, 6f); path.quadTo(4f, 4f, 6f, 4f); path.lineTo(18f, 4f); path.quadTo(20f, 4f, 20f, 6f); path.lineTo(20f, 14f); path.quadTo(20f, 16f, 18f, 16f); path.lineTo(10f, 16f); path.lineTo(5.5f, 20f); path.lineTo(5.5f, 16f); path.quadTo(4f, 16f, 4f, 14f); path.close(); c.drawPath(path, p) }
            Icon.Link -> { rr(c, 3f, 9f, 13f, 15f, 3f); rr(c, 11f, 9f, 21f, 15f, 3f) }
            Icon.Pin -> { path.reset(); path.moveTo(12f, 21f); path.quadTo(5f, 14f, 5f, 9.5f); path.quadTo(5f, 3f, 12f, 3f); path.quadTo(19f, 3f, 19f, 9.5f); path.quadTo(19f, 14f, 12f, 21f); c.drawPath(path, p); c.drawCircle(12f, 9.5f, 2.5f, p) }
            Icon.Trash -> { ln(c, 4f, 6.5f, 20f, 6.5f); poly(c, 9f, 6.5f, 9.5f, 3.5f, 14.5f, 3.5f, 15f, 6.5f); poly(c, 6f, 6.5f, 7f, 20.5f, 17f, 20.5f, 18f, 6.5f) }
            Icon.Laptop -> { rr(c, 5f, 5f, 19f, 15f, 1.5f); ln(c, 2.5f, 19f, 21.5f, 19f) }
            Icon.Globe -> { c.drawCircle(12f, 12f, 8.5f, p); ln(c, 3.5f, 12f, 20.5f, 12f); r.set(8f, 3.5f, 16f, 20.5f); c.drawOval(r, p) }
        }
        line()
    }
}

// Icon as a Drawable for settings rows
class IconDrawable(private val icon: Icon, private val color: Int, private val sizePx: Int) : Drawable() {
    override fun draw(canvas: Canvas) {
        val b = bounds
        Icons.draw(canvas, icon, b.exactCenterX(), b.exactCenterY(), minOf(b.width(), b.height()).toFloat(), color)
    }
    override fun getIntrinsicWidth() = sizePx
    override fun getIntrinsicHeight() = sizePx
    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(colorFilter: ColorFilter?) {}
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}
