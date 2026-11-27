package com.rutacomercial.app.tracking

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.core.content.ContextCompat
import com.rutacomercial.app.data.Repository
import com.rutacomercial.app.domain.Geo
import com.rutacomercial.app.domain.ZoneCoverage
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** What a zone alert should say, kept apart from Android so the rules are testable. */
internal class ZoneWatch(private val outsideDelayMs: Long = 60_000L, private val donePercent: Int = 90) {
    private var outsideSince = 0L
    var warnedOutside = false; private set
    var warnedDone = false; private set
    /** Returns "out", "back" or null. Brief GPS jumps outside do not count: it must stay out for a minute. */
    fun position(inside: Boolean, now: Long): String? {
        if (inside) { outsideSince = 0L; return if (warnedOutside) { warnedOutside = false; "back" } else null }
        if (outsideSince == 0L) outsideSince = now
        if (!warnedOutside && now - outsideSince >= outsideDelayMs) { warnedOutside = true; return "out" }
        return null
    }
    fun coverage(percent: Int?): Boolean {
        if (warnedDone || percent == null || percent < donePercent) return false
        warnedDone = true; return true
    }
}

/** Vibrates and notifies when the worker leaves the zone or has nearly covered it, with the phone in the pocket. */
internal class ZoneAlerts(private val context: Context, private val repository: Repository) {
    private var journeyId: String? = null
    private var polygon: List<Pair<Double, Double>> = emptyList()
    private var name = ""
    private var watch = ZoneWatch()
    private var coverageAt = 0L

    suspend fun check(latitude: Double, longitude: Double, now: Long) {
        val journey = repository.currentJourney() ?: return
        if (journey.id != journeyId) load(journey.id)
        if (polygon.size < 3) return
        when (watch.position(Geo.contains(polygon, latitude, longitude), now)) {
            "out" -> { vibrate(); notify(OUT_ID, "Saliste de la zona", "$name · volvé al borde marcado para seguir repartiendo") }
            "back" -> manager().cancel(OUT_ID)
        }
        if (now - coverageAt >= 120_000L) {
            coverageAt = now
            val points = repository.dao.journeyPoints(journey.id).map { it.latitude to it.longitude }
            val percent = ZoneCoverage.percent(polygon, points)
            if (watch.coverage(percent)) { vibrate(); notify(DONE_ID, "Zona casi completa · $percent %", "$name · revisá en el mapa las calles que faltan") }
        }
    }

    private suspend fun load(id: String) {
        journeyId = id; polygon = emptyList(); name = ""; watch = ZoneWatch(); coverageAt = 0L
        val audit = repository.dao.audit().filter { it.entityId == id }
        // A worker task carries its polygon in the assignment file; an Owner journey uses its saved zone.
        audit.lastOrNull { it.action == "ASSIGNMENT" }?.detail?.let { task ->
            runCatching {
                val obj = JSONObject(File(context.filesDir, "assignments/" + UUID.fromString(task) + ".task").readText())
                polygon = Geo.parsePolygon(obj.getString("polygon")); name = obj.optString("name")
            }
        }
        if (polygon.isEmpty()) audit.lastOrNull { it.action == "WALK_PLAN" }?.detail?.substringBefore('/')?.let { sectorId ->
            repository.dao.sector(sectorId)?.let { polygon = Geo.parsePolygon(it.polygonJson); name = it.name }
        }
    }

    private fun manager() = context.getSystemService(NotificationManager::class.java)
    private fun notify(id: Int, title: String, text: String) {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        manager().createNotificationChannel(NotificationChannel(CHANNEL, "Avisos de zona", NotificationManager.IMPORTANCE_HIGH)
            .apply { description = "Salir de la zona y zona casi completa" })
        manager().notify(id, android.app.Notification.Builder(context, CHANNEL).setSmallIcon(com.rutacomercial.app.R.drawable.ic_tracking)
            .setContentTitle(title).setContentText(text).setAutoCancel(true)
            .setVisibility(android.app.Notification.VISIBILITY_PUBLIC).build())
    }
    private fun vibrate() {
        val vibrator = context.getSystemService(Vibrator::class.java) ?: return
        runCatching { vibrator.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 300, 150, 300), -1)) }
    }

    companion object {
        private const val CHANNEL = "zona"
        private const val OUT_ID = 2410
        private const val DONE_ID = 2411
    }
}
