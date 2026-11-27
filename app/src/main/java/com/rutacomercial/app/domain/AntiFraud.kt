package com.rutacomercial.app.domain

/**
 * Distribution is on foot. Sustained speed above [startKmh] for [confirmMs] means a vehicle (moto, car,
 * ride app, bus); below [endKmh] for [releaseMs] it is walking again. The confirmation uses the lower
 * bound of the speed (distance minus both GPS errors), so drift never looks like a ride. Start and end are
 * then trimmed to the first and last fast step, so walking minutes around the ride stay paid.
 */
class VehicleWatch(private val startKmh: Double = 15.0, private val endKmh: Double = 8.0,
    private val confirmMs: Long = 60_000L, private val releaseMs: Long = 45_000L) {
    private val window = ArrayDeque<GpsFix>()
    private var lastFastAt = 0L
    /** Wall time the ride began, null while walking. */
    var startedAt: Long? = null; private set
    /** Wall time the last ride ended (its last fast step). */
    var endedAt: Long = 0L; private set
    val active get() = startedAt != null

    fun reset() { window.clear(); startedAt = null; lastFastAt = 0L }

    private fun stepKmh(a: GpsFix, b: GpsFix): Double {
        val s = (b.elapsedRealtimeNanos - a.elapsedRealtimeNanos) / 1e9
        return if (s <= 0) 0.0 else Geo.distanceM(a.latitude, a.longitude, b.latitude, b.longitude) / s * 3.6
    }

    /** "start" or "end" when the state changes. */
    fun accept(f: GpsFix): String? {
        if (!f.accuracyMeters.isFinite() || f.accuracyMeters > 30f) return null
        window.lastOrNull()?.let { if (stepKmh(it, f) > endKmh) lastFastAt = f.at }
        window.addLast(f)
        val span = if (active) releaseMs else confirmMs
        while (window.size > 1 && f.elapsedRealtimeNanos - window[1].elapsedRealtimeNanos >= span * 1_000_000L) window.removeFirst()
        val first = window.first()
        val seconds = (f.elapsedRealtimeNanos - first.elapsedRealtimeNanos) / 1e9
        if (seconds * 1000 < span * 0.9) return null
        val distance = Geo.distanceM(first.latitude, first.longitude, f.latitude, f.longitude) - first.accuracyMeters - f.accuracyMeters
        val kmh = distance / seconds * 3.6
        if (!active && kmh > startKmh) {
            // Walk back over consecutive fast steps to the moment the ride really began.
            var i = window.size - 1
            while (i > 0 && stepKmh(window[i - 1], window[i]) > endKmh) i--
            startedAt = window[i].at
            return "start"
        }
        if (active && kmh < endKmh) {
            endedAt = maxOf(lastFastAt, startedAt ?: 0L); startedAt = null
            window.clear(); window.addLast(f)
            return "end"
        }
        return null
    }
}

/**
 * The wall clock can be changed by hand; the elapsed-time clock cannot. A forward jump of the wall clock
 * during a journey would add active time, so it is measured and left unpaid. Normal network corrections
 * are a few seconds; only jumps over [toleranceMs] count.
 */
class ClockWatch(private val toleranceMs: Long = 60_000L) {
    private var wall = 0L; private var elapsed = 0L
    fun reset() { wall = 0L; elapsed = 0L }
    /** Baseline taken at start or resume, so a change right after starting is also caught. */
    fun start(wallNow: Long, elapsedNow: Long) { wall = wallNow; elapsed = elapsedNow }
    /** Milliseconds the wall clock jumped since the last check (+ forward, - back), or 0. */
    fun check(wallNow: Long, elapsedNow: Long): Long {
        val jump = if (wall == 0L) 0L else (wallNow - wall) - (elapsedNow - elapsed)
        wall = wallNow; elapsed = elapsedNow
        return if (kotlin.math.abs(jump) > toleranceMs) jump else 0L
    }
}
