package com.rutacomercial.app.data

import android.content.Context
import androidx.room.*

@Database(entities=[Area::class,Sector::class,SectorRevision::class,Business::class,Campaign::class,Journey::class,Visit::class,StockMovement::class,TrackPoint::class,Incident::class,RouteStop::class,AuditEvent::class,QuickDelivery::class], version=2, exportSchema=true)
abstract class AppDatabase: RoomDatabase() {
    abstract fun dao(): AppDao
    companion object {
        val MIGRATION_1_2 = object : androidx.room.migration.Migration(1, 2) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("""CREATE TABLE IF NOT EXISTS quick_deliveries (id TEXT NOT NULL PRIMARY KEY, campaignId TEXT NOT NULL, at INTEGER NOT NULL, journeyId TEXT, sectorId TEXT, latitude REAL, longitude REAL, accuracyMeters REAL, fixAt INTEGER, businessId TEXT, undoneAt INTEGER, FOREIGN KEY(campaignId) REFERENCES campaigns(id) ON UPDATE NO ACTION ON DELETE NO ACTION, FOREIGN KEY(journeyId) REFERENCES journeys(id) ON UPDATE NO ACTION ON DELETE NO ACTION, FOREIGN KEY(sectorId) REFERENCES sectors(id) ON UPDATE NO ACTION ON DELETE NO ACTION, FOREIGN KEY(businessId) REFERENCES businesses(id) ON UPDATE NO ACTION ON DELETE NO ACTION)""")
                val palette = com.rutacomercial.app.domain.ZoneColors.palette
                db.query("SELECT id FROM sectors WHERE color='#087F75' ORDER BY id").use { cursor ->
                    var i = 0
                    while(cursor.moveToNext()) db.execSQL("UPDATE sectors SET color=? WHERE id=?", arrayOf(palette[i++ % palette.size], cursor.getString(0)))
                }
                for (column in listOf("campaignId", "journeyId", "sectorId", "businessId")) db.execSQL("CREATE INDEX IF NOT EXISTS index_quick_deliveries_$column ON quick_deliveries ($column)")
            }
        }
        fun create(context: Context): AppDatabase = Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, "rutacomercial.db")
            .addMigrations(MIGRATION_1_2).setJournalMode(JournalMode.WRITE_AHEAD_LOGGING).build()
    }
}

@Dao
abstract class AppDao {
    @Query("SELECT * FROM quick_deliveries ORDER BY at DESC") abstract suspend fun quickDeliveries(): List<QuickDelivery>
    @Query("SELECT * FROM quick_deliveries WHERE id=:id") abstract suspend fun quickDelivery(id: String): QuickDelivery?
    @Insert abstract suspend fun insertQuickDelivery(value: QuickDelivery)
    @Update abstract suspend fun updateQuickDelivery(value: QuickDelivery)
    @Query("SELECT * FROM areas ORDER BY name") abstract suspend fun areas(): List<Area>
    @Query("SELECT * FROM sectors ORDER BY name") abstract suspend fun sectors(): List<Sector>
    @Query("SELECT * FROM sector_revisions ORDER BY changedAt") abstract suspend fun sectorRevisions(): List<SectorRevision>
    @Query("SELECT * FROM businesses ORDER BY name") abstract suspend fun businesses(): List<Business>
    @Query("SELECT * FROM campaigns ORDER BY startsAt DESC") abstract suspend fun campaigns(): List<Campaign>
    @Query("SELECT * FROM visits ORDER BY at DESC") abstract suspend fun visits(): List<Visit>
    @Query("SELECT * FROM stock_movements ORDER BY at, rowid") abstract suspend fun movements(): List<StockMovement>
    @Query("SELECT * FROM journeys ORDER BY startedAt DESC") abstract suspend fun journeys(): List<Journey>
    @Query("SELECT * FROM track_points ORDER BY at, rowid") abstract suspend fun points(): List<TrackPoint>
    @Query("SELECT * FROM track_points WHERE journeyId=:journeyId ORDER BY at, rowid") abstract suspend fun journeyPoints(journeyId: String): List<TrackPoint>
    @Query("SELECT * FROM incidents ORDER BY at DESC") abstract suspend fun incidents(): List<Incident>
    @Query("SELECT * FROM route_stops ORDER BY position") abstract suspend fun routeStops(): List<RouteStop>
    @Query("SELECT * FROM audit ORDER BY at") abstract suspend fun audit(): List<AuditEvent>
    @Query("SELECT * FROM journeys WHERE state IN ('ACTIVE','PAUSED') LIMIT 1") abstract suspend fun currentJourney(): Journey?
    @Query("SELECT * FROM journeys WHERE id=:id") abstract suspend fun journey(id: String): Journey?
    @Query("SELECT * FROM track_points WHERE journeyId=:journeyId ORDER BY rowid DESC LIMIT 1") abstract suspend fun lastPoint(journeyId: String): TrackPoint?
    @Query("SELECT * FROM stock_movements WHERE campaignId=:campaignId ORDER BY at, rowid") abstract suspend fun movementsFor(campaignId: String): List<StockMovement>
    @Query("SELECT * FROM stock_movements WHERE operationId=:operationId") abstract suspend fun movementByOperation(operationId: String): StockMovement?
    @Query("SELECT * FROM businesses WHERE id=:id") abstract suspend fun business(id: String): Business?
    @Query("SELECT * FROM sectors WHERE id=:id") abstract suspend fun sector(id: String): Sector?
    @Query("SELECT * FROM campaigns WHERE id=:id") abstract suspend fun campaign(id: String): Campaign?
    @Query("SELECT * FROM visits WHERE id=:id") abstract suspend fun visit(id: String): Visit?
    @Upsert abstract suspend fun putArea(value: Area)
    @Upsert abstract suspend fun putSector(value: Sector)
    @Insert abstract suspend fun putSectorRevision(value: SectorRevision)
    @Upsert abstract suspend fun putBusiness(value: Business)
    @Upsert abstract suspend fun putCampaign(value: Campaign)
    @Upsert abstract suspend fun putJourney(value: Journey)
    @Insert abstract suspend fun insertVisit(value: Visit)
    @Update abstract suspend fun updateVisit(value: Visit)
    @Insert abstract suspend fun putMovement(value: StockMovement)
    @Insert abstract suspend fun putPoint(value: TrackPoint)
    @Insert abstract suspend fun putIncident(value: Incident)
    @Insert abstract suspend fun putRouteStop(value: RouteStop)
    @Insert abstract suspend fun putAudit(value: AuditEvent)
    @Query("DELETE FROM route_stops") abstract suspend fun clearRoute()
    @Query("DELETE FROM track_points WHERE journeyId=:journeyId AND at >= :since") abstract suspend fun deletePointsSince(journeyId: String, since: Long): Int
    @Query("DELETE FROM track_points WHERE at < :cutoff AND journeyId IN (SELECT id FROM journeys WHERE state='FINISHED')") abstract suspend fun deleteOldTracks(cutoff: Long): Int
    @Transaction open suspend fun snapshot(): DataSnapshot = DataSnapshot(areas(), sectors(), sectorRevisions(), businesses(), campaigns(), visits(), movements(), journeys(), points(), incidents(), routeStops(), audit(), quickDeliveries())
}
