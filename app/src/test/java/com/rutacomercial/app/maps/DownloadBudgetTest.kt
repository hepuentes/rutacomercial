package com.rutacomercial.app.maps

import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.IOException

class DownloadBudgetTest {
    @Test fun requestsStopBelowProviderTileLimit() {
        val budget = DownloadBudget()
        repeat(2500) { budget.beforeRequest() }
        assertThrows(IOException::class.java) { budget.beforeRequest() }
    }

    @Test fun bytesStopBeforeFiftyMegabytes() {
        val budget = DownloadBudget()
        budget.consume(44_999_999)
        budget.consume(1)
        assertThrows(IOException::class.java) { budget.beforeRequest() }
        assertThrows(IOException::class.java) { budget.consume(1) }
    }
}
