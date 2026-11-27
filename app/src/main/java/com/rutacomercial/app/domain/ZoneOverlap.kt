package com.rutacomercial.app.domain

/**
 * Share of zone [a] (0-100) that also lies inside zone [b], by sampling a 40x40 grid over [a].
 * ponytail: sampling, about ±3 %; enough to warn before saving, not for billing.
 */
object ZoneOverlap {
    fun percent(a: List<Pair<Double, Double>>, b: List<Pair<Double, Double>>): Int {
        if (a.size < 3 || b.size < 3) return 0
        val south = a.minOf { it.first }; val north = a.maxOf { it.first }
        val west = a.minOf { it.second }; val east = a.maxOf { it.second }
        // Bounding boxes apart: no overlap, skip the sampling.
        if (b.maxOf { it.first } < south || b.minOf { it.first } > north || b.maxOf { it.second } < west || b.minOf { it.second } > east) return 0
        var inA = 0; var inBoth = 0
        for (i in 0 until 40) for (j in 0 until 40) {
            val lat = south + (north - south) * (i + .5) / 40
            val lon = west + (east - west) * (j + .5) / 40
            if (!Geo.contains(a, lat, lon)) continue
            inA++
            if (Geo.contains(b, lat, lon)) inBoth++
        }
        return if (inA == 0) 0 else inBoth * 100 / inA
    }
}
