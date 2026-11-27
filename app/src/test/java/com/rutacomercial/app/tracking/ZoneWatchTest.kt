package com.rutacomercial.app.tracking

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ZoneWatchTest {
    @Test fun warnsOnlyAfterStayingOutAMinuteAndWhenComingBack() {
        val w = ZoneWatch()
        assertNull(w.position(inside = false, now = 0))
        assertNull(w.position(inside = true, now = 20_000))       // a GPS jump, not leaving
        assertNull(w.position(inside = false, now = 30_000))
        assertNull(w.position(inside = false, now = 80_000))
        assertEquals("out", w.position(inside = false, now = 90_000))
        assertNull(w.position(inside = false, now = 200_000))     // once
        assertEquals("back", w.position(inside = true, now = 210_000))
    }

    @Test fun nearlyDoneOnce() {
        val w = ZoneWatch()
        assertFalse(w.coverage(null)); assertFalse(w.coverage(85))
        assertTrue(w.coverage(91)); assertFalse(w.coverage(95))
    }
}
