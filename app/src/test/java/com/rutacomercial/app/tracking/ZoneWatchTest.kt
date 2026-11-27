package com.rutacomercial.app.tracking

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ZoneWatchTest {
    @Test fun aWideAvenueOrGpsDriftNearTheBorderIsNotLeaving() {
        val w = ZoneWatch()
        for (t in 0..600_000L step 10_000L) assertNull(w.position(outsideM = 35.0, now = t))
    }

    @Test fun walkingJustOutsideGetsThreeMinutes() {
        val w = ZoneWatch()
        assertNull(w.position(80.0, 0))
        assertNull(w.position(80.0, 170_000))
        assertEquals("out", w.position(80.0, 180_000))
        assertNull(w.position(80.0, 300_000))                 // once
        assertEquals("back", w.position(10.0, 310_000))
    }

    @Test fun clearlyGoneIsWarnedSooner() {
        val w = ZoneWatch()
        assertNull(w.position(300.0, 0))
        assertEquals("out", w.position(300.0, 45_000))
    }

    @Test fun comingBackInResetsTheClock() {
        val w = ZoneWatch()
        assertNull(w.position(80.0, 0))
        assertNull(w.position(0.0, 100_000))
        assertNull(w.position(80.0, 110_000))
        assertNull(w.position(80.0, 280_000))
        assertEquals("out", w.position(80.0, 290_000))
    }

    @Test fun choosingToContinueOutsideStopsWarnings() {
        val w = ZoneWatch().apply { accepted = true }
        assertNull(w.position(500.0, 0)); assertNull(w.position(500.0, 600_000))
    }

    @Test fun distanceToBorder() {
        val square = listOf(0.0 to 0.0, 0.0 to 0.001, 0.001 to 0.001, 0.001 to 0.0)
        assertEquals(0.0, ZoneWatch.outsideMeters(square, 0.0005, 0.0005), 0.0)
        assertEquals(111.3, ZoneWatch.outsideMeters(square, 0.0005, 0.002), 1.0)
    }

    @Test fun nearlyDoneOnce() {
        val w = ZoneWatch()
        assertFalse(w.coverage(null)); assertFalse(w.coverage(85))
        assertTrue(w.coverage(91)); assertFalse(w.coverage(95))
    }
}
