package com.rutacomercial.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.lifecycle.repeatOnLifecycle
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.rutacomercial.app.data.*
import com.rutacomercial.app.domain.Geo
import com.rutacomercial.app.maps.*
import com.rutacomercial.app.tracking.TrackingStatus
import java.time.LocalDate
import java.time.ZoneId

@Composable
internal fun DashboardScreen(data: DataSnapshot, now: Long, onStart: () -> Unit, onMap: () -> Unit, onOpen: (String) -> Unit) = ScreenColumn {
    AssignmentBanner("RutaComercial Owner","Actividad y recorridos",large=true)
    val active = data.activeJourney
    if(active != null) InfoCard(if(active.state == "ACTIVE") "Jornada en curso" else "Jornada en pausa",
        "${number(active.distanceM / 1000)} km · ${hours(active.activeDuration(now))} de trabajo", { Button(onClick = onMap, modifier = Modifier.fillMaxWidth()) { Text("Ver jornada") } })
    else Button(onClick = onStart, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) { Text("Iniciar una jornada", style = MaterialTheme.typography.titleMedium) }
    var period by remember { mutableStateOf("MONTH") }
    var campaignId by remember { mutableStateOf<String?>(null) }
    var sectorId by remember { mutableStateOf<String?>(null) }
    Choice("Período", period, listOf("DAY" to "Hoy", "WEEK" to "Últimos 7 días", "MONTH" to "Últimos 30 días", "ALL" to "Todo el historial")) { period = it }
    Choice("Campaña", campaignId, listOf(null to "Todas las campañas") + data.campaigns.map { it.id to it.name }) { campaignId = it }
    Choice("Sector", sectorId, listOf(null to "Todos los sectores") + data.sectors.map { it.id to it.name }) { sectorId = it }
    val today = LocalDate.now()
    val start = when(period) { "DAY" -> today; "WEEK" -> today.minusDays(6); "MONTH" -> today.minusDays(29); else -> null }
        ?.atStartOfDay(ZoneId.systemDefault())?.toInstant()?.toEpochMilli() ?: Long.MIN_VALUE
    val visits = data.visits.filter { it.at >= start && (campaignId == null || it.campaignId == campaignId) && (sectorId == null || it.sectorId == sectorId) }
    val quick = data.activeQuickDeliveries.filter { it.at >= start && (campaignId == null || it.campaignId == campaignId) && (sectorId == null || it.sectorId == sectorId) }
    val journeyIds = (visits.mapNotNull { it.journeyId } + quick.mapNotNull { it.journeyId }).toSet()
    val work = zoneWork()
    val selectedSector = data.sectors.firstOrNull { it.id == sectorId }
    val planned = selectedSector?.let { previousJourneys(data,it.id) }.orEmpty()
    val reports = remember(work,data.sectors,selectedSector,campaignId,start) {
        val matching = when {
            campaignId != null -> emptyList()
            selectedSector != null -> work.reportsFor(work.tasksFor(selectedSector,data.sectors))
            else -> work.reports
        }
        matching.filter { it.at >= start }
    }
    val journeys = data.journeys.filter { it.startedAt >= start && (campaignId == null && (sectorId == null || it.id in planned) || it.id in journeyIds) }
    val cards = visits.sumOf { it.cards.toLong() } + quick.size
    val visited = (visits.filter { it.status != "PENDING" }.map { it.businessId } + quick.mapNotNull { it.businessId }).toSet()
    val businesses = data.visibleBusinesses.filter { sectorId == null || it.sectorId == sectorId }
    val pending = businesses.count { it.status in listOf("PENDING", "FOLLOW_UP", "PENDING_VERIFICATION") }
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        MetricCard("Jornadas finalizadas", (journeys.count { it.state == "FINISHED" } + reports.count { it.state == "FINISHED" }).toString(), Modifier.weight(1f))
        MetricCard("Kilómetros observados", number((journeys.sumOf { it.distanceM } + reports.sumOf { it.meters }) / 1000, 2), Modifier.weight(1f))
    }
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        MetricCard("Tiempo activo", hours(journeys.sumOf { it.activeDuration(now) } + reports.sumOf { it.duration }), Modifier.weight(1f))
        MetricCard("Negocios con visita", visited.size.toString(), Modifier.weight(1f))
    }
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        MetricCard("Tarjetas declaradas", cards.toString(), Modifier.weight(1f))
        MetricCard("Pendientes actuales", pending.toString(), Modifier.weight(1f))
    }
    Text("${quick.count { it.businessId == null }} entregas a un toque sin negocio identificado.")
    val knownCost = visits.mapNotNull { v -> data.campaigns.firstOrNull { it.id == v.campaignId }?.unitCostCents?.let { it * v.cards } }.sum() + quick.mapNotNull { q -> data.campaigns.firstOrNull { it.id == q.campaignId }?.unitCostCents }.sum()
    val missingCost = visits.any { it.cards > 0 && data.campaigns.firstOrNull { c -> c.id == it.campaignId }?.unitCostCents == null } || quick.any { q -> data.campaigns.firstOrNull { it.id == q.campaignId }?.unitCostCents == null }
    Text("Costo del material entregado: $ ${number(knownCost / 100.0, 2)}${if(missingCost) " · parcial, faltan costos" else ""}")
    Text("No incluye transporte ni remuneraciones. Las cantidades son entregas declaradas; el GPS no verifica una entrega.", style = MaterialTheme.typography.bodySmall)
    if(reports.isNotEmpty()) Text("Incluye ${reports.count { it.state == "FINISHED" }} jornadas finalizadas y ${reports.count { it.state != "FINISHED" }} avances de repartidores.", style = MaterialTheme.typography.bodySmall)
    if(campaignId != null) Text("Las jornadas se vinculan a una campaña por sus entregas registradas. Los informes de repartidores se consultan en Todas las campañas.", style = MaterialTheme.typography.bodySmall)
    val workedSectors = (visits.mapNotNull { it.sectorId } + quick.mapNotNull { it.sectorId }).toSet()
    InfoCard("Cobertura aproximada", "${workedSectors.size} sectores con visitas registradas de ${data.sectors.size}. " +
        "${visited.size} de ${businesses.size} negocios registrados tienen visitas en el período. Un recorrido no significa que se hayan visitado todos los negocios del área.")
    if(data.sectors.isNotEmpty()) {
        Text("Actividad por sector", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        data.sectors.filter { sectorId == null || it.id == sectorId }.sortedByDescending { s -> visits.count { it.sectorId == s.id } }.take(5).forEach { s ->
            val taskKeys = work.tasksFor(s,data.sectors).map { it.id }.toSet()
            val received = reports.count { it.taskId in taskKeys }
            if(received > 0) Text("${s.name} · $received jornadas recibidas",style=MaterialTheme.typography.labelLarge)
            Text("${s.name}: ${visits.count { it.sectorId == s.id }} visitas · ${visits.filter { it.sectorId == s.id }.sumOf { it.cards } + quick.count { it.sectorId == s.id }} tarjetas")
        }
    }
    val due = businesses.filter { it.nextContactAt != null && it.nextContactAt!! <= now && it.status != "DO_NOT_VISIT" }.sortedBy { it.nextContactAt }
    if(due.isNotEmpty()) {
        Text("Contactos pendientes", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        due.take(8).forEach { b -> OutlinedButton(onClick = { onOpen(b.id) }, modifier = Modifier.fillMaxWidth()) { Text("${b.name} · ${dateOnly(b.nextContactAt)}") } }
    }
    if(work.loaded && work.reports.isEmpty() && data.journeys.isEmpty() && data.visits.isEmpty()) InfoCard("Empezá con tus propios datos", "Creá una campaña y asigná tarjetas antes de registrar entregas. Luego marcá los negocios en el mapa. No hay datos de demostración.")
}

internal fun mapSectors(data: DataSnapshot, operationalColors: Boolean = false): List<MapSector> = data.sectors.map { s ->
    val color = if(s.status=="COMPLETED") "#68B75F" else s.color
    MapSector(s.id, s.name, color, Geo.parsePolygon(s.polygonJson).map { MapCoordinate(it.first,it.second) },completed=s.status=="COMPLETED")
}
internal fun mapTracks(data: DataSnapshot, journeyId: String?): List<MapTrack> = data.points.filter { it.journeyId == journeyId }
    .sortedBy { it.at }.groupBy { it.segment }.map { (segment, points) -> MapTrack("$journeyId-$segment", points.map { MapCoordinate(it.latitude,it.longitude) },group=journeyId.orEmpty()) }

@Composable
internal fun HistoryScreen(vm: RutaViewModel, data: DataSnapshot, onJourney: (String) -> Unit, onExport: (String,String?) -> Unit) = ScreenColumn {
    PageHeading("Historial e informes", "Resumen de jornadas y registros comerciales conservados en el teléfono.")
    var from by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf("") }
    var until by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf("") }
    val since=dayBound(from)
    val before=dayBound(until,true)
    val work=zoneWork()
    RouteExport(data,work,from,until) { start,end -> from=start;until=end }
    val context=androidx.compose.ui.platform.LocalContext.current
    val prefs=remember { context.getSharedPreferences("route_archive",0) }
    var archived by remember { mutableStateOf(prefs.getStringSet("ids",emptySet()).orEmpty().toSet()) }
    var showArchive by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    fun archive(key:String) {
        archived=if(key in archived) archived-key else archived+key
        prefs.edit().putStringSet("ids",archived).apply()
    }
    if(archived.isNotEmpty() || showArchive) TextButton(onClick={showArchive=!showArchive}) { Text(if(showArchive) "Ver historial" else "Ver archivados ("+archived.size+")") }
    val journeys=data.journeys.filter { (("local/"+it.id in archived)==showArchive) && (since==null || it.startedAt>=since) && (before==null || it.startedAt<before) }.sortedByDescending { it.startedAt }
    val visible=work.reports.filter { (("report/"+it.id in archived)==showArchive) && (since==null || it.at>=since) && (before==null || it.at<before) }
    if(visible.isNotEmpty()) Text("Recorridos de repartidores",style=MaterialTheme.typography.titleMedium)
    visible.forEach { row ->
        OutlinedButton(modifier=Modifier.fillMaxWidth(),onClick={ context.startActivity(android.content.Intent(context, AssignmentActivity::class.java).putExtra("reportId",row.id).putExtra("individual",true)) }) {
            Text(listOf(row.name,row.worker).filter { it.isNotBlank() }.joinToString(" · ").ifBlank { "Informe" }+" · "+number(row.meters/1000,2)+" km\n"+(if(row.dated) "Inicio " else "Recibido ")+dateText(row.at))
        }
        TextButton(onClick={archive("report/"+row.id)}) { Text(if(showArchive) "Restaurar al historial" else "Archivar informe") }
    }
    if(journeys.isEmpty() && visible.isEmpty()) InfoCard("Sin jornadas en este período", "Los recorridos aparecerán aquí cuando inicies una jornada.")
    journeys.forEach { j ->
        val visits = data.visits.filter { it.journeyId == j.id }
        val quick = data.activeQuickDeliveries.filter { it.journeyId == j.id }
        val sectors = (visits.mapNotNull { it.sectorId } + quick.mapNotNull { it.sectorId }).distinct().mapNotNull { id -> data.sectors.firstOrNull { it.id == id }?.name }
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                Text(dateText(j.startedAt),fontWeight=FontWeight.Bold,style=MaterialTheme.typography.titleMedium)
                Text("Propietario · ${when(j.state){"ACTIVE"->"Activa";"PAUSED"->"En pausa";else->"Finalizada"}}")
                Text("${number(j.distanceM / 1000,2)} km · ${hours(j.activeDuration(System.currentTimeMillis()))} activos · ${hours(j.pauseDuration(System.currentTimeMillis()))} en pausa")
                Text("${visits.map { it.businessId }.distinct().size} negocios · ${visits.count { it.cards > 0 } + quick.size} entregas declaradas · ${visits.sumOf { it.cards } + quick.size} tarjetas")
                val duration=j.activeDuration(System.currentTimeMillis())
                if(duration>0) Text("Ritmo medio de la jornada · ${number(j.distanceM*3600/duration,2)} km/h")
                val plan=data.audit.firstOrNull { it.entityId==j.id && it.action=="WALK_PLAN" }
                if(plan!=null) {
                    val events=data.incidents.filter { it.journeyId==j.id }
                    Text("${events.count { it.kind=="WALK_STOP" }} paradas observadas · ${events.count { it.kind=="FAST_INTERVAL" }} episodios de ritmo alto")
                    if(plan.detail=="FREE") Text("Recorrido libre") else {
                        val id=plan.detail.substringBefore('/')
                        val revision=plan.detail.substringAfter('/').toIntOrNull()
                        val boundary=data.sectorRevisions.firstOrNull { it.sectorId==id && it.revision==revision }
                        Text("Zona inicial · ${data.sectors.firstOrNull { it.id==id }?.name ?: "Zona guardada"}")
                        if(boundary!=null) ZoneCoverageLabel(remember(boundary) { Geo.parsePolygon(boundary.polygonJson) },data.points,j.id)
                    }
                    Text("Las paradas y el ritmo orientan la revisión. La señal puede variar entre edificios.",style=MaterialTheme.typography.bodySmall)
                } else Text("Este recorrido anterior conserva distancia y tiempo. Sin registro de paradas ni ritmos sostenidos.",style=MaterialTheme.typography.bodySmall)
                Text("Fin: ${dateText(j.endedAt)}\nSectores con visitas: ${sectors.joinToString().ifBlank { "Sin visitas registradas" }}")
                Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    Button(onClick={onJourney(j.id)}) { Text("Ver mapa") }
                    TextButton(onClick={onExport("gpx",j.id)}) { Text("GPX") }
                    TextButton(onClick={onExport("geojson",j.id)}) { Text("GeoJSON") }
                }
                TextButton(onClick={archive("local/"+j.id)}) { Text(if(showArchive) "Restaurar al historial" else "Archivar recorrido") }
                val incidents = data.incidents.filter { it.journeyId == j.id }
                if(incidents.isNotEmpty()) {
                    Text("Incidencias (${incidents.size})",fontWeight=FontWeight.SemiBold)
                    incidents.takeLast(5).forEach { Text("${dateText(it.at)} · ${it.message}",style=MaterialTheme.typography.bodySmall) }
                }
            }
        }
    }
    TextButton(onClick={onExport("csv",null)}) { Text("Exportar negocios y visitas por separado") }
    Text("Los registros GPS son observaciones de ubicación. Las visitas y tarjetas son declaraciones y pueden revisarse con evidencia adicional.",style=MaterialTheme.typography.bodySmall)
}

@Composable
internal fun IncidentEditor(vm: RutaViewModel, journeyId: String?, onClose: () -> Unit) {
    var message by remember { mutableStateOf("") }
    EditorDialog("Registrar incidencia",onClose,{vm.perform("Incidencia guardada",onClose) { vm.repository.addIncident(message,journeyId) }}) {
        Field("Qué ocurrió",message,{message=it},multiline=true)
        Text("Ejemplos: falta de señal, interrupción o acceso cerrado. No incluyas datos personales innecesarios.")
    }
}
