package com.rutacomercial.app.domain

import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max

/** Conservative pedestrian gate. Emits only observed fixes, never interpolated coordinates.
 * Reset at every start/resume. Repository remains responsible for time and segment integrity. */
class WalkingGpsFilter {
    private var lastSample: GpsFix? = null
    private var anchor: GpsFix? = null
    private var candidate: GpsFix? = null
    private val settling = ArrayDeque<GpsFix>()
    val lastReliableFix: GpsFix? get() = lastSample

    fun reset() { lastSample = null; anchor = null; candidate = null; settling.clear() }

    fun evaluate(fix: GpsFix): GpsDecision {
        val validity = GpsPolicy.evaluate(null, fix)
        if (!validity.accepted || fix.accuracyMeters > 25f) {
            return discard(fix, "Lectura imprecisa descartada · esperando mejor señal")
        }
        val previous = lastSample
        if (previous != null) {
            val dt = (fix.elapsedRealtimeNanos - previous.elapsedRealtimeNanos) / 1_000_000
            if (dt <= 0 || fix.at <= previous.at) return GpsDecision(false, "Lectura GPS repetida")
            // Some phones (battery savers, app cloners, blocked location assistance) deliver readings in bursts.
            // Throwing away every reading after a pause loses the whole walk, so a precise one that walking
            // explains becomes the new anchor; across more than 2 minutes it starts a new segment.
            val reach = Geo.distanceM(previous.latitude, previous.longitude, fix.latitude, fix.longitude) - previous.accuracyMeters - fix.accuracyMeters
            if (dt in (SIGNAL_GAP_MS + 1)..MAX_BRIDGE_MS && kotlin.math.abs((fix.at - previous.at) - dt) <= 10_000 &&
                anchor != null && reach / (dt / 1000.0) <= WALK_MAX_MPS) {
                lastSample = fix; anchor = fix; candidate = null; settling.clear()
                return GpsDecision(true, "Señal recuperada · punto registrado", dt > BRIDGE_MS)
            }
            if (dt > SIGNAL_GAP_MS || kotlin.math.abs((fix.at - previous.at) - dt) > 10_000) {
                reset()
                lastSample = fix; settling.add(fix)
                return GpsDecision(false, "Recuperando señal · comprobando ubicación", true)
            }
            val jump = GpsPolicy.evaluate(previous, fix)
            if (!jump.accepted && jump.breakSegment) {
                return discard(fix, "Salto GPS descartado · esperando otra lectura")
            }
        }
        lastSample = fix
        val origin = anchor
        if (origin == null) {
            settling.add(fix)
            while (settling.size > 3) settling.removeFirst()
            if (settling.size < 3 || fix.elapsedRealtimeNanos - settling.first().elapsedRealtimeNanos < 6_000_000_000L)
                return GpsDecision(false, "Comprobando ubicación · esperá unos segundos")
            anchor = fix; settling.clear()
            return GpsDecision(true, "Ubicación lista", true)
        }
        // An accuracy estimate alone must not turn a few metres of indoor drift into a walk.
        val uncertainty = hypot(origin.accuracyMeters.toDouble(), fix.accuracyMeters.toDouble())
        val radius = max(8.0, uncertainty * 1.2)
        val displacement = distance(origin, fix)
        if (displacement < radius) {
            candidate = null
            return GpsDecision(false, "GPS activo · sin desplazamiento confirmado")
        }
        val pending = candidate
        if (pending == null) {
            candidate = fix
            return GpsDecision(false, "Comprobando desplazamiento")
        }
        val age = (fix.elapsedRealtimeNanos - pending.elapsedRealtimeNanos) / 1_000_000
        val scale = cos(Math.toRadians(origin.latitude))
        val ax = (pending.longitude - origin.longitude) * scale
        val ay = pending.latitude - origin.latitude
        val bx = (fix.longitude - origin.longitude) * scale
        val by = fix.latitude - origin.latitude
        val coherent = ax * bx + ay * by >= 0.5 * hypot(ax, ay) * hypot(bx, by)
        if (age > 25_000 || !coherent || displacement < distance(origin, pending) - 3.0) {
            candidate = fix
            return GpsDecision(false, "Ubicación variable · comprobando movimiento")
        }
        if (age < 4_000 || distance(pending, fix) < 3.0)
            return GpsDecision(false, "Comprobando desplazamiento")
        anchor = fix; candidate = null
        return GpsDecision(true, "Movimiento confirmado")
    }

    private fun distance(a: GpsFix, b: GpsFix) = Geo.distanceM(a.latitude, a.longitude, b.latitude, b.longitude)

    private fun discard(fix: GpsFix, reason: String): GpsDecision {
        candidate = null
        val previous = lastSample
        val gap = previous != null && fix.elapsedRealtimeNanos > previous.elapsedRealtimeNanos &&
            (fix.elapsedRealtimeNanos - previous.elapsedRealtimeNanos) / 1_000_000 > SIGNAL_GAP_MS
        if (gap) reset()
        return GpsDecision(false, if (gap) "Señal GPS insuficiente · tramo interrumpido" else reason, gap)
    }

    companion object {
        const val SIGNAL_GAP_MS = 30_000L
        /** Up to this pause the straight line counts as walked; longer pauses keep the point but break the line. */
        const val BRIDGE_MS = 120_000L
        const val MAX_BRIDGE_MS = 10 * 60_000L
        const val WALK_MAX_MPS = 2.5
    }
}
