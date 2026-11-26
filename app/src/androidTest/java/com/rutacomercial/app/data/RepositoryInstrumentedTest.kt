package com.rutacomercial.app.data

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.rutacomercial.app.domain.*
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RepositoryInstrumentedTest {
    private lateinit var db:AppDatabase
    private lateinit var repo:Repository
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    @Before fun setup() { db=Room.inMemoryDatabaseBuilder(context,AppDatabase::class.java).build(); repo=Repository(db) }
    @After fun tearDown() { db.close() }
    private suspend fun business(name:String="Almacén",latitude:Double=-34.6,longitude:Double=-58.4)=repo.saveBusiness(Business(name=name,latitude=latitude,longitude=longitude))
    private suspend fun stockedCampaign(quantity:Int=100):Campaign { val c=repo.saveCampaign(Campaign(name="Campaña ${newId()}"),quantity); repo.moveStock(StockMovement(campaignId=c.id,type="ASSIGN",quantity=quantity)); return c }
    private suspend fun expectFailure(block:suspend ()->Unit) { try { block(); fail("Se esperaba un rechazo validado") } catch(_:IllegalArgumentException) {} }

    @Test fun journeyTransitionsArePersistentAndIdempotent()=runBlocking {
        val j=repo.startJourney(1_000_000)
        expectFailure { repo.startJourney(1_000_001) }
        val paused=repo.pauseJourney(j.id,1_010_000); assertEquals(10_000,paused.activeMs)
        assertEquals(paused,repo.pauseJourney(j.id,1_015_000))
        val resumed=repo.resumeJourney(j.id,1_030_000); assertEquals(20_000,resumed.pausedMs)
        val finished=repo.finishJourney(j.id,1_040_000); assertEquals(20_000,finished.activeMs); assertEquals(20_000,finished.pausedMs); assertEquals("FINISHED",finished.state)
        assertEquals(finished,repo.finishJourney(j.id,1_050_000)); assertNull(repo.currentJourney())
        expectFailure{repo.resumeJourney(j.id)}
    }
    @Test fun removingZoneKeepsHistoryAndAllowsReusingName()=runBlocking {
        val zone=repo.saveSector(Sector(name="Centro",polygonJson="[[1,1],[1,2],[2,1]]"))
        val journey=repo.startJourney(sectorId=zone.id)
        expectFailure { repo.removeZone(zone.id) }
        repo.finishJourney(journey.id)
        repo.removeZone(zone.id)
        repo.saveSector(Sector(name="Centro",polygonJson=zone.polygonJson))
        val snapshot=repo.snapshot()
        Validation.snapshot(snapshot)
        assertTrue(snapshot.audit.any { it.entityId==journey.id && it.detail.startsWith(zone.id) })
        assertTrue(snapshot.audit.any { it.entityId==zone.id && it.action=="REMOVE" })
    }
    @Test fun interruptionPausesAtLastHeartbeat()=runBlocking {
        val j=repo.startJourney(1_000_000); repo.heartbeat(j.id,1_020_000)
        val recovered=repo.recoverInterruptedJourney()!!
        assertEquals("PAUSED",recovered.state); assertEquals(20_000,recovered.activeMs); assertEquals(1,recovered.nextSegment)
        assertEquals(1,repo.snapshot().incidents.count{it.kind=="INTERRUPTION"})
        repo.recoverInterruptedJourney(); assertEquals(1,repo.snapshot().incidents.size)
    }
    @Test fun visitAndInventoryAreOneAtomicTransaction()=runBlocking {
        val b=business(); val c=stockedCampaign(5)
        expectFailure{repo.recordVisit(Visit(businessId=b.id,campaignId=c.id,status="CARD_DELIVERED",cards=6))}
        assertTrue(repo.snapshot().visits.isEmpty()); assertEquals("PENDING",repo.dao.business(b.id)!!.status); assertEquals(5,repo.stockBalance(c.id).carried)
        val v=Visit(businessId=b.id,campaignId=c.id,status="CARD_DELIVERED",cards=3)
        repo.recordVisit(v); repo.recordVisit(v)
        assertEquals(1,repo.snapshot().visits.size); assertEquals(2,repo.stockBalance(c.id).carried); assertEquals(3,repo.stockBalance(c.id).delivered)
    }
    @Test fun concurrentRetriesCreateOneMovement()=runBlocking {
        val c=repo.saveCampaign(Campaign(name="Reintentos"))
        val movement=StockMovement(campaignId=c.id,type="ENTRY",quantity=10)
        coroutineScope { (1..10).map{async(Dispatchers.IO){repo.moveStock(movement)}}.awaitAll() }
        assertEquals(1,repo.snapshot().movements.size); assertEquals(10,repo.stockBalance(c.id).depot)
        expectFailure{repo.moveStock(movement.copy(quantity=11))}
    }
    @Test fun stockCannotGoNegativeAndReturnConservesTotals()=runBlocking {
        val c=stockedCampaign(10)
        repo.moveStock(StockMovement(campaignId=c.id,type="DAMAGE",quantity=2))
        repo.moveStock(StockMovement(campaignId=c.id,type="RETURN",quantity=8))
        expectFailure{repo.moveStock(StockMovement(campaignId=c.id,type="RETURN",quantity=1))}
        assertEquals(StockBalance(8,0,0,2),repo.stockBalance(c.id))
    }
    @Test fun duplicateBusinessNeedsExplicitConfirmationAndMergePreservesVisits()=runBlocking {
        val first=business("Panadería Sur")
        val second=Business(name="Panaderia Sur",latitude=-34.60001,longitude=-58.4)
        assertEquals(first.id,repo.duplicateBusinesses(second).single().id)
        expectFailure{repo.saveBusiness(second)}
        repo.saveBusiness(second,allowDuplicate=true)
        val v=repo.recordVisit(Visit(businessId=second.id,status="VISITED_NO_DELIVERY"))
        repo.mergeBusinesses(second.id,first.id)
        assertEquals(first.id,repo.dao.visit(v.id)!!.businessId); assertEquals(1,repo.snapshot().visibleBusinesses.size)
        assertTrue(repo.snapshot().audit.any{it.action=="MERGE"})
    }
    @Test fun sectorEditsRetainAllBoundaryRevisions()=runBlocking {
        val p1=Geo.polygonJson(listOf(0.0 to 0.0,0.0 to 1.0,1.0 to 1.0))
        val p2=Geo.polygonJson(listOf(0.0 to 0.0,0.0 to 2.0,2.0 to 2.0))
        val s=repo.saveSector(Sector(name="Centro",polygonJson=p1))
        val b=repo.saveBusiness(Business(name="Negocio",latitude=0.2,longitude=0.4,sectorId=s.id))
        val v=repo.recordVisit(Visit(businessId=b.id,status="VISITED_NO_DELIVERY"))
        val updated=repo.saveSector(s.copy(polygonJson=p2))
        assertEquals(2,updated.revision); assertEquals(listOf(p1,p2),repo.snapshot().sectorRevisions.map{it.polygonJson}); assertEquals(s.id,repo.dao.visit(v.id)!!.sectorId)
        expectFailure{repo.saveSector(Sector(name="Céntro",polygonJson=p1))}
    }
    @Test fun repeatedMergesKeepCanonicalAliasesAndValidBackup()=runBlocking {
        val a=business("Primero"); val b=business("Segundo",latitude=-34.7); val c=business("Tercero",latitude=-34.8)
        repo.recordVisit(Visit(businessId=a.id,status="VISITED_NO_DELIVERY"))
        repo.mergeBusinesses(a.id,b.id); repo.mergeBusinesses(b.id,c.id)
        val state=repo.snapshot(); assertEquals(c.id,state.businesses.first{it.id==a.id}.mergedIntoId); assertEquals(c.id,state.businesses.first{it.id==b.id}.mergedIntoId)
        assertEquals(c.id,state.visits.single().businessId); Validation.snapshot(state)
    }
    @Test fun doNotVisitRemovesRouteStopWithoutDeletingHistory()=runBlocking {
        val a=business(); repo.addRouteStop(a.id)
        repo.recordVisit(Visit(businessId=a.id,status="DO_NOT_VISIT"))
        assertTrue(repo.snapshot().routeStops.isEmpty()); assertEquals(1,repo.snapshot().visits.size)
        expectFailure{repo.addRouteStop(a.id)}
        val b=business("Segundo",latitude=-34.7); repo.addRouteStop(b.id)
        repo.saveBusiness(b.copy(status="DO_NOT_VISIT")); assertTrue(repo.snapshot().routeStops.isEmpty()); Validation.snapshot(repo.snapshot())
    }
    @Test fun initialCampaignRetryDoesNotDuplicateStock()=runBlocking {
        val c=Campaign(name="Una sola carga")
        repo.saveCampaign(c,50); repo.saveCampaign(c,50)
        assertEquals(50,repo.stockBalance(c.id).depot); assertEquals(1,repo.snapshot().movements.size)
        expectFailure{repo.saveCampaign(c,51)}
    }
    @Test fun gpsGapsAndPausesCreateSeparateSegments()=runBlocking {
        val now=nowMillis(); val j=repo.startJourney(now-100_000)
        assertTrue(repo.recordLocation(GpsFix(0.0,0.0,5f,now-90_000,10_000_000_000)).accepted)
        assertTrue(repo.recordLocation(GpsFix(0.0,0.0001,5f,now-80_000,20_000_000_000)).accepted)
        assertTrue(repo.recordLocation(GpsFix(1.0,1.0,5f,now,100_000_000_000)).accepted)
        val first=repo.snapshot(); assertEquals(listOf(0,0,1),first.points.map{it.segment}); assertEquals(11.12,first.journeys.single().distanceM,0.1)
        repo.pauseJourney(j.id,now); assertFalse(repo.recordLocation(GpsFix(2.0,2.0,5f,now+1000,101_000_000_000)).accepted)
        repo.resumeJourney(j.id,now+1000); repo.recordLocation(GpsFix(2.0,2.0,5f,now+2000,102_000_000_000))
        assertEquals(listOf(0,0,1,2),repo.snapshot().points.map{it.segment}); assertEquals(11.12,repo.currentJourney()!!.distanceM,0.1)
    }
    @Test fun rejectedAccuracyBreaksNextSegment()=runBlocking {
        val now=nowMillis(); repo.startJourney(now-30_000)
        repo.recordLocation(GpsFix(0.0,0.0,5f,now-20_000,10_000_000_000))
        assertFalse(repo.recordLocation(GpsFix(0.0,0.1,100f,now-10_000,20_000_000_000)).accepted)
        repo.recordLocation(GpsFix(0.0,0.1,5f,now,30_000_000_000))
        assertEquals(listOf(0,1),repo.snapshot().points.map{it.segment}); assertEquals(0.0,repo.currentJourney()!!.distanceM,0.0)
    }
    @Test fun cachedFixesFromBeforeStartOrResumeAreNeverSaved()=runBlocking {
        val now=nowMillis(); val j=repo.startJourney(now-20_000)
        assertFalse(repo.recordLocation(GpsFix(0.0,0.0,5f,now-21_000,10_000_000_000)).accepted)
        assertTrue(repo.snapshot().points.isEmpty())
        assertTrue(repo.recordLocation(GpsFix(0.0,0.0,5f,now-10_000,20_000_000_000)).accepted)
        repo.pauseJourney(j.id,now); repo.resumeJourney(j.id,now+1000)
        assertFalse(repo.recordLocation(GpsFix(1.0,1.0,5f,now,30_000_000_000)).accepted)
        assertEquals(1,repo.snapshot().points.size)
        assertTrue(repo.recordLocation(GpsFix(1.0,1.0,5f,now+2000,32_000_000_000)).accepted)
        assertEquals(2,repo.snapshot().points.size); assertEquals(0.0,repo.currentJourney()!!.distanceM,0.0)
    }
    @Test fun restoreIsAtomicAndNeverOverwritesExistingData()=runBlocking {
        val b=business(); val c=stockedCampaign(10); repo.recordVisit(Visit(businessId=b.id,campaignId=c.id,status="CARD_DELIVERED",cards=4)); repo.startJourney()
        val source=repo.snapshot()
        val target=Room.inMemoryDatabaseBuilder(context,AppDatabase::class.java).build()
        try {
            val destination=Repository(target)
            val corrupted=source.copy(visits=source.visits.map{it.copy(cards=999)})
            expectFailure{destination.restoreIntoEmpty(corrupted)}; assertTrue(destination.snapshot().isEmpty)
            destination.restoreIntoEmpty(source)
            assertEquals(1,destination.snapshot().businesses.size); assertEquals(6,destination.stockBalance(c.id).carried); assertEquals("PAUSED",destination.currentJourney()!!.state)
            expectFailure{destination.restoreIntoEmpty(source)}; assertEquals(1,destination.snapshot().visits.size)
        } finally { target.close() }
    }
    @Test fun manualRouteOrderAndDuplicatesAreHandled()=runBlocking {
        val a=business("Uno"); val b=business("Dos",latitude=-34.7)
        repo.addRouteStop(a.id); repo.addRouteStop(a.id); repo.addRouteStop(b.id)
        assertEquals(listOf(a.id,b.id),repo.snapshot().routeStops.map{it.businessId})
        repo.setRoute(listOf(b.id,a.id)); assertEquals(listOf(b.id,a.id),repo.snapshot().routeStops.map{it.businessId})
        expectFailure{repo.setRoute(listOf(a.id,a.id))}; assertEquals(2,repo.snapshot().routeStops.size)
    }
    @Test fun fileDatabaseSurvivesCloseAndReopen()=runBlocking {
        val name="persistence-test-${newId()}.db"
        val first=Room.databaseBuilder(context,AppDatabase::class.java,name).build()
        val saved=try { Repository(first).saveBusiness(Business(name="Persistente",latitude=-34.0,longitude=-58.0)) } finally { first.close() }
        val second=Room.databaseBuilder(context,AppDatabase::class.java,name).build()
        try { assertEquals(saved,Repository(second).dao.business(saved.id)) } finally { second.close(); context.deleteDatabase(name) }
    }
    @Test fun retentionPreservesActiveTracksAndCommercialAggregates()=runBlocking {
        val now=nowMillis(); val old=repo.startJourney(now-50_000)
        repo.recordLocation(GpsFix(0.0,0.0,5f,now-40_000,10_000_000_000)); repo.recordLocation(GpsFix(0.0,0.0001,5f,now-30_000,20_000_000_000)); repo.finishJourney(old.id,now-20_000)
        val current=repo.startJourney(now-10_000); repo.recordLocation(GpsFix(1.0,1.0,5f,now,30_000_000_000))
        assertEquals(2,repo.deleteOldTracks(now+1)); val state=repo.snapshot()
        assertEquals(current.id,state.points.single().journeyId); assertEquals(11.12,state.journeys.first{it.id==old.id}.distanceM,0.1); assertEquals(2,state.journeys.size)
    }
}
