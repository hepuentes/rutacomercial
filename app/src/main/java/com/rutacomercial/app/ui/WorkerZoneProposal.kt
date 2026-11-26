package com.rutacomercial.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.rutacomercial.app.data.newId
import com.rutacomercial.app.domain.Geo
import com.rutacomercial.app.maps.*
import org.json.JSONObject

internal fun proposalKey(id:String,kind:String):String = java.util.UUID.nameUUIDFromBytes(
    ("RutaComercial/proposal/"+kind+"/"+java.util.UUID.fromString(id)).toByteArray(Charsets.UTF_8)).toString()

@Composable
internal fun WorkerZoneProposal(worker:String, position:MapCoordinate?, initialZone:List<MapCoordinate>,
    references:List<MapSector>,tracks:List<MapTrack>,reference:MapCoordinate?,referenceRequest:Int,onImport:()->Unit,
    onLocate:()->Unit, onShare:(JSONObject)->Unit, onClose:()->Unit) {
    val ctx=androidx.compose.ui.platform.LocalContext.current
    val nav=remember { PlaceNavigation() }
    val status by com.rutacomercial.app.tracking.TrackingRuntime.state.collectAsState()
    NavigationLocation(nav.routing,status.isTracking,com.rutacomercial.app.tracking.TrackingController.hasLocationPermission(ctx))
    var menu by remember { mutableStateOf(false) }
    var anchor by remember { mutableStateOf<MapCoordinate?>(null) }
    var viewport by remember { mutableStateOf<MapCoordinate?>(null) }
    var encoded by rememberSaveable { mutableStateOf("[]") }
    var title by rememberSaveable { mutableStateOf("") }
    var person by rememberSaveable { mutableStateOf(worker) }
    var details by rememberSaveable { mutableStateOf(false) }
    var requested by rememberSaveable { mutableStateOf(false) }
    var request by remember { mutableIntStateOf(0) }
    var center by remember { mutableStateOf(position) }
    var proposalId by rememberSaveable { mutableStateOf(newId()) }
    LaunchedEffect(referenceRequest,reference) { if(reference!=null) { nav.close();anchor=reference;center=reference;request++ } }
    val points=remember(encoded) { Geo.parsePolygon(encoded) }
    val valid=remember(points) { Geo.validPolygon(points) }
    val polygon=remember(points) { points.map { MapCoordinate(it.first,it.second) } }
    val frame=remember { if(position==null) initialZone else emptyList() }
    LaunchedEffect(position,requested) {
        if(requested && position!=null) { center=position;request++;requested=false }
    }
    BackHandler { if(details) details=false else onClose() }
    Column(Modifier.fillMaxSize().systemBarsPadding().padding(8.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment=androidx.compose.ui.Alignment.CenterVertically) {
            Text("Proponer zona",Modifier.weight(1f),style=MaterialTheme.typography.titleLarge)
            Box {
                TextButton(onClick={menu=true}) { Text("Ubicar lugar") }
                DropdownMenu(menu,{menu=false}) {
                    DropdownMenuItem(text={Text("Buscar un lugar")},onClick={menu=false;nav.search=true})
                    DropdownMenuItem(text={Text("Consultar en Google Maps")},onClick={menu=false;consultGoogleMap(ctx,viewport ?: center)})
                    DropdownMenuItem(text={Text("Traer ubicación de Google Maps")},onClick={menu=false;onImport()})
                }
            }
        }
        Text("Dibujá el borde con un dedo. Con dos podés mover el mapa y acercarlo.",style=MaterialTheme.typography.bodySmall)
        TerritoryMap(Modifier.fillMaxWidth().weight(1f),selectingZone=true,freehand=true,
            center=center,centerRequest=request,fitArea=if(center==null) frame else emptyList(),
            navigation=nav,onNavigateLocate=onLocate,onCameraIdle={viewport=it},onDrawHere={p->anchor=p;center=p;request++;nav.close()},
            accuracyMeters=status.accuracyMeters,currentLocation=position,tracks=tracks,
            businesses=anchor?.let { listOf(MapBusiness("reference","Referencia",it.latitude,it.longitude,"#B04C0B")) }.orEmpty(),
            sectors=references+if(valid) listOf(MapSector("draft",title,"#9A3474",polygon,true)) else emptyList(),selectionColor="#9A3474",
            onSelectionChanged={ encoded=Geo.polygonJson(it.map { p->p.latitude to p.longitude });proposalId=newId() })
        if(references.isNotEmpty()) Text("Bordes grises · zonas recibidas     Azul · recorrido GPS",style=MaterialTheme.typography.labelSmall)
        if(points.isNotEmpty() && !valid) Text("Volvé a dibujar el borde sin cruzar el trazo.")
        Row(horizontalArrangement=Arrangement.spacedBy(6.dp)) {
            FilledTonalButton(onClick={requested=true;onLocate()},modifier=Modifier.weight(1f)) { Text("Mi ubicación") }
            Button(enabled=valid,onClick={details=true},modifier=Modifier.weight(1f)) { Text("Continuar") }
        }
        TextButton(onClick=onClose) { Text("Volver") }
    }
    if(details) AlertDialog(onDismissRequest={details=false},title={Text("Enviar al Owner")},text={
        Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            Field("Nombre de la zona",title,{title=it.take(200);proposalId=newId()})
            Field("Repartidor",person,{person=it.take(100);proposalId=newId()})
            Text("El Owner revisa el límite y te devuelve la tarea para iniciar el recorrido.")
        }
    },confirmButton={Button(enabled=title.isNotBlank() && person.isNotBlank(),onClick={
        onShare(JSONObject().put("version",1).put("kind","proposal").put("id",proposalId)
            .put("name",title.trim()).put("worker",person.trim()).put("polygon",encoded))
    }) { Text("Compartir") }},dismissButton={TextButton(onClick={details=false}) { Text("Editar borde") }})
}

internal fun consultGoogleMap(context:android.content.Context,point:MapCoordinate?) {
    val url=android.net.Uri.parse("https://www.google.com/maps/@").buildUpon().appendQueryParameter("api","1")
        .appendQueryParameter("map_action","map")
    point?.let { url.appendQueryParameter("center",it.latitude.toString()+","+it.longitude).appendQueryParameter("zoom","16") }
    try { context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW,url.build())) }
    catch(_:android.content.ActivityNotFoundException) { android.widget.Toast.makeText(context,"Google Maps no está disponible.",android.widget.Toast.LENGTH_SHORT).show() }
}
