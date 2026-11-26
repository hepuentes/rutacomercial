package com.rutacomercial.app.ui

import androidx.compose.material3.*
import com.rutacomercial.app.maps.MapCoordinate
import com.rutacomercial.app.tracking.TrackingRuntime
import com.rutacomercial.app.tracking.LocationPreview
import androidx.lifecycle.repeatOnLifecycle
import android.Manifest
import android.content.Intent
import android.location.LocationManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import com.google.android.gms.common.api.ResolvableApiException
import com.google.android.gms.location.*
import com.rutacomercial.app.tracking.TrackingController

/** Permission and the device location switch are separate requirements. No request on app launch. */
@Composable
internal fun rememberLocationAction(vm: RutaViewModel): (String) -> Unit {
    val context = LocalContext.current
    var pending by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf("") }
    var pressedAt by androidx.compose.runtime.saveable.rememberSaveable { mutableLongStateOf(0L) }
    var awaitingStart by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    var readyFix by remember { mutableStateOf<android.location.Location?>(null) }
    fun begin(at:Long, point:MapCoordinate) {
        awaitingStart=false
        readyFix=null
        TrackingController.start(context,at,point)
    }
    if(awaitingStart) {
        val lifecycle=androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
        LaunchedEffect(context,lifecycle) {
            lifecycle.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.RESUMED) {
                val preview=LocationPreview(context,true)
                try { preview.start({ fix ->
                if(fix.accuracy<=20f && System.currentTimeMillis()-fix.time in 0L..5000L) {
                    readyFix=fix
                    if(awaitingStart && fix.time-pressedAt in -5000L..5000L)
                        begin(pressedAt,MapCoordinate(fix.latitude,fix.longitude))
                }
                },{})
                    kotlinx.coroutines.awaitCancellation()
                } finally { preview.stop();readyFix=null }
            }
        }
        AlertDialog(onDismissRequest={awaitingStart=false},title={Text("Ubicar el inicio")},
            text={Text(if(readyFix==null) "Quedate en el punto de partida mientras el GPS mejora la señal." else "Ya hay señal. Tocá Iniciar aquí desde tu punto de partida.")},
            confirmButton={Button(enabled=readyFix!=null,onClick={
                val fix=readyFix
                val now=System.currentTimeMillis()
                if(fix!=null && now-fix.time in 0L..5000L) begin(now,MapCoordinate(fix.latitude,fix.longitude)) else readyFix=null
            }) { Text("Iniciar aquí") }},
            dismissButton={TextButton(onClick={awaitingStart=false}) { Text("Cancelar") }})
    }
    val enabled = { context.getSystemService(LocationManager::class.java).isProviderEnabled(LocationManager.GPS_PROVIDER) }
    fun execute() {
        val action = pending
        pending = ""
        if (!enabled()) {
            vm.message("Ubicación sigue desactivada. Tocá Mi ubicación para volver a activarla.")
            return
        }
        when (action) {
            "locate" -> TrackingController.locateOnce(context)
            "start" -> {
                val status=TrackingRuntime.state.value
                val pos=status.position
                if(pos!=null && (status.accuracyMeters ?: 999f)<=20f &&
                    status.fixAt?.let { pressedAt-it in 0L..5000L }==true && System.currentTimeMillis()-pressedAt<=5000L)
                    begin(pressedAt,pos)
                else awaitingStart=true
            }
            "resume" -> TrackingController.resume(context)
        }
    }
    val settings = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { execute() }
    val resolution = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { execute() }
    fun ensureEnabled() {
        if (enabled()) { execute(); return }
        val request = LocationSettingsRequest.Builder().addLocationRequest(
            LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 6_000).build()
        ).setAlwaysShow(true).build()
        LocationServices.getSettingsClient(context).checkLocationSettings(request)
            .addOnSuccessListener {
                if (enabled()) execute()
                else settings.launch(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
            }
            .addOnFailureListener { error ->
                if (error is ResolvableApiException) {
                    try { resolution.launch(IntentSenderRequest.Builder(error.resolution).build()) }
                    catch (_: Exception) { settings.launch(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) }
                } else settings.launch(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
            }
    }
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (TrackingController.hasLocationPermission(context) && (pending == "locate" || TrackingController.hasNotificationPermission(context))) ensureEnabled()
        else {
            pending = ""
            vm.message("Habilitá ubicación precisa y, para registrar recorridos, notificaciones en los permisos de RutaComercial.")
        }
    }
    return { action ->
        if (pending.isEmpty()) {
            pressedAt=System.currentTimeMillis()
            pending = action
            val required = buildList {
                if (!TrackingController.hasLocationPermission(context)) {
                    add(Manifest.permission.ACCESS_FINE_LOCATION); add(Manifest.permission.ACCESS_COARSE_LOCATION)
                }
                if (action != "locate" && Build.VERSION.SDK_INT >= 33 && !TrackingController.hasNotificationPermission(context)) add(Manifest.permission.POST_NOTIFICATIONS)
            }
            if (required.isEmpty()) ensureEnabled() else permissions.launch(required.toTypedArray())
        }
    }
}
