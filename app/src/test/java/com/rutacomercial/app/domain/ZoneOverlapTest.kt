package com.rutacomercial.app.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class ZoneOverlapTest {
    private fun box(s: Double, w: Double, n: Double, e: Double) = listOf(s to w, s to e, n to e, n to w)

    @Test fun sameZoneHalfAndApart() {
        val a = box(0.0, 0.0, 1.0, 1.0)
        assertEquals(100, ZoneOverlap.percent(a, a))
        assertEquals(50, ZoneOverlap.percent(a, box(0.0, 0.5, 1.0, 2.0)), )
        assertEquals(0, ZoneOverlap.percent(a, box(5.0, 5.0, 6.0, 6.0)))
    }
}
