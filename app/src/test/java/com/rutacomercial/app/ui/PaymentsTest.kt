package com.rutacomercial.app.ui

import com.rutacomercial.app.maps.MapTrack
import org.junit.Assert.assertEquals
import org.junit.Test

class PaymentsTest {
    @Test fun feeUsesActiveTimeOnly() {
        assertEquals(7_000, fee(105 * 60_000L, 4_000))        // 1 h 45 min × 4.000
        assertEquals(0, fee(0, 4_000))
        assertEquals(667, fee(10 * 60_000L, 4_000))            // rounded to the peso
    }

    @Test fun moneyUsesLocalThousands() {
        assertEquals("$ 7.000", money(7_000))
        assertEquals("$ 1.250.000", money(1_250_000))
    }

    @Test fun duesSumEveryJourneyOfATask() {
        val task = WorkTask("t1", null, "Zona 1", "Pedro", "[]", 0)
        val other = WorkTask("t2", null, "Zona 2", "Ana", "[]", 0)
        fun r(id: String, task: String, ms: Long, state: String, at: Long) =
            WorkReport(id, task, "Zona", "x", at, true, 0.0, ms, state, emptyList<MapTrack>(), emptyList(), at)
        val list = dues(listOf(task, other), listOf(r("a", "t1", 3_600_000, "FINISHED", 1), r("b", "t1", 1_800_000, "FINISHED", 2),
            r("c", "t2", 600_000, "ACTIVE", 3)), rateOf = { if (it == "t1") 4_000 else 3_000 }, paidOf = { null })
        assertEquals(6_000, list.first { it.taskId == "t1" }.amount)
        assertEquals(true, list.first { it.taskId == "t1" }.finished)
        assertEquals(false, list.first { it.taskId == "t2" }.finished)
    }

    @Test fun ridingSimulatedLocationAndClockJumpsAreNotPaid() {
        val d = Due("t", "Pedro", "Zona", activeMs = 2 * 3_600_000L, rate = 4_000, finished = true, paidAt = null,
            vehicleMs = 10 * 60_000L, mockMs = 5 * 60_000L, clockMs = 15 * 60_000L, outsideMs = 20 * 60_000L)
        assertEquals(90 * 60_000L, d.payableMs)          // outside is shown, not deducted
        assertEquals(6_000, d.amount)
    }

    @Test fun rateInputKeepsDigitsOnly() {
        assertEquals(4_000, parseRate("$ 4.000"))
        assertEquals(0, parseRate(""))
    }
}
