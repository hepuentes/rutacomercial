package com.rutacomercial.app.maps

import android.graphics.RectF
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.offline.OfflineManager
import org.maplibre.android.offline.OfflineRegion
import org.maplibre.android.offline.OfflineRegionStatus
import java.io.FileInputStream
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * REAL network integration, opt in with `-e offlineMapIntegration true` and this class.
 * Runs only on an emulator: downloads one authorized 1 km x 1 km OpenFreeMap region using
 * the production limits, disconnects the emulator and renders actual cached vector features.
 * This is not a physical-device/battery test and does not pretend to restart the app process.
 */
@LargeTest
@RunWith(AndroidJUnit4::class)
class OfflineMapInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private fun onMain(action: () -> Unit) = instrumentation.runOnMainSync { action() }
    private fun shell(command: String): String = instrumentation.uiAutomation.executeShellCommand(command).use { descriptor ->
        FileInputStream(descriptor.fileDescriptor).bufferedReader().use { it.readText().trim() }
    }
    private fun internetAvailable(): Boolean {
        val manager = context.getSystemService(ConnectivityManager::class.java)
        return manager.getNetworkCapabilities(manager.activeNetwork)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
    }
    private fun awaitCondition(timeoutMs: Long, message: () -> String, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (!condition() && SystemClock.elapsedRealtime() < deadline) Thread.sleep(200)
        assertTrue(message(), condition())
    }

    @Test(timeout = 200_000)
    fun authorizedRegionDownloadsPersistsAndRendersWithEmulatorNetworkDisabled() {
        assumeTrue("Opt-in network test: pass -e offlineMapIntegration true", InstrumentationRegistry.getArguments().getString("offlineMapIntegration") == "true")
        assumeTrue("This test changes networking only on an Android emulator", Build.HARDWARE in setOf("ranchu", "goldfish") || Build.FINGERPRINT.startsWith("generic"))
        assertTrue("The emulator needs internet before the authorized map download", internetAvailable())

        val name = "Prueba offline ${UUID.randomUUID()}"
        val center = MapCoordinate(-34.6037, -58.3816)
        val initialWifi = shell("settings get global wifi_on")
        val initialData = shell("settings get global mobile_data")
        var changedNetwork = false
        var controller: OfflineRegions? = null
        var regionId: Long? = null
        var scenario: ActivityScenario<ComponentActivity>? = null
        var mapView: MapView? = null
        var map: MapLibreMap? = null
        val styleLoaded = AtomicBoolean(false)
        val fullyRendered = AtomicBoolean(false)
        val featureCount = AtomicInteger(0)
        val mapFailure = AtomicReference<String?>(null)
        try {
            onMain {
                controller = OfflineRegions.get(context)
                check(!controller!!.busy.value) { "Another offline download is active; test will not interrupt it" }
                controller!!.download(listOf(
                    MapCoordinate(center.latitude - 0.003, center.longitude - 0.003),
                    MapCoordinate(center.latitude - 0.003, center.longitude + 0.003),
                    MapCoordinate(center.latitude + 0.003, center.longitude + 0.003),
                    MapCoordinate(center.latitude + 0.003, center.longitude - 0.003)
                ), name)
            }
            val regions = requireNotNull(controller)
            awaitCondition(120_000, {
                "OpenFreeMap region did not finish within 120 s: ${regions.message.value}; ${regions.regions.value.firstOrNull { it.name == name }}"
            }) {
                val item = regions.regions.value.firstOrNull { it.name == name }
                if (item != null) regionId = item.id
                item?.complete == true
            }
            val downloaded = regions.regions.value.single { it.name == name }
            regionId = downloaded.id
            assertTrue("Real offline region must contain downloaded tiles", downloaded.tiles > 0)
            assertTrue("Downloaded resources must have a nonzero size", downloaded.bytes > 0)
            assertTrue("Region exceeded provider tile limit", downloaded.tiles < 3000)
            assertTrue("Region exceeded provider byte limit", downloaded.bytes < 50_000_000L)
            Log.i("RutaOfflineTest", "Real region complete: id=${downloaded.id}, tiles=${downloaded.tiles}, bytes=${downloaded.bytes}")

            // Ask the native OfflineManager to enumerate stored regions again, independently of
            // the facade's StateFlow, and verify the persisted region's native complete status.
            val persisted = AtomicBoolean(false)
            val persistedFailure = AtomicReference<String?>(null)
            val checked = CountDownLatch(1)
            onMain {
                OfflineManager.getInstance(context).listOfflineRegions(object : OfflineManager.ListOfflineRegionsCallback {
                    override fun onList(offlineRegions: Array<OfflineRegion>?) {
                        val found = offlineRegions.orEmpty().firstOrNull {
                            it.id == downloaded.id && JSONObject(String(it.metadata, Charsets.UTF_8)).optString("name") == name
                        }
                        if (found == null) {
                            persistedFailure.set("The completed region is missing from native persisted storage")
                            checked.countDown()
                        } else found.getStatus(object : OfflineRegion.OfflineRegionStatusCallback {
                            override fun onStatus(status: OfflineRegionStatus?) {
                                persisted.set(status?.isComplete == true && status.completedTileCount > 0)
                                checked.countDown()
                            }
                            override fun onError(error: String?) { persistedFailure.set(error); checked.countDown() }
                        })
                    }
                    override fun onError(error: String) { persistedFailure.set(error); checked.countDown() }
                })
            }
            assertTrue("Native stored-region lookup timed out", checked.await(10, TimeUnit.SECONDS))
            assertTrue("Native region is incomplete: ${persistedFailure.get()}", persisted.get())

            changedNetwork = true
            shell("svc wifi disable")
            shell("svc data disable")
            awaitCondition(10_000, { "The emulator still has an internet-capable network; offline render assertion would be invalid" }) { !internetAvailable() }

            scenario = ActivityScenario.launch(ComponentActivity::class.java)
            scenario.onActivity { activity ->
                MapBootstrap.initialize(activity)
                val view = MapView(activity)
                mapView = view
                view.onCreate(Bundle())
                // A small viewport stays fully inside the deliberately small downloaded region.
                val frame = FrameLayout(activity)
                frame.addView(view, FrameLayout.LayoutParams(512, 512))
                activity.setContentView(frame)
                view.addOnDidFailLoadingMapListener { error -> mapFailure.set(error) }
                view.addOnDidFinishRenderingMapListener { complete ->
                    if (complete && styleLoaded.get() && view.width > 0 && view.height > 0) {
                        val rendered = map?.queryRenderedFeatures(RectF(0f, 0f, view.width.toFloat(), view.height.toFloat())).orEmpty()
                        featureCount.set(rendered.size)
                        if (rendered.isNotEmpty()) fullyRendered.set(true)
                    }
                }
                view.onStart()
                view.onResume()
                view.getMapAsync { ready ->
                    map = ready
                    ready.cameraPosition = CameraPosition.Builder().target(LatLng(center.latitude, center.longitude)).zoom(16.0).build()
                    // No fallback/empty style is installed: this callback must load the real URL.
                    ready.setStyle(MapBootstrap.STYLE_URL) { loaded ->
                        if (loaded.sources.isNotEmpty() && loaded.layers.isNotEmpty()) styleLoaded.set(true)
                    }
                }
            }
            awaitCondition(35_000, {
                "Actual offline vector map did not render: styleLoaded=${styleLoaded.get()}, features=${featureCount.get()}, error=${mapFailure.get()}"
            }) { styleLoaded.get() && fullyRendered.get() }
            assertFalse("Network returned during the offline render", internetAvailable())
            assertNull("The real map reported a load error", mapFailure.get())
            assertTrue("Offline style alone is insufficient: real vector features must render", featureCount.get() > 0)
            Log.i("RutaOfflineTest", "OFFLINE confirmed: real style loaded and ${featureCount.get()} vector features rendered with Wi-Fi/data disabled")
        } finally {
            // Do not leave test-induced connectivity changes behind even on assertion failure.
            if (changedNetwork) {
                shell(if (initialWifi == "0") "svc wifi disable" else "svc wifi enable")
                shell(if (initialData == "0") "svc data disable" else "svc data enable")
            }
            onMain {
                mapView?.onPause()
                mapView?.onStop()
                mapView?.onDestroy()
                mapView = null
                val id = regionId ?: controller?.regions?.value?.firstOrNull { it.name == name }?.id
                id?.let { controller?.delete(it) }
            }
            scenario?.close()
            controller?.let { regions ->
                awaitCondition(10_000, { "The test-owned offline region could not be removed: ${regions.message.value}" }) {
                    regions.regions.value.none { it.name == name }
                }
            }
        }
    }
}
