package com.rutacomercial.app.ui

import android.app.PictureInPictureParams
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.rutacomercial.app.RutaApplication
import com.rutacomercial.app.data.Sector
import com.rutacomercial.app.maps.MapCoordinate
import com.rutacomercial.app.tracking.TrackingRuntime
import com.rutacomercial.app.tracking.TrackingStatus
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.maplibre.android.maps.MapView
import org.maplibre.android.style.sources.GeoJsonSource
import java.util.UUID

class FloatingZoneTest {
    private fun mapView(view:View):MapView? {
        if(view is MapView) return view
        if(view is ViewGroup) for(i in 0 until view.childCount) mapView(view.getChildAt(i))?.let { return it }
        return null
    }

    @Test fun visibleFloatingWindowFollowsMovementWithAssignedBoundary() {
        val instrument=InstrumentationRegistry.getInstrumentation()
        val app=instrument.targetContext.applicationContext as RutaApplication
        val repo=app.repository
        val scenario=ActivityScenario.launch(AssignmentActivity::class.java)
        val sector=runBlocking {
            repo.saveSector(Sector(name="Mini "+UUID.randomUUID().toString().take(8),
                polygonJson="[[-32.890,-68.850],[-32.890,-68.845],[-32.885,-68.845]]"))
        }
        val journey=runBlocking { repo.startJourney(sectorId=sector.id) }
        lateinit var activity:AssignmentActivity
        fun locate(lat:Double) {
            TrackingRuntime.mutable.value=TrackingStatus(position=MapCoordinate(lat,-68.848),fixAt=System.currentTimeMillis(),accuracyMeters=5f)
        }
        fun checkFrame(lat:Double) {
            val limit=SystemClock.elapsedRealtime()+35000
            var matches=false
            while(!matches && SystemClock.elapsedRealtime()<limit) {
                instrument.runOnMainSync {
                    if(activity.isInPictureInPictureMode) mapView(activity.window.decorView)?.getMapAsync { map ->
                        val zones=map.style?.getSourceAs<GeoJsonSource>("rc-sectors-source")
                        val target=map.cameraPosition.target
                        matches=target!=null && kotlin.math.abs(target.latitude-lat)<.0002 &&
                            zones?.querySourceFeatures(null)?.any { it.getStringProperty("id")==sector.id }==true
                    }
                }
                SystemClock.sleep(200)
            }
            assertTrue("La cámara y el polígono deben actualizarse dentro de la ventana flotante",matches)
        }
        try {
            locate(-32.888)
            scenario.onActivity {
                activity=it
                assertTrue(it.enterPictureInPictureMode(PictureInPictureParams.Builder().build()))
            }
            checkFrame(-32.888)
            locate(-32.893)
            checkFrame(-32.893)
        } finally {
            runBlocking { repo.finishJourney(journey.id) }
            TrackingRuntime.mutable.value=TrackingStatus()
            scenario.close()
        }
    }
}
