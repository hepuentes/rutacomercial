package com.rutacomercial.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.rutacomercial.app.data.*
import com.rutacomercial.app.domain.Geo
import com.rutacomercial.app.maps.*

@Composable
internal fun SectorsScreen(vm:RutaViewModel,data:DataSnapshot,position:MapCoordinate?) {
    val work=zoneWork()
    var editing by remember{mutableStateOf(false)}
    var selected by remember{mutableStateOf<Sector?>(null)}
    var areaEditor by remember{mutableStateOf(false)}
    var selectedArea by remember{mutableStateOf<Area?>(null)}
    var showHistory by remember{mutableStateOf<Sector?>(null)}
    if(editing)SectorEditor(vm,data,selected,position){editing=false}
    else ScreenColumn {
        PageHeading("Territorio organizado","Delimitá sectores y mantené su historial aunque cambien los límites.")
        Button(onClick={selected=null;editing=true},modifier=Modifier.fillMaxWidth()){Text("Dibujar nuevo sector")}
        OutlinedButton(onClick={selectedArea=null;areaEditor=true},modifier=Modifier.fillMaxWidth()){Text("Crear ciudad, localidad o barrio")}
        if(data.sectors.isEmpty())InfoCard("Sin sectores","En Mapa, tocá Elegir zona para empezar con un rectángulo. Asignar una ciudad o barrio es opcional.")
        data.sectors.sortedBy{it.name}.forEach { s ->
            val visits=data.visits.filter{it.sectorId==s.id}
            val businesses=data.visibleBusinesses.filter{it.sectorId==s.id}
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                    Text("${s.code} · ${s.name}",style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.Bold)
                    Text("${work.label(s,data.sectors)} · revisión ${s.revision}",color=MaterialTheme.colorScheme.primary)
                    Text("Área: ${data.areas.firstOrNull{it.id==s.areaId}?.name ?: "Sin asignar"}")
                    Text("${businesses.size} negocios registrados · ${visits.map{it.businessId}.distinct().size} con visitas históricas\n${visits.size} visitas · ${visits.sumOf{it.cards} + data.activeQuickDeliveries.count { it.sectorId == s.id }} tarjetas declaradas")
                    Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                        Button(onClick={selected=s;editing=true}){Text("Editar sector")}
                        TextButton(onClick={showHistory=s}){Text("Ver revisiones")}
                    }
                }
            }
        }
        if(data.areas.isNotEmpty())Text("Ciudades y barrios",style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.Bold)
        data.areas.sortedBy{it.name}.forEach { a ->
            OutlinedCard(onClick={selectedArea=a;areaEditor=true},modifier=Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text(a.name,fontWeight=FontWeight.SemiBold)
                    Text("${areaKind(a.kind)}${a.parentId?.let{id->" · ${data.areas.firstOrNull{it.id==id}?.name ?: ""}"} ?: ""}",style=MaterialTheme.typography.bodySmall)
                }
            }
        }
        Text("El estado de un sector se establece manualmente. Completado no certifica la visita de todos los negocios existentes.",style=MaterialTheme.typography.bodySmall)
    }
    if(areaEditor)AreaEditor(vm,data,selectedArea){areaEditor=false}
    showHistory?.let{s->SectorHistory(data,s){showHistory=null}}
}

internal fun sectorState(value:String)=when(value){"ACTIVE"->"En curso";"COMPLETED"->"Completada";else->"Pendiente"}
private fun areaKind(value:String)=when(value){"LOCALITY"->"Localidad";"NEIGHBORHOOD"->"Barrio";else->"Ciudad"}

@Composable
private fun SectorEditor(vm:RutaViewModel,data:DataSnapshot,existing:Sector?,position:MapCoordinate?,onClose:()->Unit)=ScreenColumn {
    val id=remember{existing?.id ?: newId()}
    var name by remember{mutableStateOf(existing?.name ?: "")}
    var code by remember{mutableStateOf(existing?.code ?: "")}
    var color by remember{mutableStateOf(existing?.color ?: "#087F75")}
    var areaId by remember{mutableStateOf(existing?.areaId)}
    var status by remember{mutableStateOf(existing?.status ?: "PENDING")}
    var points by remember{mutableStateOf(existing?.let{Geo.parsePolygon(it.polygonJson).map{p->MapCoordinate(p.first,p.second)}} ?: emptyList())}
    PageHeading(if(existing==null)"Dibujar sector" else "Modificar sector","Tocá al menos tres puntos del mapa, siguiendo el contorno sin cruzar los lados.")
    TerritoryMap(modifier=Modifier.fillMaxWidth().mapHeight(.6f),sectors=mapSectors(data).filter{it.id!=existing?.id},draftPolygon=points,
        currentLocation=position,center=points.firstOrNull() ?: position,onMapTap={points=points+it})
    Text("${points.size} vértices seleccionados")
    Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick={points=points.dropLast(1)},enabled=points.isNotEmpty()){Text("Deshacer punto")}
        TextButton(onClick={points=emptyList()},enabled=points.isNotEmpty()){Text("Volver a dibujar")}
    }
    Field("Nombre del sector",name,{name=it});Field("Código único",code,{code=it})
    Choice("Color",color,listOf("#087F75" to "Verde", "#2167AD" to "Azul", "#A26012" to "Ocre", "#833CA3" to "Violeta", "#C1404B" to "Rojo")){color=it}
    Choice("Ciudad, localidad o barrio",areaId,listOf(null to "Sin asignar")+data.areas.map{it.id to it.name}){areaId=it}
    Choice("Estado operativo",status,listOf("PENDING","ACTIVE","COMPLETED").map{it to sectorState(it)}){status=it}
    if(existing!=null)Text("Se conservará la revisión anterior y todas sus visitas. Los negocios mantienen su sector asignado hasta que edites su ficha.",style=MaterialTheme.typography.bodySmall)
    Button(onClick={vm.perform("Sector guardado",onClose){
        vm.repository.saveSector((existing ?: Sector(id=id,name=name)).copy(name=name,code=code,color=color,areaId=areaId,status=status,polygonJson=Geo.polygonJson(points.map{it.latitude to it.longitude})))
    }},modifier=Modifier.fillMaxWidth(),enabled=points.size>=3){Text("Guardar sector")}
    TextButton(onClick=onClose,modifier=Modifier.fillMaxWidth()){Text("Cancelar")}
}

@Composable
private fun AreaEditor(vm:RutaViewModel,data:DataSnapshot,existing:Area?,onClose:()->Unit) {
    val id=remember{existing?.id ?: newId()}
    var name by remember{mutableStateOf(existing?.name ?: "")}
    var kind by remember{mutableStateOf(existing?.kind ?: "CITY")}
    var parent by remember{mutableStateOf(existing?.parentId)}
    EditorDialog(if(existing==null)"Nueva área" else "Editar área",onClose,{vm.perform("Área guardada",onClose){vm.repository.saveArea(Area(id=id,name=name,kind=kind,parentId=parent))}}) {
        Field("Nombre",name,{name=it})
        Choice("Tipo",kind,listOf("CITY","LOCALITY","NEIGHBORHOOD").map{it to areaKind(it)}){kind=it}
        Choice("Dentro de",parent,listOf(null to "Sin área superior")+data.areas.filter{it.id!=id}.map{it.id to it.name}){parent=it}
    }
}

@Composable
private fun SectorHistory(data:DataSnapshot,sector:Sector,onClose:()->Unit) {
    val revisions=data.sectorRevisions.filter{it.sectorId==sector.id}.sortedByDescending{it.revision}
    var revision by remember{mutableStateOf(revisions.firstOrNull()?.revision ?: sector.revision)}
    val chosen=revisions.firstOrNull{it.revision==revision}
    val polygon=Geo.parsePolygon(chosen?.polygonJson ?: sector.polygonJson).map{MapCoordinate(it.first,it.second)}
    AlertDialog(onDismissRequest=onClose,title={Text("Límites de ${sector.name}")},text={Column(verticalArrangement=Arrangement.spacedBy(10.dp)){
        Choice("Revisión",revision,revisions.map{it.revision to "${it.revision} · ${dateText(it.changedAt)}"}){revision=it}
        TerritoryMap(Modifier.fillMaxWidth().mapHeight(.55f),sectors=listOf(MapSector(sector.id,sector.name,sector.color,polygon)),center=polygon.firstOrNull())
        Text("Las visitas conservan el sector declarado en su fecha. Los cambios de límites no reescriben el historial.",style=MaterialTheme.typography.bodySmall)
    }},confirmButton={TextButton(onClick=onClose){Text("Cerrar")}})
}

@Composable
internal fun RouteScreen(vm:RutaViewModel,data:DataSnapshot,position:MapCoordinate?,onOpen:(String)->Unit)=ScreenColumn {
    PageHeading("Planificá tus paradas","Seleccioná negocios pendientes y ordenalos a tu criterio. No se calcula una ruta peatonal.")
    var sectorId by remember{mutableStateOf<String?>(null)}
    var priority by remember{mutableStateOf(-1)}
    Choice("Sector",sectorId,listOf(null to "Todos")+data.sectors.map{it.id to it.name}){sectorId=it}
    Choice("Prioridad mínima",priority,listOf(-1 to "Todas",1 to "Alta",2 to "Urgente")){priority=it}
    val stops=data.routeStops.sortedBy{it.position}
    val ids=stops.map{it.businessId}
    Text("Recorrido manual · ${stops.size} paradas",style=MaterialTheme.typography.titleLarge)
    if(stops.isEmpty())InfoCard("Sin paradas seleccionadas","Agregá negocios de la lista de pendientes. El orden se guarda aunque cierres la aplicación.")
    stops.forEachIndexed { index,stop ->
        val b=data.visibleBusinesses.firstOrNull{it.id==stop.businessId}
        if(b!=null)Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                Text("${index+1}. ${b.name}",fontWeight=FontWeight.Bold)
                Text("${VisitStatus.labelOf(b.status)} · ${b.address}")
                Row(horizontalArrangement=Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick={vm.perform{val list=ids.toMutableList();java.util.Collections.swap(list,index,index-1);vm.repository.setRoute(list)}},enabled=index>0){Text("Subir")}
                    TextButton(onClick={vm.perform{val list=ids.toMutableList();java.util.Collections.swap(list,index,index+1);vm.repository.setRoute(list)}},enabled=index<stops.lastIndex){Text("Bajar")}
                    TextButton(onClick={vm.perform{vm.repository.removeRouteStop(b.id)}}){Text("Quitar")}
                    TextButton(onClick={onOpen(b.id)}){Text("Abrir")}
                }
            }
        }
    }
    Text("Negocios pendientes",style=MaterialTheme.typography.titleLarge)
    var candidates=data.visibleBusinesses.filter{it.id !in ids && it.status in listOf("PENDING","FOLLOW_UP","PENDING_VERIFICATION","INTERESTED","CLOSED") && (sectorId==null || it.sectorId==sectorId) && it.priority>=priority}
    candidates=if(position==null)candidates.sortedByDescending{it.priority} else candidates.sortedBy{Geo.distanceM(position.latitude,position.longitude,it.latitude,it.longitude)}
    if(position!=null)Text("Ordenados por distancia en línea recta desde la última ubicación GPS; no representa el trayecto a pie.",style=MaterialTheme.typography.bodySmall)
    if(candidates.isEmpty())Text("No hay pendientes con estos filtros.")
    candidates.forEach{b->OutlinedCard(Modifier.fillMaxWidth()){
        Column(Modifier.padding(14.dp),verticalArrangement=Arrangement.spacedBy(6.dp)){
            Text(b.name,fontWeight=FontWeight.Bold)
            Text("${VisitStatus.labelOf(b.status)} · ${if(b.priority==2)"Urgente" else if(b.priority==1)"Alta prioridad" else "Normal"}")
            if(position!=null)Text("${number(Geo.distanceM(position.latitude,position.longitude,b.latitude,b.longitude),0)} m en línea recta")
            Button(onClick={vm.perform("Parada agregada"){vm.repository.addRouteStop(b.id)}}){Text("Agregar al recorrido")}
        }
    }}
}
