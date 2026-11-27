package com.rutacomercial.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rutacomercial.app.data.Journey
import com.rutacomercial.app.data.activeDuration
import kotlinx.coroutines.delay

private val Walking = Color(0xFF1B6B3A)
private val Paused = Color(0xFF9A5B00)

/** Compact so the map keeps most of the screen; still large enough to read while walking. */
@Composable
internal fun WorkerJourneyPanel(journey: Journey, gpsMessage: String, syncStatus: String?,
    onPause: () -> Unit, onResume: () -> Unit, onFinish: () -> Unit, unpaidNote: String? = null) {
    val paused = journey.state == "PAUSED"
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(journey.state) { while (!paused) { now = System.currentTimeMillis(); delay(1000) } }
    var confirmFinish by remember { mutableStateOf(false) }
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceVariant) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.Bottom) {
                Column(Modifier.weight(1f)) {
                    Text(if (paused) "EN PAUSA" else "RECORRIENDO", color = if (paused) Paused else Walking,
                        fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium)
                    Text(clock(journey.activeDuration(now)), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                }
                Text(number(journey.distanceM / 1000, 2) + " km", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            }
            unpaidNote?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary) }
            val detail = listOfNotNull(syncStatus, gpsMessage.takeIf { it.isNotBlank() }).joinToString(" · ")
            if (detail.isNotEmpty()) Text(detail, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = if (paused) onResume else onPause, modifier = Modifier.weight(1f).heightIn(min = 56.dp)) {
                    Text(if (paused) "Reanudar" else "Pausar", fontSize = 18.sp)
                }
                Button(onClick = { confirmFinish = true }, modifier = Modifier.weight(1f).heightIn(min = 56.dp)) {
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

/** syncStatus null means the task travels by file, so the manual send stays. */
@Composable
internal fun WorkerFinishedPanel(journey: Journey, syncStatus: String?, onNewDay: (() -> Unit)?, onShare: () -> Unit, unpaidNote: String? = null) {
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceVariant) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.Bottom) {
                Column(Modifier.weight(1f)) {
                    Text("TERMINADO", color = Walking, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium)
                    Text(hours(journey.activeMs), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                }
                Text(number(journey.distanceM / 1000, 2) + " km", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            }
            unpaidNote?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary) }
            syncStatus?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (syncStatus == null) FilledTonalButton(onClick = onShare, modifier = Modifier.weight(1f).heightIn(min = 52.dp)) { Text("Enviar recorrido") }
                onNewDay?.let { Button(onClick = it, modifier = Modifier.weight(1f).heightIn(min = 52.dp)) { Text("Nueva jornada") } }
            }
        }
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

/** What the worker reads the first time, and anytime from Opciones: the rules the Owner pays by. */
@Composable
internal fun HowTimeCounts(onDismiss: () -> Unit, confirm: String = "Entendido") {
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Cómo se cuenta tu tiempo") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("• Cuenta el tiempo que recorrés a pie con el recorrido activo.")
            Text("• Las pausas no cuentan. Si pasás 20 minutos sin moverte, se pausa solo.")
            Text("• Los tramos en vehículo (moto, auto, colectivo) no cuentan. Si dura más de 5 minutos, se pausa solo.")
            Text("• Una app de ubicación falsa o cambiar la hora del teléfono no suma tiempo.")
            Text("• Tu ubicación solo se registra mientras el recorrido está activo.")
            Text("• Tu Owner ve exactamente lo mismo que ves vos.")
        }
    }, confirmButton = { Button(onClick = onDismiss) { Text(confirm) } })
}

/** "No cuentan: 8 min en vehículo · Pagable 1 h 32 min", or null when everything counts. */
internal fun unpaidText(activeMs: Long, vehicleMs: Long, mockMs: Long, clockMs: Long): String? {
    val parts = listOfNotNull(
        minutes(vehicleMs).takeIf { vehicleMs >= 30_000 }?.let { "$it en vehículo" },
        minutes(mockMs).takeIf { mockMs >= 30_000 }?.let { "$it con ubicación simulada" },
        minutes(clockMs).takeIf { clockMs >= 30_000 }?.let { "$it por hora del teléfono" })
    if (parts.isEmpty()) return null
    return "No cuentan: " + parts.joinToString(", ") + " · Pagable " + hours((activeMs - vehicleMs - mockMs - clockMs).coerceAtLeast(0))
}
