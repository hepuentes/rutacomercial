package com.rutacomercial.app.data

import androidx.room.*
import java.util.UUID

fun newId(): String = UUID.randomUUID().toString()
fun nowMillis(): Long = System.currentTimeMillis()

enum class VisitStatus(val label: String) {
    PENDING("Pendiente"), VISITED_NO_DELIVERY("Visitado sin entrega"), CARD_DELIVERED("Tarjeta entregada"),
    CLOSED("Cerrado"), REJECTED("Rechazó publicidad"), INTERESTED("Cliente interesado"),
    FOLLOW_UP("Requiere seguimiento"), PENDING_VERIFICATION("Pendiente de verificación"), DO_NOT_VISIT("No volver a visitar");
    companion object { fun labelOf(value: String): String = entries.firstOrNull { it.name == value }?.label ?: value }
}
enum class MovementType(val label: String) {
    INITIAL("Inventario inicial"), ENTRY("Nueva entrada"), ASSIGN("Asignar para repartir"),
    DELIVERY("Entrega declarada"), DAMAGE("Tarjetas dañadas"), RETURN("Devolver al depósito")
}

@Entity(tableName = "areas", indices = [Index("parentId")])
data class Area(@PrimaryKey val id: String = newId(), val name: String, val kind: String = "CITY",
    val parentId: String? = null, val updatedAt: Long = nowMillis())

@Entity(tableName = "sectors", indices = [Index(value=["normalizedName"], unique=true), Index(value=["code"], unique=true), Index("areaId")])
data class Sector(@PrimaryKey val id: String = newId(), val name: String, val normalizedName: String = "", val code: String = "",
    val color: String = "#087F75", val polygonJson: String = "[]", val areaId: String? = null, val status: String = "PENDING",
    val revision: Int = 1, val updatedAt: Long = nowMillis())

@Entity(tableName="sector_revisions", indices=[Index(value=["sectorId","revision"], unique=true)],
    foreignKeys=[ForeignKey(entity=Sector::class, parentColumns=["id"], childColumns=["sectorId"])])
data class SectorRevision(@PrimaryKey val id: String = newId(), val sectorId: String, val revision: Int, val polygonJson: String, val changedAt: Long = nowMillis())

@Entity(tableName="businesses", indices=[Index("sectorId"),Index("normalizedName"),Index("status"),Index("mergedIntoId")],
    foreignKeys=[ForeignKey(entity=Sector::class, parentColumns=["id"], childColumns=["sectorId"])])
data class Business(@PrimaryKey val id: String = newId(), val name: String, val normalizedName: String = "", val category: String = "", val address: String = "",
    val latitude: Double, val longitude: Double, val neighborhood: String = "", val sectorId: String? = null, val phone: String = "",
    val photoUri: String? = null, val notes: String = "", val status: String = "PENDING", val nextContactAt: Long? = null,
    val priority: Int = 0, val mergedIntoId: String? = null, val createdAt: Long = nowMillis(), val updatedAt: Long = nowMillis())

@Entity(tableName="campaigns", indices=[Index(value=["normalizedName"],unique=true)])
data class Campaign(@PrimaryKey val id: String = newId(), val name: String, val normalizedName: String = "", val description: String = "",
    val startsAt: Long = nowMillis(), val endsAt: Long? = null, val unitCostCents: Long? = null, val createdAt: Long = nowMillis(), val updatedAt: Long = nowMillis())

@Entity(tableName="journeys", indices=[Index("state"),Index("startedAt")])
data class Journey(@PrimaryKey val id: String = newId(), val userId: String = "owner", val startedAt: Long = nowMillis(), val endedAt: Long? = null,
    val state: String = "ACTIVE", val activeMs: Long = 0, val pausedMs: Long = 0, val intervalStartedAt: Long = startedAt,
    val lastAliveAt: Long = startedAt, val distanceM: Double = 0.0, val nextSegment: Int = 0)

fun Journey.activeDuration(now: Long = nowMillis()): Long = activeMs + if(state == "ACTIVE") (now-intervalStartedAt).coerceAtLeast(0) else 0
fun Journey.pauseDuration(now: Long = nowMillis()): Long = pausedMs + if(state == "PAUSED") (now-intervalStartedAt).coerceAtLeast(0) else 0

@Entity(tableName="visits", indices=[Index("businessId"),Index("journeyId"),Index("campaignId"),Index("sectorId"),Index("at")],
    foreignKeys=[ForeignKey(entity=Business::class,parentColumns=["id"],childColumns=["businessId"]),
        ForeignKey(entity=Journey::class,parentColumns=["id"],childColumns=["journeyId"]),
        ForeignKey(entity=Campaign::class,parentColumns=["id"],childColumns=["campaignId"])])
data class Visit(@PrimaryKey val id: String = newId(), val businessId: String, val journeyId: String? = null, val campaignId: String? = null,
    val sectorId: String? = null, val at: Long = nowMillis(), val status: String, val cards: Int = 0, val comments: String = "",
    val evidenceUri: String? = null, val latitude: Double? = null, val longitude: Double? = null, val accuracyMeters: Float? = null,
    val reviewState: String = "DECLARED", val reviewNote: String = "")

@Entity(tableName="stock_movements", indices=[Index("campaignId"),Index(value=["operationId"],unique=true),Index(value=["visitId"],unique=true),Index("journeyId")],
    foreignKeys=[ForeignKey(entity=Campaign::class,parentColumns=["id"],childColumns=["campaignId"]),
        ForeignKey(entity=Visit::class,parentColumns=["id"],childColumns=["visitId"])])
data class StockMovement(@PrimaryKey val id: String = newId(), val campaignId: String, val type: String, val quantity: Int,
    val at: Long = nowMillis(), val operationId: String = id, val journeyId: String? = null, val visitId: String? = null, val note: String = "")

@Entity(tableName="track_points",indices=[Index(value=["journeyId","at"]),Index(value=["journeyId","segment","elapsedRealtimeNanos"],unique=true)],
    foreignKeys=[ForeignKey(entity=Journey::class,parentColumns=["id"],childColumns=["journeyId"],onDelete=ForeignKey.CASCADE)])
data class TrackPoint(@PrimaryKey val id: String = newId(), val journeyId: String, val latitude: Double, val longitude: Double,
    val accuracyMeters: Float, val at: Long, val elapsedRealtimeNanos: Long, val segment: Int, val isMock: Boolean = false, val distanceDeltaM: Double = 0.0)

@Entity(tableName="incidents",indices=[Index("journeyId")])
data class Incident(@PrimaryKey val id: String = newId(), val journeyId: String? = null, val at: Long = nowMillis(), val kind: String = "NOTE", val message: String)

@Entity(tableName="route_stops", indices=[Index(value=["position"],unique=true)],
    foreignKeys=[ForeignKey(entity=Business::class,parentColumns=["id"],childColumns=["businessId"])])
data class RouteStop(@PrimaryKey val businessId: String, val position: Int, val addedAt: Long = nowMillis())

@Entity(tableName="audit", indices=[Index("entityId"),Index("at")])
data class AuditEvent(@PrimaryKey val id: String = newId(), val entityType: String, val entityId: String, val action: String, val detail: String = "", val at: Long = nowMillis())

/** A declared single card, optionally located. Identifying it never creates another stock exit. */
@Entity(tableName="quick_deliveries", indices=[Index("campaignId"),Index("journeyId"),Index("sectorId"),Index("businessId")],
    foreignKeys=[ForeignKey(entity=Campaign::class,parentColumns=["id"],childColumns=["campaignId"]),
        ForeignKey(entity=Journey::class,parentColumns=["id"],childColumns=["journeyId"]),
        ForeignKey(entity=Sector::class,parentColumns=["id"],childColumns=["sectorId"]),
        ForeignKey(entity=Business::class,parentColumns=["id"],childColumns=["businessId"])])
data class QuickDelivery(@PrimaryKey val id: String = newId(), val campaignId: String, val at: Long = nowMillis(),
    val journeyId: String? = null, val sectorId: String? = null, val latitude: Double? = null, val longitude: Double? = null,
    val accuracyMeters: Float? = null, val fixAt: Long? = null, val businessId: String? = null, val undoneAt: Long? = null)

data class StockBalance(val depot: Long = 0, val carried: Long = 0, val delivered: Long = 0, val damaged: Long = 0)

data class DataSnapshot(val areas: List<Area> = emptyList(), val sectors: List<Sector> = emptyList(), val sectorRevisions: List<SectorRevision> = emptyList(),
    val businesses: List<Business> = emptyList(), val campaigns: List<Campaign> = emptyList(), val visits: List<Visit> = emptyList(),
    val movements: List<StockMovement> = emptyList(), val journeys: List<Journey> = emptyList(), val points: List<TrackPoint> = emptyList(),
    val incidents: List<Incident> = emptyList(), val routeStops: List<RouteStop> = emptyList(), val audit: List<AuditEvent> = emptyList(), val quickDeliveries: List<QuickDelivery> = emptyList()) {
    val activeQuickDeliveries: List<QuickDelivery> get() = quickDeliveries.filter { it.undoneAt == null }
    val activeJourney: Journey? get() = journeys.firstOrNull { it.state == "ACTIVE" || it.state == "PAUSED" }
    val visibleBusinesses: List<Business> get() = businesses.filter { it.mergedIntoId == null }
    val isEmpty: Boolean get() = areas.isEmpty() && sectors.isEmpty() && businesses.isEmpty() && campaigns.isEmpty() && visits.isEmpty() && movements.isEmpty() && journeys.isEmpty() && points.isEmpty() && incidents.isEmpty() && routeStops.isEmpty() && sectorRevisions.isEmpty() && audit.isEmpty() && quickDeliveries.isEmpty()
}
