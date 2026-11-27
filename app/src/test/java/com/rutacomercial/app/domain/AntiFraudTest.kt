package com.rutacomercial.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AntiFraudTest {
    // One fix every 2 s moving east at [kmh], starting at second [from].
    private fun ride(w: VehicleWatch, kmh: Double, from: Int, seconds: Int, lon0: Double = -58.38, acc: Float = 5f): Pair<List<String>, Double> {
        val events = mutableListOf<String>(); var lon = lon0
        val step = kmh / 3.6 * 2 / 91_600.0
        for (s in from until from + seconds step 2) {
            w.accept(GpsFix(-34.6, lon, acc, at = s * 1000L, elapsedRealtimeNanos = s * 1_000_000_000L))?.let { events += it }
            lon += step
        }
        return events to lon
    }

    @Test fun walkingAndBriskWalkingAreNotAVehicle() {
        val w = VehicleWatch()
        val (walk, lon) = ride(w, 5.0, 0, 300)
        assertTrue(walk.isEmpty())
        assertTrue(ride(w, 7.5, 300, 300, lon).first.isEmpty())
        assertFalse(w.active)
    }

    @Test fun aRideIsDetectedAfterAMinuteAndBackdatedToItsStart() {
        val w = VehicleWatch()
        val (_, lon) = ride(w, 5.0, 0, 120)
        val (events, lon2) = ride(w, 30.0, 120, 120, lon)
        assertEquals(listOf("start"), events)
        assertTrue("start ${w.startedAt}", w.startedAt!! in 116_000L..124_000L)     // walking before stays paid
        val (back, _) = ride(w, 4.0, 240, 120, lon2)
        assertEquals(listOf("end"), back)
        assertNull(w.startedAt)
        assertTrue("end ${w.endedAt}", w.endedAt in 234_000L..244_000L)             // walking after stays paid
    }

    @Test fun clockJumpsAreMeasuredSmallCorrectionsIgnored() {
        val c = ClockWatch()
        assertEquals(0, c.check(1_000_000, 0))
        assertEquals(0, c.check(1_010_500, 10_000))                       // half a second of network correction
        assertEquals(7_200_000, c.check(1_010_500 + 7_210_000, 20_000))   // set forward two hours
        assertEquals(-3_600_000, c.check(1_010_500 + 7_210_000 + 10_000 - 3_600_000, 30_000))
    }
}
