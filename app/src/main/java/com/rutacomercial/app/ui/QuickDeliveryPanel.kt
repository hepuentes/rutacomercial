package com.rutacomercial.app.ui

import android.content.Context
import android.os.SystemClock
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rutacomercial.app.data.*
import com.rutacomercial.app.domain.Inventory
import com.rutacomercial.app.tracking.TrackingStatus
import java.time.LocalDate
import java.time.ZoneId

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun QuickDeliveryPanel(vm: RutaViewModel, data: DataSnapshot, tracking: TrackingStatus, sectorId: String?) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("quick_delivery", Context.MODE_PRIVATE) }
    var selected by rememberSaveable { mutableStateOf(prefs.getString("campaign", null)) }
    val campaign = data.campaigns.firstOrNull { it.id == selected } ?: data.campaigns.singleOrNull()
    val balance = remember(data.movements, campaign?.id) { Inventory.balance(data.movements.filter { it.campaignId == campaign?.id }) }
    val busy by vm.busy.collectAsStateWithLifecycle()
    val haptic = LocalHapticFeedback.current
    var lastTap by remember { mutableLongStateOf(-1_000) }
    var options by remember { mutableStateOf(false) }
    var create by remember { mutableStateOf(false) }
    var name by rememberSaveable { mutableStateOf("") }
    var quantity by rememberSaveable { mutableStateOf("") }
    var assign by rememberSaveable { mutableStateOf("") }
    val today = LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
    val recent = data.activeQuickDeliveries.filter { it.at >= today }
    val last = recent.maxByOrNull { it.at }
    fun choose(id: String) { selected = id; prefs.edit().putString("campaign", id).apply() }
    Button(onClick = {
        if (campaign == null || balance.carried == 0L) { options = true; return@Button }
        val elapsed = SystemClock.elapsedRealtime()
        if (elapsed - lastTap < 600) return@Button
        lastTap = elapsed
        val now = nowMillis()
        val usable = tracking.position != null && tracking.fixAt != null && now - tracking.fixAt in 0..30_000 &&
            tracking.accuracyMeters?.let { it.isFinite() && it in 0.01f..100f } == true
        val delivery = QuickDelivery(campaignId=campaign.id, journeyId=data.activeJourney?.id, sectorId=sectorId,
            at=now, latitude=if(usable) tracking.position?.latitude else null, longitude=if(usable) tracking.position?.longitude else null,
            accuracyMeters=if(usable) tracking.accuracyMeters else null, fixAt=if(usable) tracking.fixAt else null)
        vm.perform(if (usable) "1 tarjeta guardada" else "1 tarjeta guardada sin ubicación", {
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        }) { vm.repository.recordQuickDelivery(delivery) }
    }, enabled = !busy, modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp)) {
        Text(if (campaign == null) "Preparar entrega a un toque" else "Entregué 1 tarjeta · ${recent.size} hoy")
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        TextButton(onClick = { options = true }, modifier = Modifier.weight(1f)) {
            Text(campaign?.let { "${it.name} · ${balance.carried} disponibles" } ?: "Elegir material", maxLines = 1)
        }
        if (last != null) TextButton(onClick = { vm.perform("Entrega anulada; tarjeta reintegrada") { vm.repository.undoQuickDelivery(last.id) } }, enabled = !busy) { Text("Deshacer") }
    }
    if (options) ModalBottomSheet(onDismissRequest = { options = false }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Entregas a un toque", style = MaterialTheme.typography.titleLarge)
            Text("Cada toque guarda una tarjeta. Podés identificar el negocio después. Sin señal, se guarda sin punto en el mapa.")
            if (data.campaigns.isNotEmpty()) Choice("Material que repartís", campaign?.id, data.campaigns.map { it.id to it.name }) { it?.let(::choose) }
            campaign?.let { c ->
                Text("${balance.carried} para repartir · ${balance.depot} en depósito")
                if (balance.depot > 0) {
                    Field("Tarjetas del depósito que llevás hoy", assign, { assign = it }, numeric = true)
                    OutlinedButton(onClick = {
                        val count = assign.toIntOrNull() ?: 0
                        vm.perform("Tarjetas asignadas", { assign = ""; options = false }) {
                            vm.repository.moveStock(StockMovement(campaignId=c.id,type="ASSIGN",quantity=count))
                        }
                    }, enabled = !busy) { Text("Preparar para repartir") }
                }
            }
            OutlinedButton(onClick = { create = true }) { Text("Agregar material nuevo") }
            Text("Últimas entregas", style = MaterialTheme.typography.titleMedium)
            data.activeQuickDeliveries.take(20).forEach { q ->
                OutlinedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text("${dateText(q.at)} · 1 tarjeta")
                        Text(if(q.latitude == null) "Sin ubicación" else "Ubicación estimada ±${q.accuracyMeters?.toInt()} m", style = MaterialTheme.typography.bodySmall)
                        if (data.visibleBusinesses.isNotEmpty()) Choice("Negocio (opcional)", q.businessId,
                            listOf(null to "Sin identificar") + data.visibleBusinesses.map { it.id to it.name }) { business ->
                            if (business != null) vm.perform("Negocio asociado; cantidad sin cambios") { vm.repository.identifyQuickDelivery(q.id, business) }
                        }
                        TextButton(onClick = { vm.perform("Entrega anulada") { vm.repository.undoQuickDelivery(q.id) } }, enabled = !busy) { Text("Anular esta entrega") }
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
    if (create) EditorDialog("Preparar tarjetas", { create = false }, {
        vm.perform("Material listo para repartir", { create = false; options = false; name = ""; quantity = "" }) {
            val created = vm.repository.prepareQuickCampaign(name, quantity.toIntOrNull() ?: 0)
            choose(created.id)
        }
    }) {
        Field("Nombre del material o campaña", name, { name = it })
        Field("Cantidad de tarjetas que llevás", quantity, { quantity = it }, numeric = true)
    }
}
