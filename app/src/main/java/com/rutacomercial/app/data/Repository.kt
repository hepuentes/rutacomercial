package com.rutacomercial.app.data

import androidx.room.withTransaction
import com.rutacomercial.app.domain.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlin.math.max

class Repository(val db: AppDatabase) {
    val dao: AppDao = db.dao()
    fun observeSnapshot(): Flow<DataSnapshot> = db.invalidationTracker.createFlow(
        "areas","sectors","sector_revisions","businesses","campaigns","visits","stock_movements","journeys","track_points","incidents","route_stops","audit","quick_deliveries", emitInitialState=true
    ).map { snapshot() }.distinctUntilChanged()
    suspend fun snapshot(): DataSnapshot = dao.snapshot()
    private suspend fun audit(type:String,id:String,action:String,detail:String="",at:Long=nowMillis()) {
        dao.putAudit(AuditEvent(entityType=type,entityId=id,action=action,detail=detail,at=at))
    }

    suspend fun saveArea(area:Area):Area = db.withTransaction {
        Validation.area(area)
        val all=dao.areas()
        require(all.none { it.id!=area.id && it.kind==area.kind && it.parentId==area.parentId && Names.normalize(it.name)==Names.normalize(area.name) }) { "Ya existe una zona con ese nombre" }
        require(area.parentId==null || all.any { it.id==area.parentId }) { "La zona superior no existe" }
        var cursor=area.parentId; val seen=hashSetOf(area.id)
        while(cursor!=null) { require(seen.add(cursor)){"La jerarquía de zonas no puede ser circular"}; cursor=all.firstOrNull{it.id==cursor}?.parentId }
        val saved=area.copy(name=area.name.trim(),updatedAt=nowMillis()); dao.putArea(saved); audit("area",saved.id,"SAVE",saved.name); saved
    }
    suspend fun saveSector(sector:Sector):Sector = db.withTransaction {
        Validation.sector(sector)
        val all=dao.sectors(); val old=all.firstOrNull{it.id==sector.id}
        val normalized=Names.normalize(sector.name)
        val code=sector.code.trim().ifEmpty{"S-${sector.id.take(8).uppercase()}"}
        require(all.none{it.id!=sector.id && (it.normalizedName==normalized || it.code.equals(code,true))}) { "Ya existe un sector con ese nombre o código" }
        require(sector.areaId==null || dao.areas().any{it.id==sector.areaId}){"La zona del sector no existe"}
        val geometryChanged=old==null || old.polygonJson!=sector.polygonJson
        val saved=sector.copy(color=if(old==null && sector.color=="#087F75") ZoneColors.next(all.map { it.color }) else sector.color,name=sector.name.trim(),normalizedName=normalized,code=code,revision=if(old==null)1 else old.revision+if(geometryChanged)1 else 0,updatedAt=nowMillis())
        dao.putSector(saved)
        if(geometryChanged) dao.putSectorRevision(SectorRevision(sectorId=saved.id,revision=saved.revision,polygonJson=saved.polygonJson))
        audit("sector",saved.id,if(old==null)"CREATE" else "UPDATE","Revisión ${saved.revision}; estado ${saved.status}")
        saved
    }
    suspend fun removeZone(id: String) = db.withTransaction {
        val sector = requireNotNull(dao.sector(id))
        val active = dao.currentJourney()
        require(active == null || dao.audit().none { it.entityId == active.id && it.action == "WALK_PLAN" && it.detail.substringBefore('/') == id }) {
            "Terminá el recorrido antes de eliminar esta zona."
        }
        // Keep the historical geometry and foreign keys for visits and reports.
        dao.putSector(sector.copy(normalizedName = "removed-$id", code = "X-$id"))
        audit("sector", id, "REMOVE", sector.name)
    }

    suspend fun duplicateBusinesses(business:Business):List<Business> = dao.businesses().filter {
        it.id!=business.id && it.mergedIntoId==null && Geo.distanceM(it.latitude,it.longitude,business.latitude,business.longitude)<=100 && Names.similarity(it.name,business.name)>=0.72
    }
    suspend fun saveBusiness(business:Business,allowDuplicate:Boolean=false):Business = db.withTransaction {
        Validation.business(business)
        require(business.sectorId==null || dao.sector(business.sectorId)!=null){"El sector no existe"}
        require(business.mergedIntoId==null){"No se puede editar un negocio fusionado"}
        val duplicates=duplicateBusinesses(business)
        require(allowDuplicate || duplicates.isEmpty()) { "Posible duplicado cercano: ${duplicates.joinToString { it.name }}. Confirme si es un negocio distinto" }
        val old=dao.business(business.id)
        val saved=business.copy(name=business.name.trim(),normalizedName=Names.normalize(business.name),createdAt=old?.createdAt?:business.createdAt,updatedAt=nowMillis())
        dao.putBusiness(saved)
        if(saved.status=="DO_NOT_VISIT") setRouteInternal(dao.routeStops().map{it.businessId}.filter{it!=saved.id})
        audit("business",saved.id,if(old==null)"CREATE" else "UPDATE","Estado ${old?.status?:"nuevo"} → ${saved.status}; sector ${old?.sectorId?:"sin sector"} → ${saved.sectorId?:"sin sector"}")
        saved
    }
    suspend fun mergeBusinesses(sourceId:String,targetId:String) = db.withTransaction {
        require(sourceId!=targetId){"Seleccione dos negocios diferentes"}
        val source=requireNotNull(dao.business(sourceId)){"El negocio de origen no existe"}
        val target=requireNotNull(dao.business(targetId)){"El negocio de destino no existe"}
        require(source.mergedIntoId==null && target.mergedIntoId==null){"Uno de los negocios ya fue fusionado"}
        dao.visits().filter{it.businessId==sourceId}.forEach { dao.updateVisit(it.copy(businessId=targetId)) }
        dao.quickDeliveries().filter { it.businessId == sourceId }.forEach { dao.updateQuickDelivery(it.copy(businessId=targetId)) }
        // Keep all older merge aliases pointed directly at the canonical business.
        dao.businesses().filter{it.mergedIntoId==sourceId}.forEach { dao.putBusiness(it.copy(mergedIntoId=targetId,updatedAt=nowMillis())) }
        dao.putBusiness(source.copy(mergedIntoId=targetId,updatedAt=nowMillis()))
        val latest=dao.visits().filter{it.businessId==targetId}.maxByOrNull{it.at}
        if(latest!=null) dao.putBusiness(target.copy(status=latest.status,updatedAt=nowMillis()))
        setRouteInternal(dao.routeStops().map{if(it.businessId==sourceId)targetId else it.businessId}.distinct().filter{it!=targetId || target.status!="DO_NOT_VISIT"})
        audit("business",sourceId,"MERGE","Destino=$targetId. Historial conservado; origen=${source.name}")
    }
    suspend fun saveCampaign(campaign:Campaign,initialStock:Int=0):Campaign = db.withTransaction {
        Validation.campaign(campaign); require(initialStock>=0){"El inventario inicial no puede ser negativo"}
        val old=dao.campaign(campaign.id)
        if(old!=null && initialStock>0) {
            val initial=dao.movementByOperation("initial:${campaign.id}")
            require(initial?.quantity==initialStock && old.name==campaign.name.trim() && old.description==campaign.description && old.startsAt==campaign.startsAt && old.endsAt==campaign.endsAt && old.unitCostCents==campaign.unitCostCents){"El inventario de una campaña existente se cambia mediante movimientos"}
            return@withTransaction old
        }
        require(dao.campaigns().none{it.id!=campaign.id && it.normalizedName==Names.normalize(campaign.name)}){"Ya existe una campaña con ese nombre"}
        val saved=campaign.copy(name=campaign.name.trim(),normalizedName=Names.normalize(campaign.name),createdAt=old?.createdAt?:campaign.createdAt,updatedAt=nowMillis())
        dao.putCampaign(saved)
        if(initialStock>0) insertMovement(StockMovement(campaignId=saved.id,type="INITIAL",quantity=initialStock,operationId="initial:${saved.id}"))
        audit("campaign",saved.id,if(old==null)"CREATE" else "UPDATE",saved.name); saved
    }
    suspend fun stockBalance(campaignId:String):StockBalance = Inventory.balance(dao.movementsFor(campaignId))
    suspend fun moveStock(movement:StockMovement):StockMovement = db.withTransaction {
        require(movement.type !in setOf("DELIVERY", "QUICK_DELIVERY", "QUICK_UNDO")){"Las entregas se registran desde la visita al negocio"}
        require(movement.visitId==null){"Este movimiento no puede incluir una visita"}
        insertMovement(movement)
    }
    private suspend fun insertMovement(movement:StockMovement):StockMovement {
        Validation.movement(movement)
        val existing=dao.movementByOperation(movement.operationId)
        if(existing!=null) {
            require(existing.campaignId==movement.campaignId && existing.type==movement.type && existing.quantity==movement.quantity && existing.visitId==movement.visitId && existing.journeyId==movement.journeyId){"El identificador de operación ya se usó con otros datos"}
            return existing
        }
        require(dao.campaign(movement.campaignId)!=null){"La campaña no existe"}
        require(movement.journeyId==null || dao.journey(movement.journeyId)!=null){"La jornada no existe"}
        val previous=dao.movementsFor(movement.campaignId)
        require(movement.type!="INITIAL" || previous.isEmpty()){"El inventario inicial ya fue registrado"}
        val saved=movement.copy(at=max(movement.at,previous.lastOrNull()?.at?:0L))
        Inventory.balance(previous+saved)
        dao.putMovement(saved); audit("stock",saved.id,"MOVEMENT","${saved.type}: ${saved.quantity}; campaña=${saved.campaignId}")
        return saved
    }
    suspend fun recordVisit(visit:Visit):Visit = db.withTransaction {
        Validation.visit(visit)
        val existing=dao.visit(visit.id)
        if(existing!=null) {
            require(existing.businessId==visit.businessId && existing.campaignId==visit.campaignId && existing.journeyId==visit.journeyId && existing.status==visit.status && existing.cards==visit.cards && existing.comments==visit.comments && existing.evidenceUri==visit.evidenceUri && existing.latitude==visit.latitude && existing.longitude==visit.longitude && existing.accuracyMeters==visit.accuracyMeters && existing.at==visit.at){"El identificador de visita ya existe con otros datos"}
            return@withTransaction existing
        }
        val business=requireNotNull(dao.business(visit.businessId)){"El negocio no existe"}
        require(business.mergedIntoId==null){"Abra el negocio de destino de la fusión"}
        val journey=visit.journeyId?.let{requireNotNull(dao.journey(it)){"La jornada no existe"}}
        require(journey==null || journey.state!="FINISHED"){"La jornada ya finalizó"}
        require(visit.campaignId==null || dao.campaign(visit.campaignId)!=null){"La campaña no existe"}
        val saved=visit.copy(sectorId=business.sectorId,reviewState="DECLARED",reviewNote="")
        dao.insertVisit(saved)
        if(saved.cards>0) insertMovement(StockMovement(campaignId=requireNotNull(saved.campaignId),type="DELIVERY",quantity=saved.cards,at=saved.at,operationId="visit:${saved.id}",journeyId=saved.journeyId,visitId=saved.id,note=saved.comments))
        dao.putBusiness(business.copy(status=saved.status,updatedAt=nowMillis()))
        if(saved.status=="DO_NOT_VISIT") setRouteInternal(dao.routeStops().map{it.businessId}.filter{it!=business.id})
        if(saved.sectorId!=null) {
            val sector=dao.sector(saved.sectorId)
            if(sector?.status=="PENDING") dao.putSector(sector.copy(status="ACTIVE",updatedAt=nowMillis()))
        }
        val previous=dao.visits().filter{it.businessId==business.id && it.id!=saved.id}.maxByOrNull{it.at}
        if(previous!=null && saved.at-previous.at in 0..60_000) dao.putIncident(Incident(journeyId=saved.journeyId,kind="REVIEW",message="Visitas declaradas próximas para ${business.name}; requiere revisión humana, no demuestra fraude"))
        audit("visit",saved.id,"DECLARE","Estado=${saved.status}; tarjetas=${saved.cards}; negocio=${saved.businessId}")
        saved
    }
    suspend fun recordQuickDelivery(value: QuickDelivery): QuickDelivery = db.withTransaction {
        Validation.quickDelivery(value)
        require(value.businessId == null && value.undoneAt == null) { "La entrega nueva no puede estar anulada ni identificada" }
        dao.quickDelivery(value.id)?.let { old ->
            require(old.copy(businessId = null, undoneAt = null) == value) { "Identificador de entrega usado con otros datos" }
            return@withTransaction old
        }
        require(dao.campaign(value.campaignId) != null) { "Elegí el material que estás repartiendo" }
        require(value.sectorId == null || dao.sector(value.sectorId) != null) { "La zona no existe" }
        require(value.journeyId == null || dao.journey(value.journeyId)?.state in setOf("ACTIVE", "PAUSED")) { "La jornada ya terminó" }
        dao.insertQuickDelivery(value)
        insertMovement(StockMovement(campaignId=value.campaignId,type="QUICK_DELIVERY",quantity=1,at=value.at,
            operationId="quick:${value.id}",journeyId=value.journeyId,note="Entrega a un toque"))
        audit("quick_delivery",value.id,"DECLARE","Una tarjeta; ubicación aproximada declarada")
        value
    }
    suspend fun undoQuickDelivery(id: String) = db.withTransaction {
        val old = requireNotNull(dao.quickDelivery(id)) { "La entrega no existe" }
        if (old.undoneAt != null) return@withTransaction
        val now = maxOf(nowMillis(), old.at)
        insertMovement(StockMovement(campaignId=old.campaignId,type="QUICK_UNDO",quantity=1,at=now,
            operationId="quick-undo:$id",journeyId=old.journeyId,note="Anulación de entrega a un toque"))
        dao.updateQuickDelivery(old.copy(undoneAt=now))
        audit("quick_delivery",id,"UNDO","Tarjeta reintegrada a las disponibles para repartir")
    }
    suspend fun identifyQuickDelivery(id: String, businessId: String) = db.withTransaction {
        val old = requireNotNull(dao.quickDelivery(id)) { "La entrega no existe" }
        require(old.undoneAt == null) { "La entrega fue anulada" }
        val business = requireNotNull(dao.business(businessId)) { "El negocio no existe" }
        require(business.mergedIntoId == null) { "Seleccioná el negocio de destino de la fusión" }
        dao.updateQuickDelivery(old.copy(businessId=businessId))
        audit("quick_delivery",id,"IDENTIFY","Negocio=$businessId; inventario sin cambios")
    }
    suspend fun prepareQuickCampaign(name: String, cards: Int): Campaign = db.withTransaction {
        require(cards in 1..1_000_000) { "Indicá entre 1 y 1.000.000 tarjetas" }
        val campaign = saveCampaign(Campaign(name=name), cards)
        moveStock(StockMovement(campaignId=campaign.id,type="ASSIGN",quantity=cards))
        campaign
    }

    suspend fun reviewVisit(id:String,reviewState:String,note:String) = db.withTransaction {
        require(reviewState in Validation.reviewStates){"Estado de revisión inválido"}
        require(note.isNotBlank()){"Explique la revisión para conservar el historial"}
        val old=requireNotNull(dao.visit(id)){"La visita no existe"}
        dao.updateVisit(old.copy(reviewState=reviewState,reviewNote=note.trim()))
        audit("visit",id,"REVIEW","${old.reviewState} → $reviewState: ${note.trim()}")
    }

    suspend fun currentJourney():Journey? = dao.currentJourney()
    suspend fun startJourney(now:Long=nowMillis(), sectorId:String?=null, assignmentId:String?=null, eventPosition:String=""):Journey = db.withTransaction {
        require(dao.currentJourney()==null){"Ya hay una jornada en curso"}
        val journey=Journey(startedAt=now,intervalStartedAt=now,lastAliveAt=now)
        dao.putJourney(journey)
        val zone=sectorId?.let { dao.sector(it) }
        if(zone!=null && zone.status!="ACTIVE") dao.putSector(zone.copy(status="ACTIVE",updatedAt=now))
        audit("journey",journey.id,"START",eventPosition,at=now)
        if(assignmentId!=null) audit("journey",journey.id,"ASSIGNMENT",assignmentId,now)
        audit("journey",journey.id,"WALK_PLAN",zone?.let { "${it.id}/${it.revision}" } ?: "FREE",now)
        journey
    }
    suspend fun pauseJourney(id:String,now:Long=nowMillis(),eventPosition:String=""):Journey = db.withTransaction {
        val old=requireNotNull(dao.journey(id)){"La jornada no existe"}
        if(old.state=="PAUSED") return@withTransaction old
        require(old.state=="ACTIVE"){"Solo se puede pausar una jornada activa"}
        val transition=maxOf(now,old.intervalStartedAt,old.lastAliveAt)
        val saved=old.copy(state="PAUSED",activeMs=old.activeDuration(transition),intervalStartedAt=transition,lastAliveAt=transition,nextSegment=old.nextSegment+1)
        dao.putJourney(saved); audit("journey",id,"PAUSE",eventPosition,at=transition); saved
    }
    /** Pause backdated to the last movement: the still minutes become pause, not paid active time. */
    suspend fun pauseIdle(id:String,movedAt:Long):Journey = db.withTransaction {
        val old=requireNotNull(dao.journey(id)){"La jornada no existe"}
        if(old.state!="ACTIVE") return@withTransaction old
        val transition=maxOf(movedAt,old.intervalStartedAt)
        val saved=old.copy(state="PAUSED",activeMs=old.activeDuration(transition),intervalStartedAt=transition,lastAliveAt=transition,nextSegment=old.nextSegment+1)
        dao.putJourney(saved); audit("journey",id,"PAUSE","",at=transition); saved
    }
    suspend fun resumeJourney(id:String,now:Long=nowMillis(),eventPosition:String=""):Journey = db.withTransaction {
        val old=requireNotNull(dao.journey(id)){"La jornada no existe"}
        if(old.state=="ACTIVE") return@withTransaction old
        require(old.state=="PAUSED"){"Solo se puede reanudar una jornada en pausa"}
        val transition=maxOf(now,old.intervalStartedAt,old.lastAliveAt)
        val saved=old.copy(state="ACTIVE",pausedMs=old.pauseDuration(transition),intervalStartedAt=transition,lastAliveAt=transition)
        dao.putJourney(saved); audit("journey",id,"RESUME",eventPosition,at=transition); saved
    }
    suspend fun finishJourney(id:String,now:Long=nowMillis(),eventPosition:String=""):Journey = db.withTransaction {
        val old=requireNotNull(dao.journey(id)){"La jornada no existe"}
        if(old.state=="FINISHED") return@withTransaction old
        val transition=maxOf(now,old.intervalStartedAt,old.lastAliveAt)
        val saved=old.copy(state="FINISHED",endedAt=transition,activeMs=old.activeDuration(transition),pausedMs=old.pauseDuration(transition),intervalStartedAt=transition,lastAliveAt=transition)
        dao.putJourney(saved); audit("journey",id,"FINISH",eventPosition,at=transition); saved
    }
    suspend fun heartbeat(id:String,now:Long=nowMillis()) = db.withTransaction {
        val journey=dao.journey(id) ?: return@withTransaction
        if(journey.state=="ACTIVE") dao.putJourney(journey.copy(lastAliveAt=maxOf(now,journey.intervalStartedAt,journey.lastAliveAt)))
    }
    suspend fun recoverInterruptedJourney():Journey? = db.withTransaction {
        val old=dao.currentJourney() ?: return@withTransaction null
        if(old.state!="ACTIVE") return@withTransaction old
        val cutoff=max(old.intervalStartedAt,old.lastAliveAt)
        val paused=old.copy(state="PAUSED",activeMs=old.activeDuration(cutoff),intervalStartedAt=cutoff,nextSegment=old.nextSegment+1)
        dao.putJourney(paused)
        dao.putIncident(Incident(journeyId=old.id,kind="INTERRUPTION",message="Seguimiento interrumpido. Se pausó al último registro de actividad; no se reconstruyeron tramos GPS"))
        audit("journey",old.id,"RECOVER_PAUSED","Última actividad=$cutoff")
        paused
    }
    // Only the live walking filter can attest to fresh, reliable samples during a stationary stop.
    suspend fun recordLocation(fix:GpsFix,signalContinuous:Boolean=false):GpsDecision = db.withTransaction {
        val journey=dao.currentJourney() ?: return@withTransaction GpsDecision(false,"No hay una jornada activa")
        if(journey.state!="ACTIVE") return@withTransaction GpsDecision(false,"Jornada en pausa")
        if(fix.at<journey.intervalStartedAt) return@withTransaction GpsDecision(false,"Ubicación anterior al inicio o reanudación de la jornada")
        val last=dao.lastPoint(journey.id)
        if(last!=null && fix.at<=last.at) return@withTransaction GpsDecision(false,"Punto repetido o reloj anterior al último registro")
        val previous=last?.takeIf{it.segment==journey.nextSegment}?.let{GpsFix(it.latitude,it.longitude,it.accuracyMeters,it.at,it.elapsedRealtimeNanos,it.isMock)}
        val decision=if(kotlin.math.abs(nowMillis()-fix.at)>120_000L) GpsDecision(false,"Ubicación antigua o reloj incorrecto",true) else GpsPolicy.evaluate(previous,fix,signalContinuous)
        if(!decision.accepted) {
            if(decision.breakSegment && (last==null && journey.nextSegment==0 || last!=null && journey.nextSegment==last.segment)) {
                dao.putJourney(journey.copy(nextSegment=journey.nextSegment+1,lastAliveAt=maxOf(nowMillis(),journey.intervalStartedAt,journey.lastAliveAt)))
                dao.putIncident(Incident(journeyId=journey.id,kind="GPS",message=decision.reason))
            }
            return@withTransaction decision
        }
        // Repeated elapsed realtime values are never inserted, including after a signal gap.
        if(last!=null && fix.elapsedRealtimeNanos==last.elapsedRealtimeNanos) return@withTransaction GpsDecision(false,"Punto repetido")
        val segment=if(decision.breakSegment && previous!=null)journey.nextSegment+1 else journey.nextSegment
        val point=TrackPoint(journeyId=journey.id,latitude=fix.latitude,longitude=fix.longitude,accuracyMeters=fix.accuracyMeters,at=fix.at,elapsedRealtimeNanos=fix.elapsedRealtimeNanos,segment=segment,isMock=fix.isMock,distanceDeltaM=decision.distanceM)
        dao.putPoint(point)
        dao.putJourney(journey.copy(distanceM=journey.distanceM+decision.distanceM,nextSegment=segment,lastAliveAt=maxOf(nowMillis(),journey.intervalStartedAt,journey.lastAliveAt)))
        if(fix.isMock && last?.isMock!=true) dao.putIncident(Incident(journeyId=journey.id,kind="MOCK_LOCATION",message="Android informó una ubicación simulada. Es una señal para revisión, no una prueba de fraude"))
        if(decision.breakSegment && previous!=null) dao.putIncident(Incident(journeyId=journey.id,kind="GPS_GAP",message=decision.reason))
        decision
    }
    suspend fun breakGpsSegment(reason: String) = db.withTransaction {
        val journey = dao.currentJourney() ?: return@withTransaction
        if (journey.state != "ACTIVE") return@withTransaction
        val last = dao.lastPoint(journey.id) ?: return@withTransaction
        if (last.segment == journey.nextSegment) {
            dao.putJourney(journey.copy(nextSegment = journey.nextSegment + 1))
            dao.putIncident(Incident(journeyId = journey.id, kind = "GPS", message = reason))
        }
    }
    suspend fun addIncident(message:String,journeyId:String?=null,kind:String="NOTE") = db.withTransaction {
        require(message.isNotBlank()){"Escriba la incidencia"}
        require(journeyId==null || dao.journey(journeyId)!=null){"La jornada no existe"}
        dao.putIncident(Incident(journeyId=journeyId,kind=kind,message=message.trim()))
    }
    private suspend fun setRouteInternal(ids:List<String>) {
        require(ids.distinct().size==ids.size){"Un negocio no puede aparecer dos veces en la planificación"}
        for(id in ids) require(dao.business(id)?.let{it.mergedIntoId==null && it.status!="DO_NOT_VISIT"}==true){"Hay un negocio inexistente, fusionado o marcado para no volver a visitar"}
        dao.clearRoute(); ids.forEachIndexed { index,id -> dao.putRouteStop(RouteStop(id,index)) }
    }
    suspend fun setRoute(businessIds:List<String>) = db.withTransaction { setRouteInternal(businessIds); audit("route","owner","REORDER","${businessIds.size} paradas") }
    suspend fun addRouteStop(businessId:String) = db.withTransaction { setRouteInternal((dao.routeStops().map{it.businessId}+businessId).distinct()) }
    suspend fun removeRouteStop(businessId:String) = db.withTransaction { setRouteInternal(dao.routeStops().map{it.businessId}.filter{it!=businessId}) }

    suspend fun deleteOldTracks(cutoffMillis:Long):Int = db.withTransaction {
        val count=dao.deleteOldTracks(cutoffMillis)
        audit("privacy","owner","DELETE_OLD_TRACKS","$count posiciones anteriores a $cutoffMillis; métricas agregadas conservadas")
        count
    }
    suspend fun restoreIntoEmpty(snapshot:DataSnapshot) = db.withTransaction {
        require(dao.snapshot().isEmpty){"La restauración solo está permitida en una instalación vacía. Sus datos actuales no se sobrescribieron"}
        Validation.snapshot(snapshot)
        snapshot.areas.forEach{dao.putArea(it)}
        snapshot.sectors.forEach{dao.putSector(it)}
        snapshot.sectorRevisions.forEach{dao.putSectorRevision(it)}
        snapshot.businesses.forEach{dao.putBusiness(it)}
        snapshot.campaigns.forEach{dao.putCampaign(it)}
        snapshot.journeys.forEach{dao.putJourney(it)}
        snapshot.visits.forEach{dao.insertVisit(it)}
        snapshot.quickDeliveries.forEach{dao.insertQuickDelivery(it)}
        snapshot.movements.forEach{dao.putMovement(it)}
        snapshot.points.forEach{dao.putPoint(it)}
        snapshot.incidents.forEach{dao.putIncident(it)}
        snapshot.routeStops.forEach{dao.putRouteStop(it)}
        snapshot.audit.forEach{dao.putAudit(it)}
        recoverInterruptedJourney()
        audit("backup","owner","RESTORE","Respaldo validado; no se sobrescribieron datos")
    }
}
