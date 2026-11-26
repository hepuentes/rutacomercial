package com.rutacomercial.app.maps

import com.rutacomercial.app.domain.Geo
import org.junit.Assert.*
import org.junit.Test

class TrackOutlineTest {
    @Test fun outlineContainsEveryWalkedPointWithMargin() {
        // A walk around a block plus a street through the middle.
        val walk = listOf(0 to 0, 4 to 0, 4 to 4, 0 to 4, 0 to 0, 2 to 0, 2 to 4).flatMap { (x, y) ->
            listOf(MapCoordinate(-34.6 + y * .0005, -58.38 + x * .0005))
        }
        val outline = trackOutline(walk)
        val ring = outline.map { it.latitude to it.longitude }
        assertTrue(Geo.validPolygon(ring))
        assertTrue(walk.all { Geo.contains(ring, it.latitude, it.longitude) })
        assertEquals(4, outline.size)
    }

    @Test fun tooFewPointsGiveNoOutline() {
        assertTrue(trackOutline(listOf(MapCoordinate(0.0, 0.0), MapCoordinate(0.0, 0.001))).isEmpty())
    }
}
