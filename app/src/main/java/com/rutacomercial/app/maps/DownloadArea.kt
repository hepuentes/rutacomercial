package com.rutacomercial.app.maps

import com.rutacomercial.app.domain.Geo

/** Bounds are independent of the visual map and can be saved using existing sector polygons. */
data class DownloadArea(val south: Double, val west: Double, val north: Double, val east: Double) {
    val center get() = MapCoordinate((south + north) / 2, (west + east) / 2)
    val widthM get() = Geo.distanceM(center.latitude, west, center.latitude, east)
    val heightM get() = Geo.distanceM(south, center.longitude, north, center.longitude)
    val downloadable get() = south >= -80 && north <= 80 && west >= -179.9 && east <= 179.9 &&
        widthM in 10.0..1000.0 && heightM in 10.0..1000.0 && east - west < 1.0
    companion object {
        fun from(points: List<MapCoordinate>): DownloadArea? {
            if (points.size < 3 || points.any { !Geo.valid(it.latitude, it.longitude) }) return null
            return DownloadArea(points.minOf { it.latitude }, points.minOf { it.longitude }, points.maxOf { it.latitude }, points.maxOf { it.longitude })
        }
    }
}
