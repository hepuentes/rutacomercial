package com.rutacomercial.app.ui

import com.rutacomercial.app.data.Sector
import com.rutacomercial.app.maps.MapTrack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class CampaignsTest {
    private fun r(id: String, task: String, state: String) =
        WorkReport(id, task, "Zona", "x", 1, true, 0.0, 60_000, state, emptyList<MapTrack>(), emptyList(), 1)

    @Test fun closingKeepsWalkingTasksOpenAndHidesClosedFromTheMap() {
        val zone = Sector(id = "s1", name = "Centro", polygonJson = "[]", status = "COMPLETED")
        val done = WorkTask("t1", "s1", "Centro", "Pedro", "[]", 0)
        val walking = WorkTask("t2", "s1", "Centro", "Ana", "[]", 1)
        val idle = WorkTask("t3", "s1", "Centro", "Ana", "[]", 2)
        val work = ZoneWork(listOf(done, walking, idle), listOf(r("a", "t1", "FINISHED"), r("b", "t2", "ACTIVE")), true)
        assertEquals(listOf("t1"), work.closable().map { it.id })

        val folder = Files.createTempDirectory("campaigns").toFile()
        Campaigns.close(folder, "Campaña 1", listOf("t1"))
        val after = work.copy(closed = Campaigns.read(folder))
        assertEquals(mapOf("t1" to "Campaña 1"), after.closed)
        assertEquals(listOf("t2", "t3"), after.tasksFor(zone, listOf(zone)).map { it.id })
        assertTrue(after.closable().isEmpty())
        assertEquals(2, after.reports.size)   // reports stay for history and payments
    }
}
