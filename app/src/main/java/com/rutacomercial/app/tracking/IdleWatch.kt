package com.rutacomercial.app.tracking

import com.rutacomercial.app.domain.Geo

/**
 * Pays only for walking: after [limitMs] without moving more than [radiusM] (GPS jitter while standing
 * stays inside it), the journey pauses and the still time is not counted. A forgotten "Terminar" can no
 * longer bill hours. Measured on the elapsed-time clock, so changing the phone's hour is not "standing still".
 */
internal class IdleWatch(private val radiusM: Double = 25.0, private val limitMs: Long = 20 * 60_000L) {
    private var anchor: Pair<Double, Double>? = null
    private var started = false
    private var lastMovedElapsed = 0L
    /** Wall time of the last real movement, to backdate the pause. */
    var lastMovedAt = 0L; private set

    fun start(wallNow: Long, elapsedNow: Long) { anchor = null; lastMovedAt = wallNow; lastMovedElapsed = elapsedNow; started = true }

    fun moved(lat: Double, lon: Double, wallAt: Long, elapsedAt: Long) {
        val a = anchor
        if (a == null || Geo.distanceM(a.first, a.second, lat, lon) > radiusM) { anchor = lat to lon; lastMovedAt = wallAt; lastMovedElapsed = elapsedAt }
    }

    fun shouldPause(elapsedNow: Long) = started && elapsedNow - lastMovedElapsed >= limitMs
}
