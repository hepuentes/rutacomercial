package com.rutacomercial.app.tracking

import android.Manifest
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Criteria
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.rutacomercial.app.MainActivity
import com.rutacomercial.app.data.Repository
import com.google.android.gms.location.LocationServices
import com.google.android.gms.tasks.Tasks
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Android/emulated-provider tests, not a claim of physical GPS or screen-off battery validation. */
@RunWith(AndroidJUnit4::class)
class TrackingServiceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private val repository: Repository get() = (context as TrackingDependencies).repository
    private var scenario: ActivityScenario<MainActivity>? = null
    private var testProvider = false
    private var fusedMock = false
    private val fused get() = LocationServices.getFusedLocationProviderClient(context)

    @Before fun prepare() {
        shell("pm grant ${context.packageName} ${Manifest.permission.ACCESS_COARSE_LOCATION}")
        shell("pm grant ${context.packageName} ${Manifest.permission.ACCESS_FINE_LOCATION}")
        if (Build.VERSION.SDK_INT >= 33) shell("pm grant ${context.packageName} ${Manifest.permission.POST_NOTIFICATIONS}")
        scenario = ActivityScenario.launch(MainActivity::class.java)
        if (TrackingRuntime.state.value.serviceRunning || runBlocking { repository.currentJourney() != null }) scenario!!.onActivity { TrackingController.finish(it) }
        await("El servicio previo debe quedar detenido") { !TrackingRuntime.state.value.serviceRunning && runBlocking { repository.currentJourney() == null } }
    }

    @After fun cleanup() {
        if (TrackingRuntime.state.value.serviceRunning || runBlocking { repository.currentJourney() != null }) scenario?.onActivity { TrackingController.finish(it) }
        runCatching { await("Detener servicio al terminar") { !TrackingRuntime.state.value.serviceRunning } }
        if (fusedMock) runCatching { Tasks.await(fused.setMockMode(false), 10, TimeUnit.SECONDS) }
        if (testProvider) {
            runCatching { context.getSystemService(LocationManager::class.java).removeTestProvider(LocationManager.GPS_PROVIDER) }
            shell("appops set ${context.packageName} android:mock_location default")
        }
        scenario?.close()
    }

    @Suppress("DEPRECATION")
    @Test fun foregroundJourneyPersistsPausesResumesAndFinishesWithDistinctSegments() {
        val manager = context.getSystemService(LocationManager::class.java)
        shell("appops set ${context.packageName} android:mock_location allow")
        manager.addTestProvider(LocationManager.GPS_PROVIDER, false, false, false, false, true, true, true, Criteria.POWER_LOW, Criteria.ACCURACY_FINE)
        testProvider = true
        manager.setTestProviderEnabled(LocationManager.GPS_PROVIDER, true)
        Tasks.await(fused.setMockMode(true), 10, TimeUnit.SECONDS)
        fusedMock = true
        scenario!!.onActivity { TrackingController.start(it) }
        await("Inicio con servicio y jornada activa") { TrackingRuntime.state.value.isTracking && runBlocking { repository.currentJourney()?.state == "ACTIVE" } }
        await("El recorrido usa el proveedor combinado en este emulador con Google Play") { TrackingRuntime.state.value.provider.contains("combinada") }
        val journeyId = runBlocking { repository.currentJourney()!!.id }
        await("Notificación persistente durante el recorrido") { context.getSystemService(NotificationManager::class.java).activeNotifications.any { it.id == 2401 } }

        settle(manager, -34.6037, -58.3816)
        await("Primer GPS simulado persistido") { points(journeyId).isNotEmpty() }
        val first = points(journeyId).first()
        assertTrue("Android identifica el proveedor simulado", first.isMock)
        val beforeStill = points(journeyId).size
        for (latitude in listOf(-34.60368, -34.60372, -34.60369)) {
            SystemClock.sleep(6300); inject(manager, latitude, -58.3816)
        }
        SystemClock.sleep(800)
        assertEquals("La deriva al estar quieto no crea recorrido", beforeStill, points(journeyId).size)
        SystemClock.sleep(6300)
        inject(manager, -34.60359, -58.3816)
        SystemClock.sleep(6300)
        inject(manager, -34.60353, -58.3816)
        await("Segundo GPS de marcha persistido") { points(journeyId).size >= 2 }
        assertTrue(runBlocking { repository.currentJourney()!!.distanceM } > 0.0)

        scenario!!.onActivity { TrackingController.pause(it) }
        await("Pausa persistida y servicio detenido") { !TrackingRuntime.state.value.serviceRunning && runBlocking { repository.currentJourney()?.state == "PAUSED" } }
        val pausedCount = points(journeyId).size
        inject(manager, -34.60360, -58.3816)
        SystemClock.sleep(1200)
        assertEquals("No guardar ubicaciones mientras está pausado", pausedCount, points(journeyId).size)
        assertFalse(TrackingRuntime.state.value.isTracking)
        assertFalse(context.getSystemService(NotificationManager::class.java).activeNotifications.any { it.id == 2401 })

        scenario!!.onActivity { TrackingController.resume(it) }
        await("Reanudación explícita") { TrackingRuntime.state.value.isTracking && runBlocking { repository.currentJourney()?.state == "ACTIVE" } }
        await("Proveedor registrado después de reanudar") { TrackingRuntime.state.value.provider.contains("combinada") }
        settle(manager, -34.6025, -58.3805)
        await("GPS luego de reanudar") { points(journeyId).size > pausedCount }
        val resumed = points(journeyId).last()
        assertTrue("La pausa separa los segmentos", resumed.segment > first.segment)
        assertEquals("No sumar distancia entre pausa y reanudación", 0.0, resumed.distanceDeltaM, 0.0001)

        val segmentBeforeRapidCommands = runBlocking { repository.currentJourney()!!.nextSegment }
        scenario!!.onActivity { TrackingController.pause(it); TrackingController.resume(it) }
        await("Una pausa pendiente no debe detener una reanudación posterior") {
            TrackingRuntime.state.value.isTracking && runBlocking {
                repository.currentJourney()?.let { it.state == "ACTIVE" && it.nextSegment > segmentBeforeRapidCommands } == true
            }
        }

        scenario!!.onActivity { TrackingController.finish(it) }
        await("Finalización persistida y GPS detenido") { !TrackingRuntime.state.value.serviceRunning && runBlocking { repository.currentJourney() == null } }
        val saved = runBlocking { repository.snapshot().journeys.single { it.id == journeyId } }
        assertEquals("FINISHED", saved.state)
        assertNotNull(saved.endedAt)
        assertTrue(saved.activeMs > 0)
        assertTrue(saved.pausedMs > 0)
        assertFalse(context.getSystemService(NotificationManager::class.java).activeNotifications.any { it.id == 2401 })
        SystemClock.sleep(1000)
        assertFalse("No debe continuar el servicio después de finalizar", TrackingRuntime.state.value.serviceRunning)
    }

    @Test fun startStoresTheConfirmedTapBeforeMovement() {
        val at=System.currentTimeMillis()
        val point=com.rutacomercial.app.maps.MapCoordinate(-32.901,-68.84)
        scenario!!.onActivity { TrackingController.start(it,at,point) }
        await("Inicio guardado") { runBlocking { repository.currentJourney()!=null } }
        val data=runBlocking { repository.snapshot() }
        val mark=com.rutacomercial.app.ui.journeyMarks(data,data.activeJourney!!.id).single { it.label=="IR" }
        assertEquals(point,mark.point)
        assertEquals(at,mark.at)
    }

    @Test fun controllerRejectsDeniedPermissionWithoutStartingService() {
        // Revoking OS permissions kills instrumentation's target process. Exercise this public
        // controller boundary here; adb revoke/relaunch is a separate external verification.
        val denied = object : ContextWrapper(context) {
            var attemptedStart = false
            override fun checkPermission(permission: String, pid: Int, uid: Int): Int =
                if (permission == Manifest.permission.ACCESS_FINE_LOCATION) PackageManager.PERMISSION_DENIED else super.checkPermission(permission, pid, uid)
            override fun checkSelfPermission(permission: String): Int =
                if (permission == Manifest.permission.ACCESS_FINE_LOCATION) PackageManager.PERMISSION_DENIED else super.checkSelfPermission(permission)
            override fun startForegroundService(service: Intent): ComponentName? { attemptedStart = true; return null }
            override fun startService(service: Intent): ComponentName? { attemptedStart = true; return null }
        }
        scenario!!.onActivity { TrackingController.start(denied) }
        assertFalse(denied.attemptedStart)
        assertFalse(TrackingRuntime.state.value.isTracking)
        assertNull(runBlocking { repository.currentJourney() })
        assertTrue(TrackingRuntime.state.value.message.contains("ubicación precisa"))
    }

    private fun points(id: String) = runBlocking { repository.snapshot().points.filter { it.journeyId == id }.sortedBy { it.at } }
    private fun settle(manager: LocationManager, latitude: Double, longitude: Double) {
        // The first fix can be throttled by Play Services after the preceding subscription.
        // Supply a bounded extra sample; still require actual persistence and a separate segment.
        repeat(4) { index ->
            if (index > 0) SystemClock.sleep(6300)
            inject(manager, latitude, longitude)
        }
    }
    private fun inject(manager: LocationManager, latitude: Double, longitude: Double) {
        val location = Location(LocationManager.GPS_PROVIDER).apply {
            this.latitude = latitude; this.longitude = longitude; accuracy = 4f
            time = System.currentTimeMillis(); elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
        }
        if (fusedMock) Tasks.await(fused.setMockLocation(location), 10, TimeUnit.SECONDS)
        else manager.setTestProviderLocation(LocationManager.GPS_PROVIDER, location)
    }
    private fun await(message: String, timeout: Long = 10_000L, predicate: () -> Boolean) {
        val end = SystemClock.elapsedRealtime() + timeout
        while (SystemClock.elapsedRealtime() < end) {
            if (predicate()) return
            SystemClock.sleep(100)
        }
        assertTrue("$message; estado=${TrackingRuntime.state.value}", predicate())
    }
    private fun shell(command: String) {
        instrumentation.uiAutomation.executeShellCommand(command).use { descriptor ->
            android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { it.readBytes() }
        }
    }
}
