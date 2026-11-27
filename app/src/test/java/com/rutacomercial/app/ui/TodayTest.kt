package com.rutacomercial.app.ui

import com.rutacomercial.app.maps.MapCoordinate
import com.rutacomercial.app.maps.MapTrack
import org.junit.Assert.assertEquals
import org.junit.Test

class TodayTest {
    private fun report(worker: String, state: String, updated: Long, meters: Double = 100.0) =
        WorkReport("r$updated", "t", "Zona", worker, updated, true, meters, 0, state,
            listOf(MapTrack("l", listOf(MapCoordinate(1.0, 1.0), MapCoordinate(2.0, 2.0)))), emptyList(), updated)

    @Test fun stateFollowsFreshness() {
        assertEquals("recorriendo", todayState("ACTIVE", 1_000, 60_000))
        assertEquals("sin señal", todayState("ACTIVE", 0, 6 * 60_000))
        assertEquals("en pausa", todayState("PAUSED", 0, 99 * 60_000))
        assertEquals("terminó", todayState("FINISHED", 0, 0))
    }

    @Test fun oneRowPerWorkerWithLatestStateAndTodaysDistance() {
        val list = workersToday(listOf(report("Juan", "FINISHED", 100), report("juan ", "ACTIVE", 200),
            report("Ana", "ACTIVE", 50), report("Old", "ACTIVE", 5)), since = 10)
        assertEquals(listOf("juan ", "Ana"), list.map { it.worker })
        assertEquals(200.0, list[0].meters, 0.0)
        assertEquals("ACTIVE", list[0].state)
        assertEquals(MapCoordinate(2.0, 2.0), list[0].last)
    }
}
