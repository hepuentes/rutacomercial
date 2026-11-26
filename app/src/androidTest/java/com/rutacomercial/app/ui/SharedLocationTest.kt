package com.rutacomercial.app.ui

import android.content.Context
import android.content.Intent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rutacomercial.app.MainActivity
import com.rutacomercial.app.domain.Geo
import com.rutacomercial.app.maps.DownloadArea
import com.rutacomercial.app.maps.MapCoordinate
import com.rutacomercial.app.tracking.TrackingDependencies
import com.rutacomercial.app.tracking.TrackingRuntime
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SharedLocationTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun receivedShareRequiresReviewThenSavesZoneAroundPinWithoutStartingGps() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val repository = (context as TrackingDependencies).repository
        val before = runBlocking { repository.snapshot() }
        compose.runOnUiThread {
            compose.activity.startActivity(Intent(compose.activity, MainActivity::class.java).apply {
                action = Intent.ACTION_SEND; type = "text/plain"; addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                putExtra(Intent.EXTRA_TEXT, "Pin Mendoza\nhttps://www.google.com/maps?q=-32.89,-68.84")
            })
        }
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Delimitar zona aquí").fetchSemanticsNodes().isNotEmpty() }
        assertEquals(before.sectors.size, runBlocking { repository.snapshot().sectors.size })
        assertEquals(before.points.size, runBlocking { repository.snapshot().points.size })
        compose.onNodeWithText("Delimitar zona aquí").performClick()
        compose.waitUntil(20_000) { compose.onAllNodes(hasText("Usar esta zona") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Usar esta zona").performClick()
        compose.waitUntil(10_000) { runBlocking { repository.snapshot().sectors.size == before.sectors.size + 1 } }
        val after = runBlocking { repository.snapshot() }
        val added = after.sectors.single { s -> before.sectors.none { it.id == s.id } }
        val bounds = DownloadArea.from(Geo.parsePolygon(added.polygonJson).map { MapCoordinate(it.first,it.second) })!!
        assertTrue(bounds.downloadable)
        assertTrue(Geo.distanceM(-32.89, -68.84, bounds.center.latitude, bounds.center.longitude) < 100)
        assertEquals(before.journeys.size, after.journeys.size)
        assertEquals(before.points.size, after.points.size)
        assertFalse(TrackingRuntime.state.value.isTracking)
    }

    @Test fun unsupportedShareOffersPinHelpAndDoesNotCreateRecords() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val repository = (context as TrackingDependencies).repository
        val before = runBlocking { repository.snapshot() }
        compose.runOnUiThread {
            compose.activity.startActivity(Intent(compose.activity, MainActivity::class.java).apply {
                action = Intent.ACTION_SEND; type = "text/plain"; addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                putExtra(Intent.EXTRA_TEXT, "https://www.google.com/maps?q=Mendoza")
            })
        }
        compose.waitUntil(10_000) { compose.onAllNodesWithText("No se pudo obtener un punto", substring = true).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Cancelar").performClick()
        assertEquals(before, runBlocking { repository.snapshot() })
    }
}
