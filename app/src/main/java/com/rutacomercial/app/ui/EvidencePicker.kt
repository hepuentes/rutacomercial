package com.rutacomercial.app.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.ByteArrayOutputStream
import java.util.UUID
import kotlin.math.max

@Composable
internal fun EvidencePicker(attachmentPath: String?, editable: Boolean = true, onValue: (String?) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var pickerError by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if(uri != null) scope.launch {
            loading = true; pickerError = null
            try {
                val path = withContext(Dispatchers.IO) {
                    val bytes = context.contentResolver.openInputStream(uri)?.use { stream ->
                        val output = ByteArrayOutputStream()
                        val buffer = ByteArray(8192)
                        var total = 0
                        while(true) {
                            val read = stream.read(buffer)
                            if(read < 0) break
                            total += read
                            require(total <= 10 * 1024 * 1024) { "La foto debe pesar menos de 10 MB." }
                            output.write(buffer, 0, read)
                        }
                        output.toByteArray()
                    } ?: throw IllegalArgumentException("No se pudo leer la fotografía.")
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                    require(bounds.outWidth > 0 && bounds.outHeight > 0) { "Seleccioná una imagen válida." }
                    var sample = 1
                    while(max(bounds.outWidth, bounds.outHeight) / sample > 2400) sample *= 2
                    val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
                        ?: throw IllegalArgumentException("No se pudo abrir la imagen.")
                    val scale = minOf(1.0, 1600.0 / max(bitmap.width, bitmap.height))
                    val resized = if(scale < 1) Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt().coerceAtLeast(1), (bitmap.height * scale).toInt().coerceAtLeast(1), true) else bitmap
                    val directory = File(context.filesDir, "evidence").apply { mkdirs() }
                    val file = File(directory, "${UUID.randomUUID()}.jpg")
                    file.outputStream().use { require(resized.compress(Bitmap.CompressFormat.JPEG, 85, it)) { "No se pudo guardar la foto." } }
                    if(resized !== bitmap) resized.recycle()
                    bitmap.recycle()
                    "evidence/${file.name}"
                }
                onValue(path)
            } catch(e: Exception) { pickerError = e.message ?: "No se pudo adjuntar la imagen." }
            finally { loading = false }
        }
    }
    Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
        Text("Fotografía opcional", style=MaterialTheme.typography.labelLarge)
        if(attachmentPath != null) {
            val preview by produceState<Bitmap?>(null,attachmentPath) {
                value = withContext(Dispatchers.IO) {
                    val file = File(context.filesDir,attachmentPath)
                    if(file.exists()) BitmapFactory.decodeFile(file.path,BitmapFactory.Options().apply{inSampleSize=4}) else null
                }
            }
            preview?.let { Image(it.asImageBitmap(), "Fotografía adjunta", Modifier.fillMaxWidth().heightIn(max=160.dp)) }
            Text("Fotografía guardada en el teléfono",style=MaterialTheme.typography.bodySmall)
        }
        if(editable) {
            OutlinedButton(onClick={picker.launch(arrayOf("image/*"))},enabled=!loading,modifier=Modifier.fillMaxWidth()) { Text(if(loading) "Preparando imagen…" else if(attachmentPath == null) "Adjuntar fotografía" else "Cambiar fotografía") }
            if(attachmentPath != null) TextButton(onClick={onValue(null)}) { Text("Quitar de esta ficha") }
            Text("Evitá rostros, documentos y datos privados. Se guarda una copia reducida sin metadatos EXIF.",style=MaterialTheme.typography.bodySmall)
        }
        pickerError?.let { Text(it,color=MaterialTheme.colorScheme.error) }
    }
}
