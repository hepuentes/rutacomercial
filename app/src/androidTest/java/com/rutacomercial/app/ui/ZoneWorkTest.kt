package com.rutacomercial.app.ui

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.rutacomercial.app.data.Sector
import com.rutacomercial.app.data.newId
import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ZoneWorkTest {
    @Test fun bundleKeepsIndividualMarkersAndRejectsAnotherAssignment() {
        val task=newId()
        fun session(id:String,state:String,vararg labels:String):JSONObject {
            val events=JSONArray()
            labels.forEachIndexed { i,label -> events.put(JSONObject().put("label",label).put("lat",1).put("lon",2).put("at",100L+i)) }
            return JSONObject().put("version",1).put("id",id).put("assignmentId",task).put("kind","result")
                .put("state",state).put("duration",100).put("meters",10).put("segments",JSONArray()).put("events",events)
        }
        val first=session(task,"PAUSED","IR","P","CR","P")
        val last=session(newId(),"FINISHED","IR","FR").put("previousReports",JSONArray().put(first))
        val marks=validateBundle(last).map { resultMarks(it,emptyList()) }
        assertEquals(listOf("IR","P1","CR1","P2"),com.rutacomercial.app.maps.numberedMarks(marks.first()).map { it.second })
        assertEquals(listOf("IR","FR"),marks.last().map { it.label })
        first.put("assignmentId",newId())
        assertTrue(runCatching { validateBundle(last) }.isFailure)
    }
    @Test fun importedWorkStaysWithItsZoneAcrossDaysAndWorkers() {
        val dir=File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,newId()).apply { mkdir() }
        try {
            val polygon="[[1,1],[1,2],[2,1]]"
            val zone=Sector(name="Centro",polygonJson=polygon)
            val oldTask=newId()
            val nextDay=newId()
            val otherWorker=newId()
            fun writeTask(id:String,worker:String,legacy:Boolean) {
                val task=JSONObject().put("id",id).put("worker",worker).put("polygon",polygon).put("name","Centro")
                if(!legacy) task.put("sectorId",zone.id)
                File(dir,"$id.task").writeText(task.toString())
            }
            fun result(id:String,task:String,at:Long):JSONObject {
                val doc=JSONObject().put("id",id).put("kind","result").put("startedAt",at).put("meters",1107.47)
                    .put("duration",932513).put("segments",JSONArray("[[[1,1],[1,2]]]"))
                if(id!=task) doc.put("assignmentId",task)
                File(dir,"$id.result").writeText(doc.toString())
                return doc
            }
            writeTask(oldTask,"Ana",true)
            writeTask(otherWorker,"Luis",false)
            val final=result(oldTask,oldTask,100)
            result(nextDay,oldTask,200)
            result(otherWorker,otherWorker,300)
            val partial=JSONObject(final.toString()).put("state","PAUSED").put("capturedAt",1000)
            File(dir,"$oldTask.received").writeText(partial.toString())
            assertSame(final,newerReport(final,partial))
            assertSame(final,newerReport(partial,final))
            val older=JSONObject(partial.toString()).put("capturedAt",999)
            assertSame(partial,newerReport(partial,older))
            val work=ZoneWork.read(dir)
            val renamed=zone.copy(name="Zona renombrada")
            val rows=work.reportsFor(work.tasksFor(renamed,listOf(renamed)))
            assertEquals(listOf("Luis","Ana","Ana"),rows.map { it.worker })
            assertEquals(listOf(otherWorker,nextDay,oldTask),rows.map { it.id })
            assertEquals(3,previousSegments(rows.flatMap { it.lines }).length())
            assertEquals("Zona completada",work.label(renamed.copy(status="COMPLETED"),listOf(renamed)))
            assertTrue(work.tasksFor(renamed.copy(id=newId(),polygonJson="[]"),listOf(renamed)).isEmpty())
        } finally { dir.deleteRecursively() }
    }
}
