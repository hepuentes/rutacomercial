package com.rutacomercial.app.tracking

import android.Manifest
import android.content.Context
import android.location.Criteria
import android.location.Location
import android.location.LocationManager
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class JourneyLocationSourceTest {
    @Suppress("DEPRECATION")
    @Test fun devicesWithoutPlayServicesUseFrameworkAndStopRemovesSubscription() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        fun shell(command: String) = instrumentation.uiAutomation.executeShellCommand(command).use {
            android.os.ParcelFileDescriptor.AutoCloseInputStream(it).use { stream -> stream.readBytes() }; Unit
        }
        shell("pm grant ${context.packageName} ${Manifest.permission.ACCESS_COARSE_LOCATION}")
        shell("pm grant ${context.packageName} ${Manifest.permission.ACCESS_FINE_LOCATION}")
        shell("appops set ${context.packageName} android:mock_location allow")
        val manager = context.getSystemService(LocationManager::class.java)
        val source = JourneyLocationSource(context, hasPlayServices = { false })
        val fixes = CopyOnWriteArrayList<Location>()
        val errors = CopyOnWriteArrayList<String>()
        try {
            manager.addTestProvider(LocationManager.GPS_PROVIDER, false,false,false,false,true,true,true,Criteria.POWER_LOW,Criteria.ACCURACY_FINE)
            manager.setTestProviderEnabled(LocationManager.GPS_PROVIDER, true)
            instrumentation.runOnMainSync { source.start({ fixes.add(it) }, { assertTrue(it.contains("GPS de Android")) }, { errors.add(it) }) }
            fun inject() = manager.setTestProviderLocation(LocationManager.GPS_PROVIDER, Location("gps").apply {
                latitude = -32.89; longitude = -68.84; accuracy = 4f
                time = System.currentTimeMillis(); elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
            })
            inject()
            val deadline = SystemClock.elapsedRealtime() + 10_000
            while (fixes.isEmpty() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(100)
            assertFalse(fixes.isEmpty()); assertTrue(errors.isEmpty())
            instrumentation.runOnMainSync { source.stop() }
            val count = fixes.size
            SystemClock.sleep(6300); inject(); SystemClock.sleep(500)
            assertEquals(count, fixes.size)
        } finally {
            instrumentation.runOnMainSync { source.stop() }
            runCatching { manager.removeTestProvider(LocationManager.GPS_PROVIDER) }
            shell("appops set ${context.packageName} android:mock_location default")
        }
    }
}
