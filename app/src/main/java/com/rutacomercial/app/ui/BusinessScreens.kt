package com.rutacomercial.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.rutacomercial.app.data.*
import com.rutacomercial.app.domain.Geo
import com.rutacomercial.app.domain.Inventory
import com.rutacomercial.app.maps.MapCoordinate
import com.rutacomercial.app.tracking.TrackingStatus

@Composable
internal fun BusinessesScreen(vm: RutaViewModel, data: DataSnapshot, position: MapCoordinate?, onOpen: (String) -> Unit, onPickMap: () -> Unit) = ScreenColumn {
    PageHeading("Establecimientos", "Buscá por nombre, categoría, dirección o barrio.")
    var search by remember { mutableStateOf("") }
    var sectorId by remember { mutableStateOf<String?>(null) }
    var status by remember { mutableStateOf<String?>(null) }
    var sortNearby by remember { mutableStateOf(false) }
    var manual by remember { mutableStateOf(false) }
    Field("Buscar establecimiento",search,{search=it})
    Choice("Sector",sectorId,listOf(null to "Todos los sectores")+data.sectors.map{it.id to it.name}){sectorId=it}
    Choice("Estado",status,listOf(null to "Todos los estados")+VisitStatus.entries.map{it.name to it.label}){status=it}
    Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
        Button(onClick=onPickMap,modifier=Modifier.weight(1f)) { Text("Agregar desde mapa") }
        OutlinedButton(onClick={manual=true},modifier=Modifier.weight(1f)) { Text("Ingresar ubicación") }
    }
    if(position != null) Row { Checkbox(sortNearby,{sortNearby=it}); Text("Ordenar por cercanía en línea recta",Modifier.padding(top=12.dp)) }
    var businesses = data.visibleBusinesses.filter { b ->
        (search.isBlank() || listOf(b.name,b.category,b.address,b.neighborhood).any{it.contains(search,ignoreCase=true)}) &&
        (sectorId == null || b.sectorId == sectorId) && (status == null || b.status == status)
    }
    businesses = if(sortNearby && position != null) businesses.sortedBy{Geo.distanceM(position.latitude,position.longitude,it.latitude,it.longitude)} else businesses.sortedBy{it.name.lowercase()}
    Text("${businesses.size} establecimientos",style=MaterialTheme.typography.labelLarge)
    if(businesses.isEmpty()) InfoCard("Sin resultados", "Agregá un negocio desde el mapa o ajustá la búsqueda. El mapa no es un censo completo de comercios.")
    businesses.forEach { b ->
        OutlinedCard(onClick={onOpen(b.id)},modifier=Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                Text(b.name,style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.Bold)
                Text(listOf(b.category,b.address).filter{it.isNotBlank()}.joinToString(" · "))
                Text(VisitStatus.labelOf(b.status),color=MaterialTheme.colorScheme.primary,fontWeight=FontWeight.SemiBold)
                if(position != null) Text("${number(Geo.distanceM(position.latitude,position.longitude,b.latitude,b.longitude),0)} m en línea recta desde la última ubicación",style=MaterialTheme.typography.bodySmall)
                if(b.nextContactAt != null) Text("Próximo contacto: ${dateOnly(b.nextContactAt)}",style=MaterialTheme.typography.bodySmall)
            }
        }
    }
    if(manual) BusinessEditor(vm,data,null,null,onClose={manual=false},onOpen=onOpen)
}

@Composable
internal fun BusinessDetail(vm: RutaViewModel, data: DataSnapshot, business: Business, tracking: TrackingStatus, onMap: () -> Unit, onOpenBusiness:(String)->Unit) = ScreenColumn {
    var edit by remember { mutableStateOf(false) }
    var visit by remember { mutableStateOf(false) }
    var review by remember { mutableStateOf<Visit?>(null) }
    var mergeCandidates by remember { mutableStateOf<List<Business>?>(null) }
    PageHeading(business.name, listOf(business.category,business.address).filter{it.isNotBlank()}.joinToString(" · "))
    Text(VisitStatus.labelOf(business.status),style=MaterialTheme.typography.titleMedium,color=MaterialTheme.colorScheme.primary)
    val visits=data.visits.filter{it.businessId==business.id}.sortedByDescending{it.at}
    Text("Última visita: ${dateText(visits.firstOrNull()?.at)}")
    Text("Barrio: ${business.neighborhood.ifBlank{"Sin asignar"}}\nSector: ${data.sectors.firstOrNull{it.id==business.sectorId}?.name ?: "Sin asignar"}")
    Text("Ubicación: ${number(business.latitude,6)}, ${number(business.longitude,6)}")
    if(business.phone.isNotBlank()) Text("Teléfono: ${business.phone}")
    if(business.nextContactAt != null) Text("Próximo contacto: ${dateOnly(business.nextContactAt)}")
    if(business.notes.isNotBlank()) InfoCard("Observaciones",business.notes)
    Button(onClick={visit=true},modifier=Modifier.fillMaxWidth().heightIn(min=56.dp)) { Text("Registrar visita o entrega") }
    Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick={edit=true},modifier=Modifier.weight(1f)){Text("Editar ficha")}
        OutlinedButton(onClick={vm.perform("Agregado al recorrido planificado"){vm.repository.addRouteStop(business.id)}},modifier=Modifier.weight(1f),enabled=business.status!="DO_NOT_VISIT"){Text("Planificar visita")}
    }
    TextButton(onClick=onMap){Text("Ir al mapa de negocios")}
    TextButton(onClick={vm.perform { mergeCandidates=vm.repository.duplicateBusinesses(business) }}){Text("Revisar posibles duplicados")}
    Text("Historial de visitas (${visits.size})",style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.Bold)
    if(visits.isEmpty()) Text("Todavía no se registraron visitas.")
    visits.forEach { v ->
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                Text(dateText(v.at),fontWeight=FontWeight.Bold)
                Text("${VisitStatus.labelOf(v.status)} · ${v.cards} tarjetas")
                Text("Campaña: ${data.campaigns.firstOrNull{it.id==v.campaignId}?.name ?: "Sin campaña"}")
                if(v.comments.isNotBlank())Text(v.comments)
                Text("Revisión: ${reviewLabel(v.reviewState)}",style=MaterialTheme.typography.labelLarge)
                if(v.reviewNote.isNotBlank())Text(v.reviewNote,style=MaterialTheme.typography.bodySmall)
                if(v.evidenceUri != null)Text("Contiene fotografía adicional",style=MaterialTheme.typography.bodySmall)
                TextButton(onClick={review=v}){Text("Revisar evidencia y declaración")}
            }
        }
    }
    val quick = data.quickDeliveries.filter { it.businessId == business.id }
    if (quick.isNotEmpty()) Text("Entregas a un toque asociadas", style=MaterialTheme.typography.titleMedium)
    quick.forEach { q ->
        Text("${dateText(q.at)} · ${if(q.undoneAt == null) "1 tarjeta declarada" else "Entrega anulada"}")
    }
    Text("ID: ${business.id}",style=MaterialTheme.typography.bodySmall)
    if(edit)BusinessEditor(vm,data,business,null,{edit=false},onOpenBusiness)
    if(visit)VisitEditor(vm,data,business,tracking){visit=false}
    review?.let{v->ReviewEditor(vm,v){review=null}}
    mergeCandidates?.let { candidates ->
        if(candidates.isEmpty()) AlertDialog(onDismissRequest={mergeCandidates=null},title={Text("Sin duplicados cercanos")},text={Text("No se encontraron nombres similares a corta distancia. Esta comprobación es orientativa.")},confirmButton={TextButton(onClick={mergeCandidates=null}){Text("Entendido")}})
        else MergeBusinessDialog(vm,business,candidates){mergeCandidates=null}
    }
}

@Composable
internal fun BusinessEditor(vm: RutaViewModel,data:DataSnapshot,existing:Business?,point:MapCoordinate?,onClose:()->Unit,onOpen:(String)->Unit,initialSectorId:String? = null) {
    val id=remember{existing?.id ?: newId()}
    var name by remember { mutableStateOf(existing?.name ?: "") }
    var category by remember { mutableStateOf(existing?.category ?: "") }
    var address by remember { mutableStateOf(existing?.address ?: "") }
    var latitude by remember { mutableStateOf(existing?.latitude?.toString() ?: point?.latitude?.toString() ?: "") }
    var longitude by remember { mutableStateOf(existing?.longitude?.toString() ?: point?.longitude?.toString() ?: "") }
    var neighborhood by remember { mutableStateOf(existing?.neighborhood ?: "") }
    var sectorId by remember { mutableStateOf(existing?.sectorId ?: if (existing == null && point != null) {
        val containing = data.sectors.filter { Geo.contains(Geo.parsePolygon(it.polygonJson), point.latitude, point.longitude) }
        containing.firstOrNull { it.id == initialSectorId }?.id ?: containing.singleOrNull()?.id
    } else null) }
    var phone by remember { mutableStateOf(existing?.phone ?: "") }
    var notes by remember { mutableStateOf(existing?.notes ?: "") }
    var nextDate by remember { mutableStateOf(dateOnly(existing?.nextContactAt)) }
    var priority by remember { mutableStateOf(existing?.priority ?: 0) }
    var photo by remember { mutableStateOf(existing?.photoUri) }
    var duplicateDraft by remember { mutableStateOf<Business?>(null) }
    var duplicates by remember { mutableStateOf(emptyList<Business>()) }
    var moreDetails by remember { mutableStateOf(existing != null) }
    fun draft():Business {
        val lat=latitude.replace(',','.').toDoubleOrNull() ?: throw IllegalArgumentException("Ingresá una latitud válida.")
        val lon=longitude.replace(',','.').toDoubleOrNull() ?: throw IllegalArgumentException("Ingresá una longitud válida.")
        return (existing ?: Business(id=id,name=name,latitude=lat,longitude=lon)).copy(name=name,category=category,address=address,latitude=lat,longitude=lon,neighborhood=neighborhood,
            sectorId=sectorId,phone=phone,notes=notes,nextContactAt=parseDate(nextDate),priority=priority,photoUri=photo)
    }
    EditorDialog(if(existing==null)"Nuevo establecimiento" else "Editar establecimiento",onClose,{
        vm.perform {
            val candidate=draft()
            val found=vm.repository.duplicateBusinesses(candidate)
            if(found.isNotEmpty()){duplicateDraft=candidate;duplicates=found}
            else {val saved=vm.repository.saveBusiness(candidate);onClose();if(existing==null)onOpen(saved.id);vm.message("Establecimiento guardado")}
        }
    }) {
        Field("Nombre comercial",name,{name=it});Field("Categoría",category,{category=it});Field("Dirección o referencia",address,{address=it})
        if (point == null || moreDetails) {
            Field("Latitud",latitude,{latitude=it},numeric=true);Field("Longitud",longitude,{longitude=it},numeric=true)
        } else Text("Ubicación tomada del punto elegido en el mapa", style = MaterialTheme.typography.bodySmall)
        Choice("Sector",sectorId,listOf(null to "Sin asignar")+data.sectors.map{it.id to it.name}){sectorId=it}
        TextButton(onClick = { moreDetails = !moreDetails }) { Text(if (moreDetails) "Ocultar datos opcionales" else "Más datos (opcionales)") }
        if (moreDetails) {
        Field("Barrio",neighborhood,{neighborhood=it})
        Field("Teléfono (opcional)",phone,{phone=it});Field("Próximo contacto (AAAA-MM-DD)",nextDate,{nextDate=it})
        Choice("Prioridad",priority,listOf(0 to "Normal",1 to "Alta",2 to "Urgente")){priority=it}
        Field("Observaciones",notes,{notes=it},multiline=true)
        EvidencePicker(photo){photo=it}
        }
        Text("Los cambios se registran en el historial. El estado comercial se actualiza al registrar una visita.",style=MaterialTheme.typography.bodySmall)
    }
    duplicateDraft?.let { candidate ->
        AlertDialog(onDismissRequest={duplicateDraft=null},title={Text("Posible establecimiento duplicado")},text={Column(verticalArrangement=Arrangement.spacedBy(8.dp)){
            Text("Hay nombres similares cerca de esta ubicación. No se fusionará ninguna ficha automáticamente.")
            duplicates.forEach{d->TextButton(onClick={duplicateDraft=null;onClose();onOpen(d.id)}){Text("Abrir ${d.name}")}}
        }},confirmButton={Button(onClick={vm.perform("Ficha guardada por separado",{duplicateDraft=null;onClose()}){vm.repository.saveBusiness(candidate,allowDuplicate=true)}}){Text("Guardar separado")}},
            dismissButton={TextButton(onClick={duplicateDraft=null}){Text("Corregir ficha")}})
    }
}

@Composable
private fun MergeBusinessDialog(vm:RutaViewModel,source:Business,candidates:List<Business>,onClose:()->Unit) {
    var target by remember { mutableStateOf(candidates.first().id) }
    var confirm by remember { mutableStateOf(false) }
    EditorDialog("Revisar fichas similares",onClose,{confirm=true},"Fusionar fichas") {
        Text("Se conservará la ficha de destino y se trasladarán las visitas de ${source.name}. La ficha de origen quedará identificada como fusionada, con auditoría.")
        Choice("Ficha que se conservará",target,candidates.map{it.id to "${it.name} · ${it.address}"}){target=it}
    }
    if(confirm)ConfirmDialog("Confirmar fusión", "¿Fusionar ${source.name} con ${candidates.first{it.id==target}.name}? Revisá que se trate del mismo establecimiento.","Confirmar fusión",{confirm=false}) {
        vm.perform("Fichas fusionadas",onClose){vm.repository.mergeBusinesses(source.id,target)}
    }
}

@Composable
private fun VisitEditor(vm:RutaViewModel,data:DataSnapshot,business:Business,tracking:TrackingStatus,onClose:()->Unit) {
    val id=remember{newId()}
    var status by remember { mutableStateOf("CARD_DELIVERED") }
    var cards by remember { mutableStateOf("1") }
    var campaignId by remember { mutableStateOf(data.campaigns.firstOrNull()?.id) }
    var comments by remember { mutableStateOf("") }
    var evidence by remember { mutableStateOf<String?>(null) }
    val campaign=data.campaigns.firstOrNull{it.id==campaignId}
    val balance=Inventory.balance(data.movements.filter{it.campaignId==campaignId})
    EditorDialog("Registrar visita",onClose,{
        vm.perform("Visita guardada",onClose) {
            val quantity=cards.toIntOrNull() ?: throw IllegalArgumentException("Ingresá una cantidad entera de tarjetas.")
            val freshLocation=tracking.fixAt?.let{System.currentTimeMillis()-it in 0..30_000}==true
            vm.repository.recordVisit(Visit(id=id,businessId=business.id,journeyId=data.activeJourney?.id,campaignId=campaignId,sectorId=business.sectorId,status=status,cards=quantity,
                comments=comments,evidenceUri=evidence,latitude=if(freshLocation)tracking.position?.latitude else null,longitude=if(freshLocation)tracking.position?.longitude else null,accuracyMeters=if(freshLocation)tracking.accuracyMeters else null))
        }
    },"Guardar visita") {
        Text(business.name,fontWeight=FontWeight.Bold)
        Choice("Resultado",status,VisitStatus.entries.map{it.name to it.label}){status=it;if(it !in listOf("CARD_DELIVERED","INTERESTED","FOLLOW_UP"))cards="0"}
        Choice("Campaña",campaignId,listOf(null to "Sin campaña")+data.campaigns.map{it.id to it.name}){campaignId=it}
        Field("Tarjetas entregadas",cards,{cards=it},numeric=true)
        Text(if(campaign!=null)"${balance.carried} tarjetas asignadas disponibles en ${campaign.name}" else "Para entregar tarjetas, seleccioná una campaña y asigná material desde Campañas.",style=MaterialTheme.typography.bodySmall)
        Field("Comentario",comments,{comments=it},multiline=true)
        EvidencePicker(evidence){evidence=it}
        Text("Se registra como declaración. La ubicación y una foto adicional no garantizan una entrega. ${if(data.activeJourney==null)"No se asociará a una jornada." else "Se asociará a la jornada actual."}",style=MaterialTheme.typography.bodySmall)
    }
}

internal fun reviewLabel(value:String)=when(value){"REVIEWED"->"Entrega revisada";"ADDITIONAL_EVIDENCE"->"Evidencia adicional";"REJECTED"->"Declaración observada";else->"Declarada, sin revisión"}

@Composable
private fun ReviewEditor(vm:RutaViewModel,visit:Visit,onClose:()->Unit) {
    var state by remember{mutableStateOf(visit.reviewState)}
    var note by remember{mutableStateOf(visit.reviewNote)}
    EditorDialog("Revisión humana",onClose,{vm.perform("Revisión registrada",onClose){vm.repository.reviewVisit(visit.id,state,note)}}) {
        Text("Una señal aislada no demuestra fraude ni confirma una entrega. La revisión se audita y no modifica las cantidades.")
        Choice("Resultado de revisión",state,listOf("DECLARED","ADDITIONAL_EVIDENCE","REVIEWED","REJECTED").map{it to reviewLabel(it)}){state=it}
        Field("Motivo y evidencia revisada",note,{note=it},multiline=true)
        if(visit.evidenceUri!=null)EvidencePicker(visit.evidenceUri,editable=false){}
        Text("GPS: ${visit.latitude?.let{number(it,6)} ?: "No registrado"}, ${visit.longitude?.let{number(it,6)} ?: ""} · precisión ${visit.accuracyMeters?.toInt()?.toString() ?: "—"} m",style=MaterialTheme.typography.bodySmall)
    }
}
