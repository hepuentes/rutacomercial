package com.rutacomercial.app.tracking

import com.rutacomercial.app.domain.Geo
import com.rutacomercial.app.domain.GpsFix

/** A display fix is not evidence of movement and never enters the journey repository. */
internal object LocationPreviewPolicy {
    fun usable(fix: GpsFix, nowMillis: Long, elapsedNanos: Long, maxAgeMillis: Long = 30_000): Boolean =
        Geo.valid(fix.latitude, fix.longitude) && fix.accuracyMeters.isFinite() && fix.accuracyMeters in 0.01f..500f &&
            fix.at > 0 && fix.elapsedRealtimeNanos > 0 && nowMillis - fix.at in 0..maxAgeMillis &&
            elapsedNanos - fix.elapsedRealtimeNanos in 0..maxAgeMillis * 1_000_000
}
