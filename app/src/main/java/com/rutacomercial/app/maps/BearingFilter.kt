package com.rutacomercial.app.maps

import kotlin.math.*

/** Matrix columns are screen right, screen top and screen normal in east/north/up. */
internal fun forwardAzimuth(matrix: FloatArray): Float? {
    if (matrix[8] < -.8f) return null
    // Screen inclination is independent of roll, including a locked portrait display.
    val flat = ((matrix[8] - .35f) / .5f).coerceIn(0f,1f)
    val upright = 1f - flat
    val east = flat * matrix[1] - upright * matrix[2]
    val north = flat * matrix[4] - upright * matrix[5]
    if (east * east + north * north < 0.04f) return null
    return Math.toDegrees(atan2(east,north).toDouble()).toFloat()
}

internal class BearingFilter {
    private var lastTime = 0L
    private var bearing = 0f
    fun reset() { lastTime = 0L }
    fun update(raw: Float, nanos: Long): Float {
        val dt = (nanos-lastTime)/1e9
        val normalized = ((raw % 360f) + 360f) % 360f
        val delta = (normalized-bearing+540f)%360f-180f
        bearing = if(lastTime == 0L || dt !in 0.0..1.0) raw
            else bearing + delta * (1.0-exp(-dt/0.30)).toFloat()
        lastTime = nanos
        bearing = (bearing+360f)%360f
        return bearing
    }
}
