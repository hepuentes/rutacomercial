package com.rutacomercial.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.rutacomercial.app.maps.SharedMapLocation
import com.rutacomercial.app.maps.SharedMapResolver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

@Composable
internal fun SharedLocationDialog(text: String, onDismiss: () -> Unit, onUse: (SharedMapLocation) -> Unit) {
    var input by remember(text) { mutableStateOf(text) }
    var working by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var result by remember(text) { mutableStateOf<SharedMapLocation?>(null) }
    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current
    val resolver = remember(context) { SharedMapResolver { name ->
        @Suppress("DEPRECATION")
        android.location.Geocoder(context).getFromLocationName(name,1)?.firstOrNull()?.let { com.rutacomercial.app.maps.MapCoordinate(it.latitude,it.longitude) }
    } }
    fun resolve() {
        working = true; error = null; result = null
        scope.launch {
            try { result = withContext(Dispatchers.IO) { resolver.resolve(input) } }
            catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) { error = if (e is java.io.IOException) "No se pudo abrir el enlace. Revisá internet o pegá las coordenadas." else e.message }
            finally { working = false }
        }
    }
    LaunchedEffect(text) { if (text.isNotBlank()) resolve() }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Ubicación de Google Maps") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("En Google Maps buscá el lugar o mantené pulsado para poner un pin. Tocá Compartir y elegí RutaComercial.")
            if (result == null) {
                OutlinedTextField(input, { input = it.take(8192); error = null }, enabled = !working,
                    label = { Text("Enlace o latitud, longitud") }, modifier = Modifier.fillMaxWidth(), maxLines = 3)
                if (working) { LinearProgressIndicator(Modifier.fillMaxWidth()); Text("Buscando el punto…") }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            } else {
                val location = result!!
                Text(String.format(Locale.US, "%.6f, %.6f", location.point.latitude, location.point.longitude))
                Text(if (location.approximate) "Ubicación aproximada. Revisá el lugar en el mapa antes de guardar." else "Revisá el punto y ajustá el recuadro para elegir unas cuadras. La zona se guarda al tocar Usar esta zona.")
            }
        }
    }, confirmButton = {
        TextButton(onClick = { result?.let(onUse) ?: resolve() }, enabled = !working && input.isNotBlank()) {
            Text(if (result == null) "Buscar punto" else "Delimitar zona aquí")
        }
    }, dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } })
}
