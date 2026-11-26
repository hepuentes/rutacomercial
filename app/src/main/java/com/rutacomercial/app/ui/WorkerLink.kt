package com.rutacomercial.app.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.zxing.BarcodeFormat
import com.journeyapps.barcodescanner.BarcodeEncoder
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions

/** What the worker learns from the Owner's QR: where to send its code and which signature to trust. */
internal data class LinkInvite(val owner: String, val key: String)

private const val linkPrefix = "rutacomercial:vincular:1:"

internal fun linkQr(owner: String, key: String) = "$linkPrefix$owner:$key"

internal fun parseLinkQr(text: String, validKey: (String) -> Boolean = OwnerIdentity::validKey): LinkInvite? {
    if (!text.startsWith(linkPrefix)) return null
    val parts = text.removePrefix(linkPrefix).split(':')
    if (parts.size != 2 || !parts[0].matches(Regex("[A-Za-z0-9_-]{20,128}")) || parts[1].length > 400 || !validKey(parts[1])) return null
    return LinkInvite(parts[0], parts[1])
}

@Composable
internal fun OwnerLinkQrDialog(ownerUid: String, close: () -> Unit) {
    val qr = remember(ownerUid) {
        BarcodeEncoder().encodeBitmap(linkQr(ownerUid, OwnerIdentity.publicKey()), BarcodeFormat.QR_CODE, 600, 600).asImageBitmap()
    }
    AlertDialog(onDismissRequest = close, title = { Text("Vincular repartidor") }, text = {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("En el teléfono del repartidor, abrí RutaComercial Repartidor y tocá Escanear QR de mi Owner.")
            Image(qr, "QR para vincular repartidor", Modifier.fillMaxWidth().height(240.dp))
            Text("Se hace una sola vez por teléfono. Después aparece en tu lista de repartidores.", style = MaterialTheme.typography.bodySmall)
        }
    }, confirmButton = { TextButton(onClick = close) { Text("Listo") } })
}

/** One scan links the phone; WhatsApp stays as a fallback for phones without camera. */
@Composable
internal fun WorkerLinkCard(deviceId: String, onLink: (LinkInvite, String) -> Unit, onShareCode: () -> Unit) {
    var invite by remember { mutableStateOf<LinkInvite?>(null) }
    var name by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    val scan = rememberLauncherForActivityResult(ScanContract()) { result ->
        val text = result.contents ?: return@rememberLauncherForActivityResult
        invite = parseLinkQr(text)
        error = if (invite == null) "Ese QR no es el de vinculación del Owner. Pedile que toque Vincular repartidor." else ""
    }
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.primaryContainer) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Primer paso: vinculá este teléfono con tu Owner", style = MaterialTheme.typography.titleMedium)
            Text("Pedile que toque Vincular repartidor y escaneá el QR. Se hace una sola vez.")
            Button(onClick = {
                scan.launch(ScanOptions().setOrientationLocked(false).setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                    .setPrompt("Apuntá al QR del Owner").setBeepEnabled(false))
            }, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) { Text("Escanear QR de mi Owner", fontSize = 18.sp) }
            if (error.isNotEmpty()) Text(error, color = MaterialTheme.colorScheme.error)
            TextButton(enabled = deviceId.isNotBlank(), onClick = onShareCode) { Text("Sin cámara: enviar mi código por WhatsApp") }
        }
    }
    invite?.let { found ->
        AlertDialog(onDismissRequest = { invite = null }, title = { Text("¿Cómo te llamás?") }, text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Tu Owner te va a ver con este nombre.")
                OutlinedTextField(value = name, onValueChange = { name = it.take(100) }, label = { Text("Tu nombre") }, singleLine = true)
            }
        }, confirmButton = {
            Button(enabled = name.isNotBlank(), onClick = { invite = null; onLink(found, name.trim()) }) { Text("Vincular") }
        }, dismissButton = { TextButton(onClick = { invite = null }) { Text("Cancelar") } })
    }
}
