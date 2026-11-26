package com.rutacomercial.app.domain

import org.junit.Assert.*
import org.junit.Test

class WalkingObservationsTest {
    private fun fix(second:Int, meters:Double, error:Float=3f) = GpsFix(0.0,meters/111_320,error,1000L+second*1000,1_000_000_000L+second*1_000_000_000L)
    @Test fun repeatedStationarySamplesCountOnceAndGapRestartsDwell() {
        val monitor=WalkingObservations()
        val events=(0..60 step 6).mapNotNull { monitor.accept(fix(it,2.0)) }
        assertEquals(listOf("WALK_STOP"),events)
        assertNull(monitor.accept(fix(100,2.0)))
        assertEquals(listOf("WALK_STOP"),(106..142 step 6).mapNotNull { monitor.accept(fix(it,2.0)) })
    }
    @Test fun fastSeriesFlagsOnceWhileWalkingAndOneSpikeDoNot() {
        val monitor=WalkingObservations()
        assertEquals(listOf("FAST_INTERVAL"),(0..90 step 6).mapNotNull { monitor.accept(fix(it,it*8.0)) })
        monitor.reset()
        assertTrue((0..60 step 6).mapNotNull { monitor.accept(fix(it,if(it==30) 500.0 else it*1.3)) }.isEmpty())
    }
    @Test fun coverageCountsRepeatedPositionsOnlyOnce() {
        val zone=listOf(0.0 to 0.0,0.0 to .0009,.0009 to .0009,.0009 to 0.0)
        assertEquals(25,ZoneCoverage.percent(zone,List(10){.0002 to .0002}))
        assertEquals(0,ZoneCoverage.percent(zone,listOf(1.0 to 1.0)))
    }
}
