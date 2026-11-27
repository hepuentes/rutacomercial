package com.rutacomercial.app.tracking

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.content.ContextCompat
import com.rutacomercial.app.data.Repository
import com.rutacomercial.app.domain.GpsFix
import com.rutacomercial.app.domain.WalkingGpsFilter
import com.rutacomercial.app.maps.MapCoordinate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

interface TrackingDependencies { val repository: Repository }

data class TrackingStatus(
    val isTracking: Boolean = false,
    val serviceRunning: Boolean = false,
    val position: MapCoordinate? = null,
    val accuracyMeters: Float? = null,
    val fixAt: Long? = null,
    val provider: String = "",
    val isLocating: Boolean = false,
    val locationProblem: String? = null,
    /** Zone name while the worker is outside it past the tolerance and has not chosen to continue. */
    val outsideZone: String? = null,
    val message: String = "GPS detenido. Iniciá una jornada para registrar el recorrido.",
)

object TrackingRuntime {
    internal val mutable = MutableStateFlow(TrackingStatus())
    internal val transitionMutex = Mutex()
    val state: StateFlow<TrackingStatus> = mutable.asStateFlow()
}

object TrackingController {
    private var preview: LocationPreview? = null
    fun hasLocationPermission(context: Context) = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
    fun hasNotificationPermission(context: Context): Boolean {
        val runtime = Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        val manager = context.getSystemService(NotificationManager::class.java)
        return runtime && manager.areNotificationsEnabled() && manager.getNotificationChannel(TrackingService.CHANNEL)?.importance != NotificationManager.IMPORTANCE_NONE
    }

    fun start(context: Context, at: Long = System.currentTimeMillis(), anchor: MapCoordinate? = null) = command(context, TrackingService.START, true, at, anchor)
    fun resume(context: Context) = command(context, TrackingService.RESUME, true)
    fun pause(context: Context) = command(context, TrackingService.PAUSE, false)
    fun finish(context: Context) = command(context, TrackingService.FINISH, false)
    fun continueOutside(context: Context) = command(context, TrackingService.OUTSIDE_OK, false)

    private fun command(context: Context, action: String, foreground: Boolean, at: Long = System.currentTimeMillis(), anchor: MapCoordinate? = null) {
        if (foreground && (!hasLocationPermission(context) || !hasNotificationPermission(context))) {
            TrackingRuntime.mutable.update { it.copy(message = "Concedé ubicación precisa y notificaciones antes de iniciar el recorrido.") }
            return
        }
        try {
            preview?.stop(); preview = null
            TrackingRuntime.mutable.update { it.copy(isLocating = false, locationProblem = null) }
            val intent = Intent(context, TrackingService::class.java).setAction(action).putExtra("pressedAt",at)
            if(anchor!=null) intent.putExtra("anchor",anchor.latitude.toString()+","+anchor.longitude)
            if (foreground) ContextCompat.startForegroundService(context, intent) else context.startService(intent)
        } catch (_: RuntimeException) {
            TrackingRuntime.mutable.update { it.copy(message = "Android no permitió iniciar el servicio. Volvé a abrir la app y revisá los permisos.") }
        }
    }

    suspend fun recoverIfNeeded(context: Context) {
        TrackingRuntime.transitionMutex.withLock {
            if (!TrackingRuntime.state.value.serviceRunning) {
                val repository = (context.applicationContext as TrackingDependencies).repository
                if (repository.currentJourney()?.state == "ACTIVE") {
                    repository.recoverInterruptedJourney()
                    TrackingRuntime.mutable.update { it.copy(message = "Jornada recuperada en pausa. Revisá la interrupción y tocá Reanudar cuando estés listo.") }
                }
            }
        }
    }

    /** One explicit request, bounded to 20 s. Never persists outside a journey. */
    fun locateOnce(context: Context) {
        preview?.stop(); preview = null
        val manager = context.getSystemService(LocationManager::class.java)
        if (!hasLocationPermission(context) || !manager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
            TrackingRuntime.mutable.update { it.copy(isLocating = false, locationProblem = "Activá Ubicación y concedé ubicación precisa.") }
            return
        }
        val current = TrackingRuntime.state.value
        if (current.isTracking && current.fixAt != null && System.currentTimeMillis() - current.fixAt in 0..10_000 && (current.accuracyMeters ?: 500f) <= 25f) return
        TrackingRuntime.mutable.update { it.copy(isLocating = true, locationProblem = null, message = "Buscando ubicación…") }
        val request = LocationPreview(context.applicationContext)
        preview = request
        request.start({ fix ->
            if (preview === request) TrackingRuntime.mutable.update { it.copy(
                position = MapCoordinate(fix.latitude, fix.longitude), accuracyMeters = fix.accuracy, fixAt = fix.time,
                message = if (fix.accuracy <= 25) "Ubicación encontrada" else "Ubicación aproximada · mejorando señal…") }
        }, { found ->
            if (preview === request) {
                preview = null
                TrackingRuntime.mutable.update { it.copy(isLocating = false,
                    locationProblem = if (found) null else "Sin ubicación reciente. Probá cerca de una ventana o al aire libre.",
                    message = if (found) "Ubicación encontrada · precisión estimada ±${it.accuracyMeters?.toInt()} m" else it.message) }
            }
        })
    }

}

/** Foreground only after an explicit START/RESUME from visible UI; never restarted by Android. */
class TrackingService : Service(), LocationListener {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val serial = TrackingRuntime.transitionMutex
    private lateinit var manager: LocationManager
    private lateinit var locationSource: JourneyLocationSource
    private val repository get() = (application as TrackingDependencies).repository
    private var listening = false
    private var generation = 0L
    private var lastFixElapsed = 0L
    private var requestedAfterNanos = 0L
    private var foreground = false
    private var commandGeneration = 0L
    private val observations = com.rutacomercial.app.domain.WalkingObservations()
    private val walkingFilter = WalkingGpsFilter()

    override fun onCreate() {
        super.onCreate()
        manager = getSystemService(LocationManager::class.java)
        locationSource = JourneyLocationSource(this)
        // Default importance without sound: many phones hide low-importance notices on the lock screen,
        // and the Pausar/Terminar buttons must be reachable there.
        val notifications = getSystemService(NotificationManager::class.java)
        notifications.deleteNotificationChannel("journey_tracking")
        notifications.createNotificationChannel(NotificationChannel(CHANNEL, "Recorrido en curso", NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "Seguimiento GPS iniciado por vos"; setSound(null, null); enableVibration(false); lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC })
        TrackingRuntime.mutable.update { it.copy(serviceRunning = true) }
        scope.launch {
            while (isActive) {
                delay(10_000)
                serial.withLock {
                    if (listening) {
                        if (!TrackingController.hasLocationPermission(this@TrackingService)) pauseForProblem("Permiso de ubicación revocado. Jornada pausada.", "PERMISSION_REVOKED")
                        else if (!TrackingController.hasNotificationPermission(this@TrackingService)) pauseForProblem("Notificaciones desactivadas. Jornada pausada para mantener el seguimiento visible.", "NOTIFICATION_DISABLED")
                        else if (!manager.isProviderEnabled(LocationManager.GPS_PROVIDER)) pauseForProblem("Ubicación desactivada en Android. Jornada pausada.", "GPS_DISABLED")
                        else if (idle.shouldPause(System.currentTimeMillis())) pauseIdle()
                        else try {
                            repository.currentJourney()?.let { repository.heartbeat(it.id) }
                            if (android.os.SystemClock.elapsedRealtime() - lastFixElapsed > WalkingGpsFilter.SIGNAL_GAP_MS) TrackingRuntime.mutable.update { it.copy(message = "Sin ubicación precisa reciente · esperando señal GPS") }
                        } catch (_: Exception) { pauseForProblem("No se pudo guardar el recorrido. Revisá el almacenamiento.", "STORAGE_ERROR") }
                    }
                }
            }
        }
    }

    @SuppressLint("MissingPermission") // Runtime fine-location check precedes the location foreground service.
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = if(intent==null) "RESTORE_ACTIVE" else intent.action
        val requestedGeneration = ++commandGeneration
        val requestedAt = intent?.getLongExtra("pressedAt",System.currentTimeMillis()) ?: System.currentTimeMillis()
        val status=TrackingRuntime.state.value
        val eventPosition=intent?.getStringExtra("anchor") ?: status.position?.takeIf { (status.accuracyMeters ?: 999f)<=20f && status.fixAt?.let { at -> requestedAt-at in 0L..5000L }==true }
            ?.let { "${it.latitude},${it.longitude}" }.orEmpty()
        if (action == START || action == RESUME || action == "RESTORE_ACTIVE") {
            getSystemService(NotificationManager::class.java).cancel(PAUSED_ID)
            try {
                if (!TrackingController.hasLocationPermission(this)) throw SecurityException("Sin permiso")
                if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIFICATION_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
                else startForeground(NOTIFICATION_ID, notification())
                foreground = true
            } catch (_: Exception) {
                stopListening()
                TrackingRuntime.mutable.update { it.copy(message = "Android rechazó el seguimiento. Revisá los permisos y abrí la app.") }
                stopSelf(startId)
                return START_NOT_STICKY
            }
        } else if (action == OUTSIDE_OK) {
            scope.launch { serial.withLock { runCatching { zoneAlerts.acceptOutside() } } }
            if (!foreground) stopSelf(startId)
            return START_NOT_STICKY
        } else if (action == ASK_FINISH) {
            // Lock-screen finish needs a second tap; a pocket tap must never close the day.
            getSystemService(NotificationManager::class.java).notify(if (foreground) NOTIFICATION_ID else PAUSED_ID, notification(confirm = true, paused = !foreground))
            if (!foreground) stopSelf(startId)
            return START_NOT_STICKY
        } else if (action == KEEP) {
            getSystemService(NotificationManager::class.java).notify(if (foreground) NOTIFICATION_ID else PAUSED_ID, notification(paused = !foreground))
            if (!foreground) stopSelf(startId)
            return START_NOT_STICKY
        } else if (action == PAUSE || action == FINISH) {
            // Stop the sensor synchronously, before waiting for the database transaction.
            stopListening()
        } else {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        scope.launch {
            serial.withLock {
                try {
                    when (action) {
                        "RESTORE_ACTIVE" -> {
                            if(repository.currentJourney()?.state=="ACTIVE" && requestedGeneration==commandGeneration) {
                                repository.breakGpsSegment("Android reinició el servicio. Se reanudó la recepción GPS.")
                                startListening()
                            } else if(requestedGeneration==commandGeneration) finishService()
                        }
                        START -> {
                            if (listening) return@withLock
                            val existing = repository.currentJourney()
                            if (existing == null) repository.startJourney(requestedAt,getSharedPreferences("map_workspace",MODE_PRIVATE).getString("sector",null),getSharedPreferences("assignments",MODE_PRIVATE).let { if(com.rutacomercial.app.BuildConfig.WORKER_EDITION) it.getString("selected",null) else null },eventPosition)
                            else if (existing.state == "ACTIVE") {
                                repository.recoverInterruptedJourney()
                                repository.resumeJourney(existing.id, requestedAt,eventPosition)
                            } else throw IllegalArgumentException("La jornada está en pausa. Usá Reanudar para continuar.")
                            if (requestedGeneration == commandGeneration) startListening()
                        }
                        RESUME -> {
                            if (listening) return@withLock
                            val journey = repository.currentJourney() ?: throw IllegalArgumentException("No hay jornada para reanudar")
                            if (journey.state == "ACTIVE") repository.recoverInterruptedJourney()
                            repository.resumeJourney(journey.id, requestedAt,eventPosition)
                            if (requestedGeneration == commandGeneration) startListening()
                        }
                        PAUSE -> {
                            TrackingRuntime.mutable.update { it.copy(outsideZone = null) }
                            repository.currentJourney()?.let { repository.pauseJourney(it.id, requestedAt,eventPosition)
                                getSystemService(NotificationManager::class.java).notify(PAUSED_ID, notification(paused = true)) }
                            (application as? com.rutacomercial.app.RutaApplication)?.cloud?.scheduleDelivery()
                            TrackingRuntime.mutable.update { it.copy(message = "Jornada en pausa. GPS detenido.") }
                            if (requestedGeneration == commandGeneration) finishService()
                        }
                        FINISH -> {
                            TrackingRuntime.mutable.update { it.copy(outsideZone = null) }
                            getSystemService(NotificationManager::class.java).cancel(PAUSED_ID)
                            repository.currentJourney()?.let { repository.finishJourney(it.id, requestedAt,eventPosition) }
                            (application as? com.rutacomercial.app.RutaApplication)?.cloud?.scheduleDelivery()
                            TrackingRuntime.mutable.update { it.copy(message = "Jornada finalizada y guardada. GPS detenido.") }
                            if (requestedGeneration == commandGeneration) finishService()
                        }
                    }
                } catch (e: Exception) {
                    if (requestedGeneration == commandGeneration) pauseForProblem(e.message ?: "No se pudo completar la operación.", "TRACKING_ERROR")
                }
            }
        }
        return if(action==PAUSE || action==FINISH) START_NOT_STICKY else START_STICKY
    }

    @SuppressLint("MissingPermission") // Explicit runtime check; SecurityException is handled by the command loop.
    private fun startListening() {
        if (!TrackingController.hasLocationPermission(this)) throw SecurityException("Se necesita ubicación precisa")
        check(manager.isProviderEnabled(LocationManager.GPS_PROVIDER)) { "Activá Ubicación en Android antes de iniciar el recorrido." }
        listening = true
        observations.reset()
        walkingFilter.reset()
        generation++
        lastFixElapsed = android.os.SystemClock.elapsedRealtime()
        idle.start(System.currentTimeMillis())
        requestedAfterNanos = android.os.SystemClock.elapsedRealtimeNanos()
        TrackingRuntime.mutable.update { it.copy(isTracking = true, isLocating = false, locationProblem = null, provider = "", message = "Comprobando ubicación · esperá unos segundos") }
        val token = generation
        locationSource.start(::onLocationChanged,
            { provider -> if (listening && generation == token) TrackingRuntime.mutable.update { it.copy(provider = provider) } },
            { message -> scope.launch { serial.withLock { if (listening && generation == token) pauseForProblem(message, "LOCATION_UNAVAILABLE") } } })
    }

    private fun stopListening() {
        listening = false; generation++
        observations.reset()
        walkingFilter.reset()
        if (::locationSource.isInitialized) locationSource.stop()
        if (::manager.isInitialized) runCatching { manager.removeUpdates(this) }
        TrackingRuntime.mutable.update { it.copy(isTracking = false) }
    }

    private val idle = IdleWatch()

    private suspend fun pauseIdle() {
        stopListening()
        val message = "Pausamos tu recorrido: 20 minutos sin moverte. Ese tiempo no se cuenta. Tocá Reanudar para seguir."
        runCatching {
            repository.currentJourney()?.let {
                repository.pauseIdle(it.id, idle.lastMovedAt)
                repository.addIncident(message, it.id, "IDLE_PAUSE")
            }
        }
        TrackingRuntime.mutable.update { it.copy(message = message, outsideZone = null) }
        getSystemService(NotificationManager::class.java).notify(PAUSED_ID, notification(paused = true))
        (application as? com.rutacomercial.app.RutaApplication)?.cloud?.scheduleDelivery()
        finishService()
    }

    private suspend fun pauseForProblem(message: String, kind: String) {
        stopListening()
        runCatching {
            repository.currentJourney()?.let {
                repository.pauseJourney(it.id)
                repository.addIncident(message, it.id, kind)
            }
        }
        TrackingRuntime.mutable.update { it.copy(message = message) }
        finishService()
    }

    private fun finishService() {
        if (foreground) { stopForeground(STOP_FOREGROUND_REMOVE); foreground = false }
        stopSelf()
    }

    private val zoneAlerts by lazy { ZoneAlerts(this, repository) }

    override fun onLocationChanged(location: Location) {
        val observedGeneration = generation
        if (!listening) return
        if (location.elapsedRealtimeNanos < requestedAfterNanos) return
        val fix = GpsFix(location.latitude, location.longitude, if (location.hasAccuracy()) location.accuracy else Float.POSITIVE_INFINITY,
            location.time, location.elapsedRealtimeNanos, if (Build.VERSION.SDK_INT >= 31) location.isMock else @Suppress("DEPRECATION") location.isFromMockProvider)
        scope.launch {
            serial.withLock {
                if (!listening || generation != observedGeneration) return@withLock
                try {
                    if (android.os.SystemClock.elapsedRealtimeNanos() - fix.elapsedRealtimeNanos !in 0..15_000_000_000L || kotlin.math.abs(System.currentTimeMillis() - fix.at) > 30_000L) return@withLock
                    // The blue dot can be approximate; the stored walking trace keeps stricter gates.
                    if (LocationPreview.usable(location)) TrackingRuntime.mutable.update {
                        it.copy(position = MapCoordinate(fix.latitude, fix.longitude), accuracyMeters = fix.accuracyMeters, fixAt = fix.at, locationProblem = null)
                    }
                    observations.accept(fix)?.let { kind ->
                        val journeyId=repository.currentJourney()?.id
                        if(journeyId!=null) {
                            val message=if(kind=="FAST_INTERVAL") "Ritmo elevado sostenido durante al menos 30 s. Revisar el contexto."
                            else {
                                val nearby=repository.dao.businesses().filter { it.mergedIntoId==null &&
                                    com.rutacomercial.app.domain.Geo.distanceM(fix.latitude,fix.longitude,it.latitude,it.longitude)<=50 }
                                "Permanencia de al menos 30 s en un radio de 15 m. Negocios registrados a 50 m: ${nearby.size}."
                            }
                            repository.addIncident(message,journeyId,kind)
                        }
                    }
                    val filtered = walkingFilter.evaluate(fix)
                    walkingFilter.lastReliableFix?.let { lastFixElapsed = it.elapsedRealtimeNanos / 1_000_000 }
                    if (walkingFilter.lastReliableFix == fix) TrackingRuntime.mutable.update {
                        it.copy(position = MapCoordinate(fix.latitude, fix.longitude), accuracyMeters = fix.accuracyMeters, fixAt = fix.at)
                    }
                    if (filtered.breakSegment) repository.breakGpsSegment(filtered.reason)
                    if (!filtered.accepted) {
                        TrackingRuntime.mutable.update { it.copy(message = filtered.reason) }
                        return@withLock
                    }
                    val result = repository.recordLocation(fix, signalContinuous = !filtered.breakSegment)
                    if (result.accepted) {
                        idle.moved(fix.latitude, fix.longitude, fix.at)
                        runCatching { zoneAlerts.check(fix.latitude, fix.longitude, System.currentTimeMillis()) }
                        lastFixElapsed = android.os.SystemClock.elapsedRealtime()
                        TrackingRuntime.mutable.update { it.copy(position = MapCoordinate(fix.latitude, fix.longitude), accuracyMeters = fix.accuracyMeters,
                            fixAt = fix.at, message = if (fix.isMock) "Ubicación simulada · requiere revisión" else if (filtered.reason == "Ubicación lista") "GPS listo · esperando movimiento" else "Recorrido activo · movimiento confirmado") }
                    } else TrackingRuntime.mutable.update { it.copy(message = result.reason) }
                } catch (_: Exception) { pauseForProblem("No se pudo guardar el punto GPS. Jornada en pausa.", "STORAGE_ERROR") }
            }
        }
    }

    override fun onProviderDisabled(provider: String) {
        if (listening) {
            TrackingRuntime.mutable.update { it.copy(message = "Proveedor GPS desactivado. Esperando una ubicación válida.") }
            scope.launch { runCatching { repository.addIncident("Proveedor desactivado: $provider", repository.currentJourney()?.id, "GPS_DISABLED") } }
        }
    }
    override fun onProviderEnabled(provider: String) = Unit
    @Deprecated("Legacy callback") override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onDestroy() {
        val interrupted = listening
        stopListening()
        scope.cancel()
        TrackingRuntime.mutable.update { it.copy(serviceRunning = false, message = if (interrupted) "Servicio de GPS interrumpido. Volvé a abrir la jornada para recuperarla en pausa." else it.message) }
        super.onDestroy()
    }

    /** Public on the lock screen: it only says a journey is running, never where. */
    private fun notification(confirm: Boolean = false, paused: Boolean = false): Notification {
        val launch = packageManager.getLaunchIntentForPackage(packageName)
        val content = launch?.let { PendingIntent.getActivity(this, 0, it, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE) }
        fun action(name: String, code: Int) = PendingIntent.getService(this, code, Intent(this, TrackingService::class.java).setAction(name), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        // Resuming starts location in the foreground; a notification tap is allowed to do that from the background.
        fun resume() = PendingIntent.getForegroundService(this, 5, Intent(this, TrackingService::class.java).setAction(RESUME), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val builder = Notification.Builder(this, CHANNEL)
            .setSmallIcon(com.rutacomercial.app.R.drawable.ic_tracking)
            .setContentTitle(when { confirm -> "¿Terminar el recorrido de hoy?"; paused -> "RutaComercial · recorrido en pausa"; else -> "RutaComercial · recorrido activo" })
            .setContentText(when { confirm -> "Se guarda y se envía al Owner."; paused -> "GPS detenido. Tocá Reanudar para seguir."; else -> "Se está registrando tu ubicación durante esta jornada." })
            .setContentIntent(content).setOngoing(true).setOnlyAlertOnce(true)
            .setVisibility(Notification.VISIBILITY_PUBLIC).setCategory(Notification.CATEGORY_SERVICE)
        when {
            confirm -> builder.addAction(Notification.Action.Builder(null, "Sí, terminar", action(FINISH, 2)).build())
                .addAction(Notification.Action.Builder(null, "Seguir", action(KEEP, 4)).build())
            paused -> builder.addAction(Notification.Action.Builder(null, "Reanudar", resume()).build())
                .addAction(Notification.Action.Builder(null, "Terminar", action(ASK_FINISH, 3)).build())
            else -> builder.addAction(Notification.Action.Builder(null, "Pausar", action(PAUSE, 1)).build())
                .addAction(Notification.Action.Builder(null, "Terminar", action(ASK_FINISH, 3)).build())
        }
        if (Build.VERSION.SDK_INT >= 31) builder.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
        return builder.build()
    }

    companion object {
        const val START = "com.rutacomercial.START"
        const val PAUSE = "com.rutacomercial.PAUSE"
        const val RESUME = "com.rutacomercial.RESUME"
        const val FINISH = "com.rutacomercial.FINISH"
        const val ASK_FINISH = "com.rutacomercial.ASK_FINISH"
        const val OUTSIDE_OK = "com.rutacomercial.OUTSIDE_OK"
        const val KEEP = "com.rutacomercial.KEEP"
        private const val PAUSED_ID = 2402
        internal const val CHANNEL = "journey_tracking_v2"
        private const val NOTIFICATION_ID = 2401
    }
}
