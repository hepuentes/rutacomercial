package com.rutacomercial.app.ui

import com.rutacomercial.app.data.*
import org.json.*

internal fun sessionReport(data: DataSnapshot, journey: Journey, sessions: List<String>, zone: JSONObject): JSONObject {
    val id = zone.getString("id")
    val segments=JSONArray()
    mapTracks(data,journey.id).forEach { t->segments.put(JSONArray().apply { t.points.forEach { put(JSONArray().put(it.latitude).put(it.longitude)) } }) }
    val speeds=JSONArray()
    data.points.filter { it.journeyId==journey.id }.groupBy { it.segment }.toSortedMap().values.forEach { group ->
        val ordered=group.sortedBy { it.elapsedRealtimeNanos }
        val seconds=(ordered.last().elapsedRealtimeNanos-ordered.first().elapsedRealtimeNanos)/1e9
        if(seconds>0) speeds.put(JSONObject().put("from",ordered.first().at).put("to",ordered.last().at)
            .put("kmh",ordered.drop(1).sumOf { it.distanceDeltaM }*3.6/seconds))
    }
    val observations=JSONArray()
    data.incidents.filter { it.journeyId==journey.id && it.kind in setOf("FAST_INTERVAL","WALK_STOP","GPS","GPS_DISABLED","LOCATION_UNAVAILABLE","OUTSIDE_ZONE","OUTSIDE_OK","IDLE_PAUSE","VEHICLE","VEHICLE_PAUSE","MOCK_LOCATION","CLOCK_CHANGE") }.forEach {
        observations.put(JSONObject().put("kind",it.kind).put("at",it.at))
    }
    val reportKey=if(journey.id==sessions.firstOrNull()) id else journey.id
    return JSONObject().put("assignmentId",id).put("state",journey.state)
        .put("capturedAt",System.currentTimeMillis()).put("startedAt",journey.startedAt).put("worker",zone.optString("worker")).put("name",zone.optString("name"))
        .put("speedSegments",speeds).put("observations",observations).put("version",1).put("kind","result").put("id",reportKey).put("meters",journey.distanceM).put("duration",journey.activeDuration(System.currentTimeMillis())).put("segments",segments).put("events",encodedMarks(journeyMarks(data,journey.id)))
}

/** Unpaid and informative times of a journey, so the Owner sees exactly what the worker sees. */
internal fun JSONObject.withAntiFraud(context: android.content.Context, journeyId: String): JSONObject {
    val ledger = com.rutacomercial.app.tracking.AntiFraudLedger(context)
    com.rutacomercial.app.tracking.AntiFraudLedger.KINDS.forEach { put(it + "Ms", ledger.get(journeyId, it).coerceAtLeast(0)) }
    com.rutacomercial.app.tracking.AntiFraudLedger.GPS.forEach { put(it, ledger.get(journeyId, it)) }
    return this
}

/** "312 lecturas · 280 usadas · 25 imprecisas · 4 pausas (máx. 95 s)", or null for reports from older versions. */
internal fun gpsSummary(report: JSONObject): String? {
    if (!report.has("gpsFixes")) return null
    val parts = mutableListOf("${report.optLong("gpsFixes")} lecturas", "${report.optLong("gpsUsed")} usadas")
    report.optLong("gpsImprecise").takeIf { it > 0 }?.let { parts += "$it imprecisas" }
    report.optLong("gpsStale").takeIf { it > 0 }?.let { parts += "$it viejas o con hora desfasada" }
    report.optLong("gpsGaps").takeIf { it > 0 }?.let { parts += "$it pausas de señal (máx. ${report.optLong("gpsMaxGapMs") / 1000} s)" }
    return parts.joinToString(" · ")
}
