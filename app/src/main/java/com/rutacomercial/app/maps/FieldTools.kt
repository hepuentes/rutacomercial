package com.rutacomercial.app.maps

import android.content.Context
import android.graphics.PointF
import android.hardware.*
import android.view.Surface
import android.view.WindowManager
import androidx.compose.foundation.Canvas
import android.view.MotionEvent
import android.view.View
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.*
import androidx.lifecycle.compose.LocalLifecycleOwner
import org.maplibre.android.maps.MapLibreMap
import kotlin.math.*

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
internal fun FreehandZone(map: MapLibreMap, view: View, color: Color, changed: (List<MapCoordinate>) -> Unit) {
    val publish by rememberUpdatedState(changed)
    val stroke = remember { mutableStateListOf<Offset>() }
    var navigating by remember { mutableStateOf(false) }
    Canvas(Modifier.fillMaxSize().semantics { contentDescription = "Dibujar contorno de zona" }
        .pointerInteropFilter { event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    navigating = false
                    map.cancelTransitions()
                    stroke.clear()
                    stroke.add(Offset(event.x,event.y))
                }
                MotionEvent.ACTION_POINTER_DOWN -> {
                    if (!navigating) {
                        navigating = true
                        stroke.clear()
                        val down = MotionEvent.obtain(event.downTime,event.eventTime,MotionEvent.ACTION_DOWN,event.getX(0),event.getY(0),0)
                        try { view.dispatchTouchEvent(down) } finally { down.recycle() }
                    }
                    view.dispatchTouchEvent(event)
                }
                else -> if (navigating) {
                    view.dispatchTouchEvent(event)
                } else when (event.actionMasked) {
                    MotionEvent.ACTION_MOVE -> {
                        val pos = Offset(event.x,event.y)
                        if (stroke.isEmpty() || (pos-stroke.last()).getDistance() >= 4 * view.resources.displayMetrics.density) stroke.add(pos)
                    }
                    MotionEvent.ACTION_UP -> {
                        val step = max(1, ceil(stroke.size / 180.0).toInt())
                        // A finger that returns to the starting corner can overshoot it by a few pixels.
                        val closureRadius = 10 * view.resources.displayMetrics.density
                        val outline = stroke.toMutableList()
                        if (outline.any { (it-outline.first()).getDistance() > 2*closureRadius }) {
                            while (outline.size > 3 && (outline.last()-outline.first()).getDistance() < closureRadius) {
                                outline.removeAt(outline.lastIndex)
                            }
                        }
                        val points = outline.filterIndexed { i, _ -> i % step == 0 }.map {
                            map.projection.fromScreenLocation(PointF(it.x,it.y)).let { p -> MapCoordinate(p.latitude,p.longitude) }
                        }.distinct()
                        if (points.size >= 3) publish(points)
                        stroke.clear()
                    }
                    MotionEvent.ACTION_CANCEL -> stroke.clear()
                }
            }
            true
        }) {
        if (stroke.size >= 2) {
            val path = Path().apply { moveTo(stroke.first().x,stroke.first().y); stroke.drop(1).forEach { lineTo(it.x,it.y) }; close() }
            drawPath(path,color.copy(alpha=.12f)); drawPath(path,color,style=Stroke(3.dp.toPx()))
        }
    }
}

internal data class Heading(val degrees: Float, val spread: Float, val movement: Boolean = false)

/** A travel direction is available only while successive good fixes show movement. */
@Composable
internal fun travelHeading(enabled:Boolean, position:MapCoordinate?, accuracy:Float?): Heading? {
    var anchor by remember(enabled) { mutableStateOf<MapCoordinate?>(null) }
    var anchorError by remember(enabled) { mutableFloatStateOf(0f) }
    var anchorAt by remember(enabled) { mutableLongStateOf(0L) }
    var measuredAt by remember(enabled) { mutableLongStateOf(0L) }
    var course by remember(enabled) { mutableStateOf<Heading?>(null) }
    LaunchedEffect(enabled,position) {
        if(!enabled || position==null || accuracy==null || accuracy !in 0f..25f) {
            anchor=null;course=null;return@LaunchedEffect
        }
        val then=anchor
        val now=android.os.SystemClock.elapsedRealtime()
        if(then!=null && now-anchorAt in 1..45000L) {
            val leg=FloatArray(2)
            android.location.Location.distanceBetween(then.latitude,then.longitude,position.latitude,position.longitude,leg)
            if(leg[0]<maxOf(10f,anchorError+accuracy)) return@LaunchedEffect
            course=Heading((leg[1]+360f)%360f,70f,movement=true)
            measuredAt=now
        }
        anchor=position;anchorError=accuracy;anchorAt=now
    }
    LaunchedEffect(measuredAt) { if(measuredAt!=0L) { kotlinx.coroutines.delay(12000);course=null } }
    return course
}

@Composable
internal fun deviceHeading(enabled: Boolean, position: MapCoordinate?, whileVisible: Boolean = false): Heading? {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var result by remember { mutableStateOf<Heading?>(null) }
    val declination = remember(position) { position?.let { GeomagneticField(it.latitude.toFloat(),it.longitude.toFloat(),0f,System.currentTimeMillis()).declination } ?: 0f }
    val correction by rememberUpdatedState(declination)
    DisposableEffect(enabled, lifecycle, whileVisible) {
        val manager = context.getSystemService(SensorManager::class.java)
        val sensor = manager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
            ?: manager.getDefaultSensor(Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR)
        val accelerometer=if(sensor==null) manager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) else null
        val magnetometer=if(sensor==null) manager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD) else null
        var gravity:FloatArray?=null
        var magnetic:FloatArray?=null
        var magneticAccuracy=SensorManager.SENSOR_STATUS_UNRELIABLE
        val matrix = FloatArray(9)
        val rotated = FloatArray(9)
        val filter = BearingFilter()
        val listener = object : SensorEventListener {
            override fun onAccuracyChanged(s: Sensor?, accuracy: Int) { if (accuracy == SensorManager.SENSOR_STATUS_UNRELIABLE) result = null }
            override fun onSensorChanged(event: SensorEvent) {
                val accuracy=if(sensor!=null) {
                    SensorManager.getRotationMatrixFromVector(matrix,event.values)
                    event.accuracy
                } else {
                    when(event.sensor.type) {
                        Sensor.TYPE_ACCELEROMETER -> gravity=event.values.copyOf(3)
                        Sensor.TYPE_MAGNETIC_FIELD -> {
                            magnetic=event.values.copyOf(3)
                            magneticAccuracy=event.accuracy
                        }
                    }
                    val g=gravity ?: return
                    val m=magnetic ?: return
                    if(!SensorManager.getRotationMatrix(matrix,null,g,m)) { result=null;return }
                    magneticAccuracy
                }
                if(accuracy<=SensorManager.SENSOR_STATUS_UNRELIABLE) { result=null;return }
                @Suppress("DEPRECATION")
                val rotation = context.getSystemService(WindowManager::class.java).defaultDisplay.rotation
                val axes = when(rotation) {
                    Surface.ROTATION_90 -> SensorManager.AXIS_Y to SensorManager.AXIS_MINUS_X
                    Surface.ROTATION_180 -> SensorManager.AXIS_MINUS_X to SensorManager.AXIS_MINUS_Y
                    Surface.ROTATION_270 -> SensorManager.AXIS_MINUS_Y to SensorManager.AXIS_X
                    else -> SensorManager.AXIS_X to SensorManager.AXIS_Y
                }
                if(!SensorManager.remapCoordinateSystem(matrix,axes.first,axes.second,rotated)) return
                val azimuth = forwardAzimuth(rotated) ?: run { result=null; return }
                val angle = filter.update(azimuth + correction,event.timestamp)
                val uncertainty=event.values.getOrNull(4)?.takeIf { sensor!=null }?.takeIf { it.isFinite() && it>=0f }
                val minimum=if(accuracy<=SensorManager.SENSOR_STATUS_ACCURACY_LOW) 100f else 40f
                val width=uncertainty?.let { (2*Math.toDegrees(it.toDouble())).toFloat().coerceIn(minimum,160f) } ?: maxOf(minimum,60f)
                val old = result
                if(old == null || abs((angle-old.degrees+540f)%360f-180f) >= 3 || old.spread != width) result=Heading(angle,width)
            }
        }
        val observer = object: DefaultLifecycleObserver {
            private fun start() { if(enabled) {
                if(sensor!=null) manager.registerListener(listener,sensor,150_000)
                else if(accelerometer!=null && magnetometer!=null) {
                    manager.registerListener(listener,accelerometer,150_000)
                    manager.registerListener(listener,magnetometer,150_000)
                }
            } }
            private fun stop() { manager.unregisterListener(listener); gravity=null;magnetic=null;filter.reset();result=null }
            override fun onStart(owner: LifecycleOwner) { if(whileVisible) start() }
            override fun onResume(owner: LifecycleOwner) { if(!whileVisible) start() }
            override fun onPause(owner: LifecycleOwner) { if(!whileVisible) stop() }
            override fun onStop(owner: LifecycleOwner) { if(whileVisible) stop() }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); manager.unregisterListener(listener); result=null }
    }
    return result
}

@Composable
internal fun DirectionFan(origin: Offset, heading: Heading, bearing: Double) {
    Canvas(Modifier.fillMaxSize()) {
        val radius=48.dp.toPx()
        val angle=heading.degrees-bearing.toFloat()-90f
        val path=Path().apply {
            moveTo(origin.x,origin.y)
            for(i in 0..24) {
                val a=Math.toRadians((angle-heading.spread/2+heading.spread*i/24).toDouble())
                lineTo(origin.x+radius*cos(a).toFloat(),origin.y+radius*sin(a).toFloat())
            }
            close()
        }
        drawPath(path,Brush.radialGradient(listOf(Color(0x661D77E4),Color.Transparent),origin,radius))
    }
}
