package com.rutacomercial.app.ui

import android.app.PictureInPictureParams
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Bundle
import android.util.Rational
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.activity.compose.LocalActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.rutacomercial.app.RutaApplication
import com.rutacomercial.app.maps.TerritoryMap
import com.rutacomercial.app.tracking.TrackingRuntime

abstract class TrackingMapActivity : ComponentActivity() {
    internal var floatingEligible=false
    private var leavingForAction=false
    private var compact by mutableStateOf(false)

    override fun onResume() {
        super.onResume()
        compact=isInPictureInPictureMode
        leavingForAction=false
    }

    override fun startActivityForResult(intent:Intent,requestCode:Int,options:Bundle?) {
        leavingForAction=true
        super.startActivityForResult(intent,requestCode,options)
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if(floatingEligible && !leavingForAction && !isFinishing && !isInPictureInPictureMode &&
            packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)) {
            runCatching { enterPictureInPictureMode(PictureInPictureParams.Builder().setAspectRatio(Rational(4,3)).build()) }
        }
    }

    override fun onPictureInPictureModeChanged(inPip:Boolean, config:Configuration) {
        super.onPictureInPictureModeChanged(inPip,config)
        compact=inPip
    }

    @Composable
    protected fun MapWindow(content:@Composable ()->Unit) {
        val holder=rememberSaveableStateHolder()
        if(compact) {
            val repo=(application as RutaApplication).repository
            val vm:RutaViewModel=viewModel(factory=remember { RutaViewModel.factory(repo) })
            val data by vm.data.collectAsStateWithLifecycle()
            val status by TrackingRuntime.state.collectAsStateWithLifecycle()
            val now by vm.now.collectAsStateWithLifecycle()
            val lines=remember(data.points,data.activeJourney?.id) { mapTracks(data,data.activeJourney?.id) }
            val fresh=status.fixAt?.let { now-it in -2000L..30000L }==true
            val journeyId=data.activeJourney?.id
            val assignment=data.audit.lastOrNull { it.entityId==journeyId && it.action=="ASSIGNMENT" }?.detail
            val plan=data.audit.lastOrNull { it.entityId==journeyId && it.action=="WALK_PLAN" }?.detail
            val task by produceState<org.json.JSONObject?>(null,assignment) {
                value=kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    assignment?.let { runCatching {
                        val id=java.util.UUID.fromString(it)
                        org.json.JSONObject(java.io.File(filesDir,"assignments/"+id+".task").readText())
                    }.getOrNull() }
                }
            }
            val sector=data.sectors.find { it.id==plan?.substringBefore('/') }
            val geometry=task?.optString("polygon") ?: data.sectorRevisions.find {
                it.sectorId==sector?.id && it.revision==plan?.substringAfter('/')?.toIntOrNull()
            }?.polygonJson ?: sector?.polygonJson
            val outline=remember(geometry) { geometry?.let(com.rutacomercial.app.domain.Geo::parsePolygon).orEmpty().map {
                com.rutacomercial.app.maps.MapCoordinate(it.first,it.second)
            } }
            val zones=remember(outline) { if(outline.isEmpty()) emptyList() else listOf(
                com.rutacomercial.app.maps.MapSector(assignment ?: sector?.id.orEmpty(),task?.optString("name") ?: sector?.name.orEmpty(),sector?.color ?: "#087F75",outline,true)) }
            val here=status.position ?: lines.lastOrNull()?.points?.lastOrNull()
            // Keep the dot at the centre while including the assigned boundary in the small window.
            val frame=remember(here,outline) {
                if(here==null || outline.isEmpty()) outline else {
                    val lat=outline.maxOf { kotlin.math.abs(it.latitude-here.latitude) }.coerceAtLeast(.0008)
                    val lon=outline.maxOf { kotlin.math.abs(it.longitude-here.longitude) }.coerceAtLeast(.0008)
                    listOf(com.rutacomercial.app.maps.MapCoordinate((here.latitude-lat).coerceAtLeast(-85.0),(here.longitude-lon).coerceAtLeast(-180.0)),
                        com.rutacomercial.app.maps.MapCoordinate((here.latitude+lat).coerceAtMost(85.0),(here.longitude+lon).coerceAtMost(180.0)))
                }
            }
            TerritoryMap(Modifier.fillMaxSize(),compactNavigation=true,sectors=zones,fitArea=frame,tracks=lines,currentLocation=status.position,
                locationFresh=fresh,accuracyMeters=status.accuracyMeters,center=here.takeIf { frame.isEmpty() })
        } else holder.SaveableStateProvider("full") { content() }
    }
}

@Composable
internal fun FloatingMapWhileTracking(enabled:Boolean) {
    val activity=LocalActivity.current as? TrackingMapActivity ?: return
    DisposableEffect(activity,enabled) {
        activity.floatingEligible=enabled
        onDispose { activity.floatingEligible=false }
    }
}
