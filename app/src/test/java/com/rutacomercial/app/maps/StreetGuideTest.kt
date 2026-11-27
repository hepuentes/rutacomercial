package com.rutacomercial.app.maps

import org.junit.Assert.*
import org.junit.Test

class StreetGuideTest {
    private fun p(x:Int,y:Int)=MapCoordinate(y*.001,x*.001)
    private fun n(id:Long,x:Int,y:Int)=StreetNode(id,p(x,y))
    private val square=listOf(p(0,0),p(6,0),p(6,6),p(0,6))
    private fun directed(plan:StreetGuide)=plan.steps.filter { !it.connector }.flatMap { it.points.zipWithNext() }

    @Test fun walkedDirectionIsSkippedButTheOtherSidewalkRemains() {
        val a=n(1,1,1); val b=n(2,3,1); val c=n(3,5,1); val d=n(4,3,4)
        val streets=listOf(GuideStreet("Norte",listOf(a,b,c)), GuideStreet("Sur",listOf(b,d)))
        // The worker walked Norte eastbound from a to c, a few meters off the centerline.
        val walked=listOf((0..40).map { MapCoordinate(.001+.00005,.001+it*.0001) })
        val plan=planStreetGuide(streets,square,d.point,walked)
        val legs=directed(plan)
        assertEquals(0,legs.count { it==a.point to b.point || it==b.point to c.point })
        assertEquals(1,legs.count { it==c.point to b.point })
        assertEquals(1,legs.count { it==b.point to a.point })
        assertEquals(2,plan.doneLegs)
        assertEquals(6,plan.totalLegs)
        // The next pending block is reached along streets, never by a straight jump.
        assertTrue(plan.steps.zipWithNext().filter { (a,b)->a.section==b.section }.all { (a,b)->a.points.last()==b.points.first() })
        // Walking it back the other way finishes the street.
        val both=walked+listOf(walked.first().asReversed())
        assertEquals(4,planStreetGuide(streets,square,d.point,both).doneLegs)
    }
    @Test fun eachBlockIsWalkedInBothDirectionsStartingNearWorker() {
        val a=n(1,1,1); val b=n(2,3,1); val c=n(3,5,1); val d=n(4,3,4)
        val plan=planStreetGuide(listOf(GuideStreet("Norte",listOf(a,b,c)), GuideStreet("Sur",listOf(b,d))),square,d.point)
        assertEquals(d.point,plan.steps.first().points.first())
        val legs=directed(plan)
        for((u,v) in listOf(a to b,b to c,b to d)) {
            assertEquals(1,legs.count { it==u.point to v.point })
            assertEquals(1,legs.count { it==v.point to u.point })
        }
        assertEquals(6,legs.size)
        assertTrue(plan.steps.zipWithNext().all { (first,next)->first.points.last()==next.points.first() })
    }
    @Test fun concaveZoneSplitsOneStreetIntoTwoPartsWithoutBridgingOutside() {
        val u=listOf(p(0,0),p(6,0),p(6,6),p(4,6),p(4,2),p(2,2),p(2,6),p(0,6))
        val plan=planStreetGuide(listOf(GuideStreet("Calle",listOf(n(1,-1,4),n(2,7,4)))),u,p(0,4))
        assertEquals(2,plan.steps.last().section)
        assertEquals(4,directed(plan).size)
        assertTrue(directed(plan).all { (a,b)->kotlin.math.abs(a.longitude-b.longitude)<.00201 })
    }
    @Test fun overpassAndSurfaceRoadDoNotConnectWithoutSharedNodes() {
        val ways=listOf(GuideStreet("Puente",listOf(n(1,1,2),n(2,4,2))),
            GuideStreet("Calle",listOf(n(3,2,1),n(4,2,4))))
        assertEquals(2,planStreetGuide(ways,square,p(1,2)).steps.last().section)
    }
    @Test fun borderStreetsAreKeptAndDuplicateSegmentsAreWalkedOnlyTwice() {
        val street=GuideStreet("Borde",listOf(n(1,1,6),n(2,4,6)))
        assertEquals(2,directed(planStreetGuide(listOf(street,street),square,p(1,6))).size)
        assertTrue(planStreetGuide(emptyList(),square,p(1,6)).steps.isEmpty())
    }
}
