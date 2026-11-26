package com.rutacomercial.app.domain

import org.junit.Assert.*
import org.junit.Test

class WalkingGpsFilterTest {
    private fun fix(x: Double, seconds: Long, accuracy: Float = 2f, y: Double = 0.0) =
        GpsFix(y / 111195, x / 111195, accuracy, 1_000_000 + seconds * 1000, 1_000_000_000_000 + seconds * 1_000_000_000)
    private fun ready(filter: WalkingGpsFilter) {
        assertFalse(filter.evaluate(fix(0.0, 0)).accepted)
        assertFalse(filter.evaluate(fix(0.0, 3)).accepted)
        assertTrue(filter.evaluate(fix(0.0, 6)).accepted)
    }
    @Test fun indoorZigzagsDoNotProduceAWalkEvenWithTwoMetreReportedAccuracy() {
        val filter = WalkingGpsFilter(); ready(filter)
        listOf(3.0, -4.0, 6.0, -6.0, 2.0, 0.0, 5.0, -3.0).forEachIndexed { i, x ->
            assertFalse(filter.evaluate(fix(x, 10L + i * 5, y = if(i % 2 == 0) 2.0 else -2.0)).accepted)
        }
    }
    @Test fun IsolatedOutlierAndReturnDoNotBecomeDistance() {
        val filter = WalkingGpsFilter(); ready(filter)
        assertFalse(filter.evaluate(fix(25.0, 11)).accepted)
        assertFalse(filter.evaluate(fix(1.0, 16)).accepted)
        assertFalse(filter.evaluate(fix(0.0, 21)).accepted)
    }
    @Test fun actualWalkingIsConfirmedAndReturningIsNotSuppressed() {
        val filter = WalkingGpsFilter(); ready(filter)
        assertFalse(filter.evaluate(fix(9.0, 12)).accepted)
        assertTrue(filter.evaluate(fix(15.0, 17)).accepted)
        assertFalse(filter.evaluate(fix(5.0, 25)).accepted)
        assertTrue(filter.evaluate(fix(0.0, 30)).accepted)
    }
    @Test fun oppositeJumpsAreNotConfirmation() {
        val filter = WalkingGpsFilter(); ready(filter)
        assertFalse(filter.evaluate(fix(14.0, 11)).accepted)
        assertFalse(filter.evaluate(fix(-14.0, 16)).accepted)
        assertFalse(filter.evaluate(fix(14.0, 21)).accepted)
    }
    @Test fun isolatedPoorAccuracyDoesNotBreakWalkingContinuity() {
        val filter = WalkingGpsFilter(); ready(filter)
        val poor = filter.evaluate(fix(25.0, 11, 45f))
        assertFalse(poor.accepted); assertFalse(poor.breakSegment)
        assertFalse(filter.evaluate(fix(12.0, 16)).accepted)
        val walking = filter.evaluate(fix(20.0, 22))
        assertTrue(walking.accepted); assertFalse(walking.breakSegment)
    }
    @Test fun sustainedPoorAccuracyBreaksAndRequiresNewStabilization() {
        val filter = WalkingGpsFilter(); ready(filter)
        assertFalse(filter.evaluate(fix(50.0, 12, 45f)).breakSegment)
        assertTrue(filter.evaluate(fix(50.0, 40, 45f)).breakSegment)
        assertFalse(filter.evaluate(fix(30.0, 46)).accepted)
        assertFalse(filter.evaluate(fix(30.0, 52)).accepted)
        val stable = filter.evaluate(fix(30.0, 58))
        assertTrue(stable.accepted); assertTrue(stable.breakSegment)
    }
    @Test fun impossibleIsolatedJumpDoesNotPoisonNextGoodReading() {
        val filter = WalkingGpsFilter(); ready(filter)
        val jump = filter.evaluate(fix(300.0, 12))
        assertFalse(jump.accepted); assertFalse(jump.breakSegment)
        assertEquals(6_000_000_000L + 1_000_000_000_000L, filter.lastReliableFix!!.elapsedRealtimeNanos)
        assertFalse(filter.evaluate(fix(12.0, 18)).accepted)
        assertTrue(filter.evaluate(fix(20.0, 24)).accepted)
    }
    @Test fun aLongStopWithReliableSamplesIsNotSignalLoss() {
        val filter = WalkingGpsFilter(); ready(filter)
        for (seconds in 12L..126L step 6) assertFalse(filter.evaluate(fix(0.0, seconds)).accepted)
        assertFalse(filter.evaluate(fix(10.0, 132)).accepted)
        val walking = filter.evaluate(fix(18.0, 138))
        assertTrue(walking.accepted); assertFalse(walking.breakSegment)
        val persisted = GpsPolicy.evaluate(fix(0.0, 6), fix(18.0, 138), signalContinuous = !walking.breakSegment)
        assertTrue(persisted.accepted); assertFalse(persisted.breakSegment)
        assertEquals(18.0, persisted.distanceM, 0.1)
        assertTrue("Without live continuity, the repository must still separate a real gap", GpsPolicy.evaluate(fix(0.0, 6), fix(18.0, 138)).breakSegment)
    }
    @Test fun gapAndResumeRequireFreshFixes() {
        val filter = WalkingGpsFilter(); ready(filter)
        val gap = filter.evaluate(fix(100.0, 80))
        assertFalse(gap.accepted); assertTrue(gap.breakSegment)
        assertFalse(filter.evaluate(fix(100.0, 84)).accepted)
        assertTrue(filter.evaluate(fix(100.0, 88)).accepted)
        filter.reset()
        assertFalse(filter.evaluate(fix(400.0, 92)).accepted)
    }
    @Test fun duplicateFixCannotConfirmMovement() {
        val filter = WalkingGpsFilter(); ready(filter)
        assertFalse(filter.evaluate(fix(12.0, 12)).accepted)
        repeat(3) { assertFalse(filter.evaluate(fix(12.0, 12)).accepted) }
        assertTrue(filter.evaluate(fix(18.0, 18)).accepted)
    }
    @Test fun largeUncertaintyDoesNotCreateShortWalks() {
        val filter = WalkingGpsFilter()
        filter.evaluate(fix(0.0, 0, 20f)); filter.evaluate(fix(0.0, 3, 20f)); filter.evaluate(fix(0.0, 6, 20f))
        assertFalse(filter.evaluate(fix(15.0, 12, 20f)).accepted)
        assertFalse(filter.evaluate(fix(25.0, 17, 20f)).accepted)
    }
}
