package com.rutacomercial.app.maps

import android.content.Context
import android.net.ConnectivityManager

/**
 * Saves the map of a working zone for use without signal, once per zone, only on an unmetered network
 * (Wi-Fi) so it never spends the worker's mobile data. Zones over 1 km stay manual (Guardar mapa sin internet).
 */
internal object OfflineZones {
    fun ensure(context: Context, key: String, polygon: List<MapCoordinate>, name: String) {
        val prefs = context.getSharedPreferences("offline_zones", Context.MODE_PRIVATE)
        if (prefs.getBoolean(key, false) || polygon.size < 3) return
        val area = DownloadArea.from(polygon) ?: return
        if (!area.downloadable) return
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        if (connectivity.activeNetwork == null || connectivity.isActiveNetworkMetered) return
        val regions = OfflineRegions.get(context)
        if (regions.busy.value) return
        regions.download(polygon, "Zona · $name")
        prefs.edit().putBoolean(key, true).apply()
    }
}
