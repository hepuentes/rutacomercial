package com.rutacomercial.app.tracking

import com.rutacomercial.app.domain.Geo

/**
 * Pays only for walking: after [limitMs] without moving more than [radiusM] (GPS jitter while standing
 * stays inside it), the journey pauses and the still time is not counted. A forgotten "Terminar" can no
 * longer bill hours.
 */
internal class IdleWatch(private val radiusM: Double = 25.0, private val limitMs: Long = 20 * 60_000L) {
    private var anchor: Pair<Double, Double>? = null
    var lastMovedAt = 0L; private set
    private var started = false

    fun start(now: Long) { anchor = null; lastMovedAt = now; started = true }

    fun moved(lat: Double, lon: Double, at: Long) {
        val a = anchor
        if (a == null || Geo.distanceM(a.first, a.second, lat, lon) > radiusM) { anchor = lat to lon; lastMovedAt = at }
    }

    fun shouldPause(now: Long) = started && now - lastMovedAt >= limitMs
}
