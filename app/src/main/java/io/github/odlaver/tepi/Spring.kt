package io.github.odlaver.tepi

import kotlin.math.abs

// Damped spring; slight overshoot at defaults
class Spring(var value: Float, private val eps: Float, private val k: Float = 380f, private val c: Float = 32f) {
    var target = value
    private var v = 0f

    // True while still moving
    fun step(dt: Float): Boolean {
        var left = dt
        while (left > 0f) {
            val h = minOf(left, 1f / 240f)
            v += (k * (target - value) - c * v) * h
            value += v * h
            left -= h
        }
        if (abs(target - value) < eps && abs(v) < eps * 10f) {
            value = target
            v = 0f
            return false
        }
        return true
    }

    fun snap(x: Float) {
        value = x
        target = x
        v = 0f
    }
}
