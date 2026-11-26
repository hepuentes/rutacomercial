package com.rutacomercial.app.tracking

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority

/** No Maps SDK, key, account or billing. One subscription, explicitly removed on pause/finish. */
internal class JourneyLocationSource(
    private val context: Context,
    private val hasPlayServices: () -> Boolean = {
        GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context) == ConnectionResult.SUCCESS
    },
) {
    private val manager = context.getSystemService(LocationManager::class.java)
    private val fused by lazy { LocationServices.getFusedLocationProviderClient(context) }
    private var cleanup: (() -> Unit)? = null
    private var generation = 0

    @SuppressLint("MissingPermission") // TrackingService checks permission and handles revocation.
    fun start(onLocation: (Location) -> Unit, onProvider: (String) -> Unit, onError: (String) -> Unit) {
        stop()
        val token = generation
        check(manager.isProviderEnabled(LocationManager.GPS_PROVIDER)) { "Activá Ubicación en Android antes de iniciar el recorrido." }
        if (!hasPlayServices()) {
            startFramework(token, onLocation, onProvider, onError)
            return
        }
        val callback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                if (generation == token) result.locations.sortedBy { it.elapsedRealtimeNanos }.forEach(onLocation)
            }
        }
        cleanup = { fused.removeLocationUpdates(callback) }
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, INTERVAL_MS)
            .setMinUpdateIntervalMillis(INTERVAL_MS)
            .setMaxUpdateAgeMillis(0)
            .setMaxUpdateDelayMillis(0)
            .setWaitForAccurateLocation(true)
            .build()
        fused.requestLocationUpdates(request, callback, Looper.getMainLooper())
            .addOnSuccessListener {
                if (generation != token) fused.removeLocationUpdates(callback)
                else onProvider("Ubicación combinada · cada 6 s")
            }
            .addOnFailureListener {
                if (generation == token) {
                    runCatching { fused.removeLocationUpdates(callback) }
                    try { startFramework(token, onLocation, onProvider, onError) }
                    catch (_: Exception) { onError("No se pudo solicitar ubicación. Revisá los permisos y Ubicación en Android.") }
                }
            }
    }

    @SuppressLint("MissingPermission")
    private fun startFramework(token: Int, onLocation: (Location) -> Unit, onProvider: (String) -> Unit, onError: (String) -> Unit) {
        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) { if (generation == token) onLocation(location) }
            override fun onProviderDisabled(provider: String) { if (generation == token) onError("Ubicación desactivada en Android. Jornada pausada.") }
            override fun onProviderEnabled(provider: String) = Unit
            @Deprecated("Legacy callback") override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
        }
        cleanup = { manager.removeUpdates(listener) }
        manager.requestLocationUpdates(LocationManager.GPS_PROVIDER, INTERVAL_MS, 0f, listener, Looper.getMainLooper())
        onProvider("GPS de Android · cada 6 s")
    }

    fun stop() {
        generation++
        cleanup?.let { runCatching(it) }
        cleanup = null
    }

    companion object { const val INTERVAL_MS = 6_000L }
}
