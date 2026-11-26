package com.rutacomercial.app.ui

import android.app.DatePickerDialog
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.rutacomercial.app.BuildConfig
import com.rutacomercial.app.data.*
import com.rutacomercial.app.io.Exports
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.ZoneId

internal fun dayBound(date: String, next: Boolean = false): Long? = date.takeIf { it.isNotBlank() }?.let {
    LocalDate.parse(it).plusDays(if(next) 1 else 0).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
}

@Composable
internal fun RouteExport(data: DataSnapshot, work: ZoneWork, from: String, until: String, dates: (String,String)->Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var message by remember { mutableStateOf("") }
    val since = dayBound(from)
    val before = dayBound(until, true)
    fun inRange(at: Long) = (since==null || at>=since) && (before==null || at<before)
    val routes = data.journeys.filter { inRange(it.startedAt) }
    val reports = work.reports.filter { inRange(it.at) }
    val valid = since==null || before==null || since<before
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if(uri!=null) scope.launch {
            message = runCatching {
                withContext(Dispatchers.IO) {
                    val rows = mutableListOf<List<Any?>>(listOf("Origen","Recorrido_ID","Asignación_ID","Zona","Persona","Inicio_o_recepción","Estado","Metros","Segundos_activos","Velocidad_media_kmh"))
                    fun row(origin:String,id:String,task:String?,zone:String?,worker:String?,at:Long,status:String,meters:Double,ms:Long) {
                        val label=when(status) { "ACTIVE" -> "En curso"; "PAUSED" -> "En pausa"; else -> "Terminado" }
                        rows.add(listOf(origin,id,task,zone,worker,dateText(at),label,meters,ms/1000,if(ms>0) meters*3600/ms else null))
                    }
                    val tasks=work.tasks.associateBy { it.id }
                    routes.forEach { journey ->
                        val assignment=data.audit.firstOrNull { it.entityId==journey.id && it.action=="ASSIGNMENT" }?.detail
                        val plan=data.audit.firstOrNull { it.entityId==journey.id && it.action=="WALK_PLAN" }?.detail?.substringBefore('/')
                        val task=tasks[assignment]
                        row(if(BuildConfig.WORKER_EDITION) "Repartidor" else "Owner",journey.id,assignment,
                            task?.name ?: data.sectors.firstOrNull { it.id==plan }?.name,task?.worker,journey.startedAt,journey.state,
                            journey.distanceM,journey.activeDuration(System.currentTimeMillis()))
                    }
                    reports.forEach { row("Informe recibido",it.id,it.taskId,it.name,it.worker,it.at,it.state,it.meters,it.duration) }
                    requireNotNull(context.contentResolver.openOutputStream(uri,"wt")).bufferedWriter(Charsets.UTF_8).use { it.write(Exports.csv(rows)) }
                }
                "CSV guardado"
            }.getOrDefault("No se pudo guardar el CSV.")
        }
    }
    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(6.dp)) {
        listOf("Desde" to from,"Hasta" to until).forEachIndexed { index,(label,value) ->
            OutlinedButton(onClick={
                val initial=if(value.isBlank()) LocalDate.now() else LocalDate.parse(value)
                DatePickerDialog(context,{ _,y,m,d ->
                    val chosen=LocalDate.of(y,m+1,d).toString()
                    if(index==0) dates(chosen,until) else dates(from,chosen)
                },initial.year,initial.monthValue-1,initial.dayOfMonth).show()
            }, modifier=Modifier.weight(1f)) { Text(label+" · "+value.ifBlank { "Elegir" }) }
        }
    }
    if(from.isNotBlank() || until.isNotBlank()) TextButton(onClick={dates("","")}) { Text("Quitar fechas") }
    if(!valid) Text("Hasta debe ser igual o posterior a Desde.",color=MaterialTheme.colorScheme.error)
    val count=routes.size+reports.size
    Button(enabled=work.loaded && valid && count>0,onClick={launcher.launch("RutaComercial_recorridos_"+LocalDate.now()+".csv")},modifier=Modifier.fillMaxWidth()) {
        Text("Exportar recorridos a CSV ("+count+")")
    }
    if(message.isNotBlank()) Text(message,style=MaterialTheme.typography.bodySmall)
}
