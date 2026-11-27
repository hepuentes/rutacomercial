package com.rutacomercial.app.tracking

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.core.content.ContextCompat
import com.rutacomercial.app.data.Repository
import com.rutacomercial.app.domain.Geo
import com.rutacomercial.app.domain.ZoneCoverage
import kotlinx.coroutines.flow.update
import org.json.JSONObject
import java.io.File
import java.util.UUID
import kotlin.math.cos
import kotlin.math.hypot

/**
 * When to say "you left the zone". Hand-drawn borders often cut an avenue in half and phone GPS drifts
 * 5-15 m, so the first [band] meters outside still count as inside. Beyond that, a worker crossing a
 * corner or walking the far sidewalk gets [nearDelayMs]; someone clearly gone ([far] meters) only [farDelayMs].
 */
internal class ZoneWatch(private val band: Double = 40.0, private val far: Double = 250.0,
    private val nearDelayMs: Long = 180_000L, private val farDelayMs: Long = 45_000L, private val donePercent: Int = 90) {
    private var outsideSince: Long? = null
    /** When the current stretch beyond the tolerance began, if any. */
    val outsideStartedAt: Long? get() = outsideSince
    var warnedOutside = false; private set
    var warnedDone = false; private set
    /** The worker chose to keep working outside: no more exit warnings this journey. */
    var accepted = false

    /** Returns "out", "back" or null. [outsideM] is 0 inside the zone, else meters to its border. */
    fun position(outsideM: Double, now: Long): String? {
        if (outsideM <= band) { outsideSince = null; return if (warnedOutside) { warnedOutside = false; "back" } else null }
        if (accepted) return null
        val since = outsideSince ?: now.also { outsideSince = it }
        val delay = if (outsideM >= far) farDelayMs else nearDelayMs
        if (!warnedOutside && now - since >= delay) { warnedOutside = true; return "out" }
        return null
    }
    fun coverage(percent: Int?): Boolean {
        if (warnedDone || percent == null || percent < donePercent) return false
        warnedDone = true; return true
    }

    companion object {
        /** 0 inside; otherwise meters from the point to the nearest border segment. */
        fun outsideMeters(polygon: List<Pair<Double, Double>>, lat: Double, lon: Double): Double {
            if (polygon.size < 3 || Geo.contains(polygon, lat, lon)) return 0.0
            val kx = 111_320.0 * cos(Math.toRadians(lat)); val ky = 110_540.0
            var best = Double.MAX_VALUE
            for (i in polygon.indices) {
                val (alat, alon) = polygon[i]; val (blat, blon) = polygon[(i + 1) % polygon.size]
                val ax = (alon - lon) * kx; val ay = (alat - lat) * ky
                val bx = (blon - lon) * kx; val by = (blat - lat) * ky
                val dx = bx - ax; val dy = by - ay; val len2 = dx * dx + dy * dy
                val t = if (len2 == 0.0) 0.0 else (-(ax * dx + ay * dy) / len2).coerceIn(0.0, 1.0)
                best = minOf(best, hypot(ax + t * dx, ay + t * dy))
            }
            return best
        }
    }
}

/** Vibrates and notifies when the worker leaves the zone or has nearly covered it, with the phone in the pocket. */
internal class ZoneAlerts(private val context: Context, private val repository: Repository) {
    private var journeyId: String? = null
    private var polygon: List<Pair<Double, Double>> = emptyList()
    private var name = ""
    private var watch = ZoneWatch()
    private var coverageAt = 0L
    private val prefs = context.getSharedPreferences("zone_alerts", Context.MODE_PRIVATE)
    private val ledger = AntiFraudLedger(context)
    /** Start of a warned exit the worker has not answered; that time is shown to the Owner. */
    private var warnedFrom: Long? = null

    private fun closeWarned(now: Long) {
        val id = journeyId ?: return
        warnedFrom?.let { ledger.add(id, AntiFraudLedger.OUTSIDE, now - it) }
        warnedFrom = null
    }
    suspend fun flush(now: Long) { closeWarned(now) }

    suspend fun check(latitude: Double, longitude: Double, now: Long) {
        val journey = repository.currentJourney() ?: return
        if (journey.id != journeyId) load(journey.id)
        if (polygon.size < 3) return
        when (watch.position(ZoneWatch.outsideMeters(polygon, latitude, longitude), now)) {
            "out" -> {
                warnedFrom = watch.outsideStartedAt ?: now
                vibrate()
                repository.addIncident("Fuera de la zona $name por más tiempo que la tolerancia", journey.id, "OUTSIDE_ZONE")
                TrackingRuntime.mutable.update { it.copy(outsideZone = name) }
                notify(OUT_ID, "Saliste de la zona", "$name · volvé al borde marcado, o seguí fuera si elegiste otra parte", outsideAction = true)
            }
            "back" -> { closeWarned(now); manager().cancel(OUT_ID); TrackingRuntime.mutable.update { it.copy(outsideZone = null) } }
        }
        if (now - coverageAt >= 120_000L) {
            coverageAt = now
            val points = repository.dao.journeyPoints(journey.id).map { it.latitude to it.longitude }
            val percent = ZoneCoverage.percent(polygon, points)
            if (watch.coverage(percent)) { vibrate(); notify(DONE_ID, "Zona casi completa · $percent %", "$name · revisá en el mapa las calles que faltan") }
        }
    }

    /** The worker decided to work another area: stop warning, keep it on record so the Owner sees it. */
    suspend fun acceptOutside() {
        val journey = repository.currentJourney() ?: return
        if (journey.id != journeyId) load(journey.id)
        if (watch.accepted) return
        closeWarned(System.currentTimeMillis())
        watch.accepted = true
        prefs.edit().putBoolean(journey.id, true).apply()
        repository.addIncident("Decidió seguir el recorrido fuera de la zona $name", journey.id, "OUTSIDE_OK")
        manager().cancel(OUT_ID)
        TrackingRuntime.mutable.update { it.copy(outsideZone = null) }
    }

    private suspend fun load(id: String) {
        journeyId = id; polygon = emptyList(); name = ""; watch = ZoneWatch(); coverageAt = 0L; warnedFrom = null
        watch.accepted = prefs.getBoolean(id, false)
        TrackingRuntime.mutable.update { it.copy(outsideZone = null) }
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
    private fun notify(id: Int, title: String, text: String, outsideAction: Boolean = false) {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        manager().createNotificationChannel(NotificationChannel(CHANNEL, "Avisos de zona", NotificationManager.IMPORTANCE_HIGH)
            .apply { description = "Salir de la zona y zona casi completa" })
        val builder = android.app.Notification.Builder(context, CHANNEL).setSmallIcon(com.rutacomercial.app.R.drawable.ic_tracking)
            .setContentTitle(title).setContentText(text).setStyle(android.app.Notification.BigTextStyle().bigText(text)).setAutoCancel(true)
            .setVisibility(android.app.Notification.VISIBILITY_PUBLIC)
        context.packageManager.getLaunchIntentForPackage(context.packageName)?.let {
            builder.setContentIntent(PendingIntent.getActivity(context, 7, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        }
        if (outsideAction) builder.addAction(android.app.Notification.Action.Builder(null, "Seguir fuera de la zona",
            PendingIntent.getService(context, 6, Intent(context, TrackingService::class.java).setAction(TrackingService.OUTSIDE_OK),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)).build())
        manager().notify(id, builder.build())
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
