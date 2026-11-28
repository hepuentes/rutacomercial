package com.rutacomercial.app.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.rutacomercial.app.data.*
import com.rutacomercial.app.maps.*
import com.rutacomercial.app.tracking.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RutaApp(repository: Repository, onExport: (format: String, journeyId: String?) -> Unit,
    onBackup: (password: String) -> Unit, onRestore: (password: String) -> Unit,
    mapRequest:Int=0, sharedMapText: String? = null, onSharedMapConsumed: () -> Unit = {}) {
    val vm: RutaViewModel = viewModel(factory = remember(repository) { RutaViewModel.factory(repository) })
    val data by vm.data.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val tracking by TrackingRuntime.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    var page by rememberSaveableState("Mapa")
    var selectedBusiness by remember { mutableStateOf<String?>(null) }
    var selectedJourney by remember { mutableStateOf<String?>(null) }
    var mapFocus by remember { mutableStateOf<MapCoordinate?>(null) }
    var sharedZone by remember { mutableStateOf<ZoneRequest?>(null) }
    var pasteLocation by remember { mutableStateOf(false) }
    var selectedMapPoint by remember { mutableStateOf<MapCoordinate?>(null) }
    var showEnd by remember { mutableStateOf(false) }
    LaunchedEffect(mapRequest) { if(mapRequest>0) { page="Mapa"; selectedJourney=null;selectedBusiness=null;mapFocus=null } }
    val locationAction = rememberLocationAction(vm)
    fun begin(resume: Boolean = false) = locationAction(if (resume) "resume" else "start")
    fun locate() = locationAction("locate")
    LaunchedEffect(vm) { vm.events.collect { snackbar.showSnackbar(it) } }
    LaunchedEffect(repository) {
        try { TrackingController.recoverIfNeeded(context) }
        catch(e:Exception){vm.message(e.message ?: "No se pudo comprobar la jornada anterior.")}
    }
    BackHandler(page != "Mapa" || selectedBusiness != null) {
        if(selectedBusiness != null) selectedBusiness = null else page = "Mapa"
    }
    RutaTheme {
        Scaffold(topBar = {
            if (page != "Mapa" || selectedBusiness != null) {
            TopAppBar(title = { Column { Text("RutaComercial", fontWeight = FontWeight.Bold)
                Text(if(selectedBusiness != null) "Ficha del establecimiento" else page, style = MaterialTheme.typography.labelMedium) } },
                actions = { TextButton(onClick = { if(selectedBusiness != null) selectedBusiness = null else page = "Mapa" }) { Text("Volver") } })
            }
        }, snackbarHost = { SnackbarHost(snackbar) }, bottomBar = {
            NavigationBar(modifier=Modifier.navigationBarsPadding().height(60.dp), windowInsets=WindowInsets(0,0,0,0)) {
                listOf("Inicio" to "⌂", "Mapa" to "◎", "Negocios" to "▣", "Más" to "☰").forEach { (name, symbol) ->
                    NavigationBarItem(selected = page == name, onClick = { page = name; selectedBusiness = null; if(name == "Mapa") {selectedJourney = null;mapFocus=null} },
                        icon = { Text(symbol, style = MaterialTheme.typography.titleLarge) }, label = { Text(name) })
                }
            }
        }) { padding ->
            Column(Modifier.fillMaxSize().padding(padding)) {
                if(busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                Box(Modifier.weight(1f)) {
                    if(selectedBusiness != null) {
                        val business = data.visibleBusinesses.firstOrNull { it.id == selectedBusiness }
                        if(business != null) BusinessDetail(vm, data, business, tracking,
                            onMap = { mapFocus=MapCoordinate(business.latitude,business.longitude);selectedJourney=null;selectedBusiness = null; page = "Mapa" },onOpenBusiness={selectedBusiness=it})
                        else InfoCard("Establecimiento no disponible", "Puede haber sido fusionado con otra ficha.")
                    } else when(page) {
                        "Inicio" -> {
                            val now by vm.now.collectAsStateWithLifecycle()
                            DashboardScreen(data, now, onStart = { page = "Mapa" }, onMap = { page = "Mapa" }, onOpen = { selectedBusiness = it })
                        }
                        "Mapa" -> key(mapRequest) { MapScreen(vm, data, tracking, selectedJourney, mapFocus, onStart = { begin() },
                            onPause = { TrackingController.pause(context) }, onResume = { begin(true) }, onFinish = { showEnd = true },
                            onBusiness = { selectedBusiness = it }, onLive = { selectedJourney = null;mapFocus=null },onLocate={locate()},onPointSelected={selectedMapPoint=it},
                            zoneRequest = sharedZone, onZoneRequestConsumed = { sharedZone = null }, onImportLocation = { pasteLocation = true }) }
                        "Negocios" -> BusinessesScreen(vm, data, tracking.position, onOpen = { selectedBusiness = it }, onPickMap = { page = "Mapa" })
                        "Sectores" -> SectorsScreen(vm, data, tracking.position)
                        "Campañas" -> CampaignsScreen(vm, data)
                        "Historial" -> HistoryScreen(vm, data, onJourney = { selectedJourney = it; page = "Mapa" }, onExport = onExport)
                        "Recorrido planificado" -> RouteScreen(vm, data, tracking.position, onOpen = { selectedBusiness = it })
                        "Mapas sin conexión" -> ScreenColumn {
                            PageHeading("Mapas sin conexión", "Descargá una región pequeña antes de salir. El GPS y los registros funcionan sin internet.")
                            Text("Tocá el mapa para elegir el centro de la región, incluso si todavía no estás allí.")
                            TerritoryMap(Modifier.fillMaxWidth().mapHeight(.5f),currentLocation=tracking.position,
                                center=selectedMapPoint ?: tracking.position,onMapTap={selectedMapPoint=it},
                                draftPolygon=selectedMapPoint?.let{listOf(it)} ?: emptyList())
                            OutlinedButton(onClick={selectedMapPoint=null;locate()},modifier=Modifier.fillMaxWidth()){Text("Usar mi ubicación")}
                            OfflineRegionsPanel(center = selectedMapPoint ?: tracking.position)
                        }
                        "Configuración" -> SettingsScreen(vm, data, onBackup, onRestore, onExport, onPermissions = {
                            context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")))
                        })
                        else -> MoreScreen { page = it }
                    }
                }
            }
        }
        if(showEnd) ConfirmDialog("Finalizar jornada", "Se detendrá el GPS y se guardará el resumen. Las visitas y entregas declaradas se conservarán por separado del recorrido observado.",
            "Finalizar", { showEnd = false }) {
            TrackingController.finish(context); showEnd = false; page = "Historial"
        }
        if (sharedMapText != null || pasteLocation) SharedLocationDialog(sharedMapText ?: "",
            onDismiss = { pasteLocation = false; onSharedMapConsumed() },
            onUse = { location ->
                selectedJourney = null; selectedBusiness = null; mapFocus = null; page = "Mapa"
                sharedZone = ZoneRequest(point = location.point)
                pasteLocation = false; onSharedMapConsumed()
            })
    }
}

@Composable
private fun rememberSaveableState(initial: String) = androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(initial) }

@Composable
private fun MoreScreen(onNavigate: (String) -> Unit) = ScreenColumn {
    PageHeading("Tu operación", "Organizá el territorio, el material y las próximas visitas.")
    val context = LocalContext.current
    if (!com.rutacomercial.app.BuildConfig.WORKER_EDITION) OutlinedCard(onClick = { context.startActivity(Intent(context, AssignmentActivity::class.java)) }, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Asignaciones y repartidores", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleMedium)
            Text("Vincular repartidores, asignar zonas, pagos y campañas", style = MaterialTheme.typography.bodyMedium)
        }
    }
    listOf("Sectores" to "Ciudades, barrios y polígonos de trabajo", "Campañas" to "Material disponible, asignado y entregado",
        "Recorrido planificado" to "Ordená manualmente tus próximas paradas", "Historial" to "Jornadas, incidencias e informes",
        "Mapas sin conexión" to "Regiones descargadas para salir a caminar", "Configuración" to "Permisos, privacidad y respaldos").forEach { (title, subtitle) ->
        OutlinedCard(onClick = { onNavigate(title) }, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleMedium)
                Text(subtitle, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}
