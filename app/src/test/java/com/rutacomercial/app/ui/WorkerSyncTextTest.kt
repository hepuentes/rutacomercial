package com.rutacomercial.app.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class WorkerSyncTextTest {
    @Test fun plainWordsForWorker() {
        assertEquals("✓ Enviado al Owner", workerSyncText(delivered = true, finished = true))
        assertEquals("✓ El Owner ve tu avance", workerSyncText(delivered = true, finished = false))
        assertEquals("Guardado en el teléfono · se envía apenas haya señal", workerSyncText(delivered = false, finished = true))
    }

    @Test fun clockShowsSeconds() {
        assertEquals("0:00:52", clock(52_400))
        assertEquals("1:05:09", clock(3_909_000))
    }
}
