package com.rutacomercial.app.ui

import android.content.Intent
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.*
import androidx.core.content.FileProvider
import com.rutacomercial.app.RutaApplication
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class ZoneProposalTest {
    @get:Rule val compose=createAndroidComposeRule<AssignmentActivity>()

    @Test fun receivedOutlineCreatesOneSectorAndResendableSignedTask() {
        val ctx=compose.activity
        val launchIntent=ctx.intent
        val instrumentation=androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
        val chooser=instrumentation.addMonitor(android.content.IntentFilter(Intent.ACTION_CHOOSER),
            android.app.Instrumentation.ActivityResult(android.app.Activity.RESULT_CANCELED,null),true)
        try {
        val key=UUID.randomUUID().toString()
        val name="Propuesta "+key.take(8)
        val polygon="[[-32.89,-68.85],[-32.89,-68.84],[-32.88,-68.84]]"
        val proposal=JSONObject().put("version",1).put("kind","proposal").put("id",key)
            .put("worker","Nestor").put("name",name).put("polygon",polygon)
        val folder=File(ctx.cacheDir,"task-share").apply { mkdirs() }
        val file=File(folder,"Propuesta-"+key+".ruta.json").apply { writeText(proposal.toString()) }
        val uri=FileProvider.getUriForFile(ctx,ctx.packageName+".tasks",file)
        assertEquals("application/vnd.rutacomercial.proposal",ctx.contentResolver.getType(uri))
        fun open() {
            val intent=Intent(Intent.ACTION_VIEW,uri,ctx,AssignmentActivity::class.java)
                .setDataAndType(uri,ctx.contentResolver.getType(uri))
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            compose.runOnUiThread { ctx.startActivity(intent) }
        }
        fun waitFor(text:String) = compose.waitUntil(20000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
        open()
        waitFor("Aceptar y compartir tarea")
        compose.onNodeWithText("Aceptar y compartir tarea").performClick()
        val taskFile=File(ctx.filesDir,"assignments/"+proposalKey(key,"task")+".task")
        compose.waitUntil(20000) { taskFile.exists() }
        val task=JSONObject(taskFile.readText())
        assertTrue(OwnerIdentity.verified(task))
        val repo=(ctx.application as RutaApplication).repository
        assertEquals(polygon,runBlocking { repo.dao.sector(task.getString("sectorId")) }?.polygonJson)
        open()
        waitFor("Reenviar tarea")
        assertEquals(1,runBlocking { repo.dao.sectors() }.count { it.name==name })
        assertEquals(task.getString("signature"),JSONObject(taskFile.readText()).getString("signature"))
        assertTrue(chooser.hits>0)
        } finally {
            instrumentation.removeMonitor(chooser)
            instrumentation.runOnMainSync {
                ctx.intent=launchIntent
                ctx.finish()
            }
        }
    }
}
