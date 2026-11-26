package com.rutacomercial.app.maps

import org.junit.Assert.*
import org.junit.Test

class WalkingDirectionsTest {
    private val start=MapCoordinate(-32.90,-68.85)
    private fun north(m:Int)=start.copy(latitude=start.latitude+m/111_320.0)
    @Test fun walkingIntoTurnAdvancesButWaitingOrPassingOnParallelRoadDoesNot() {
        val tracker=StepFollower(listOf(start,north(100)))
        assertFalse(tracker.sample(north(95),5f)!!.advance)
        assertFalse(tracker.sample(north(95),5f)!!.advance)
        tracker.sample(north(45),5f)
        assertFalse(tracker.sample(north(97).copy(longitude=-68.8495),5f)!!.advance)
        assertNull(tracker.sample(north(97),60f))
        assertTrue(tracker.sample(north(96),5f)!!.advance)
    }
    @Test fun entranceIsOnTheNearestEdgeAndTurnsRespectTravelDirection() {
        val right=north(100).copy(longitude=-68.847)
        val next=GuideStep("Transversal",listOf(north(100),right),1)
        val street=GuideStep("Principal",listOf(start,north(100)),1)
        assertEquals("↱",turnArrow(street,next))
        assertEquals("↶",turnArrow(street,street.copy(points=street.points.reversed())))
        val near=north(55).copy(longitude=-68.8501)
        val edge=zoneEntrance(listOf(start,north(100),right,start.copy(longitude=right.longitude)),near)!!
        assertTrue(metersBetween(north(55),edge)<0.2)
    }
}
