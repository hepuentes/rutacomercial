package com.rutacomercial.app.maps

import android.location.Geocoder
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.rutacomercial.app.tracking.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.update
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap

internal data class Place(val name:String,val point:MapCoordinate)
internal class PlaceNavigation {
    var search by mutableStateOf(false)
    var place by mutableStateOf<Place?>(null)
    var zone by mutableStateOf(emptyList<MapCoordinate>())
    var routing by mutableStateOf(false)
    var request by mutableIntStateOf(0)
    var route by mutableStateOf<FootRoute?>(null)
    var message by mutableStateOf("")
    var focus by mutableIntStateOf(0)
    fun select(value:Place) {
        zone=emptyList();routing=false;route=null;message="";place=value;search=false;focus++
    }
    fun start(value:Place) { select(value);routing=true;request++ }
    fun startZone(name:String,outline:List<MapCoordinate>) {
        if(outline.isEmpty()) return
        start(Place(name,outline.first()))
        zone=outline
    }
    fun close() { routing=false;route=null;message="";place=null;search=false }
}

/** A foreground preview only. The journey service owns GPS whenever recording is active. */
@Composable
internal fun NavigationLocation(enabled:Boolean,serviceActive:Boolean,allowed:Boolean) {
    val ctx=LocalContext.current
    val life=LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(enabled,serviceActive,allowed,life) {
        if(enabled && !serviceActive && allowed) life.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            val source=LocationPreview(ctx,true)
            try { source.start({ fix-> TrackingRuntime.mutable.update { s->
                if(s.isTracking || fix.time < (s.fixAt ?: 0L)) s else s.copy(position=MapCoordinate(fix.latitude,fix.longitude),
                    fixAt=fix.time,accuracyMeters=fix.accuracy,locationProblem=null)
            } },{});awaitCancellation() } finally { source.stop() }
        }
    }
}

@Composable
internal fun PlaceSearch(near:MapCoordinate?,onPick:(Place)->Unit,onClose:()->Unit) {
    val ctx=LocalContext.current
    val scope=rememberCoroutineScope()
    var input by remember { mutableStateOf("") }
    var working by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf("") }
    var places by remember { mutableStateOf(emptyList<Place>()) }
    AlertDialog(onDismissRequest=onClose,title={Text("Buscar un lugar")},text={
        Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(4.dp)) {
            OutlinedTextField(input,{input=it.take(250);places=emptyList();msg=""},singleLine=true,enabled=!working,label={Text("Lugar, dirección o coordenadas")})
            if(working) LinearProgressIndicator()
            if(msg.isNotBlank()) Text(msg)
            places.forEach { place->TextButton(onClick={onPick(place)}) { Text(place.name) } }
        }
    },dismissButton={TextButton(onClick=onClose) { Text("Cerrar") }},confirmButton={
        Button(enabled=input.isNotBlank() && !working,onClick={
            val query=input.trim()
            val coords=SharedMapLinks.coordinates(query)
            if(coords!=null) onPick(Place(query,coords)) else scope.launch {
                working=true;msg=""
                try { places=withContext(Dispatchers.IO) {
                    @Suppress("DEPRECATION")
                    fun results():List<android.location.Address> {
                        val gc=Geocoder(ctx)
                        if(near!=null) {
                            val local=gc.getFromLocationName(query,5,(near.latitude-.25).coerceAtLeast(-90.0),(near.longitude-.25).coerceAtLeast(-180.0),
                                (near.latitude+.25).coerceAtMost(90.0),(near.longitude+.25).coerceAtMost(180.0)).orEmpty()
                            if(local.isNotEmpty()) return local
                        }
                        return gc.getFromLocationName(query,5).orEmpty()
                    }
                    results().filter { it.hasLatitude() && it.hasLongitude() }.map {
                        Place(it.getAddressLine(0) ?: it.featureName ?: query,MapCoordinate(it.latitude,it.longitude))
                    }.distinct()
                };if(places.isEmpty()) msg="Sin resultados. Probá con la calle y la ciudad."
                } catch(e:CancellationException) { throw e } catch(e:Exception) { msg="Búsqueda no disponible. Revisá la conexión o ingresá coordenadas." }
                finally { working=false }
            }
        }) { Text("Buscar") }
    })
}

@Composable
internal fun BoxScope.NavigationOverlay(nav:PlaceNavigation,map:MapLibreMap?,here:MapCoordinate?,accuracy:Float?,tick:Int,
    onExplore:()->Unit,onLocate:()->Unit,onDraw:((MapCoordinate)->Unit)?) {
    val dest=nav.place
    val route=nav.route
    val ctx=LocalContext.current
    val explore by rememberUpdatedState(onExplore)
    var attempted by remember(nav) { mutableIntStateOf(-1) }
    LaunchedEffect(nav.routing,nav.request,here!=null) {
        if(!nav.routing || dest==null || attempted==nav.request) return@LaunchedEffect
        if(here==null) { nav.message="Buscando tu ubicación…";onLocate();return@LaunchedEffect }
        attempted=nav.request
        nav.message="Calculando camino a pie…"
        try {
            if(nav.zone.isNotEmpty() && com.rutacomercial.app.domain.Geo.contains(nav.zone.map { it.latitude to it.longitude },here.latitude,here.longitude)) {
                nav.message="Ya estás dentro de la zona";return@LaunchedEffect
            }
            val target=zoneEntrance(nav.zone,here) ?: dest.point
            if(target!=dest.point) nav.place=dest.copy(point=target)
            if(metersBetween(here,target)<10) nav.message="Ya estás junto al destino"
            else { nav.route=FootRouter.route(here,target);nav.message="" }
        } catch(e:CancellationException) { attempted=-1;throw e }
        catch(e:Exception) { nav.message="No se pudo calcular el camino. Revisá la conexión o elegí otro punto.";nav.routing=false }
    }
    LaunchedEffect(map,nav.focus,route) {
        if(map==null || dest==null) return@LaunchedEffect
        explore()
        if(route==null) map.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(dest.point.latitude,dest.point.longitude),16.0),350)
        else {
            val b=LatLngBounds.Builder()
            route.steps.forEach { step->step.points.forEach { b.include(LatLng(it.latitude,it.longitude)) } }
            map.animateCamera(CameraUpdateFactory.newLatLngBounds(b.build(),(28*ctx.resources.displayMetrics.density).toInt()),350)
        }
    }
    if(nav.search) PlaceSearch(map?.cameraPosition?.target?.let { MapCoordinate(it.latitude,it.longitude) } ?: here,nav::select) { nav.search=false }
    if(dest==null) return
    var index by remember(route) { mutableIntStateOf(0) }
    var arrived by remember(route) { mutableStateOf(false) }
    val step=route?.steps?.getOrNull(index)
    val follower=remember(step) { step?.let { StepFollower(it.points) } }
    var progress by remember(step) { mutableStateOf<StepProgress?>(null) }
    LaunchedEffect(here,accuracy,follower) {
        progress=here?.let { follower?.sample(it,accuracy) }
        if(progress?.advance==true && route!=null) {
            if(index<route.steps.lastIndex) index++ else arrived=true
        }
    }
    if(step!=null && map!=null && !arrived) GuideFlow(step,map,tick)
    Surface(Modifier.align(Alignment.BottomCenter).padding(start=6.dp,end=6.dp,bottom=45.dp),shape=MaterialTheme.shapes.medium,tonalElevation=4.dp) {
        Column(Modifier.padding(horizontal=10.dp,vertical=3.dp)) {
            Row(verticalAlignment=Alignment.CenterVertically) {
                Text(if(route!=null) "A pie · "+route.distance.toInt()+" m" else dest.name,Modifier.weight(1f),maxLines=2,style=MaterialTheme.typography.titleSmall)
                TextButton(onClick=nav::close) { Text("Cerrar") }
            }
            if(nav.message.isNotEmpty()) Text(nav.message,style=MaterialTheme.typography.bodySmall)
            if(step!=null && route!=null) {
                val next=route.steps.getOrNull(index+1)
                val near=progress?.let { it.remaining<45 && it.offRoute<=18 }==true
                Text(when { arrived->"⚑ Llegaste al final del camino"; near->turnArrow(step,next)+"  "+progress!!.remaining.toInt()+" m · "+(next?.street ?: "Llegada")
                    else->"↑  "+step.street },style=MaterialTheme.typography.titleMedium)
                Text("Ruta peatonal · OSRM / FOSSGIS",style=MaterialTheme.typography.labelSmall)
            }
            if(!nav.routing || progress?.offRoute?.let { it>35 }==true) Row {
                TextButton(onClick={nav.start(dest)}) { Text(if(step==null) "Cómo llegar" else "Recalcular") }
                if(onDraw!=null) TextButton(onClick={onDraw(dest.point)}) { Text("Dibujar aquí") }
            }
        }
    }
}
