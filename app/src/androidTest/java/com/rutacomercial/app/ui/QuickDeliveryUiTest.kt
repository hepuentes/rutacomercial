package com.rutacomercial.app.ui

import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.room.Room
import com.rutacomercial.app.data.*
import com.rutacomercial.app.tracking.TrackingStatus
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class QuickDeliveryUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @Test fun oneTapWithoutGpsAndUndoUpdateStockWithoutBusinessForm() {
        val db = Room.inMemoryDatabaseBuilder(compose.activity,AppDatabase::class.java).build()
        val repo = Repository(db)
        val campaign = runBlocking { repo.prepareQuickCampaign("Folletos", 5) }
        val vm = RutaViewModel(repo)
        try {
            compose.setContent {
                val data by vm.data.collectAsState()
                RutaTheme { androidx.compose.foundation.layout.Column { QuickDeliveryPanel(vm,data,TrackingStatus(),null) } }
            }
            compose.waitUntil(10_000) { compose.onAllNodesWithText("Entregué 1 tarjeta · 0 hoy").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Entregué 1 tarjeta · 0 hoy").performClick()
            compose.waitUntil(10_000) { runBlocking { repo.snapshot().activeQuickDeliveries.size == 1 } }
            compose.waitUntil(10_000) {
                compose.onAllNodes(hasText("Deshacer") and isEnabled(), useUnmergedTree=true).fetchSemanticsNodes().isNotEmpty() && !vm.busy.value
            }
            compose.onNodeWithText("Deshacer", useUnmergedTree=true).performClick()
            compose.waitUntil(10_000) { runBlocking { repo.snapshot().activeQuickDeliveries.isEmpty() } }
            val snapshot = runBlocking { repo.snapshot() }
            assertNull(snapshot.quickDeliveries.single().latitude)
            assertTrue(snapshot.businesses.isEmpty())
            assertEquals(5L,runBlocking { repo.stockBalance(campaign.id).carried })
        } finally {
            compose.runOnUiThread { compose.activity.setContentView(android.widget.FrameLayout(compose.activity)) }
            db.close()
        }
    }
}
