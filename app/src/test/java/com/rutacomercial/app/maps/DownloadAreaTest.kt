package com.rutacomercial.app.maps

import org.junit.Assert.*
import org.junit.Test

class DownloadAreaTest {
    @Test fun boundsIncludeEveryCornerAndKeepSmallSectorDownloadable() {
        val points = listOf(MapCoordinate(-32.89,-68.84),MapCoordinate(-32.891,-68.836),MapCoordinate(-32.894,-68.837),MapCoordinate(-32.892,-68.84))
        val bounds = DownloadArea.from(points)!!
        assertEquals(-32.894, bounds.south, 0.000001)
        assertEquals(-68.836, bounds.east, 0.000001)
        assertTrue(bounds.downloadable)
        assertTrue(bounds.widthM in 370.0..375.0)
        assertTrue(bounds.heightM in 440.0..450.0)
    }
    @Test fun oversizedAndAntimeridianAreasAreNotDownloadedSilently() {
        assertFalse(DownloadArea(0.0,0.0,0.1,0.1).downloadable)
        assertFalse(DownloadArea(0.0,-179.99,0.001,179.99).downloadable)
        assertFalse(DownloadArea(85.0,0.0,85.001,0.001).downloadable)
    }
    @Test fun invalidOrDegenerateSelectionCannotBeDownloaded() {
        assertNull(DownloadArea.from(emptyList()))
        assertNull(DownloadArea.from(List(4) { MapCoordinate(Double.NaN,0.0) }))
        assertFalse(DownloadArea.from(List(4) { MapCoordinate(0.0,0.0) })!!.downloadable)
    }
}
