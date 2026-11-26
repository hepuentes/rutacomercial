package com.rutacomercial.app.maps

import kotlin.math.cos
import kotlin.math.hypot

/**
 * Suggested zone around what was actually walked: convex hull plus a small margin so the
 * sidewalks stay inside. ponytail: convex, so L-shaped walks include the corner; the Owner
 * trims it in the editor.
 */
internal fun trackOutline(points: List<MapCoordinate>, marginM: Double = 15.0): List<MapCoordinate> {
    if (points.size < 3) return emptyList()
    val lat0 = points.sumOf { it.latitude } / points.size
    val kx = 111_320.0 * cos(Math.toRadians(lat0)); val ky = 110_540.0
    val xy = points.map { it.longitude * kx to it.latitude * ky }.distinct().sortedWith(compareBy({ it.first }, { it.second }))
    if (xy.size < 3) return emptyList()
    fun cross(o: Pair<Double, Double>, a: Pair<Double, Double>, b: Pair<Double, Double>) =
        (a.first - o.first) * (b.second - o.second) - (a.second - o.second) * (b.first - o.first)
    val hull = mutableListOf<Pair<Double, Double>>()
    for (pass in 0..1) {
        val start = hull.size
        for (p in if (pass == 0) xy else xy.asReversed()) {
            while (hull.size >= start + 2 && cross(hull[hull.size - 2], hull.last(), p) <= 0) hull.removeAt(hull.size - 1)
            hull.add(p)
        }
        hull.removeAt(hull.size - 1)
    }
    if (hull.size < 3) return emptyList()
    val cx = hull.sumOf { it.first } / hull.size; val cy = hull.sumOf { it.second } / hull.size
    return hull.map { (x, y) ->
        val d = hypot(x - cx, y - cy).coerceAtLeast(1.0)
        MapCoordinate((y + (y - cy) / d * marginM) / ky, (x + (x - cx) / d * marginM) / kx)
    }
}
