package com.rutacomercial.app.tracking

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.google.android.gms.location.*

/** A bounded foreground preview, never a journey point. Cached/coarse results are improved. */
internal class LocationPreview(context: Context, private val continuous:Boolean=false) {
    private val manager = context.getSystemService(LocationManager::class.java)
    private val fused = LocationServices.getFusedLocationProviderClient(context)
    private val handler = Handler(Looper.getMainLooper())
    private var active = false
    private var callback: LocationCallback? = null
    private var listener: LocationListener? = null
    private var best: Location? = null

    @SuppressLint("MissingPermission")
    fun start(onFix: (Location) -> Unit, onDone: (Boolean) -> Unit) {
        active = true
        fun accept(location: Location) {
            if (!active || !usable(location)) return
            val previous = best
            if (previous != null && location.accuracy > previous.accuracy * 1.5f && location.time - previous.time < 10_000) return
            best = location
            onFix(location)
            if (!continuous && location.accuracy <= 20 && System.currentTimeMillis() - location.time < 10_000) {
                stop(); onDone(true)
            }
        }
        fun fallback() {
            if (!active || listener != null) return
            val receiving = object : LocationListener {
                override fun onLocationChanged(location: Location) = accept(location)
                override fun onProviderEnabled(provider: String) = Unit
                override fun onProviderDisabled(provider: String) = Unit
                @Deprecated("Legacy callback") override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
            }
            listener = receiving
            for (provider in listOf(LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER)) {
                if (!active) break
                runCatching {
                    if (manager.isProviderEnabled(provider)) {
                        manager.getLastKnownLocation(provider)?.let(::accept)
                        if (active) manager.requestLocationUpdates(provider, 2_000L, 0f, receiving, Looper.getMainLooper())
                    }
                }
            }
        }
        val receiving = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) { result.locations.forEach(::accept) }
        }
        callback = receiving
        try {
            fused.lastLocation.addOnSuccessListener { it?.let(::accept) }.addOnFailureListener { fallback() }
            fused.requestLocationUpdates(LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, if(continuous) 5_000L else 2_000L)
                .setMinUpdateIntervalMillis(1_000).setWaitForAccurateLocation(false).setMaxUpdateAgeMillis(30_000).build(), receiving, Looper.getMainLooper())
                .addOnSuccessListener { if (!active) fused.removeLocationUpdates(receiving) }
                .addOnFailureListener { fallback() }
        } catch (_: Exception) { fallback() }
        handler.postDelayed({ if (best == null) fallback() }, 3_000)
        if(!continuous) handler.postDelayed({ if (active) { val found = best != null; stop(); onDone(found) } }, 20_000)
    }

    fun stop() {
        active = false
        handler.removeCallbacksAndMessages(null)
        callback?.let { runCatching { fused.removeLocationUpdates(it) } }; callback = null
        listener?.let { runCatching { manager.removeUpdates(it) } }; listener = null
    }

    companion object {
        fun usable(location: Location): Boolean = location.hasAccuracy() && LocationPreviewPolicy.usable(
            com.rutacomercial.app.domain.GpsFix(location.latitude, location.longitude, location.accuracy, location.time, location.elapsedRealtimeNanos),
            System.currentTimeMillis(), SystemClock.elapsedRealtimeNanos())
    }
}
