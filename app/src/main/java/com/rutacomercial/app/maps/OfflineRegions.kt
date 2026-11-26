package com.rutacomercial.app.maps

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.offline.OfflineManager
import org.maplibre.android.offline.OfflineRegion
import org.maplibre.android.offline.OfflineRegionError
import org.maplibre.android.offline.OfflineRegionStatus
import org.maplibre.android.offline.OfflineTilePyramidRegionDefinition
import kotlin.math.cos

data class OfflineRegionItem(
    val id: Long,
    val name: String,
    val complete: Boolean = false,
    val downloading: Boolean = false,
    val bytes: Long = 0,
    val tiles: Long = 0,
    val progress: Float = 0f,
    val note: String = "Consultando estado…",
)

/** Main-thread facade. Downloads are opt-in, small and persisted by MapLibre's offline database. */
class OfflineRegions private constructor(context: Context) {
    private val manager: OfflineManager
    private val handles = mutableMapOf<Long, OfflineRegion>()
    private val mutable = MutableStateFlow<List<OfflineRegionItem>>(emptyList())
    val regions: StateFlow<List<OfflineRegionItem>> = mutable.asStateFlow()
    private val mutableMessage = MutableStateFlow("Las regiones completas se conservan en este teléfono.")
    val message: StateFlow<String> = mutableMessage.asStateFlow()
    private val mutableBusy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = mutableBusy.asStateFlow()
    private var activeId: Long? = null

    init { MapBootstrap.initialize(context); manager = OfflineManager.getInstance(context.applicationContext) }

    fun refresh() {
        manager.listOfflineRegions(object : OfflineManager.ListOfflineRegionsCallback {
            override fun onList(offlineRegions: Array<OfflineRegion>?) {
                val list = offlineRegions.orEmpty()
                handles.clear()
                mutable.value = list.map { region ->
                    handles[region.id] = region
                    OfflineRegionItem(region.id, runCatching { JSONObject(String(region.metadata, Charsets.UTF_8)).optString("name", "Región ${region.id}") }.getOrDefault("Región ${region.id}"))
                }
                list.forEach { watch(it); it.getStatus(object : OfflineRegion.OfflineRegionStatusCallback {
                    override fun onStatus(status: OfflineRegionStatus?) { status?.let { statusValue -> update(it, statusValue) } }
                    override fun onError(error: String?) { mutableMessage.value = "No se pudo consultar la región: $error" }
                }) }
            }
            override fun onError(error: String) { mutableMessage.value = "No se pudieron leer los mapas: $error" }
        })
    }

    fun download(center: MapCoordinate, name: String) {
        if (mutableBusy.value) return
        if (!center.latitude.isFinite() || !center.longitude.isFinite() || center.latitude !in -80.0..80.0 || center.longitude !in -179.9..179.9) {
            mutableMessage.value = "La descarga está disponible entre 80° S y 80° N, fuera del antimeridiano."
            return
        }
        val latitudeHalf = 500.0 / 111_320.0
        val longitudeHalf = latitudeHalf / cos(Math.toRadians(center.latitude))
        val bounds = LatLngBounds.Builder().include(LatLng(center.latitude - latitudeHalf, center.longitude - longitudeHalf))
            .include(LatLng(center.latitude + latitudeHalf, center.longitude + longitudeHalf)).build()
        downloadBounds(bounds, center, name)
    }

    fun download(polygon: List<MapCoordinate>, name: String) {
        val area = DownloadArea.from(polygon)
        if (area == null || !area.downloadable) {
            mutableMessage.value = "Para descargar, elegí una zona de hasta 1 km de ancho y 1 km de alto."
            return
        }
        val bounds = LatLngBounds.Builder().include(LatLng(area.south, area.west)).include(LatLng(area.north, area.east)).build()
        downloadBounds(bounds, area.center, name)
    }

    private fun downloadBounds(bounds: LatLngBounds, center: MapCoordinate, name: String) {
        if (mutableBusy.value) return
        val definition = OfflineTilePyramidRegionDefinition(MapBootstrap.STYLE_URL, bounds, 12.0, 16.0, 1f, false)
        val metadata = JSONObject().put("name", name.trim().ifBlank { "Región ${System.currentTimeMillis()}" }.take(80))
            .put("latitude", center.latitude).put("longitude", center.longitude).put("createdAt", System.currentTimeMillis()).toString().toByteArray(Charsets.UTF_8)
        mutableBusy.value = true
        manager.createOfflineRegion(definition, metadata, object : OfflineManager.CreateOfflineRegionCallback {
            override fun onCreate(region: OfflineRegion) {
                handles[region.id] = region
                mutable.value = mutable.value + OfflineRegionItem(region.id, JSONObject(String(metadata, Charsets.UTF_8)).getString("name"))
                watch(region)
                begin(region)
            }
            override fun onError(error: String) { mutableBusy.value = false; mutableMessage.value = "No se pudo preparar la región: $error" }
        })
    }

    fun resume(id: Long) {
        if (mutableBusy.value) return
        val item = mutable.value.firstOrNull { it.id == id } ?: return
        if (item.complete || item.bytes >= 40_000_000L || item.tiles >= 2400) {
            mutableMessage.value = "La región ya está completa o alcanzó su límite seguro."
            return
        }
        handles[id]?.let { begin(it) }
    }

    private fun begin(region: OfflineRegion) {
        DownloadBudget.current = DownloadBudget()
        activeId = region.id
        mutableBusy.value = true
        mutate(region.id) { it.copy(downloading = true, note = "Descargando…") }
        mutableMessage.value = "Descargando el área elegida. Podés pausarla y continuar después."
        region.setDownloadState(OfflineRegion.STATE_ACTIVE)
    }

    fun pause(id: Long) {
        handles[id]?.setDownloadState(OfflineRegion.STATE_INACTIVE)
        if (activeId == id) { activeId = null; mutableBusy.value = false; DownloadBudget.current = null }
        mutate(id) { it.copy(downloading = false, note = if (it.complete) "Disponible sin conexión" else "Incompleta · descarga pausada") }
    }

    fun delete(id: Long) {
        pause(id)
        handles[id]?.delete(object : OfflineRegion.OfflineRegionDeleteCallback {
            override fun onDelete() { handles.remove(id); mutable.value = mutable.value.filterNot { it.id == id }; mutableMessage.value = "Mapa descargado eliminado. Los registros comerciales y GPS se conservaron." }
            override fun onError(error: String) { mutableMessage.value = "No se pudo borrar el mapa: $error" }
        })
    }

    private fun watch(region: OfflineRegion) {
        region.setObserver(object : OfflineRegion.OfflineRegionObserver {
            override fun onStatusChanged(status: OfflineRegionStatus) = update(region, status)
            override fun onError(error: OfflineRegionError) {
                pause(region.id)
                mutableMessage.value = "Descarga interrumpida: ${error.message}. Revisá internet y reanudá cuando esté disponible."
            }
            override fun mapboxTileCountLimitExceeded(limit: Long) {
                pause(region.id); mutableMessage.value = "Se alcanzó el límite de teselas. La región quedó incompleta."
            }
        })
    }

    private fun update(region: OfflineRegion, status: OfflineRegionStatus) {
        val complete = status.isComplete
        mutate(region.id) { it.copy(complete = complete,
            downloading = status.downloadState == OfflineRegion.STATE_ACTIVE && !complete,
            bytes = status.completedResourceSize, tiles = status.completedTileCount,
            progress = if (status.requiredResourceCount > 0) (status.completedResourceCount.toDouble() / status.requiredResourceCount).toFloat().coerceIn(0f, 1f) else 0f,
            note = if (complete) "Disponible sin conexión" else "Incompleta · requiere conexión para terminar") }
        if (complete) {
            if (activeId == region.id) {
                region.setDownloadState(OfflineRegion.STATE_INACTIVE)
                activeId = null; mutableBusy.value = false; DownloadBudget.current = null
                mutableMessage.value = "Región completa. Ya podés usarla sin internet entre niveles 12 y 16."
            }
        } else if (status.downloadState == OfflineRegion.STATE_ACTIVE && (status.completedResourceSize >= 40_000_000L || status.completedTileCount >= 2400)) {
            pause(region.id)
            mutableMessage.value = "Descarga detenida antes del límite del proveedor. Esta región no está completa; elegí otra zona."
        }
    }

    private fun mutate(id: Long, transform: (OfflineRegionItem) -> OfflineRegionItem) { mutable.value = mutable.value.map { if (it.id == id) transform(it) else it } }

    companion object {
        @Volatile private var instance: OfflineRegions? = null
        fun get(context: Context): OfflineRegions = instance ?: synchronized(this) { instance ?: OfflineRegions(context.applicationContext).also { instance = it } }
    }
}

@Composable
fun OfflineRegionsPanel(center: MapCoordinate?, modifier: Modifier = Modifier, polygon: List<MapCoordinate> = emptyList(), suggestedName: String = "") {
    val context = LocalContext.current
    val controller = remember { OfflineRegions.get(context) }
    val regions by controller.regions.collectAsState()
    val message by controller.message.collectAsState()
    val busy by controller.busy.collectAsState()
    var name by rememberSaveable(suggestedName) { mutableStateOf(suggestedName.ifBlank { "Mi zona" }) }
    val area = remember(polygon) { DownloadArea.from(polygon) }
    var deleting by remember { mutableStateOf<OfflineRegionItem?>(null) }
    LaunchedEffect(controller) { controller.refresh() }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Mapas sin conexión", style = MaterialTheme.typography.titleLarge)
        Text(if (area != null) "Área de ${area.widthM.toInt()} × ${area.heightM.toInt()} m. Se guarda el rectángulo que abarca la zona elegida." else "Se guarda 1 km × 1 km alrededor del punto elegido.")
        Text("Tamaño variable según la zona. La descarga se pausa al alcanzar 40 MB almacenados; solo un mapa completo está listo para usar sin internet.", style = MaterialTheme.typography.bodySmall)
        if (area != null && !area.downloadable) Text("Esta zona es demasiado grande para una descarga. Elegí un sector de hasta 1 km de ancho y alto.")
        if (center == null) Text("Elegí un punto en Mapa o usá Mi ubicación para preparar una descarga.")
        OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Nombre de la región") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Button(onClick = { if (area != null) controller.download(polygon, name) else center?.let { controller.download(it, name) } }, enabled = (area?.downloadable ?: (center != null)) && name.isNotBlank() && !busy, modifier = Modifier.fillMaxWidth()) { Text("Guardar mapa sin internet") }
        Text(message, style = MaterialTheme.typography.bodyMedium)
        regions.forEach { item ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(item.name, style = MaterialTheme.typography.titleMedium)
                    Text(item.note)
                    Text("${item.tiles} teselas · ${"%.1f".format(item.bytes / 1_000_000.0)} MB")
                    if (item.downloading) LinearProgressIndicator(progress = { item.progress }, modifier = Modifier.fillMaxWidth())
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (!item.complete) OutlinedButton(onClick = { if (item.downloading) controller.pause(item.id) else controller.resume(item.id) }, enabled = item.downloading || !busy) { Text(if (item.downloading) "Pausar" else "Reanudar") }
                        TextButton(onClick = { deleting = item }) { Text("Eliminar mapa") }
                    }
                }
            }
        }
        Text("Fuente: OpenFreeMap. No se descargan teselas de los servidores públicos de OpenStreetMap. Sin un mapa disponible, GPS, jornadas y visitas siguen funcionando.", style = MaterialTheme.typography.bodySmall)
    }
    deleting?.let { item ->
        AlertDialog(onDismissRequest = { deleting = null }, title = { Text("¿Eliminar ${item.name}?") },
            text = { Text("Se elimina solo el mapa descargado de esta región. Tus jornadas, sectores y comercios se conservan.") },
            confirmButton = { TextButton(onClick = { controller.delete(item.id); deleting = null }) { Text("Eliminar mapa") } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancelar") } })
    }
}
