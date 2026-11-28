package com.rutacomercial.app.ui

import com.rutacomercial.app.maps.MapCoordinate
import com.rutacomercial.app.maps.MapSector
import org.junit.Assert.assertEquals
import org.junit.Test

class ZoneClusterTest {
    private fun zone(id: String, lat: Double, lon: Double) =
        MapSector(id, id, "#000000", listOf(MapCoordinate(lat, lon), MapCoordinate(lat + .002, lon), MapCoordinate(lat, lon + .002)))

    @Test fun aStrayZoneFarAwayDoesNotZoomOutAllZones() {
        val mendoza = listOf(zone("a", -32.89, -68.84), zone("b", -32.92, -68.80), zone("c", -32.85, -68.83))
        val stray = zone("x", 1.2, -77.3)
        assertEquals(mendoza.flatMap { it.points }, zoneCluster(mendoza + stray))
        assertEquals(emptyList<MapCoordinate>(), zoneCluster(emptyList()))
    }
}
