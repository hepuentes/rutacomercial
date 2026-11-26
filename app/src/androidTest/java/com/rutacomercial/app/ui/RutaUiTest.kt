package com.rutacomercial.app.ui

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rutacomercial.app.data.AppDatabase
import com.rutacomercial.app.data.Repository
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** UI integration fixtures live only in an in-memory test database; never shipped as demo data. */
@RunWith(AndroidJUnit4::class)
class RutaUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private lateinit var database: AppDatabase
    private lateinit var repository: Repository
    private var backupRequested: String? = null

    @Before fun prepare() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        repository = Repository(database)
        compose.setContent { RutaApp(repository, onExport = { _, _ -> }, onBackup = { backupRequested = it }, onRestore = {}) }
        compose.waitForIdle()
    }

    @After fun close() {
        compose.runOnUiThread { compose.activity.setContentView(android.widget.FrameLayout(compose.activity)) }
        database.close()
    }

    @Test fun emptyScreensAndBackupPasswordValidation() {
        compose.onNodeWithText("Empezar recorrido").assertIsDisplayed()
        compose.onNodeWithText("Negocios", useUnmergedTree = true).performClick()
        compose.onNodeWithText("Sin resultados").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Más", useUnmergedTree = true).performClick()
        compose.onNodeWithText("Configuración").performScrollTo().performClick()
        compose.onNodeWithText("Crear respaldo cifrado").performScrollTo().performClick()
        compose.onNodeWithText("Elegir destino").assertIsNotEnabled()
        compose.onNodeWithText("Contraseña (mínimo 12 caracteres)").performTextInput("frase de prueba local")
        compose.onNodeWithText("Repetir contraseña").performTextInput("frase de prueba local")
        compose.onNodeWithText("Elegir destino").assertIsEnabled().performClick()
        assertEquals("frase de prueba local", backupRequested)
        assertTrue(runBlocking { repository.snapshot().isEmpty })
    }

    @Test fun mapStartsWithoutJourneyAndSavesRectangleWithoutAdministrativeSetup() {
        compose.onNodeWithText("Empezar recorrido").assertIsDisplayed()
        assertTrue(runBlocking { repository.snapshot().isEmpty })
        compose.onNodeWithText("Elegir zona").performClick()
        compose.onNodeWithContentDescription("Esquina 1 de la zona").assertExists()
        compose.waitUntil(15_000) { compose.onAllNodes(hasText("Usar esta zona") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Usar esta zona").assertIsEnabled().performClick()
        compose.waitUntil(10_000) { runBlocking { repository.snapshot().sectors.size == 1 } }
        val snapshot = runBlocking { repository.snapshot() }
        assertEquals("Zona 1", snapshot.sectors.single().name)
        assertNull(snapshot.sectors.single().areaId)
        assertTrue(snapshot.journeys.isEmpty())
        assertTrue(snapshot.points.isEmpty())
        assertEquals(4, com.rutacomercial.app.domain.Geo.parsePolygon(snapshot.sectors.single().polygonJson).size)
        val bounds = com.rutacomercial.app.maps.DownloadArea.from(com.rutacomercial.app.domain.Geo.parsePolygon(snapshot.sectors.single().polygonJson).map { com.rutacomercial.app.maps.MapCoordinate(it.first,it.second) })!!
        assertTrue("La zona inicial debe abarcar pocas cuadras y permitir descarga", bounds.downloadable)
        compose.onNodeWithText("Opciones").performClick()
        compose.onNodeWithText("Guardar mapa sin internet").performClick()
        compose.onNodeWithText("Mapas sin conexión").assertIsDisplayed()
        assertTrue(runBlocking { repository.snapshot().journeys.isEmpty() })
    }

    @Test fun saveHandDrawnZoneWithoutStartingJourney() {
        compose.onNodeWithText("Elegir zona").performClick()
        compose.onNodeWithText("Trazar a mano").performClick()
        val canvas=compose.onNodeWithContentDescription("Dibujar contorno de zona")
        canvas.performTouchInput {
            fun pt(x:Float,y:Float)=androidx.compose.ui.geometry.Offset(width*x,height*y)
            down(pt(.25f,.35f)); moveTo(pt(.40f,.32f)); moveTo(pt(.70f,.36f))
            moveTo(pt(.65f,.58f)); moveTo(pt(.46f,.51f)); moveTo(pt(.34f,.64f)); up()
        }
        compose.onNodeWithText("Usar esta zona").assertIsEnabled().performClick()
        compose.waitUntil(10_000) { runBlocking { repository.snapshot().sectors.size == 1 } }
        val data=runBlocking { repository.snapshot() }
        assertTrue(com.rutacomercial.app.domain.Geo.parsePolygon(data.sectors.single().polygonJson).size>=3)
        assertTrue(data.journeys.isEmpty())
    }

    @Test fun commercialFlowCreatesCampaignAssignsAndRecordsDeliveryWithoutGps() {
        compose.onNodeWithText("Más", useUnmergedTree = true).performClick()
        compose.onNodeWithText("Campañas").performScrollTo().performClick()
        compose.onNodeWithText("Crear campaña").performClick()
        compose.onNodeWithText("Nombre de campaña").performTextInput("Campaña de prueba UI")
        compose.onNodeWithText("Tarjetas iniciales en depósito").performScrollTo().performTextReplacement("100")
        compose.onNodeWithText("Guardar").performClick()
        compose.waitUntil(10_000) { runBlocking { repository.snapshot().campaigns.size == 1 } }
        compose.onNodeWithText("Registrar movimiento").performScrollTo().performClick()
        compose.onNodeWithText("Cantidad").performTextInput("40")
        compose.onNodeWithText("Registrar", substring = false).performClick()
        compose.waitUntil(10_000) { runBlocking { repository.snapshot().movements.size == 2 } }

        compose.onNodeWithText("Negocios", useUnmergedTree = true).performClick()
        compose.onNodeWithText("Ingresar ubicación").performScrollTo().performClick()
        compose.onNodeWithText("Nombre comercial").performTextInput("Kiosco prueba UI")
        compose.onNodeWithText("Categoría").performTextInput("Kiosco")
        // The preceding service tests may leave a last known position, which the real UI prefills.
        compose.onNodeWithText("Latitud", substring = false).performScrollTo().performTextReplacement("-34.6037")
        compose.onNodeWithText("Longitud", substring = false).performScrollTo().performTextReplacement("-58.3816")
        compose.onNodeWithText("Guardar").performClick()
        compose.waitUntil(10_000) { runBlocking { repository.snapshot().businesses.size == 1 } }
        compose.onNodeWithText("Registrar visita o entrega").performScrollTo().performClick()
        compose.onNodeWithText("Tarjetas entregadas").performScrollTo().performTextReplacement("3")
        compose.onNodeWithText("Guardar visita").performClick()
        compose.waitUntil(10_000) { runBlocking { repository.snapshot().visits.size == 1 } }

        val snapshot = runBlocking { repository.snapshot() }
        val balance = runBlocking { repository.stockBalance(snapshot.campaigns.single().id) }
        assertEquals(60L, balance.depot)
        assertEquals(37L, balance.carried)
        assertEquals(3L, balance.delivered)
        assertNull(snapshot.visits.single().journeyId)
        assertEquals("DECLARED", snapshot.visits.single().reviewState)
        assertTrue(snapshot.journeys.isEmpty())
        assertTrue(snapshot.points.isEmpty())
    }
}
