package com.rutacomercial.app.ui

import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.room.Room
import com.rutacomercial.app.data.*
import com.rutacomercial.app.maps.MapCoordinate
import com.rutacomercial.app.tracking.TrackingStatus
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class MapFollowTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun openingDoesNotRequestGpsAndJourneyEnablesFollowUntilUserExplores() {
        val db = Room.inMemoryDatabaseBuilder(compose.activity, AppDatabase::class.java).build()
        val vm = RutaViewModel(Repository(db))
        val tracking = mutableStateOf(TrackingStatus())
        var requests = 0
        try {
            compose.setContent { RutaTheme {
                MapScreen(vm, DataSnapshot(), tracking.value, null, null, {}, {}, {}, {}, {}, {}, { requests++ }, {})
            } }
            compose.waitForIdle()
            assertEquals(0, requests)
            compose.onNodeWithContentDescription("Volver a centrar en mi ubicación").performClick()
            assertEquals(1, requests)
            compose.runOnIdle { tracking.value = TrackingStatus(isTracking = true, position = MapCoordinate(-32.89,-68.84), fixAt = System.currentTimeMillis()) }
            compose.waitUntil(5000) { compose.onAllNodesWithContentDescription("Siguiendo mi ubicación").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithContentDescription("Mapa del territorio").performTouchInput { click(center) }
            compose.onNodeWithContentDescription("Volver a centrar en mi ubicación").assertExists().performClick()
            compose.waitUntil(5000) { compose.onAllNodesWithContentDescription("Siguiendo mi ubicación").fetchSemanticsNodes().isNotEmpty() }
        } finally {
            compose.runOnUiThread { compose.activity.setContentView(android.widget.FrameLayout(compose.activity)) }
            db.close()
        }
    }
}
