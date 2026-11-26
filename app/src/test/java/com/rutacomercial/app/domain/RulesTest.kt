package com.rutacomercial.app.domain

import com.rutacomercial.app.data.*
import org.junit.Assert.*
import org.junit.Test

class RulesTest {
    private fun movement(type:String,quantity:Int,operation:String=newId())=StockMovement(campaignId=newId(),type=type,quantity=quantity,operationId=operation)
    private fun fix(lat:Double=0.0,lon:Double=0.0,accuracy:Float=5f,time:Long=1_000_000,elapsed:Long=time*1_000_000,isMock:Boolean=false)=GpsFix(lat,lon,accuracy,time,elapsed,isMock)
    @Test fun inventoryConservesCardsAcrossAllMovements() {
        val result=Inventory.balance(listOf(movement("INITIAL",100),movement("ENTRY",20),movement("ASSIGN",50),movement("DELIVERY",10),movement("DAMAGE",5),movement("RETURN",20)))
        assertEquals(StockBalance(90,15,10,5),result)
        assertEquals(120,result.depot+result.carried+result.delivered+result.damaged)
    }
    @Test(expected=IllegalArgumentException::class) fun assignmentCannotOverdrawDepot() { Inventory.balance(listOf(movement("INITIAL",3),movement("ASSIGN",4))) }
    @Test(expected=IllegalArgumentException::class) fun deliveryCannotOverdrawCarried() { Inventory.balance(listOf(movement("INITIAL",10),movement("DELIVERY",1))) }
    @Test(expected=IllegalArgumentException::class) fun duplicateOperationsAreRejected() { Inventory.balance(listOf(movement("ENTRY",3,"same"),movement("ENTRY",3,"same"))) }
    @Test(expected=IllegalArgumentException::class) fun negativeQuantityRejected() { Inventory.balance(listOf(movement("ENTRY",-5))) }
    @Test fun gpsInitialFixHasZeroDistance() { val decision=GpsPolicy.evaluate(null,fix()); assertTrue(decision.accepted); assertTrue(decision.breakSegment); assertEquals(0.0,decision.distanceM,0.0) }
    @Test fun walkingMovementIsCounted() { val decision=GpsPolicy.evaluate(fix(),fix(lon=0.0001,time=1_010_000)); assertTrue(decision.accepted); assertFalse(decision.breakSegment); assertEquals(11.12,decision.distanceM,0.1) }
    @Test fun gapNeverInventsConnectingDistance() { val decision=GpsPolicy.evaluate(fix(),fix(lon=0.2,time=1_120_000)); assertTrue(decision.accepted); assertTrue(decision.breakSegment); assertEquals(0.0,decision.distanceM,0.0) }
    @Test fun impossibleJumpIsRejectedAndBreaksSegment() { val decision=GpsPolicy.evaluate(fix(),fix(lon=0.1,time=1_010_000)); assertFalse(decision.accepted); assertTrue(decision.breakSegment) }
    @Test fun inaccurateAndInvalidCoordinatesAreRejected() {
        assertFalse(GpsPolicy.evaluate(null,fix(accuracy=80f)).accepted)
        assertFalse(GpsPolicy.evaluate(null,fix(lat=91.0)).accepted)
        assertFalse(GpsPolicy.evaluate(null,fix(lon=Double.NaN)).accepted)
        assertFalse(GpsPolicy.evaluate(null,fix(accuracy=Float.NaN)).accepted)
    }
    @Test fun staleAndDuplicateFixesAreRejected() { assertFalse(GpsPolicy.evaluate(fix(),fix()).accepted); assertFalse(GpsPolicy.evaluate(fix(),fix(time=999_000)).accepted) }
    @Test fun clockChangeStartsNewSegment() { val decision=GpsPolicy.evaluate(fix(),fix(lon=0.01,time=1_040_000,elapsed=1_010_000_000_000)); assertTrue(decision.accepted); assertTrue(decision.breakSegment); assertEquals(0.0,decision.distanceM,0.0) }
    @Test fun stationaryJitterDoesNotIncreaseDistance() { assertFalse(GpsPolicy.evaluate(fix(),fix(lon=0.000001,time=1_010_000)).accepted) }
    @Test fun mockLocationIsLabelledWithoutClaimingFraud() { val decision=GpsPolicy.evaluate(null,fix(isMock=true)); assertTrue(decision.accepted); assertTrue(decision.reason.contains("simulada")); assertFalse(decision.reason.contains("fraude")) }
    @Test fun namesNormalizeAccentsAndSpacing() { assertEquals("panaderia san jose",Names.normalize(" Panadería  San José ")); assertTrue(Names.similarity("Kiosco Pepe","Kiosko Pepe")>0.8) }
    @Test fun polygonRoundTripAndContainment() { val p=listOf(0.0 to 0.0,0.0 to 1.0,1.0 to 1.0,1.0 to 0.0); assertEquals(p,Geo.parsePolygon(Geo.polygonJson(p))); assertTrue(Geo.contains(p,0.5,0.5)); assertFalse(Geo.contains(p,2.0,2.0)); assertTrue(Geo.validPolygon(p)) }
    @Test fun selfCrossingAndDegeneratePolygonsRejected() { assertFalse(Geo.validPolygon(listOf(0.0 to 0.0,1.0 to 1.0,0.0 to 1.0,1.0 to 0.0))); assertFalse(Geo.validPolygon(listOf(0.0 to 0.0,1.0 to 1.0,2.0 to 2.0))) }
    @Test fun durationTracksOnlyCurrentState() { val j=Journey(startedAt=1000,activeMs=2000,pausedMs=3000,intervalStartedAt=5000,lastAliveAt=5000); assertEquals(4000,j.activeDuration(7000)); assertEquals(3000,j.pauseDuration(7000)); assertEquals(2000,j.copy(state="PAUSED").activeDuration(7000)); assertEquals(5000,j.copy(state="PAUSED").pauseDuration(7000)) }
    @Test(expected=IllegalArgumentException::class) fun evidencePathCannotEscapeStorage() { Validation.business(Business(name="Tienda",latitude=0.0,longitude=0.0,photoUri="../secret.jpg")) }
    @Test(expected=IllegalArgumentException::class) fun deliveredStatusRequiresPositiveQuantity() { Validation.visit(Visit(businessId=newId(),status="CARD_DELIVERED",cards=0)) }
    @Test(expected=IllegalArgumentException::class) fun malformedRestoreUuidRejected() { Validation.snapshot(DataSnapshot(areas=listOf(Area(id="malformed",name="Ciudad")))) }
    @Test(expected=IllegalArgumentException::class) fun restoreMissingReferenceRejected() { Validation.snapshot(DataSnapshot(businesses=listOf(Business(name="Tienda",normalizedName="tienda",latitude=0.0,longitude=0.0,sectorId=newId())))) }
    @Test(expected=IllegalArgumentException::class) fun restoreRejectsCircularAreaHierarchy() { val a=newId(); val b=newId(); Validation.snapshot(DataSnapshot(areas=listOf(Area(a,"A",parentId=b),Area(b,"B",parentId=a)))) }
}
