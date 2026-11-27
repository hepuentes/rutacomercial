package com.rutacomercial.app.tracking

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IdleWatchTest {
    @Test fun standingStillWithGpsJitterPausesAfterTwentyMinutes() {
        val w = IdleWatch().apply { start(1_000_000, 0) }
        w.moved(-34.6037, -58.3816, 1_000_000, 0)
        for (t in 1..19) w.moved(-34.6037 + 0.00005 * (t % 3), -58.3816, 1_000_000 + t * 60_000L, t * 60_000L)   // ±10 m jitter
        assertFalse(w.shouldPause(19 * 60_000L))
        assertTrue(w.shouldPause(20 * 60_000L))
        assertEquals(1_000_000, w.lastMovedAt)                                                                    // the bill stops here
    }

    @Test fun walkingKeepsItGoing() {
        val w = IdleWatch().apply { start(0, 0) }
        for (t in 0..30) w.moved(-34.6037, -58.3816 + t * 0.0004, t * 60_000L, t * 60_000L)                      // ~37 m per minute
        assertFalse(w.shouldPause(30 * 60_000L))
    }

    @Test fun movingThePhoneClockIsNotStandingStill() {
        val w = IdleWatch().apply { start(0, 0) }
        w.moved(-34.6037, -58.3816, 7_200_000, 60_000)   // wall clock jumped two hours, one real minute passed
        assertFalse(w.shouldPause(120_000))
    }
}
