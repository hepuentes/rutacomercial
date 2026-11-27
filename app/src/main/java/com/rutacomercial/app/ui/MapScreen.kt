package com.rutacomercial.app.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rutacomercial.app.data.*
import com.rutacomercial.app.domain.Geo
import com.rutacomercial.app.maps.*
import com.rutacomercial.app.tracking.TrackingStatus
import java.time.LocalDate
import java.time.ZoneId

internal data class ZoneRequest(val id: String = newId(), val point: MapCoordinate)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MapScreen(vm: RutaViewModel, data: DataSnapshot, tracking: TrackingStatus,
    selectedJourneyId: String?, focus: MapCoordinate?, onStart: () -> Unit, onPause: () -> Unit, onResume: () -> Unit,
    onFinish: () -> Unit, onBusiness: (String) -> Unit, onLive: () -> Unit, onLocate: () -> Unit, onPointSelected: (MapCoordinate) -> Unit,
    zoneRequest: ZoneRequest? = null, onZoneRequestConsumed: () -> Unit = {}, onImportLocation: () -> Unit = {}) {
    val ownerEdition = !com.rutacomercial.app.BuildConfig.WORKER_EDITION
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("map_workspace", Context.MODE_PRIVATE) }
    var sectorId by rememberSaveable { mutableStateOf(prefs.getString("sector", null)) }
    val sector = data.sectors.firstOrNull { it.id == sectorId }
    val work = zoneWork()
    val assigned = remember(work, sector, data.sectors) { sector?.let { work.tasksFor(it, data.sectors) }.orEmpty() }
    val reports = remember(work, assigned) { work.reportsFor(assigned) }
    val latestTask = assigned.firstOrNull()
    val latestReport = reports.firstOrNull { it.taskId == latestTask?.id }
    val personal = remember(data.audit, sectorId) { sectorId?.let { previousJourneys(data,it) }.orEmpty() }
    val progressLabel = sector?.let { work.label(it,data.sectors) } ?: "Sin zona elegida"
    val polygon = remember(sector?.polygonJson) { sector?.let { Geo.parsePolygon(it.polygonJson).map { p -> MapCoordinate(p.first, p.second) } }.orEmpty() }
    val area = remember(polygon) { DownloadArea.from(polygon) }
    val journey = if (selectedJourneyId != null) data.journeys.firstOrNull { it.id == selectedJourneyId } else data.activeJourney
    var cameraCenter by remember(selectedJourneyId, focus) { mutableStateOf(focus ?: data.points.firstOrNull { selectedJourneyId != null && it.journeyId == selectedJourneyId }?.let { MapCoordinate(it.latitude,it.longitude) } ?: area?.center ?: tracking.position) }
    var viewportCenter by remember { mutableStateOf(cameraCenter) }
    var centerRequest by remember { mutableIntStateOf(0) }
    var allZones by rememberSaveable { mutableStateOf(false) }
    var waitingLocation by remember { mutableStateOf(false) }
    var point by remember { mutableStateOf<MapCoordinate?>(null) }
    var editing by rememberSaveable { mutableStateOf(false) }
    var draft by remember { mutableStateOf(emptyList<MapCoordinate>()) }
    var outlineFit by remember { mutableStateOf(emptyList<MapCoordinate>()) }
    var movingDraft by rememberSaveable { mutableStateOf(false) }
    var sharedPin by remember { mutableStateOf<MapCoordinate?>(null) }
    var freehand by rememberSaveable { mutableStateOf(false) }
    var zoneName by rememberSaveable { mutableStateOf("") }
    var draftId by rememberSaveable { mutableStateOf(newId()) }
    var sheet by remember { mutableStateOf<String?>(null) }
    var addBusiness by remember { mutableStateOf(false) }
    var incident by remember { mutableStateOf(false) }
    var showPrevious by rememberSaveable { mutableStateOf(false) }
    // Owner: today's workers drawn on the main map, refreshed with each progress report.
    var live by rememberSaveable { mutableStateOf(false) }
    var userMovedMap by remember { mutableStateOf(false) }
    var followLocation by remember { mutableStateOf(tracking.isTracking && selectedJourneyId == null && focus == null) }
    var rename by remember { mutableStateOf(false) }
    var renamedZone by remember { mutableStateOf("") }
    FloatingMapWhileTracking(journey?.state=="ACTIVE" && selectedJourneyId==null && !editing && sheet==null && !incident && !addBusiness)
    val busy by vm.busy.collectAsStateWithLifecycle()
    val today = LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
    val tracks = remember(data.points, selectedJourneyId, journey?.id, showPrevious, today, personal) {
        data.points.filter { if (selectedJourneyId != null) it.journeyId == selectedJourneyId else it.journeyId in personal || it.at >= today || it.journeyId == journey?.id || showPrevious && it.at >= today - 30L * 86_400_000 }
            .groupBy { it.journeyId to it.segment }.map { (key, values) ->
                val sorted = values.sortedBy { it.at }
                MapTrack("${key.first}-${key.second}", sorted.map { MapCoordinate(it.latitude,it.longitude) }, if (sorted.last().at < today && key.first != journey?.id) "#8C8599" else "#374BD4",group=key.first)
            }
    }
    val visibleReports=if(allZones || editing) work.reports.filter { it.taskId !in work.closed } else reports
    val mapLines = remember(tracks,visibleReports,selectedJourneyId) {
        if(selectedJourneyId==null) visibleReports.flatMap { it.lines }+tracks else tracks
    }
    val mapMarks=remember(data.points,data.audit,personal,journey?.id,reports,selectedJourneyId) {
        val ids=if(selectedJourneyId!=null) setOf(selectedJourneyId) else personal+listOfNotNull(journey?.id)
        ids.flatMap { journeyMarks(data,it) } + if(selectedJourneyId==null) reports.flatMap { it.marks } else emptyList()
    }
    val sectors = remember(data.sectors, sectorId) { mapSectors(data).map { shape ->
        shape.copy(selected=shape.id==sectorId)
    } }
    val newZoneColor = remember(data.sectors) { com.rutacomercial.app.domain.ZoneColors.next(data.sectors.map { it.color }) }
    val nav=remember(sectorId) { PlaceNavigation() }
    var guiding by remember { mutableStateOf(false) }
    NavigationLocation(guiding || nav.routing,tracking.isTracking,com.rutacomercial.app.tracking.TrackingController.hasLocationPermission(context))
    val now by vm.now.collectAsStateWithLifecycle()
    val freshLocation = tracking.fixAt?.let { now - it in -2000L..30_000L } == true
    val deliveries = remember(data.quickDeliveries, selectedJourneyId, today) {
        data.activeQuickDeliveries.filter { if(selectedJourneyId != null) it.journeyId == selectedJourneyId else it.at >= today }
            .mapNotNull { q -> if(q.latitude != null && q.longitude != null) MapCoordinate(q.latitude, q.longitude) else null }
    }
    val businesses = remember(data.businesses) { data.visibleBusinesses.map {
        MapBusiness(it.id,it.name,it.latitude,it.longitude, when(it.status) {
            "PENDING", "PENDING_VERIFICATION" -> "#C55C16"
            "FOLLOW_UP", "INTERESTED" -> "#7B3FC5"
            "DO_NOT_VISIT" -> "#6B7280"
            else -> "#087F75"
        })
    } }
    fun choose(id: String?) { allZones=false;sectorId = id; prefs.edit().putString("sector", id).apply() }
    fun followingOverview() {
        nav.close();followLocation=false;waitingLocation=false;userMovedMap=true;allZones=true;centerRequest++
    }
    fun createZone() {
        allZones=false
        followLocation = false; waitingLocation = false
        cameraCenter = point ?: viewportCenter ?: cameraCenter ?: MapCoordinate(-34.6037, -58.3816)
        centerRequest++
        sheet = null; point = null; draft = emptyList(); draftId = newId()
        zoneName = generateSequence(1) { it + 1 }.map { "Zona $it" }.first { name -> data.sectors.none { it.name.equals(name, true) } }
        sharedPin = null; movingDraft = false; freehand = false; editing = true
    }
    LaunchedEffect(data.sectors) {
        if (!com.rutacomercial.app.BuildConfig.WORKER_EDITION) data.sectors.filter { it.status == "ACTIVE" }.forEach { s ->
            runCatching { OfflineZones.ensure(context, "zone:" + s.id + ":" + s.polygonJson.hashCode(), Geo.parsePolygon(s.polygonJson).map { MapCoordinate(it.first, it.second) }, s.name) }
        }
    }
    var pendingZoneFit by remember { mutableStateOf(sectorId!=null && selectedJourneyId==null && focus==null) }
    LaunchedEffect(area,sectorId) {
        if(pendingZoneFit && area!=null && !editing && !userMovedMap && !tracking.isTracking) {
            cameraCenter=area.center;centerRequest++;pendingZoneFit=false
        }
    }
    LaunchedEffect(zoneRequest?.id) {
        zoneRequest?.let {
            waitingLocation = false; userMovedMap = true; point = it.point
            createZone()
            sharedPin = it.point
            onZoneRequestConsumed()
        }
    }
    LaunchedEffect(tracking.isTracking, selectedJourneyId, focus) {
        followLocation = tracking.isTracking && selectedJourneyId == null && focus == null && !editing
    }
    LaunchedEffect(tracking.position, tracking.fixAt, waitingLocation, followLocation, editing) {
        if (tracking.position != null && tracking.fixAt != null && System.currentTimeMillis()-tracking.fixAt in 0..30_000 && !editing && selectedJourneyId == null && (waitingLocation || followLocation)) {
            if(waitingLocation) centerRequest++
            cameraCenter = tracking.position
            if (tracking.fixAt != null && System.currentTimeMillis()-tracking.fixAt < 30_000) waitingLocation = false
        }
    }
    LaunchedEffect(tracking.locationProblem) { if (tracking.locationProblem != null) waitingLocation = false }
    // Opening the map never requests location. Only Mi ubicación / Start does.
    BackHandler(editing) { editing = false }
    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            val liveWorkers = remember(live, work, today) { if (live) workersToday(work.reports, today).filter { it.state != "FINISHED" } else emptyList() }
            TerritoryMap(Modifier.fillMaxSize(), tracks = mapLines + liveWorkers.flatMap { it.lines }, marks=if(editing) emptyList() else mapMarks, sectors = if (editing && freehand && draft.size >= 3)
                    sectors.filterNot { it.id==draftId } + MapSector(draftId,zoneName,newZoneColor,draft,true) else sectors, businesses = businesses + listOfNotNull(sharedPin?.takeIf { editing }?.let { MapBusiness("shared-point","Punto compartido",it.latitude,it.longitude,"#AC2754") }) +
                    liveWorkers.mapNotNull { w -> w.last?.let { MapBusiness("live-"+w.worker, w.worker+" · "+w.zone, it.latitude, it.longitude, if (w.state == "PAUSED") "#9A5B00" else "#1B6B3A") } },
                navigation=nav.takeIf { selectedJourneyId==null },onNavigateLocate=onLocate,
                onGuideActive={guiding=it;if(it && !com.rutacomercial.app.tracking.TrackingController.hasLocationPermission(context)) onLocate()},onDrawHere={p->nav.close();point=p;createZone();sharedPin=p},
                currentLocation = tracking.position, accuracyMeters = tracking.accuracyMeters, locationFresh = freshLocation,
                selectionColor = newZoneColor, deliveryPoints = deliveries, draftPolygon = if (editing) emptyList() else point?.let { listOf(it) }.orEmpty(),
                fitArea=if(allZones) sectors.flatMap { it.points } else if(editing && draftId==sectorId) polygon+outlineFit+reports.flatMap { r->r.lines.flatMap { it.points } } else emptyList(),
                center = cameraCenter.takeUnless { allZones || editing && draftId==sectorId }, centerRequest = centerRequest, selectingZone = editing, freehand = freehand, moveWhileDrawing = movingDraft, fieldTools = selectedJourneyId == null,
                followingLocation = followLocation && (tracking.isTracking || freshLocation),
                onRecenter = {
                    allZones=false;point=null;waitingLocation=true;followLocation=true;userMovedMap=false
                    onLocate()
                },
                onSelectionChanged = { draft = it }, onCameraIdle = { viewportCenter = it },
                onMapGesture = { userMovedMap = true; waitingLocation = false; followLocation = false },
                onMapTap = { point = it; onPointSelected(it) }, onMapLongPress = { point = it; createZone() }, onBusinessTap = onBusiness)
            Surface(Modifier.align(Alignment.TopCenter).padding(start=8.dp,end=if(!editing && selectedJourneyId==null) 64.dp else 8.dp,top=8.dp).fillMaxWidth(), shape = MaterialTheme.shapes.large, tonalElevation = 3.dp) {
                Row(Modifier.padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(if (editing) "Elegí tu zona" else if(allZones) "Todas las zonas · "+sectors.size else if (selectedJourneyId != null) "Recorrido guardado" else sector?.name ?: "RutaComercial", fontWeight = FontWeight.Bold)
                        Text(if (editing && freehand && movingDraft) "Mové el mapa o ajustá el zoom" else if (editing && freehand) "Seguí el límite de tu zona con el dedo" else if (editing) "Mové el mapa y arrastrá las esquinas" else if (selectedJourneyId != null) dateOnly(journey?.startedAt) else if(allZones) "Verde rayado · completada" else listOfNotNull(progressLabel,latestTask?.worker).joinToString(" · "), style = MaterialTheme.typography.labelSmall)
                    }
                    if (!editing) TextButton(onClick = { sheet = "options" }) { Text("Opciones") }
                }
            }
            if (selectedJourneyId == null && editing && !freehand) FilledTonalButton(onClick = {
                editing = false; point = null; waitingLocation = true; followLocation = true; userMovedMap = false
                tracking.position?.takeIf { tracking.fixAt != null && System.currentTimeMillis()-tracking.fixAt in 0..30_000 }?.let { cameraCenter = it }
                centerRequest++; onLocate()
            }, modifier = Modifier.align(Alignment.BottomEnd).padding(end = 12.dp, bottom = 58.dp)) { Text(if (tracking.isLocating) "Buscando ubicación…" else if (followLocation && tracking.isTracking) "Siguiendo ubicación" else "Mi ubicación") }
        }
        Surface(tonalElevation = 3.dp) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 3.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                if (editing) {
                    val bounds = remember(draft) { DownloadArea.from(draft) }
                    Row(verticalAlignment=Alignment.CenterVertically) {
                        TextButton(onClick={renamedZone=zoneName;rename=true},modifier=Modifier.weight(1f)) { Text(zoneName+" ✎",maxLines=1) }
                        if(freehand) TextButton(onClick={movingDraft=!movingDraft}) { Text(if(movingDraft) "Dibujar" else "Mover mapa") }
                        TextButton(onClick={movingDraft=false;freehand=!freehand; draft=emptyList()}) { Text(if(freehand) "Usar rectángulo" else "Trazar a mano") }
                    }
                    if(freehand) Text(if(movingDraft) "Mové el mapa o ajustá el zoom. El borde queda guardado." else if(draft.isEmpty()) "Un dedo dibuja. Dos dedos mueven el mapa." else if(!Geo.validPolygon(draft.map { it.latitude to it.longitude })) "Volvé a dibujar el borde sin cruzar el trazo." else "Podés volver a dibujar antes de guardar.")
                    Text(bounds?.let { "${it.widthM.toInt()} × ${it.heightM.toInt()} m" } ?: "Preparando selección…", style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { editing = false }, enabled = !busy, modifier = Modifier.weight(1f)) { Text("Cancelar") }
                        var overlapWarning by remember { mutableStateOf<String?>(null) }
                        fun saveZone() = vm.perform("Zona guardada", { choose(draftId); editing = false }) {
                            val original=vm.repository.dao.sector(draftId) ?: Sector(id=draftId,name=zoneName,color=newZoneColor)
                            vm.repository.saveSector(original.copy(name=zoneName,polygonJson=Geo.polygonJson(draft.map { it.latitude to it.longitude })))
                        }
                        overlapWarning?.let { warning -> AlertDialog(onDismissRequest = { overlapWarning = null }, title = { Text("Zona superpuesta") },
                            text = { Text("$warning Dos repartidores podrían cubrir las mismas calles.") },
                            dismissButton = { TextButton(onClick = { overlapWarning = null }) { Text("Ajustar borde") } },
                            confirmButton = { Button(onClick = { overlapWarning = null; saveZone() }) { Text("Guardar igual") } }) }
                        Button(onClick = {
                            val ring = draft.map { it.latitude to it.longitude }
                            val clash = data.sectors.filter { it.id != draftId }.map { it to com.rutacomercial.app.domain.ZoneOverlap.percent(ring, Geo.parsePolygon(it.polygonJson)) }
                                .filter { it.second >= 15 }.maxByOrNull { it.second }
                            if (clash != null) overlapWarning = "El ${clash.second} % de esta zona ya está en ${clash.first.name}." else saveZone()
                        }, enabled = !busy && zoneName.isNotBlank() && draft.size >= 3 && Geo.validPolygon(draft.map { it.latitude to it.longitude }), modifier = Modifier.weight(1f)) { Text("Usar esta zona") }
                    }
                } else if (selectedJourneyId != null) {
                    journey?.let { JourneyMeter(vm, it) }
                    Button(onClick = onLive, modifier = Modifier.fillMaxWidth()) { Text("Volver al mapa actual") }
                } else {
                    Text(tracking.locationProblem ?: if(journey==null && (latestTask!=null || sector?.status=="COMPLETED")) "Podés consultar el trabajo o continuar esta zona." else if (journey == null && !waitingLocation && !tracking.isLocating && !freshLocation) "Tocá el botón de centrar para ver tu ubicación" else tracking.message, style = MaterialTheme.typography.bodySmall, maxLines = 2)
                    if (tracking.position != null) Text(if(freshLocation) "Punto azul · precisión estimada ±${tracking.accuracyMeters?.toInt()} m" else "Punto gris: última ubicación, aún sin señal reciente", style = MaterialTheme.typography.labelSmall)
                    journey?.let { JourneyMeter(vm, it) }
                    
                    if (point != null) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Punto elegido en el mapa", Modifier.weight(1f), style = MaterialTheme.typography.labelMedium)
                            TextButton(onClick = { point = null }) { Text("Quitar") }
                        }
                    }
                    if (journey == null) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilledTonalButton(onClick = { if (data.sectors.isEmpty()) createZone() else sheet = "zones" }, modifier = Modifier.weight(1f)) { Text("Elegir zona") }
                        Button(onClick = {
                            when {
                                latestReport!=null -> context.startActivity(Intent(context,AssignmentActivity::class.java).putExtra("reportId",latestReport.id))
                                latestTask!=null || sector?.status=="COMPLETED" || personal.isNotEmpty() -> sheet="continue"
                                // The Owner plans and audits; walking it personally stays in Opciones.
                                ownerEdition -> if (sector == null) sheet = "zones" else context.startActivity(Intent(context,AssignmentActivity::class.java).putExtra("sector",sector.id))
                                else -> onStart()
                            }
                        }, enabled = !busy, modifier = Modifier.weight(1.3f)) {
                            Text(when { latestReport!=null -> "Ver informe"; latestTask!=null -> "Ver asignación"; sector?.status=="COMPLETED" || personal.isNotEmpty() -> "Continuar zona"; ownerEdition -> "Asignar zona"; else -> "Empezar recorrido" })
                        }
                    } else Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilledTonalButton(onClick = if(journey.state == "ACTIVE") onPause else onResume, modifier = Modifier.weight(1f)) { Text(if(journey.state == "ACTIVE") "Pausar" else "Reanudar") }
                        Button(onClick = onFinish, modifier = Modifier.weight(1f)) { Text("Terminar") }
                    }
                    if (point != null) TextButton(onClick = {
                        if (point == null) point = tracking.position?.takeIf { tracking.fixAt != null && System.currentTimeMillis()-tracking.fixAt < 60_000 }
                        if (point != null) addBusiness = true else vm.message("Tocá el mapa donde está el negocio para ubicarlo.")
                    }, modifier = Modifier.fillMaxWidth()) { Text("Agregar negocio") }
                }
            }
        }
    }
    if (sheet != null) ModalBottomSheet(onDismissRequest = { sheet = null }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            when (sheet) {
                "continue" -> sector?.let { s ->
                    Text(s.name,style=MaterialTheme.typography.titleLarge)
                    latestTask?.let { Text(progressLabel+" · "+it.worker) }
                    Button(onClick={sheet=null;context.startActivity(Intent(context,AssignmentActivity::class.java).putExtra("sector",s.id))}, modifier=Modifier.fillMaxWidth()) { Text("Asignar otra jornada") }
                    FilledTonalButton(onClick={sheet=null;onStart()},modifier=Modifier.fillMaxWidth()) { Text("Recorrer personalmente") }
                    latestTask?.let { task ->
                        TextButton(onClick={sheet=null;context.startActivity(Intent(context,AssignmentActivity::class.java).putExtra("taskId",task.id))}) { Text("Abrir asignación actual") }
                    }
                }
                "remove" -> sector?.let { s ->
                    Text("¿Eliminar la zona «${s.name}»?",style=MaterialTheme.typography.titleLarge)
                    Text("Se quitará del mapa y de las zonas disponibles. Los recorridos e informes guardados seguirán en el historial.")
                    Button(onClick={vm.perform("Zona eliminada",{choose(null); sheet=null}) { vm.repository.removeZone(s.id) }},enabled=!busy) { Text("Eliminar zona") }
                    TextButton(onClick={sheet=null}) { Text("Cancelar") }
                }
                "download" -> OfflineRegionsPanel(center = area?.center ?: point ?: viewportCenter ?: tracking.position, polygon = polygon, suggestedName = sector?.name ?: "Mi zona")
                "deliveries" -> QuickDeliveryPanel(vm, data, tracking, sector?.id)
                "zones" -> {
                    Text("Tu zona de trabajo", style = MaterialTheme.typography.titleLarge)
                    if(sectors.isNotEmpty()) FilledTonalButton(onClick={sheet=null;followingOverview()}) { Text("Ver todas las zonas") }
                    Button(onClick = { createZone() }, modifier = Modifier.fillMaxWidth()) { Text("Crear zona en el mapa") }
                    TextButton(onClick = { choose(null); sheet = null }) { Text("Recorrer sin delimitar zona") }
                    data.sectors.forEach { saved ->
                        OutlinedCard(onClick = {
                            choose(saved.id)
                            followLocation = false; waitingLocation = false
                            cameraCenter = DownloadArea.from(Geo.parsePolygon(saved.polygonJson).map { MapCoordinate(it.first,it.second) })?.center
                            centerRequest++; sheet = null
                        }, modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(14.dp)) {
                                Text("● ${saved.name}", color = androidx.compose.ui.graphics.Color(android.graphics.Color.parseColor(if(saved.status=="COMPLETED") "#68B75F" else saved.color)), fontWeight = FontWeight.Bold)
                                // Coverage from every track in the zone: worker reports and the Owner's own journeys.
                                val progress = remember(saved.id, saved.polygonJson, work, data.points.size) {
                                    val walked = work.reportsFor(work.tasksFor(saved, data.sectors)).flatMap { r -> r.lines.flatMap { it.points } } +
                                        previousJourneys(data, saved.id).flatMap { j -> mapTracks(data, j).flatMap { it.points } }
                                    com.rutacomercial.app.domain.ZoneCoverage.percent(Geo.parsePolygon(saved.polygonJson), walked.map { it.latitude to it.longitude }) ?: 0
                                }
                                val (stage, tone) = when { saved.status == "COMPLETED" || progress >= 90 -> "Completa" to "#2E7D32"; progress > 0 -> "En curso" to "#B26A00"; else -> "Sin empezar" to "#6B7280" }
                                val color = androidx.compose.ui.graphics.Color(android.graphics.Color.parseColor(tone))
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text("$stage · $progress %", color = color, style = MaterialTheme.typography.labelLarge, modifier = Modifier.width(140.dp))
                                    LinearProgressIndicator(progress = { progress / 100f }, color = color, modifier = Modifier.weight(1f))
                                }
                                val visits = data.visits.filter { it.sectorId == saved.id && it.status != "PENDING" }
                                Text("${work.label(saved,data.sectors)} · última visita: ${dateOnly((visits.map { it.at } + data.activeQuickDeliveries.filter { it.sectorId == saved.id }.map { it.at }).maxOrNull())}", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
                else -> {
                    if (journey!=null && polygon.isNotEmpty()) ZoneCoverageLabel(polygon.map { it.latitude to it.longitude },data.points,journey.id)
                    if (journey!=null) TextButton(onClick={
                        point = point ?: tracking.position?.takeIf { tracking.fixAt != null && System.currentTimeMillis()-tracking.fixAt < 60_000 }
                        sheet=null
                        if(point!=null) addBusiness=true else vm.message("Tocá el mapa donde está el negocio.")
                    }) { Text("Agregar negocio") }
                    Button(onClick={sheet=null;context.startActivity(Intent(context,AssignmentActivity::class.java).putExtra("sector",sector?.id))},modifier=Modifier.fillMaxWidth()) { Text("Asignar zona o abrir tarea") }
                    Text(sector?.name ?: "Opciones del mapa", style = MaterialTheme.typography.titleLarge)
                    if (selectedJourneyId == null) FilledTonalButton(onClick={sheet="deliveries"}, modifier=Modifier.fillMaxWidth()) { Text("Registrar tarjetas") }
                    if(sectors.isNotEmpty()) FilledTonalButton(onClick={
                        sheet=null;followingOverview()
                    },modifier=Modifier.fillMaxWidth()) { Text("Ver todas las zonas") }
                    FilledTonalButton(onClick = { sheet = "zones" }, modifier = Modifier.fillMaxWidth()) { Text("Elegir o cambiar zona") }
                    FilledTonalButton(onClick={sheet=null;nav.search=true},modifier=Modifier.fillMaxWidth()) { Text("Buscar un lugar") }
                    if(point!=null || polygon.isNotEmpty()) FilledTonalButton(onClick={
                        sheet=null
                        val chosen=point
                        if(chosen!=null) nav.start(Place("Punto elegido",chosen)) else nav.startZone(sector?.name ?: "Zona",polygon)
                    },modifier=Modifier.fillMaxWidth()) { Text("Cómo llegar a pie") }
                    FilledTonalButton(onClick = { sheet = "download" }, modifier = Modifier.fillMaxWidth()) { Text("Guardar mapa sin internet") }
                    FilledTonalButton(onClick = {
                        val target = area?.center ?: point ?: viewportCenter ?: tracking.position
                        if (target == null) vm.message("Mové el mapa hasta la zona que querés consultar.")
                        else try {
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/maps/@?api=1&map_action=map&center=${target.latitude},${target.longitude}&zoom=16")))
                        } catch (_: ActivityNotFoundException) { vm.message("No se encontró una aplicación para abrir Google Maps.") }
                    }, modifier = Modifier.fillMaxWidth()) { Text("Consultar zona en Google Maps") }
                    Text("En Google Maps elegí un lugar o poné un pin. Tocá Compartir → RutaComercial para delimitar una zona aquí.", style = MaterialTheme.typography.bodySmall)
                    FilledTonalButton(onClick = { sheet = null; onImportLocation() }, modifier = Modifier.fillMaxWidth()) { Text("Traer ubicación de Google Maps") }
                    if (!com.rutacomercial.app.BuildConfig.WORKER_EDITION) FilledTonalButton(onClick = { sheet = null; context.startActivity(Intent(context, TodayActivity::class.java)) }, modifier = Modifier.fillMaxWidth()) { Text("Ver repartidores hoy") }
                    if (!com.rutacomercial.app.BuildConfig.WORKER_EDITION) Row(Modifier.fillMaxWidth().heightIn(min = 48.dp)
                        .toggleable(value = live, role = androidx.compose.ui.semantics.Role.Switch) { live = it }, verticalAlignment = Alignment.CenterVertically) {
                        Switch(live, null); Text("Repartidores en vivo", Modifier.padding(start = 8.dp))
                    }
                    DarkMapSwitch()
                    if (selectedJourneyId == null) Row(verticalAlignment = Alignment.CenterVertically) {
                        Switch(showPrevious, { showPrevious = it }); Text("Mostrar recorridos anteriores (30 días)", Modifier.padding(start = 8.dp))
                    }
                    Text("Azul · recorrido GPS. Verde claro · zona completada. El mapa incorpora el avance del repartidor cuando abrís su archivo.", style = MaterialTheme.typography.bodySmall)
                    sector?.let { s ->
                        FilledTonalButton(onClick={
                            createZone();draftId=s.id;zoneName=s.name;freehand=true;draft=emptyList();outlineFit=emptyList()
                        },modifier=Modifier.fillMaxWidth()) { Text("Redibujar límite de la zona") }
                        val walkedHere = remember(mapLines) { mapLines.flatMap { it.points } }
                        if (walkedHere.size >= 3) FilledTonalButton(onClick={
                            val outline = trackOutline(walkedHere)
                            if (outline.isEmpty()) vm.message("No hay suficiente recorrido para sugerir un borde.")
                            else { createZone();draftId=s.id;zoneName=s.name;freehand=true;draft=outline;outlineFit=outline
                                vm.message("Borde sugerido según lo recorrido. Ajustalo a mano o con rectángulo y tocá Usar esta zona.") }
                        },modifier=Modifier.fillMaxWidth()) { Text("Ajustar zona a lo recorrido") }
                        TextButton(onClick={sheet="continue"}) { Text("Continuar esta zona") }
                        if (ownerEdition && journey == null) TextButton(onClick={sheet=null;onStart()}) { Text("Recorrerla yo") }
                        TextButton(onClick={sheet="remove"}) { Text("Eliminar zona del mapa") }
                        TextButton(onClick = { renamedZone = s.name; rename = true; sheet = null }) { Text("Cambiar nombre de la zona") }
                        val registered = data.visibleBusinesses.filter { it.sectorId == s.id }
                        val visited = data.visits.filter { it.sectorId == s.id && it.status != "PENDING" }.map { it.businessId }.toSet()
                        Text("${registered.size} negocios registrados · ${registered.count { it.id in visited }} con visitas")
                        Choice("Estado de la zona", s.status, listOf("PENDING" to "Pendiente", "ACTIVE" to "En curso", "COMPLETED" to "Completada")) { status ->
                            vm.perform("Estado actualizado") { vm.repository.saveSector(s.copy(status = status)) }
                        }
                        Text("El estado se cambia manualmente. Caminar una calle no confirma visitas a sus negocios.", style = MaterialTheme.typography.bodySmall)
                    }
                    if (journey != null && selectedJourneyId == null) TextButton(onClick = { sheet = null; incident = true }) { Text("Registrar incidencia") }
                    tracking.accuracyMeters?.let { Text("Precisión estimada por el teléfono: ±${it.toInt()} m. No garantiza precisión dentro de edificios.", style = MaterialTheme.typography.bodySmall) }
                    if (tracking.isTracking && tracking.provider.isNotBlank()) Text(tracking.provider, style = MaterialTheme.typography.bodySmall)
                }
            }
            Spacer(Modifier.height(12.dp))
        }
    }
    if (addBusiness && point != null) BusinessEditor(vm, data, null, point, { addBusiness = false }, onBusiness, sectorId)
    if (incident) IncidentEditor(vm, journey?.id) { incident = false }
    if(rename && editing) EditorDialog("Nombre de la zona",{rename=false},{ if(renamedZone.isNotBlank()) { zoneName=renamedZone.trim();rename=false } }) { Field("Nombre",renamedZone,{renamedZone=it.take(100)}) }
    if (rename && !editing && sector != null) EditorDialog("Nombre de la zona", { rename = false }, {
        vm.perform("Nombre actualizado", { rename = false }) { vm.repository.saveSector(sector.copy(name = renamedZone)) }
    }) { Field("Nombre", renamedZone, { renamedZone = it }) }
}

@Composable
private fun JourneyMeter(vm: RutaViewModel, journey: Journey) {
    val now by vm.now.collectAsStateWithLifecycle()
    Text("${number(journey.distanceM / 1000, 2)} km · ${hours(journey.activeDuration(now))}${if(journey.state == "PAUSED") " · En pausa" else ""}", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.labelLarge)
}
