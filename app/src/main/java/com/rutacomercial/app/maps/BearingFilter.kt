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

/**
 * Learns the compass error from the GPS course while walking. Magnetometers drift near cars,
 * metal and buildings; the walked direction does not. The learned offset then corrects the
 * compass when the worker stops and turns the phone toward a street.
 */
internal class CompassBias {
    var offset = 0f; private set
    var samples = 0; private set
    private var lastCourse = Float.NaN
    /** device: compass azimuth; course: direction of travel from successive good GPS fixes. */
    fun learn(device: Float, course: Float) {
        if (course == lastCourse) return
        lastCourse = course
        val diff = wrap(course - device - offset)
        // A phone held sideways while walking is not a compass error; ignore big disagreements.
        if (abs(diff) > 60f) return
        offset = wrap(offset + diff * if (samples < 3) 0.5f else 0.25f)
        samples++
    }
    fun apply(device: Float) = (device + (if (samples >= 2) offset else 0f) + 360f) % 360f
    private fun wrap(a: Float) = ((a + 540f) % 360f) - 180f
}
