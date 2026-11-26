package com.rutacomercial.app

import android.net.Uri
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import com.rutacomercial.app.io.BackupManager
import com.rutacomercial.app.io.Exports
import com.rutacomercial.app.ui.RutaApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate

class MainActivity : com.rutacomercial.app.ui.TrackingMapActivity() {
    private val repository get() = (application as RutaApplication).repository
    private var exportFormat = "csv"
    private var exportJourney: String? = null
    private var backupPassword: CharArray? = null
    private var restorePassword: CharArray? = null
    private var transferBusy = false
    private var mapRequest by mutableStateOf(0)
    private var sharedMapText by mutableStateOf<String?>(null)

    private val createExport = registerForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        if (uri != null) transfer("Exportación guardada. Contiene datos personales: compartila con cuidado.") {
            val data = repository.snapshot()
            val text = when (exportFormat) {
                "gpx" -> Exports.gpx(data, exportJourney)
                "geojson" -> Exports.geoJson(data, exportJourney)
                "inventory_csv" -> Exports.inventoryCsv(data)
                else -> Exports.commercialCsv(data)
            }
            contentResolver.openOutputStream(uri, "wt")?.bufferedWriter(Charsets.UTF_8)?.use { it.write(text) }
                ?: error("No se pudo escribir el archivo.")
        }
    }
    private val createBackup = registerForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val password = backupPassword
        backupPassword = null
        if (uri != null && password != null) transfer("Respaldo cifrado guardado. Conservá su contraseña en un lugar seguro.") { BackupManager(this@MainActivity, repository).create(uri, password) }
        else {
            password?.fill('\u0000')
            if (uri != null) Toast.makeText(this, "La contraseña no se conserva al reiniciar la pantalla. Volvé a crear el respaldo.", Toast.LENGTH_LONG).show()
        }
    }
    private val openBackup = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val password = restorePassword
        restorePassword = null
        if (uri != null && password != null) transfer("Respaldo restaurado y validado. El rastreo permanece detenido.") { BackupManager(this@MainActivity, repository).restore(uri, password) }
        else {
            password?.fill('\u0000')
            if (uri != null) Toast.makeText(this, "Por seguridad, ingresá nuevamente la contraseña y elegí el respaldo otra vez.", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Avoid displaying precise location previews in Android's recent-apps list.
        if (android.os.Build.VERSION.SDK_INT >= 33) setRecentsScreenshotEnabled(false)
        exportFormat = savedInstanceState?.getString("exportFormat") ?: "csv"
        exportJourney = savedInstanceState?.getString("exportJourney")
        if (savedInstanceState == null) receiveSharedMap(intent)
        else sharedMapText = savedInstanceState.getString("sharedMapText")
        setContent { MapWindow {
            RutaApp(repository = repository, mapRequest=mapRequest,
                sharedMapText = sharedMapText, onSharedMapConsumed = { sharedMapText = null },
                onExport = { format, journey ->
                    if (!transferBusy) {
                        exportFormat = format; exportJourney = journey
                        val extension = if (format == "inventory_csv") "csv" else format
                        createExport.launch("RutaComercial_${format}_${LocalDate.now()}.$extension")
                    }
                },
                onBackup = { password ->
                    if (!transferBusy) { backupPassword = password.toCharArray(); createBackup.launch("RutaComercial_${LocalDate.now()}.rcb") }
                },
                onRestore = { password ->
                    if (!transferBusy) { restorePassword = password.toCharArray(); openBackup.launch(arrayOf("application/octet-stream", "*/*")) }
                }
            )
        } }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("sharedMapText", sharedMapText)
        outState.putString("exportFormat", exportFormat)
        outState.putString("exportJourney", exportJourney)
        super.onSaveInstanceState(outState)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if(intent.getBooleanExtra("returnMap",false)) mapRequest++
        // The transient share is saved in UI state, without replacing the activity's launch identity.
        receiveSharedMap(intent)
    }

    private fun receiveSharedMap(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND || intent.type != "text/plain") return
        val text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
        if (text.isNullOrBlank() || text.length > 8192) {
            Toast.makeText(this, "Compartí un pin de Google Maps o pegá sus coordenadas.", Toast.LENGTH_LONG).show()
            return
        }
        sharedMapText = text
    }

    private fun transfer(success: String, action: suspend () -> Unit) {
        if (transferBusy) { Toast.makeText(this, "Esperá a que termine la operación actual.", Toast.LENGTH_LONG).show(); return }
        transferBusy = true
        Toast.makeText(this, "Procesando el archivo…", Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) { action() }
                Toast.makeText(this@MainActivity, success, Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                Toast.makeText(this@MainActivity, e.message ?: "No se pudo completar la operación. Tus registros se conservaron.", Toast.LENGTH_LONG).show()
            } finally { transferBusy = false }
        }
    }

    override fun onDestroy() {
        backupPassword?.fill('\u0000'); restorePassword?.fill('\u0000')
        super.onDestroy()
    }
}
