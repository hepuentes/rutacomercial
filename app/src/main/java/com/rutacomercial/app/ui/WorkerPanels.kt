package com.rutacomercial.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rutacomercial.app.data.Journey
import com.rutacomercial.app.data.activeDuration
import kotlinx.coroutines.delay

private val Walking = Color(0xFF1B6B3A)
private val Paused = Color(0xFF9A5B00)

/** Large state, time and distance so a worker on the street reads it at a glance. */
@Composable
internal fun WorkerJourneyPanel(journey: Journey, gpsMessage: String, syncStatus: String?,
    onPause: () -> Unit, onResume: () -> Unit, onFinish: () -> Unit) {
    val paused = journey.state == "PAUSED"
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(journey.state) { while (!paused) { now = System.currentTimeMillis(); delay(1000) } }
    var confirmFinish by remember { mutableStateOf(false) }
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceVariant) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(if (paused) "EN PAUSA" else "RECORRIENDO", color = if (paused) Paused else Walking,
                fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelLarge)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Metric(clock(journey.activeDuration(now)), "tiempo")
                Metric(number(journey.distanceM / 1000, 2) + " km", "recorrido")
            }
            if (gpsMessage.isNotBlank()) Text(gpsMessage, style = MaterialTheme.typography.bodySmall)
            syncStatus?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = if (paused) onResume else onPause, modifier = Modifier.weight(1f).heightIn(min = 64.dp)) {
                    Text(if (paused) "Reanudar" else "Pausar", fontSize = 18.sp)
                }
                Button(onClick = { confirmFinish = true }, modifier = Modifier.weight(1f).heightIn(min = 64.dp)) {
                    Text("Terminar", fontSize = 18.sp)
                }
            }
        }
    }
    // A mistaken tap would close the day early; Pausar is the safe action.
    if (confirmFinish) AlertDialog(onDismissRequest = { confirmFinish = false },
        title = { Text("¿Terminar el recorrido de hoy?") },
        text = { Text("Se guarda y se envía al Owner. Si solo vas a descansar, tocá Pausar.") },
        dismissButton = { TextButton(onClick = { confirmFinish = false }) { Text("Seguir") } },
        confirmButton = { Button(onClick = { confirmFinish = false; onFinish() }) { Text("Terminar") } })
}

@Composable
internal fun WorkerFinishedPanel(journey: Journey, syncStatus: String?, onNewDay: (() -> Unit)?, onShare: () -> Unit) {
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceVariant) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("RECORRIDO TERMINADO", color = Walking, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelLarge)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Metric(hours(journey.activeMs), "tiempo")
                Metric(number(journey.distanceM / 1000, 2) + " km", "recorrido")
            }
            syncStatus?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            onNewDay?.let { Button(onClick = it, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) { Text("Nueva jornada en esta zona") } }
            TextButton(onClick = onShare) { Text(if (syncStatus == null) "Enviar recorrido" else "Compartir copia por WhatsApp") }
        }
    }
}

@Composable
private fun Metric(value: String, label: String) {
    Column(horizontalAlignment = Alignment.Start) {
        Text(value, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text(label, style = MaterialTheme.typography.labelMedium)
    }
}

/** A running clock (h:mm:ss) so the worker sees the day is being counted. */
internal fun clock(ms: Long): String { val t = ms / 1000; return "%d:%02d:%02d".format(t / 3600, t / 60 % 60, t % 60) }

/** The journey's own delivery state in plain words, never a stale global event. */
internal fun workerSyncText(delivered: Boolean, finished: Boolean): String = when {
    delivered && finished -> "✓ Enviado al Owner"
    delivered -> "✓ El Owner ve tu avance"
    else -> "Guardado en el teléfono · se envía apenas haya señal"
}
