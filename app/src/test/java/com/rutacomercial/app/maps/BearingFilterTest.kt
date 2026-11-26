package com.rutacomercial.app.maps

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*

class BearingFilterTest {
    @Test fun headingStaysAlignedFromFlatToUpright() {
        for (bearing in listOf(-90,0,45,90,179)) for(tilt in listOf(0,30,60,85,90)) {
            val a=Math.toRadians(bearing.toDouble())
            val t=Math.toRadians(tilt.toDouble())
            val matrix=doubleArrayOf(cos(a),sin(a)*cos(t),-sin(a)*sin(t),
                -sin(a),cos(a)*cos(t),-cos(a)*sin(t),0.0,sin(t),cos(t)).map { it.toFloat() }.toFloatArray()
            assertEquals(bearing.toFloat(),forwardAzimuth(matrix)!!,.01f)
        }
    }
    @Test fun uprightPhoneStillPointsForwardWhenRolledSideways() {
        val rolled=floatArrayOf(0f,1f,0f, 0f,0f,-1f, -1f,0f,0f)
        assertEquals(0f,forwardAzimuth(rolled)!!,.01f)
    }
    @Test fun smoothsAcrossNorthAndResetsAfterSleep() {
        val filter=BearingFilter()
        filter.update(358f,1_000_000_000)
        val angle=filter.update(2f,1_150_000_000)
        assertTrue(angle>358f || angle<2f)
        assertEquals(90f,filter.update(90f,4_000_000_000),.001f)
    }
}
