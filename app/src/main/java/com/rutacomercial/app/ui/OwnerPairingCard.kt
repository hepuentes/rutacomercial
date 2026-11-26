package com.rutacomercial.app.ui

import android.content.SharedPreferences
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.journeyapps.barcodescanner.BarcodeEncoder
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import org.json.JSONObject

@Composable
internal fun OwnerPairingCard(task:JSONObject, close:()->Unit) {
    val qr=remember(task) { BarcodeEncoder().encodeBitmap(OwnerIdentity.qr(task),BarcodeFormat.QR_CODE,480,480).asImageBitmap() }
    AlertDialog(onDismissRequest=close,title={Text("QR y código de tarea")},text={
        Column(horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(8.dp)) {
            Text("En el otro teléfono, abrí la tarea y tocá Escanear QR.")
            Image(qr,"QR de esta tarea",Modifier.fillMaxWidth().height(185.dp))
            HorizontalDivider()
            Text("Sin cámara, dictale el código de esta tarea.")
            Text(OwnerIdentity.code(task),style=MaterialTheme.typography.headlineLarge)
        }
    },confirmButton={TextButton(onClick=close) { Text("Listo") }})
}

@Composable
internal fun PairFirstTask(task:JSONObject,prefs:SharedPreferences,confirm:()->Unit) {
    var manual by remember(task) { mutableStateOf(false) }
    var digits by remember(task) { mutableStateOf("") }
    var error by remember(task) { mutableStateOf("") }
    val scan=rememberLauncherForActivityResult(ScanContract()) { result ->
        if(result.contents?.let { OwnerIdentity.matchesQr(task,it) }==true) confirm()
        else if(result.contents!=null) error="Ese QR no corresponde a esta tarea."
        else { manual=true;error="" }
    }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            Text("Confirmá la tarea",style=MaterialTheme.typography.titleLarge)
            Text("Escaneá el QR de esta asignación o ingresá el código que te dicta el Owner.")
            if(!manual) {
                Button(onClick={
                    scan.launch(ScanOptions().setOrientationLocked(false).setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                        .setPrompt("Apuntá al QR del Owner").setBeepEnabled(false))
                }) { Text("Escanear QR") }
                TextButton(onClick={ manual=true;error="" }) { Text("Usar código de 4 dígitos") }
            } else {
                OutlinedTextField(value=digits,onValueChange={ digits=it.filter { c->c in '0'..'9' }.take(4);error="" },
                    label={Text("Código que te dicta el Owner")},singleLine=true,
                    keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.NumberPassword))
                Button(enabled=digits.length==4,onClick={
                    val now=System.currentTimeMillis()
                    when {
                        now<prefs.getLong("pairRetryAfter",0L) -> error="Esperá un minuto antes de volver a probar."
                        OwnerIdentity.matchesPin(task,digits) -> {
                            prefs.edit().remove("pairAttempts").remove("pairRetryAfter").apply()
                            confirm()
                        }
                        else -> {
                            val attempts=prefs.getInt("pairAttempts",0)+1
                            prefs.edit().putInt("pairAttempts",attempts%3)
                                .putLong("pairRetryAfter",if(attempts%3==0) now+60000 else 0L).apply()
                            error=if(attempts%3==0) "Esperá un minuto y revisá el código con el Owner." else "Revisá el código de esta tarea."
                        }
                    }
                }) { Text("Confirmar código") }
                TextButton(onClick={manual=false;error=""}) { Text("Usar cámara") }
            }
            if(error.isNotEmpty()) Text(error,color=MaterialTheme.colorScheme.error)
        }
    }
}
