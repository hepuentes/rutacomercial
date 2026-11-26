package com.rutacomercial.app.data

import com.rutacomercial.app.domain.*
import java.util.UUID

/** Shared by normal writes and the all-or-nothing restore transaction. */
object Validation {
    val reviewStates=setOf("DECLARED","ADDITIONAL_EVIDENCE","REVIEWED","REJECTED")
    private fun id(value:String) { require(runCatching { UUID.fromString(value).toString().equals(value,true) }.getOrDefault(false)){"Identificador inválido en los datos"} }
    private fun title(value:String) { require(value.isNotBlank() && value.length<=200 && Names.normalize(value).isNotEmpty()){"Escriba un nombre válido de hasta 200 caracteres"} }
    private fun text(value:String,max:Int=10_000) { require(value.length<=max){"El texto supera el tamaño permitido"} }
    private fun time(value:Long) { require(value>0){"Fecha inválida"} }
    private fun photo(value:String?) {
        require(value==null || Regex("evidence/[0-9a-fA-F-]{36}\\.jpg").matches(value)){"Referencia de fotografía inválida"}
        if(value!=null) id(value.removePrefix("evidence/").removeSuffix(".jpg"))
    }
    fun area(value:Area) {
        id(value.id); title(value.name); time(value.updatedAt)
        require(value.kind in setOf("CITY","LOCALITY","NEIGHBORHOOD")){"Tipo de zona inválido"}
        value.parentId?.let{ id(it); require(it!=value.id){"Una zona no puede depender de sí misma"} }
    }
    fun sector(value:Sector) {
        id(value.id); title(value.name); time(value.updatedAt); value.areaId?.let{ id(it) }
        require(value.code.length<=40){"El código del sector es demasiado largo"}
        require(Regex("#[0-9a-fA-F]{6}").matches(value.color)){"Use un color hexadecimal, por ejemplo #087F75"}
        require(value.status in setOf("PENDING","ACTIVE","COMPLETED")){"Estado del sector inválido"}
        require(value.revision>0){"Revisión de sector inválida"}
        require(value.polygonJson.length<=100_000){"El polígono contiene demasiados datos"}
        require(Geo.validPolygon(Geo.parsePolygon(value.polygonJson))){"Dibuje un polígono con al menos tres vértices, sin cruces ni superficie nula"}
    }
    fun business(value:Business) {
        id(value.id); title(value.name); time(value.createdAt); time(value.updatedAt)
        require(Geo.valid(value.latitude,value.longitude)){"Coordenadas del negocio inválidas"}
        require(value.status in VisitStatus.entries.map{it.name}){"Estado comercial inválido"}
        require(value.priority in 0..3){"La prioridad debe estar entre 0 y 3"}
        value.sectorId?.let{ id(it) }; value.mergedIntoId?.let{ id(it); require(it!=value.id){"Un negocio no puede fusionarse consigo mismo"} }
        value.nextContactAt?.let{ time(it) }; photo(value.photoUri)
        text(value.category,200); text(value.address,1000); text(value.neighborhood,200); text(value.phone,100); text(value.notes)
    }
    fun campaign(value:Campaign) {
        id(value.id); title(value.name); time(value.startsAt); time(value.createdAt); time(value.updatedAt); text(value.description)
        require(value.endsAt==null || value.endsAt>=value.startsAt){"La fecha final debe ser igual o posterior al inicio"}
        require(value.unitCostCents==null || value.unitCostCents>=0){"El costo no puede ser negativo"}
    }
    fun visit(value:Visit) {
        id(value.id); id(value.businessId); time(value.at); value.journeyId?.let{ id(it) }; value.campaignId?.let{ id(it) }; value.sectorId?.let{ id(it) }
        require(value.status in VisitStatus.entries.map{it.name}){"Estado de visita inválido"}
        require(value.reviewState in reviewStates){"Estado de revisión inválido"}
        require(value.cards in 0..1_000_000){"La cantidad de tarjetas debe estar entre 0 y 1.000.000"}
        require(value.cards==0 || value.campaignId!=null){"Seleccione una campaña para registrar tarjetas"}
        require(value.status!="CARD_DELIVERED" || value.cards>0){"Indique cuántas tarjetas entregó"}
        require(value.cards==0 || value.status in setOf("CARD_DELIVERED","INTERESTED","FOLLOW_UP","PENDING_VERIFICATION")){"Ese estado no admite tarjetas entregadas"}
        require((value.latitude==null)==(value.longitude==null)){"La evidencia de ubicación está incompleta"}
        if(value.latitude!=null && value.longitude!=null) require(Geo.valid(value.latitude,value.longitude)){"Ubicación de visita inválida"}
        value.accuracyMeters?.let{ require(it.isFinite() && it>0){"Precisión de visita inválida"} }
        photo(value.evidenceUri); text(value.comments); text(value.reviewNote)
    }
    fun movement(value:StockMovement) {
        id(value.id); id(value.campaignId); time(value.at); value.journeyId?.let{ id(it) }; value.visitId?.let{ id(it) }
        require(value.operationId.isNotBlank() && value.operationId.length<=200){"Identificador de operación inválido"}
        require(value.type in MovementType.entries.map{it.name} + listOf("QUICK_DELIVERY", "QUICK_UNDO")){"Tipo de movimiento inválido"}
        require(value.quantity in 1..1_000_000){"La cantidad debe estar entre 1 y 1.000.000"}
        require((value.type=="DELIVERY")== (value.visitId!=null)){"Una entrega debe estar vinculada a una visita"}
        text(value.note)
    }
    fun journey(value:Journey) {
        id(value.id); time(value.startedAt); time(value.intervalStartedAt); time(value.lastAliveAt)
        require(value.state in setOf("ACTIVE","PAUSED","FINISHED")){"Estado de jornada inválido"}
        require((value.state=="FINISHED")== (value.endedAt!=null)){"La jornada tiene un cierre incoherente"}
        require(value.endedAt==null || value.endedAt>=value.startedAt){"Los horarios de la jornada son incoherentes"}
        require(value.intervalStartedAt>=value.startedAt && value.lastAliveAt>=value.intervalStartedAt){"Las transiciones de la jornada son incoherentes"}
        require(value.activeMs>=0 && value.pausedMs>=0 && value.nextSegment>=0){"Duración o segmento inválido"}
        val completedMs=value.activeMs+value.pausedMs
        require(completedMs>=0 && completedMs<=value.intervalStartedAt-value.startedAt){"La duración de la jornada supera sus horarios"}
        require(value.endedAt==null || value.intervalStartedAt<=value.endedAt && value.lastAliveAt<=value.endedAt){"La actividad supera el cierre de la jornada"}
        require(value.distanceM.isFinite() && value.distanceM>=0){"Distancia de jornada inválida"}
        require(value.userId=="owner"){"Esta versión admite solamente al propietario local"}
    }
    fun quickDelivery(value: QuickDelivery) {
        id(value.id); id(value.campaignId); time(value.at)
        value.journeyId?.let { id(it) }; value.sectorId?.let { id(it) }; value.businessId?.let { id(it) }
        require((value.latitude == null) == (value.longitude == null)) { "Ubicación incompleta" }
        require((value.latitude == null) == (value.fixAt == null) && (value.latitude == null) == (value.accuracyMeters == null)) { "La ubicación necesita fecha y precisión" }
        if (value.latitude != null && value.longitude != null) require(Geo.valid(value.latitude, value.longitude)) { "Ubicación inválida" }
        value.accuracyMeters?.let { require(it.isFinite() && it in 0.01f..100f) { "Precisión insuficiente para marcar la entrega" } }
        value.fixAt?.let { require(value.at - it in 0..30_000) { "Ubicación antigua para la entrega" } }
        value.undoneAt?.let { require(it >= value.at) { "Fecha de anulación inválida" } }
    }
    fun snapshot(s:DataSnapshot) {
        require(s.areas.size+s.sectors.size+s.sectorRevisions.size+s.businesses.size+s.campaigns.size+s.visits.size+s.movements.size+s.journeys.size+s.points.size+s.incidents.size+s.routeStops.size+s.audit.size+s.quickDeliveries.size<=1_000_000){"El respaldo supera el límite de registros"}
        fun unique(ids:List<String>) { require(ids.distinct().size==ids.size){"El respaldo contiene registros duplicados"}; ids.forEach{ id(it) } }
        unique(s.areas.map{it.id}); unique(s.sectors.map{it.id}); unique(s.sectorRevisions.map{it.id}); unique(s.businesses.map{it.id}); unique(s.campaigns.map{it.id}); unique(s.visits.map{it.id}); unique(s.movements.map{it.id}); unique(s.journeys.map{it.id}); unique(s.points.map{it.id}); unique(s.incidents.map{it.id}); unique(s.audit.map{it.id})
        s.areas.forEach{area(it)}; s.sectors.forEach{sector(it)}; s.businesses.forEach{business(it)}; s.campaigns.forEach{campaign(it)}; s.visits.forEach{visit(it)}; s.movements.forEach{movement(it)}; s.journeys.forEach{journey(it)}
        val areas=s.areas.associateBy{it.id}; val sectors=s.sectors.associateBy{it.id}; val businesses=s.businesses.associateBy{it.id}; val campaigns=s.campaigns.associateBy{it.id}; val visits=s.visits.associateBy{it.id}; val journeys=s.journeys.associateBy{it.id}
        fun reference(value:String?,keys:Set<String>,message:String) { require(value==null || value in keys){message} }
        for(a in s.areas) {
            reference(a.parentId,areas.keys,"Falta una zona superior")
            val seen=hashSetOf(a.id); var current=a.parentId
            while(current!=null) { require(seen.add(current)){"Jerarquía circular en el respaldo"}; current=areas[current]?.parentId }
        }
        require(s.areas.distinctBy{Triple(it.kind,it.parentId,Names.normalize(it.name))}.size==s.areas.size){"Zonas duplicadas"}
        require(s.sectors.map{it.normalizedName}.distinct().size==s.sectors.size && s.sectors.map{it.code.lowercase()}.distinct().size==s.sectors.size){"Sectores duplicados"}
        val removedSectors=s.audit.filter { it.entityType=="sector" && it.action=="REMOVE" }.map { it.entityId }.toSet()
        for(sector in s.sectors) {
            reference(sector.areaId,areas.keys,"Falta la zona de un sector")
            val expectedIndex=if(sector.id in removedSectors) "removed-"+sector.id else Names.normalize(sector.name)
            require(sector.normalizedName==expectedIndex && sector.code.isNotBlank()){"Índice de sector inválido"}
            val revisions=s.sectorRevisions.filter{it.sectorId==sector.id}.sortedBy{it.revision}
            require(revisions.map{it.revision}==(1..sector.revision).toList() && revisions.lastOrNull()?.polygonJson==sector.polygonJson){"Historial de límites incompleto"}
        }
        for(r in s.sectorRevisions) { reference(r.sectorId,sectors.keys,"Falta el sector de una revisión"); require(r.revision>0 && Geo.validPolygon(Geo.parsePolygon(r.polygonJson))){"Revisión de límites inválida"}; time(r.changedAt) }
        for(b in s.businesses) {
            reference(b.sectorId,sectors.keys,"Falta el sector de un negocio"); reference(b.mergedIntoId,businesses.keys,"Falta el destino de una fusión")
            require(b.normalizedName==Names.normalize(b.name)){"Índice de negocio inválido"}
            require(b.mergedIntoId==null || businesses[b.mergedIntoId]?.mergedIntoId==null){"Cadena de fusiones inválida"}
        }
        require(s.campaigns.map{it.normalizedName}.distinct().size==s.campaigns.size){"Campañas duplicadas"}
        s.campaigns.forEach{require(it.normalizedName==Names.normalize(it.name)){"Índice de campaña inválido"}}
        require(s.journeys.count{it.state!="FINISHED"}<=1){"El respaldo contiene varias jornadas en curso"}
        for(v in s.visits) { reference(v.businessId,businesses.keys,"Falta el negocio de una visita"); reference(v.journeyId,journeys.keys,"Falta la jornada de una visita"); reference(v.campaignId,campaigns.keys,"Falta la campaña de una visita"); reference(v.sectorId,sectors.keys,"Falta el sector histórico de una visita") }
        require(s.movements.map{it.operationId}.distinct().size==s.movements.size){"Operaciones de inventario duplicadas"}
        for(m in s.movements) {
            reference(m.campaignId,campaigns.keys,"Falta la campaña de un movimiento"); reference(m.journeyId,journeys.keys,"Falta la jornada de un movimiento"); reference(m.visitId,visits.keys,"Falta la visita de una entrega")
            if(m.visitId!=null) { val v=visits.getValue(m.visitId); require(v.cards==m.quantity && v.campaignId==m.campaignId && v.journeyId==m.journeyId){"La entrega no coincide con la visita"} }
        }
        for(c in s.campaigns) {
            val ledger=s.movements.filter{it.campaignId==c.id}
            require(ledger.count{it.type=="INITIAL"}<=1 && ledger.drop(1).none{it.type=="INITIAL"}){"Inventario inicial duplicado o fuera de orden"}
            require(ledger.zipWithNext().all{it.first.at<=it.second.at}){"Movimientos de inventario fuera de orden"}
            Inventory.balance(ledger)
        }
        for(v in s.visits) require(s.movements.count{it.visitId==v.id}==if(v.cards>0)1 else 0){"Visita y salida de inventario no conciliadas"}
        unique(s.quickDeliveries.map { it.id })
        for (q in s.quickDeliveries) {
            quickDelivery(q)
            reference(q.campaignId,campaigns.keys,"Falta la campaña de una entrega rápida")
            reference(q.journeyId,journeys.keys,"Falta la jornada de una entrega rápida")
            reference(q.sectorId,sectors.keys,"Falta la zona de una entrega rápida")
            reference(q.businessId,businesses.keys,"Falta el negocio de una entrega rápida")
            val exit = s.movements.singleOrNull { it.operationId == "quick:${q.id}" }
            require(exit != null && exit.type == "QUICK_DELIVERY" && exit.campaignId == q.campaignId && exit.quantity == 1 && exit.journeyId == q.journeyId) { "Entrega rápida sin salida de inventario" }
            val undo = s.movements.singleOrNull { it.operationId == "quick-undo:${q.id}" }
            require((undo == null) == (q.undoneAt == null)) { "Anulación sin conciliar" }
            if (undo != null) require(undo.type == "QUICK_UNDO" && undo.campaignId == q.campaignId && undo.quantity == 1 && undo.journeyId == q.journeyId && undo.at >= exit.at) { "Anulación inconsistente" }
        }
        for (m in s.movements.filter { it.type in setOf("QUICK_DELIVERY", "QUICK_UNDO") }) {
            val prefix = if (m.type == "QUICK_DELIVERY") "quick:" else "quick-undo:"
            require(s.quickDeliveries.any { prefix + it.id == m.operationId }) { "Movimiento rápido sin entrega" }
        }
        require(s.points.distinctBy{Triple(it.journeyId,it.segment,it.elapsedRealtimeNanos)}.size==s.points.size){"Puntos GPS duplicados"}
        for(p in s.points) {
            reference(p.journeyId,journeys.keys,"Falta la jornada de un punto GPS"); time(p.at)
            require(Geo.valid(p.latitude,p.longitude) && p.accuracyMeters.isFinite() && p.accuracyMeters>0 && p.accuracyMeters<=GpsPolicy.MAX_ACCURACY_M && p.elapsedRealtimeNanos>0 && p.segment>=0 && p.distanceDeltaM.isFinite() && p.distanceDeltaM>=0){"Punto GPS inválido en el respaldo"}
            val journey=journeys.getValue(p.journeyId)
            require(p.segment<=journey.nextSegment && p.at>=journey.startedAt-120_000 && (journey.endedAt==null || p.at<=journey.endedAt+120_000)){"El punto GPS no corresponde a los horarios o segmentos de la jornada"}
        }
        for(j in s.journeys) {
            val points=s.points.filter{it.journeyId==j.id}
            require(points.sumOf{it.distanceDeltaM}<=j.distanceM+0.01){"La distancia de la jornada no coincide con sus puntos"}
            require(points.zipWithNext().all{it.first.at<=it.second.at && it.first.segment<=it.second.segment}){"Recorrido GPS fuera de orden"}
        }
        for(i in s.incidents) { reference(i.journeyId,journeys.keys,"Falta la jornada de una incidencia"); time(i.at); require(i.message.isNotBlank()){ "Incidencia vacía" }; text(i.message) }
        require(s.routeStops.map{it.businessId}.distinct().size==s.routeStops.size && s.routeStops.sortedBy{it.position}.map{it.position}==s.routeStops.indices.toList()){"Orden de planificación inválido"}
        for(stop in s.routeStops) { reference(stop.businessId,businesses.keys,"Falta un negocio de la planificación"); time(stop.addedAt); require(businesses[stop.businessId]?.mergedIntoId==null && businesses[stop.businessId]?.status!="DO_NOT_VISIT"){"La planificación contiene un negocio fusionado o marcado para no volver"} }
        for(a in s.audit) { time(a.at); text(a.detail,20_000); require(a.action.isNotBlank() && a.entityType.isNotBlank()){"Auditoría inválida"} }
    }
}
