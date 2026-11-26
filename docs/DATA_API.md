# Contrato Kotlin de datos

Paquete `com.rutacomercial.app.data`. Room, todos los ID `String` UUID. Tiempo epoch milisegundos UTC; coordenadas lat/lon. Entidades tienen valores por defecto para campos opcionales y `id = newId()`.

`AppDatabase.create(context):AppDatabase`; `Repository(val db:AppDatabase)`, `val dao = db.dao()`.

`DataSnapshot(areas:List<Area>, sectors:List<Sector>, sectorRevisions:List<SectorRevision>, businesses:List<Business>, campaigns:List<Campaign>, visits:List<Visit>, movements:List<StockMovement>, journeys:List<Journey>, points:List<TrackPoint>, incidents:List<Incident>, routeStops:List<RouteStop>, audit:List<AuditEvent>, quickDeliveries:List<QuickDelivery>)`. Todas las listas vacías por defecto. Propiedad `activeJourney:Journey?` (estado ACTIVE o PAUSED), `visibleBusinesses` excluye fusionados. `repo.observeSnapshot():Flow<DataSnapshot>` y `suspend repo.snapshot():DataSnapshot`.

Entidades (campos exactos):

- `Area(id, name, kind="CITY", parentId:String?=null, updatedAt:Long)`; kinds CITY, LOCALITY, NEIGHBORHOOD.
- `Sector(id, name, normalizedName="", code="", color="#087F75", polygonJson="[]", areaId:String?=null, status="PENDING", revision:Int=1, updatedAt:Long)`; polygonJson es array `[[lat,lon],[lat,lon],...]`, al menos tres vértices; states PENDING, ACTIVE, COMPLETED.
- `SectorRevision(id, sectorId, revision:Int, polygonJson, changedAt:Long)`.
- `Business(id, name, normalizedName="", category="", address="", latitude:Double, longitude:Double, neighborhood="", sectorId:String?=null, phone="", photoUri:String?=null, notes="", status="PENDING", nextContactAt:Long?=null, priority:Int=0, mergedIntoId:String?=null, createdAt:Long, updatedAt:Long)`.
- `Campaign(id, name, normalizedName="", description="", startsAt:Long, endsAt:Long?=null, unitCostCents:Long?=null, createdAt:Long, updatedAt:Long)`.
- `Visit(id, businessId, journeyId:String?=null, campaignId:String?=null, sectorId:String?=null, at:Long, status, cards:Int=0, comments="", evidenceUri:String?=null, latitude:Double?=null, longitude:Double?=null, accuracyMeters:Float?=null, reviewState="DECLARED", reviewNote="")`.
- `StockMovement(id, campaignId, type:String, quantity:Int, at:Long, operationId:String=id, journeyId:String?=null, visitId:String?=null, note="")`.
- `Journey(id, userId="owner", startedAt:Long, endedAt:Long?=null, state="ACTIVE", activeMs:Long=0, pausedMs:Long=0, intervalStartedAt:Long, lastAliveAt:Long, distanceM:Double=0.0, nextSegment:Int=0)`.
- `TrackPoint(id, journeyId, latitude:Double, longitude:Double, accuracyMeters:Float, at:Long, elapsedRealtimeNanos:Long, segment:Int, isMock:Boolean=false, distanceDeltaM:Double=0.0)`.
- `Incident(id, journeyId:String?=null, at:Long, kind="NOTE", message)`.
- `RouteStop(businessId:String, position:Int, addedAt:Long)`.
- `AuditEvent(id, entityType, entityId, action, detail="", at:Long)`.

Enums with `label:String`: `VisitStatus` PENDING, VISITED_NO_DELIVERY, CARD_DELIVERED, CLOSED, REJECTED, INTERESTED, FOLLOW_UP, PENDING_VERIFICATION, DO_NOT_VISIT; `MovementType` INITIAL, ENTRY, ASSIGN, DELIVERY, DAMAGE, RETURN. Entities store `.name` strings. `VisitStatus.labelOf(value)` returns Spanish label.

Repository suspend methods:

- `saveArea(area):Area`, `saveSector(sector):Sector`, `saveBusiness(business, allowDuplicate:Boolean=false):Business`, `duplicateBusinesses(business):List<Business>`, `mergeBusinesses(sourceId,targetId)` (explicit confirmed UI only), `saveCampaign(campaign, initialStock:Int=0):Campaign`.
- `recordVisit(visit):Visit` atomically consumes CARRIED inventory; cards>0 requires campaign; persist same visit id is idempotent. `reviewVisit(id, reviewState, note)` where reviewState DECLARED, ADDITIONAL_EVIDENCE, REVIEWED, REJECTED. Review is auditable, not silent overwrite.
- `moveStock(movement):StockMovement` INITIAL/ENTRY add depot, ASSIGN transfers depot to carried, DELIVERY and DAMAGE reduce carried, RETURN transfers carried to depot. No negatives, unique operationId. `stockBalance(campaignId):StockBalance(depot:Long,carried:Long,delivered:Long,damaged:Long)`. Pure `Inventory.balance(movements)` also available in domain package. Delivery movement only through visit.
- `startJourney(now:Long=System.currentTimeMillis()):Journey`, `pauseJourney(id,now=...):Journey`, `resumeJourney(id,now=...):Journey`, `finishJourney(id,now=...):Journey`, `heartbeat(id,now=...)`, `recoverInterruptedJourney():Journey?` (call only if tracking service is NOT running; pauses at lastAliveAt and records incident). `currentJourney():Journey?`.
- `recordLocation(fix:com.rutacomercial.app.domain.GpsFix):com.rutacomercial.app.domain.GpsDecision`. GpsFix(latitude,longitude,accuracyMeters:Float,at:Long=now,elapsedRealtimeNanos:Long,isMock:Boolean=false). Repository applies pure GpsPolicy validation, rejects impossible/stale/inaccurate points, starts new segment on gap/restart/pause, never draws missing portions. GPSDecision(accepted:Boolean,reason:String,breakSegment:Boolean=false,distanceM:Double=0.0). Reason strings human Spanish. `addIncident(message, journeyId:String?=null, kind="NOTE")`.
- `setRoute(businessIds:List<String>)`, `addRouteStop(businessId)`, `removeRouteStop(businessId)`.
- `restoreIntoEmpty(snapshot:DataSnapshot)` validated transaction and only empty destination; no destructive restore. `deleteOldTracks(cutoffMillis:Long):Int` explicit retention, only deletes precise trackpoints of FINISHED journeys; preserves journey metrics/commercial history and audits deletion.

Domain helpers `Geo.distanceM(lat1,lon1,lat2,lon2)`, `Geo.parsePolygon(json):List<Pair<Double,Double>>`, `Geo.polygonJson(points)`, `Geo.contains(polygon,lat,lon)`, `Names.normalize(text)`, `GpsPolicy.evaluate(previous:GpsFix?, current:GpsFix):GpsDecision`, `Inventory.balance(movements):StockBalance`.

Journey totals displayed: persisted activeMs/pausedMs + current interval only if relevant state, clamp time >=0. `Journey.activeDuration(now)` / `pauseDuration(now)` extensions in data package. All user input validation errors are `IllegalArgumentException` with Spanish message.


## Entregas a un toque (0.4.0)

`QuickDelivery(id, campaignId, at, journeyId?, sectorId?, latitude?, longitude?, accuracyMeters?, fixAt?, businessId?, undoneAt?)`. Todos los opcionales nulos por defecto. Una fila declara exactamente una tarjeta; no es una visita verificada. `activeQuickDeliveries` excluye anuladas. Se valida ubicación completa, hasta 30 s anterior al evento y precisión hasta 100 m.

`recordQuickDelivery(value)` inserta entrega y movimiento `QUICK_DELIVERY`, operación `quick:<id>`, de forma atómica e idempotente. `undoQuickDelivery(id)` conserva la fila, marca fecha de anulación y agrega `QUICK_UNDO` con operación `quick-undo:<id>` una sola vez. `identifyQuickDelivery(id,businessId)` asocia un negocio sin crear visita ni consumir inventario nuevamente. La fusión de negocios redirige también esas referencias. Estos tipos de stock son internos y no se permiten en `moveStock` manual.

`prepareQuickCampaign(name,cards)` crea material, inventario inicial y asignación dentro de una transacción. Resúmenes y exportaciones suman las entregas activas además de las visitas; anulaciones conservadas en CSV/respaldo.

Room v2 agrega quick_deliveries con índices y referencias, conservando tablas existentes. Recolorea únicamente sectores con el color predeterminado al migrar. Backups schema2 incluyen la nueva lista; el lector completa una lista vacía para backups schema1 anteriores.
