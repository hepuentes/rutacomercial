package com.rutacomercial.app.ui

import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.rutacomercial.app.data.*
import com.rutacomercial.app.domain.Geo
import com.rutacomercial.app.maps.MapCoordinate
import com.rutacomercial.app.maps.RouteMark
import com.rutacomercial.app.maps.MapTrack
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

internal data class WorkTask(val id: String, val sectorId: String?, val name: String, val worker: String, val polygon: String, val created: Long) {
    fun belongsTo(zone: Sector, zones: List<Sector>): Boolean {
        if (sectorId != null) return sectorId == zone.id
        val candidates = zones.filter { it.polygonJson == polygon }
        return (candidates.singleOrNull() ?: candidates.singleOrNull { it.name == name })?.id == zone.id
    }
}

internal data class WorkReport(val id: String, val taskId: String, val name: String, val worker: String,
    val at: Long, val dated: Boolean, val meters: Double, val duration: Long, val state: String, val lines: List<MapTrack>, val marks: List<RouteMark>,
    val updated: Long = at, val outside: Boolean = false,
    /** Not paid: riding, simulated location, clock moved forward. Informative: exits left unanswered. */
    val vehicleMs: Long = 0, val mockMs: Long = 0, val clockMs: Long = 0, val outsideMs: Long = 0) {
    val unpaidMs get() = vehicleMs + mockMs + clockMs
}

internal data class ZoneWork(val tasks: List<WorkTask> = emptyList(), val reports: List<WorkReport> = emptyList(), val loaded: Boolean = false) {
    fun tasksFor(zone: Sector, zones: List<Sector>) = tasks.filter { it.belongsTo(zone, zones) }
    fun label(zone: Sector, zones: List<Sector>): String {
        if(zone.status=="COMPLETED") return "Zona completada"
        val task=tasksFor(zone,zones).firstOrNull() ?: return sectorState(zone.status)
        return when(reports.firstOrNull { it.taskId==task.id }?.state) {
            "FINISHED" -> "Recorrido terminado"
            "PAUSED" -> "En pausa · último avance"
            "ACTIVE" -> "En curso · último avance"
            else -> "Asignada · esperando informe"
        }
    }
    fun reportsFor(tasks: List<WorkTask>): List<WorkReport> {
        val keys = tasks.map { it.id }.toSet()
        return reports.filter { it.taskId in keys }
    }
    companion object {
        fun read(folder: File): ZoneWork {
            val files = folder.listFiles().orEmpty()
            val tasks = files.filter { it.extension == "task" }.mapNotNull { file -> runCatching {
                val obj = JSONObject(file.readText())
                WorkTask(file.nameWithoutExtension, obj.optString("sectorId").ifBlank { null }, obj.getString("name"),
                    obj.getString("worker"), obj.getString("polygon"), file.lastModified())
            }.getOrNull() }.sortedByDescending { it.created }
            val byId = tasks.associateBy { it.id }
            val reports = files.filter { it.extension in setOf("result", "received") }
                .sortedByDescending { it.extension == "result" }.distinctBy { it.nameWithoutExtension }.mapNotNull { file -> runCatching {
                    val obj = JSONObject(file.readText())
                    if (obj.optString("kind") != "result") return@runCatching null
                    val key = file.nameWithoutExtension
                    val taskId = obj.optString("assignmentId", key)
                    val task = byId[taskId]
                    val lines=decodeLines(obj.getJSONArray("segments"),key)
                    WorkReport(key, taskId, task?.name ?: obj.optString("name"), task?.worker ?: obj.optString("worker"),
                        obj.optLong("startedAt", file.lastModified()), obj.has("startedAt"), obj.getDouble("meters"),
                        obj.getLong("duration"), obj.optString("state", "FINISHED"), lines, resultMarks(obj,lines).map { it.copy(group=key) },
                        obj.optLong("capturedAt", file.lastModified()), outsideChosen(obj),
                        obj.optLong("vehicleMs"), obj.optLong("mockMs"), obj.optLong("clockMs"), obj.optLong("outsideMs"))
                }.getOrNull() }.sortedByDescending { it.at }
            return ZoneWork(tasks, reports, loaded = true)
        }
    }
}

/** The worker chose "Seguir fuera de la zona" in this journey. */
internal fun outsideChosen(report: JSONObject?): Boolean {
    val list = report?.optJSONArray("observations") ?: return false
    return (0 until list.length()).any { list.optJSONObject(it)?.optString("kind") == "OUTSIDE_OK" }
}

internal fun resultMarks(report:JSONObject?, lines:List<MapTrack>):List<RouteMark> {
    val events=report?.optJSONArray("events")
    if(events!=null) return (0 until events.length()).map { i ->
        val e=events.getJSONObject(i)
        val label=e.getString("label")
        val lat=e.getDouble("lat")
        val lon=e.getDouble("lon")
        require(label in setOf("IR","P","CR","FR") && Geo.valid(lat,lon))
        RouteMark(label,MapCoordinate(lat,lon),e.optLong("at"),e.optInt("sequence"))
    }
    val first=lines.firstNotNullOfOrNull { it.points.firstOrNull() }
    val last=lines.asReversed().firstNotNullOfOrNull { it.points.lastOrNull() }
    return listOfNotNull(first?.let { RouteMark("IR",it) },
        last?.takeIf { report?.optString("state","FINISHED")=="FINISHED" }?.let { RouteMark("FR",it) })
}

internal fun journeyMarks(data: DataSnapshot, journeyId: String?): List<RouteMark> {
    if(journeyId==null) return emptyList()
    val points=data.points.filter { it.journeyId==journeyId }.sortedBy { it.at }
    val transitions=data.audit.filter { it.entityId==journeyId && it.action in setOf("START","RESUME","PAUSE","FINISH","RECOVER_PAUSED") }.sortedBy { it.at }
    var pauseIndex=0
    return transitions.mapIndexedNotNull { index,event ->
        val label=when(event.action) { "START"->"IR"; "RESUME"->"CR"; "FINISH"->"FR"; else->"P" }
        if(label=="P") pauseIndex++
        val xy=event.detail.split(",").mapNotNull { it.toDoubleOrNull() }
        if(xy.size==2 && Geo.valid(xy[0],xy[1])) return@mapIndexedNotNull RouteMark(label,MapCoordinate(xy[0],xy[1]),event.at,pauseIndex,journeyId)
        val opening=event.action=="START" || event.action=="RESUME"
        val p=if(opening) points.firstOrNull { it.at>=event.at && it.at<=(transitions.getOrNull(index+1)?.at ?: Long.MAX_VALUE) }
            else if(event.action=="FINISH") points.lastOrNull { it.at<=event.at }
            else points.lastOrNull { it.at<=event.at && it.at>=(transitions.getOrNull(index-1)?.at ?: 0L) }
        p?.let { RouteMark(label,MapCoordinate(it.latitude,it.longitude),event.at,pauseIndex,journeyId) }
    }
}

internal fun decodeLines(segments: JSONArray, prefix: String): List<MapTrack> {
    require(segments.length() <= 10000)
    return (0 until segments.length()).map { i ->
        val line = segments.getJSONArray(i)
        MapTrack("$prefix-$i", group=prefix, points=(0 until line.length()).map { j ->
            val xy = line.getJSONArray(j)
            val lat = xy.getDouble(0)
            val lon = xy.getDouble(1)
            require(Geo.valid(lat, lon))
            MapCoordinate(lat, lon)
        })
    }
}

internal fun previousJourneys(data: DataSnapshot, sectorId: String): Set<String> =
    data.audit.filter { it.action == "WALK_PLAN" && it.detail.substringBefore('/') == sectorId }.map { it.entityId }.toSet()

internal fun encodedMarks(marks: List<RouteMark>) = JSONArray().apply {
    marks.forEach { put(JSONObject().put("label",it.label).put("at",it.at).put("sequence",it.sequence)
        .put("lat",it.point.latitude).put("lon",it.point.longitude).put("group",it.group)) }
}

internal fun previousMarks(task:JSONObject):List<RouteMark> {
    if(!task.has("previousEvents")) return emptyList()
    val events=JSONArray(task.getString("previousEvents"))
    val marks=resultMarks(JSONObject().put("events",events),emptyList())
    return marks.mapIndexed { i,m -> m.copy(group="prior-"+events.getJSONObject(i).optString("group")) }
}

internal fun previousSegments(lines: List<MapTrack>): JSONArray {
    val usable = lines.filter { it.points.size >= 2 }
    val stride = (usable.sumOf { it.points.size.toLong() } / 16000 + 1).toInt()
    return JSONArray().apply {
        usable.forEach { line -> put(JSONArray().apply {
            line.points.forEachIndexed { i, p ->
                if (i % stride == 0 || i == line.points.lastIndex) put(JSONArray().put(p.latitude).put(p.longitude))
            }
        }) }
    }
}

internal fun validateBundle(doc:JSONObject):List<JSONObject> {
    val prior=doc.optJSONArray("previousReports") ?: JSONArray()
    val assignment=doc.optString("assignmentId",doc.getString("id"))
    java.util.UUID.fromString(assignment)
    val ids=mutableSetOf<String>()
    val entries=(0 until prior.length()).map { prior.getJSONObject(it) }+doc
    for(entry in entries) {
        val id=entry.getString("id")
        java.util.UUID.fromString(id)
        require(ids.add(id) && entry.optString("assignmentId",id)==assignment)
        require(entry.getString("kind")=="result" && entry.getInt("version") in 1..3)
        require(entry.optString("state","FINISHED") in setOf("ACTIVE","PAUSED","FINISHED"))
        require(entry.getDouble("meters").isFinite() && entry.getDouble("meters")>=0 && entry.getLong("duration")>=0)
        require(entry===doc || !entry.has("previousReports"))
        resultMarks(entry,decodeLines(entry.getJSONArray("segments"),id))
    }
    return entries
}

internal fun newerReport(saved: JSONObject?, incoming: JSONObject): JSONObject {
    if (saved == null) return incoming
    val wasFinal = saved.optString("state", "FINISHED") == "FINISHED"
    val isFinal = incoming.optString("state", "FINISHED") == "FINISHED"
    if (wasFinal != isFinal) return if (wasFinal) saved else incoming
    return if (saved.optLong("capturedAt") > incoming.optLong("capturedAt")) saved else incoming
}

@Composable
internal fun zoneWork(revision: Int = 0): ZoneWork {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val onlineRevision by (context.applicationContext as com.rutacomercial.app.RutaApplication).cloud.revision.collectAsState()
    val work by produceState(ZoneWork(), lifecycle, revision, onlineRevision) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            value = withContext(Dispatchers.IO) { ZoneWork.read(File(context.filesDir, "assignments")) }
        }
    }
    return work
}
