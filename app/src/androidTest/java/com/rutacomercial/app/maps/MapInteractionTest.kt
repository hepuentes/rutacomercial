package com.rutacomercial.app.maps

import androidx.activity.ComponentActivity
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import androidx.test.platform.app.InstrumentationRegistry
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.atomic.AtomicReference

class MapInteractionTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun newLiveCenterMovesCameraWithoutAnotherCenterRequest() {
        val center = androidx.compose.runtime.mutableStateOf(MapCoordinate(-32.89, -68.84))
        val observed = AtomicReference<MapCoordinate?>(null)
        compose.setContent { TerritoryMap(Modifier.fillMaxSize(), center = center.value, onCameraIdle = { observed.set(it) }) }
        compose.waitUntil(15_000) { observed.get()?.let { kotlin.math.abs(it.latitude + 32.89) < 0.00001 } == true }
        compose.runOnIdle { center.value = MapCoordinate(-32.885, -68.835) }
        compose.waitUntil(10_000) { observed.get()?.let { kotlin.math.abs(it.latitude + 32.885) < 0.00001 && kotlin.math.abs(it.longitude + 68.835) < 0.00001 } == true }
    }

    @Test fun twoFingerRotationCanBeResetToNorth() {
        val camera=AtomicReference<MapCoordinate?>()
        compose.setContent { TerritoryMap(Modifier.fillMaxSize(),fieldTools=true,center=MapCoordinate(-34.60,-58.39),onCameraIdle={camera.set(it)}) }
        compose.waitUntil(20000) { camera.get()?.let { kotlin.math.abs(it.latitude+34.60)<0.0001 }==true }
        compose.onNodeWithContentDescription("Brújula: norte arriba").assertExists()
        Thread.sleep(1200)
        val bounds=compose.onNodeWithContentDescription("Mapa del territorio").fetchSemanticsNode().boundsInWindow
        val center=bounds.center
        val radius=minOf(bounds.width,bounds.height)*.21f
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val down=SystemClock.uptimeMillis()
        val props=Array(2) { i->MotionEvent.PointerProperties().apply { id=i;toolType=MotionEvent.TOOL_TYPE_FINGER } }
        fun touch(action:Int,count:Int,angle:Double) {
            val points=Array(count) { i->MotionEvent.PointerCoords().apply {
                val sign=if(i==0) -1 else 1
                x=center.x+sign*radius*kotlin.math.cos(angle).toFloat()
                y=center.y+sign*radius*kotlin.math.sin(angle).toFloat()
                pressure=1f;size=1f
            } }
            val e=MotionEvent.obtain(down,SystemClock.uptimeMillis(),action,count,props,points,0,0,1f,1f,0,0,InputDevice.SOURCE_TOUCHSCREEN,0)
            try { instrumentation.runOnMainSync { compose.activity.dispatchTouchEvent(e) } } finally { e.recycle() }
            SystemClock.sleep(25)
        }
        touch(MotionEvent.ACTION_DOWN,1,0.0)
        touch(MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),2,0.0)
        for(i in 1..24) touch(MotionEvent.ACTION_MOVE,2,i*.05)
        touch(MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),2,1.2)
        touch(MotionEvent.ACTION_UP,1,1.2)
        compose.waitUntil(5000) { compose.onAllNodesWithContentDescription("Brújula: giro libre, volver al norte").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("Brújula: giro libre, volver al norte").performClick()
        compose.onNodeWithContentDescription("Brújula: norte arriba").assertExists()
    }

    @Test fun receivedZonePreviewsCachedStreetsAndReopensInSteps() {
        val ctx=compose.activity
        val polygon=listOf(MapCoordinate(-34.604,-58.394),MapCoordinate(-34.604,-58.39),MapCoordinate(-34.601,-58.39),MapCoordinate(-34.601,-58.394))
        val zone=MapSector(java.util.UUID.randomUUID().toString(),"Prueba","#087F75",polygon,true)
        val bounds="-34.604,-58.394,-34.601,-58.39"
        val key=java.security.MessageDigest.getInstance("SHA-256").digest(bounds.toByteArray()).take(12).joinToString("") { "%02x".format(it) }
        val dir=java.io.File(ctx.cacheDir,"guide-streets-v1").apply { mkdirs() }
        val cache=java.io.File(dir,"$key.json")
        cache.writeText("""{"elements":[{"type":"way","nodes":[1,2],"geometry":[{"lat":-34.603,"lon":-58.393},{"lat":-34.602,"lon":-58.391}],"tags":{"highway":"residential","name":"Calle de prueba"}}]}""")
        try {
            compose.setContent { TerritoryMap(Modifier.fillMaxSize(),sectors=listOf(zone),fitArea=polygon,fieldTools=true,previewGuide=true) }
            fun waitFor(label:String) = compose.waitUntil(16000) { compose.onAllNodesWithText(label).fetchSemanticsNodes().isNotEmpty() }
            waitFor("Guía · paso 1 de 2")
            compose.mainClock.advanceTimeBy(9500)
            waitFor("Guía")
            compose.onNodeWithText("Guía").performClick()
            waitFor("Guía · paso 1 de 2")
            compose.onNodeWithText("Siguiente").performClick()
            waitFor("Guía · paso 2 de 2")
            compose.onNodeWithText("Volvé por Calle de prueba").assertExists()
            compose.onNodeWithText("Ocultar guía").performClick()
            compose.onNodeWithText("Guía").assertExists()
        } finally { cache.delete() }
    }

    @Test fun draggingHandlesResizesAndPanningMovesTheSelectedGeographicArea() {
        val polygon = AtomicReference<List<MapCoordinate>>(emptyList())
        compose.setContent {
            TerritoryMap(Modifier.fillMaxSize(), center = MapCoordinate(-34.6037,-58.3816), selectingZone = true, onSelectionChanged = { polygon.set(it) })
        }
        compose.waitUntil(15_000) { polygon.get().size == 4 }
        // Wait for the initial camera animation to settle before comparing geographic bounds.
        Thread.sleep(1200)
        val original = DownloadArea.from(polygon.get())!!
        val handle = compose.onNodeWithContentDescription("Esquina 1 de la zona").fetchSemanticsNode().boundsInWindow
        capture("before", "Handle=$handle; polygon=${polygon.get()}")
        drag(handle.center.x, handle.center.y, handle.center.x + 60f, handle.center.y + 80f)
        capture("after", "Handle=$handle; polygon=${polygon.get()}")
        compose.waitUntil(5000) { DownloadArea.from(polygon.get())?.let { it.widthM < original.widthM * 0.97 } == true }
        val resized = DownloadArea.from(polygon.get())!!
        assertTrue(resized.heightM < original.heightM)
        // MapLibre's native gesture detector uses Android's real event clock, not Compose's
        // virtual touch clock. Dispatch this part through the OS as a physical gesture.
        val bounds = compose.onNodeWithContentDescription("Mapa del territorio").fetchSemanticsNode().boundsInWindow
        drag(bounds.left + bounds.width * 0.55f, bounds.top + bounds.height * 0.48f,
            bounds.left + bounds.width * 0.25f, bounds.top + bounds.height * 0.48f)
        compose.waitUntil(8000) { DownloadArea.from(polygon.get())?.let { kotlin.math.abs(it.center.longitude - resized.center.longitude) > 0.00005 } == true }
        val moved = DownloadArea.from(polygon.get())!!
        assertEquals(resized.widthM, moved.widthM, resized.widthM * 0.02)
        assertTrue(com.rutacomercial.app.domain.Geo.validPolygon(polygon.get().map { it.latitude to it.longitude }))
    }

    // Both gestures use one real clock. Mixing a Compose virtual UP in the future with a native
    // DOWN can make Android ignore the second gesture even though both calls report success.
    private fun drag(startX: Float, startY: Float, endX: Float, endY: Float) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        val down = SystemClock.uptimeMillis()
        for (step in 0..30) {
            val fraction = step / 30f
            val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), when(step) { 0 -> MotionEvent.ACTION_DOWN; 30 -> MotionEvent.ACTION_UP; else -> MotionEvent.ACTION_MOVE },
                startX + (endX-startX)*fraction, startY + (endY-startY)*fraction, 0)
            event.source = InputDevice.SOURCE_TOUCHSCREEN
            assertTrue("Android accepts the touch event", instrumentation.uiAutomation.injectInputEvent(event, true))
            event.recycle()
            SystemClock.sleep(20)
        }
    }

    private fun capture(name: String, geometry: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val root = instrumentation.targetContext.cacheDir
        java.io.File(root, "gesture-$name.txt").writeText(geometry)
        instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
            java.io.File(root, "gesture-$name.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }
}
