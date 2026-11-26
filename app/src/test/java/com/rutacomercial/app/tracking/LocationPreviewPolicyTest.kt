package com.rutacomercial.app.tracking

import com.rutacomercial.app.domain.GpsFix
import org.junit.Assert.*
import org.junit.Test

class LocationPreviewPolicyTest {
    private val fix = GpsFix(-32.89,-68.84,80f,100_000,100_000_000_000)
    @Test fun approximatePreviewAcceptsFreshFixButRejectsEitherStaleClockAndInvalidAccuracy() {
        assertTrue(LocationPreviewPolicy.usable(fix,110_000,110_000_000_000))
        assertFalse(LocationPreviewPolicy.usable(fix,140_000,110_000_000_000))
        assertFalse(LocationPreviewPolicy.usable(fix,110_000,140_000_000_000))
        assertFalse(LocationPreviewPolicy.usable(fix.copy(accuracyMeters=Float.NaN),110_000,110_000_000_000))
        assertFalse(LocationPreviewPolicy.usable(fix.copy(latitude=91.0),110_000,110_000_000_000))
        assertFalse(LocationPreviewPolicy.usable(fix,90_000,110_000_000_000))
    }
}
