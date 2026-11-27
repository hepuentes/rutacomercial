package com.rutacomercial.app.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.rutacomercial.app.maps.*
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import java.time.LocalDate
import java.time.ZoneId

/** One worker as the Owner sees them today: last known place and how fresh it is. */
internal data class WorkerToday(val worker: String, val zone: String, val state: String, val meters: Double,
    val updated: Long, val last: MapCoordinate?, val lines: List<MapTrack>, val activeMs: Long = 0, val zones: Int = 1,
    val outside: Boolean = false, val marks: List<RouteMark> = emptyList())

/** ACTIVE without news for 5 minutes reads as no signal: the phone sends about once a minute. */
internal fun todayState(state: String, updated: Long, now: Long, outside: Boolean = false): String = when {
    state == "FINISHED" -> "terminó"
    state == "PAUSED" -> "en pausa"
    now - updated > 5 * 60_000L -> "sin señal"
    outside -> "fuera de zona"
    else -> "recorriendo"
}

internal fun workersToday(reports: List<WorkReport>, since: Long): List<WorkerToday> =
    reports.filter { it.updated >= since || it.at >= since }.groupBy { it.worker.trim().lowercase() }.map { (_, mine) ->
        val latest = mine.maxBy { it.updated }
        WorkerToday(latest.worker, latest.name, latest.state, mine.sumOf { it.meters }, latest.updated,
            latest.lines.lastOrNull { it.points.isNotEmpty() }?.points?.last(), mine.flatMap { it.lines },
            mine.sumOf { it.duration }, mine.map { it.taskId }.distinct().size, latest.outside, mine.flatMap { it.marks })
    }.sortedByDescending { it.updated }

/** Plain text for WhatsApp or the end-of-day notice. */
internal fun daySummary(workers: List<WorkerToday>): String = if (workers.isEmpty()) "Hoy no hubo recorridos." else
    "Resumen de hoy\n" + workers.joinToString("\n") { w ->
        "• ${w.worker}: ${number(w.meters / 1000, 2)} km · ${hours(w.activeMs)} · ${w.zones} ${if (w.zones == 1) "zona" else "zonas"}"
    } + "\nTotal: ${number(workers.sumOf { it.meters } / 1000, 2)} km"

private val stateColors = mapOf("recorriendo" to "#1B6B3A", "fuera de zona" to "#7A4FB5", "en pausa" to "#9A5B00", "sin señal" to "#B3261E", "terminó" to "#5F6B73")

class TodayActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { RutaTheme { Surface(Modifier.fillMaxSize()) { TodayScreen { finish() } } } }
    }
}

@Composable
private fun TodayScreen(close: () -> Unit) {
    val work = zoneWork()
    val context = androidx.compose.ui.platform.LocalContext.current
    val repo = remember { (context.applicationContext as com.rutacomercial.app.RutaApplication).repository }
    val vm: RutaViewModel = androidx.lifecycle.viewmodel.compose.viewModel(factory = remember { RutaViewModel.factory(repo) })
    val data by vm.data.collectAsState()
    val zones = remember(data.sectors) { mapSectors(data) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(30_000); now = System.currentTimeMillis() } }
    val since = remember { LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli() }
    val workers = remember(work, since) { workersToday(work.reports, since) }
    var center by remember { mutableStateOf<MapCoordinate?>(null) }
    // Follows everyone as they move until the Owner pans the map by hand.
    var follow by remember { mutableStateOf(true) }
    var request by remember { mutableIntStateOf(0) }
    // Start, pause and resume marks only for the worker tapped below, so the map stays readable.
    var selected by remember { mutableStateOf<String?>(null) }
    val cloud = remember { (context.applicationContext as com.rutacomercial.app.RutaApplication).cloud }
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(Unit) {
        lifecycle.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
            while (true) { cloud.watch(cloud.linkedWorkers().map { it.second }); delay(120_000) }
        }
    }
    Column(Modifier.fillMaxSize().systemBarsPadding().padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Hoy", style = MaterialTheme.typography.titleLarge)
                Text(if (workers.isEmpty()) "Ningún repartidor envió avances hoy" else (if (workers.size == 1) "1 repartidor" else "${workers.size} repartidores") + " · tocá uno para ver sus marcas",
                    style = MaterialTheme.typography.bodySmall)
            }
            if (workers.isNotEmpty()) TextButton(onClick = {
                context.startActivity(android.content.Intent.createChooser(android.content.Intent(android.content.Intent.ACTION_SEND)
                    .setType("text/plain").putExtra(android.content.Intent.EXTRA_TEXT, daySummary(workers)), "Compartir resumen"))
            }) { Text("Compartir") }
            TextButton(onClick = close) { Text("Volver") }
        }
        TerritoryMap(Modifier.fillMaxWidth().weight(1f), tracks = workers.flatMap { it.lines }, sectors = zones,
            marks = workers.firstOrNull { it.worker == selected }?.marks.orEmpty(),
            businesses = workers.mapNotNull { w ->
                val state = todayState(w.state, w.updated, now, w.outside)
                w.last?.let { MapBusiness("today-" + w.worker, w.worker + " · " + state, it.latitude, it.longitude, stateColors.getValue(state)) }
            },
            fitArea = if (follow && center == null) workers.flatMap { w -> w.lines.flatMap { it.points } } else emptyList(),
            center = center, centerRequest = request, onMapGesture = { follow = false; center = null })
        if (!follow || selected != null) TextButton(onClick = { follow = true; center = null; selected = null }) { Text("Seguir a todos") }
        Column(Modifier.fillMaxWidth().heightIn(max = 220.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            workers.forEach { w ->
                val state = todayState(w.state, w.updated, now, w.outside)
                val minutes = ((now - w.updated) / 60_000).coerceAtLeast(0)
                Surface(onClick = { selected = w.worker; w.last?.let { follow = false; center = it; request++ } }, shape = MaterialTheme.shapes.medium,
                    color = if (selected == w.worker) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceVariant) {
                    Row(Modifier.fillMaxWidth().padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(w.worker + " · " + w.zone, style = MaterialTheme.typography.titleSmall)
                            Text(number(w.meters / 1000, 2) + " km · " + hours(w.activeMs) + " · " + w.zones + (if (w.zones == 1) " zona" else " zonas") +
                                " · " + if (minutes == 0L) "ahora" else "hace $minutes min", style = MaterialTheme.typography.bodySmall)
                        }
                        Text(state, color = Color(android.graphics.Color.parseColor(stateColors.getValue(state))), style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
        }
    }
}
