package com.rutacomercial.app.maps

import android.content.ComponentCallbacks2
import android.content.res.Configuration
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.compose.ui.graphics.graphicsLayer
import android.graphics.PointF
import android.view.MotionEvent
import androidx.compose.foundation.border
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import org.json.JSONArray
import org.json.JSONObject
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.FillLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.PropertyFactory.*
import org.maplibre.android.style.sources.GeoJsonSource

data class MapCoordinate(val latitude: Double, val longitude: Double)
data class RouteMark(val label:String,val point:MapCoordinate,val at:Long=0,val sequence:Int=0,val group:String="")
internal fun numberedMarks(marks:List<RouteMark>):List<Pair<RouteMark,String>> {
    var pause=0
    return marks.map { mark ->
        if(mark.label=="P") pause=if(mark.sequence>0) mark.sequence else pause+1
        val suffix=if(mark.label=="P" || mark.label=="CR") (mark.sequence.takeIf { it>0 } ?: pause.coerceAtLeast(1)).toString() else ""
        mark to mark.label+suffix
    }
}
data class MapTrack(val id: String, val points: List<MapCoordinate>, val color: String = "#374BD4", val group:String="")
data class MapSector(val id: String, val name: String, val color: String, val points: List<MapCoordinate>, val selected: Boolean = false, val completed:Boolean = false)
data class MapBusiness(val id: String, val name: String, val latitude: Double, val longitude: Double, val color: String = "#C55C16")

/** Each MapTrack is one continuous observed GPS segment; never join segments here. */
@Composable
internal fun TerritoryMap(
    modifier: Modifier = Modifier,
    tracks: List<MapTrack> = emptyList(),
    marks: List<RouteMark> = emptyList(),
    sectors: List<MapSector> = emptyList(),
    businesses: List<MapBusiness> = emptyList(),
    currentLocation: MapCoordinate? = null,
    accuracyMeters: Float? = null,
    locationFresh: Boolean = true,
    selectionColor: String = "#087F75",
    deliveryPoints: List<MapCoordinate> = emptyList(),
    draftPolygon: List<MapCoordinate> = emptyList(),
    center: MapCoordinate? = null,
    centerRequest: Int = 0,
    fitArea: List<MapCoordinate> = emptyList(),
    selectingZone: Boolean = false,
    freehand: Boolean = false,
    moveWhileDrawing: Boolean = false,
    fieldTools: Boolean = false,
    compactNavigation: Boolean = false,
    navigation:PlaceNavigation?=null,
    onNavigateLocate:()->Unit={},
    onDrawHere:((MapCoordinate)->Unit)?=null,
    onRecenter: (() -> Unit)? = null,
    followingLocation: Boolean = false,
    previewGuide: Boolean = false,
    onGuidePreview: (Boolean) -> Unit = {},
    onGuideActive:(Boolean)->Unit={},
    onSelectionChanged: (List<MapCoordinate>) -> Unit = {},
    onCameraIdle: (MapCoordinate) -> Unit = {},
    onMapGesture: () -> Unit = {},
    onMapTap: (MapCoordinate) -> Unit = {},
    onMapLongPress: (MapCoordinate) -> Unit = {},
    onBusinessTap: (String) -> Unit = {},
) {
    val zoneColor = Color(android.graphics.Color.parseColor(selectionColor))
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val savedState = rememberSaveable { Bundle() }
    val needsProjection by rememberUpdatedState(fieldTools || compactNavigation || navigation?.route!=null || marks.isNotEmpty())
    val gesture by rememberUpdatedState(onMapGesture)
    var compassMode by rememberSaveable { mutableStateOf(0) }
    val canRotate by rememberUpdatedState(fieldTools && !selectingZone)
    val mapView = remember(context) {
        MapBootstrap.initialize(context)
        object : MapView(context) {
            override fun dispatchTouchEvent(event: MotionEvent): Boolean {
                if (event.actionMasked == MotionEvent.ACTION_DOWN) gesture()
                if (canRotate && event.actionMasked == MotionEvent.ACTION_POINTER_DOWN) compassMode=0
                parent?.requestDisallowInterceptTouchEvent(event.actionMasked != MotionEvent.ACTION_UP && event.actionMasked != MotionEvent.ACTION_CANCEL)
                return super.dispatchTouchEvent(event)
            }
        }.apply {
            onCreate(savedState)
        }
    }
    var map by remember { mutableStateOf<MapLibreMap?>(null) }
    var style by remember { mutableStateOf<Style?>(null) }
    var warning by remember { mutableStateOf<String?>(null) }
    val connectivity = remember(context) { context.getSystemService(ConnectivityManager::class.java) }
    var online by remember { mutableStateOf(connectivity.getNetworkCapabilities(connectivity.activeNetwork)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true) }
    val tap by rememberUpdatedState(onMapTap)
    val businessTap by rememberUpdatedState(onBusinessTap)
    val longPress by rememberUpdatedState(onMapLongPress)
    val drawing by rememberUpdatedState(draftPolygon.isNotEmpty() || selectingZone)
    val selectionActive by rememberUpdatedState(selectingZone)
    val selectionChanged by rememberUpdatedState(onSelectionChanged)
    val cameraIdle by rememberUpdatedState(onCameraIdle)
    var viewport by remember { mutableStateOf(IntSize.Zero) }
    var cameraMoving by remember { mutableStateOf(false) }
    var keepZoom by remember { mutableStateOf(false) }
    val compassLabel = when(compassMode) { 2 -> "Seguir giro"; 1 -> "Rumbo fijo"; else -> "Norte arriba" }
    val followBearing = compassMode == 2
    val showHeading=(fieldTools || compactNavigation) && !selectingZone
    val device = deviceHeading(showHeading,currentLocation,compactNavigation)
    val course = travelHeading(showHeading && locationFresh && device==null,currentLocation,accuracyMeters)
    val heading = device ?: course
    var cameraTick by remember { androidx.compose.runtime.mutableIntStateOf(0) }
    val rectangle by rememberUpdatedState(selectingZone && !freehand)
    LaunchedEffect(map,fieldTools,selectingZone) {
        map?.uiSettings?.isRotateGesturesEnabled=fieldTools && !selectingZone
    }
    LaunchedEffect(selectingZone) { if(selectingZone) compassMode=0 }
    LaunchedEffect(map, followBearing, heading?.degrees) {
        if(followBearing && heading != null) map?.moveCamera(CameraUpdateFactory.bearingTo(heading.degrees.toDouble()))
    }
    var frame by remember(selectingZone) { mutableStateOf(listOf(0.18f, 0.20f, 0.82f, 0.75f)) }
    val updateSelection by rememberUpdatedState(newValue = {
        val projection = map?.projection
        if (rectangle && !cameraMoving && projection != null && viewport.width > 0 && viewport.height > 0) {
            selectionChanged(listOf(frame[0] to frame[1], frame[2] to frame[1], frame[2] to frame[3], frame[0] to frame[3]).map { (x,y) ->
                projection.fromScreenLocation(PointF(x * viewport.width, y * viewport.height)).let { MapCoordinate(it.latitude, it.longitude) }
            })
        }
    })
    DisposableEffect(connectivity) {
        val handler = Handler(Looper.getMainLooper())
        var disposed = false
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                handler.post { if (!disposed) online = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) }
            }
            override fun onLost(network: Network) { handler.post { if (!disposed) online = connectivity.getNetworkCapabilities(connectivity.activeNetwork)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true } }
        }
        connectivity.registerDefaultNetworkCallback(callback)
        onDispose { disposed = true; connectivity.unregisterNetworkCallback(callback) }
    }
    DisposableEffect(mapView, lifecycle) {
        val observer = object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) { mapView.onStart(); if(compactNavigation) mapView.onResume() }
            override fun onResume(owner: LifecycleOwner) { if(!compactNavigation) mapView.onResume() }
            override fun onPause(owner: LifecycleOwner) { if(!compactNavigation) mapView.onPause() }
            override fun onStop(owner: LifecycleOwner) { if(compactNavigation) mapView.onPause(); mapView.onSaveInstanceState(savedState); mapView.onStop() }
        }
        val memory = object : ComponentCallbacks2 {
            override fun onConfigurationChanged(newConfig: Configuration) = Unit
            override fun onLowMemory() = mapView.onLowMemory()
            override fun onTrimMemory(level: Int) { if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) mapView.onLowMemory() }
        }
        context.registerComponentCallbacks(memory)
        lifecycle.addObserver(observer)
        var fallback = false
        val failure = MapView.OnDidFailLoadingMapListener {
            warning = "Mapa base no disponible. Los registros locales y el GPS siguen funcionando."
            if (!fallback) {
                fallback = true
                map?.setStyle(Style.Builder().fromJson("""{"version":8,"sources":{},"layers":[{"id":"base","type":"background","paint":{"background-color":"#E7ECE8"}}]}""")) { style = it }
            }
        }
        mapView.addOnDidFailLoadingMapListener(failure)
        mapView.getMapAsync { ready ->
            map = ready
            ready.uiSettings.isLogoEnabled = false
            ready.uiSettings.isCompassEnabled = false
            ready.uiSettings.isAttributionEnabled = !compactNavigation
            ready.uiSettings.isRotateGesturesEnabled = fieldTools && !selectionActive
            ready.uiSettings.isTiltGesturesEnabled = false
            if (savedState.isEmpty) ready.cameraPosition = CameraPosition.Builder()
                .target(LatLng(center?.latitude ?: -34.6037, center?.longitude ?: -58.3816))
                .zoom(if (center == null) 11.0 else 16.0).build()
            ready.addOnMapClickListener { location ->
                if (selectionActive) return@addOnMapClickListener true
                val hits = ready.queryRenderedFeatures(ready.projection.toScreenLocation(location), "rc-business")
                if (hits.isNotEmpty() && !drawing) {
                    hits.first().getStringProperty("id")?.takeUnless { it=="navigation-target" }?.let(businessTap)
                } else tap(MapCoordinate(location.latitude, location.longitude))
                true
            }
            ready.addOnMapLongClickListener { location ->
                if (!selectionActive) longPress(MapCoordinate(location.latitude, location.longitude))
                true
            }
            ready.addOnCameraMoveListener { if (needsProjection) cameraTick++ }
            ready.addOnCameraIdleListener {
                if(needsProjection) cameraTick++
                cameraMoving = false
                ready.cameraPosition.target?.let { cameraIdle(MapCoordinate(it.latitude, it.longitude)) }
                updateSelection()
            }
            ready.addOnCameraMoveStartedListener { reason ->
                if(reason==MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE && !selectionActive) {
                    compassMode=0
                    gesture()
                }
                cameraMoving = true
                if (rectangle) selectionChanged(emptyList())
            }
            ready.setStyle(MapBootstrap.STYLE_URL) { style = it }
        }
        onDispose {
            lifecycle.removeObserver(observer)
            context.unregisterComponentCallbacks(memory)
            mapView.removeOnDidFailLoadingMapListener(failure)
            mapView.onSaveInstanceState(savedState)
            mapView.onPause(); mapView.onStop(); mapView.onDestroy()
            map = null; style = null
        }
    }
    LaunchedEffect(map, style, viewport, fitArea, centerRequest) {
        val ready = map ?: return@LaunchedEffect
        if (fitArea.isEmpty() || viewport.width < 2 || viewport.height < 2) return@LaunchedEffect
        if (fitArea.all { it == fitArea.first() }) {
            val p=fitArea.first()
            ready.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(p.latitude,p.longitude),16.0))
            return@LaunchedEffect
        }
        val bounds = org.maplibre.android.geometry.LatLngBounds.Builder()
        fitArea.forEach { bounds.include(LatLng(it.latitude,it.longitude)) }
        val padding = (minOf(viewport.width,viewport.height) * 0.09f).toInt()
        ready.moveCamera(CameraUpdateFactory.newLatLngBounds(bounds.build(),padding))
    }
    var appliedCenterRequest by remember(map) { mutableStateOf<Int?>(null) }
    LaunchedEffect(map, center, centerRequest) {
        val ready = map
        if (ready != null && center != null) {
            val target = LatLng(center.latitude, center.longitude)
            val update = if (appliedCenterRequest != centerRequest && !keepZoom) CameraUpdateFactory.newLatLngZoom(target, 16.0) else CameraUpdateFactory.newLatLng(target)
            appliedCenterRequest = centerRequest
            keepZoom=false
            if (followBearing || compactNavigation) ready.moveCamera(update) else ready.animateCamera(update, 600)
        }
    }
    LaunchedEffect(online) {
        if (online && warning != null) map?.setStyle(MapBootstrap.STYLE_URL) { style = it; warning = null }
    }
    LaunchedEffect(map, viewport, frame, selectingZone, freehand, cameraMoving) { updateSelection() }
    LaunchedEffect(style) {
        if(compactNavigation) style?.layers?.filterIsInstance<org.maplibre.android.style.layers.SymbolLayer>()
            ?.filter { it.id.contains("poi",ignoreCase=true) || it.id.contains("transit",ignoreCase=true) }
            ?.forEach { it.setProperties(org.maplibre.android.style.layers.PropertyFactory.visibility("none")) }
        listOf("highway-name-minor" to 12f, "highway-name-major" to 11f, "highway-name-path" to 14f).forEach { (id, minimum) ->
            style?.getLayerAs<org.maplibre.android.style.layers.SymbolLayer>(id)?.apply {
                minZoom = minimum
                setProperties(textSize(12.5f), textPadding(0.5f), textAllowOverlap(id!="highway-name-path"), textHaloWidth(1.4f), textHaloColor("#FFFFFF"), textColor("#27352F"), symbolSpacing(95f))
            }
        }
    }
    val route=navigation?.route
    val displayTracks=remember(tracks,route) {
        if(route==null) tracks else listOf(MapTrack("walk-to",route.steps.flatMap { it.points },"#B04C0B"))+tracks
    }
    val destination=navigation?.place
    val displayPins=remember(businesses,destination) { if(destination==null) businesses else businesses+
        MapBusiness("navigation-target",destination.name,destination.point.latitude,destination.point.longitude,"#A94412") }
    val sourceInputs = remember(style) { mutableMapOf<String, Any?>() }
    LaunchedEffect(style, displayTracks, sectors, displayPins, currentLocation, draftPolygon, accuracyMeters, locationFresh, deliveryPoints) {
        style?.let { updateLayers(it, displayTracks, sectors, displayPins, currentLocation, draftPolygon, sourceInputs, accuracyMeters, locationFresh, deliveryPoints) }
    }
    Box(modifier) {
        AndroidView(factory = { mapView }, modifier = Modifier.fillMaxSize().onSizeChanged { viewport = it }.semantics { contentDescription = "Mapa del territorio" })
        if(marks.isNotEmpty()) Box(Modifier.fillMaxSize().clipToBounds()) {
            val density=androidx.compose.ui.platform.LocalDensity.current
            val measurer = androidx.compose.ui.text.rememberTextMeasurer()
            val labelStyle = MaterialTheme.typography.labelSmall
            data class Tag(val text:String,val pin:Offset,val rect:androidx.compose.ui.geometry.Rect,val end:Offset?,val color:Color)
            val endpointsByGroup=remember(tracks) { tracks.groupBy { it.group }.mapValues { (_,lines) ->
                lines.mapNotNull { t -> t.points.firstOrNull()?.let { it to t.points.last() } }
            } }
            val grouped=remember(marks) { marks.groupBy { it.group }.values.sortedBy { group -> group.minOf { it.at } } }
            
            val labels=remember(map,viewport,marks,endpointsByGroup,cameraTick,style,density,labelStyle,measurer) {
                val projection=map?.projection
                val placed=mutableListOf<Tag>()
                fun project(p:MapCoordinate)=projection?.toScreenLocation(LatLng(p.latitude,p.longitude))?.let { Offset(it.x,it.y) }
                grouped.forEachIndexed { index,group ->
                val endpoints=endpointsByGroup[group.first().group].orEmpty()
                val continuousPeriods=endpoints.size==1+group.count { it.label=="CR" }
                for((mark,text) in numberedMarks(group)) {
                    val caption = if(grouped.size>1) "J${index+1}·$text" else text
                    val measured = measurer.measure(caption,style=labelStyle,maxLines=1).size
                    val width = measured.width + with(density){8.dp.toPx()}
                    val height = measured.height + with(density){3.dp.toPx()}
                    val pin=project(mark.point) ?: continue
                    if(pin.x !in 0f..viewport.width.toFloat() || pin.y !in 0f..viewport.height.toFloat()) continue
                    val left=pin.x < viewport.width/2f
                    val candidates=sequence {
                        for(step in 0..(viewport.height/height).toInt()) for(side in listOf(left,!left)) for(vertical in listOf(-1,1)) {
                            val x=(if(side) pin.x+7 else pin.x-width-7).coerceIn(0f,(viewport.width-width).coerceAtLeast(0f))
                            val y=(pin.y+vertical*(step+.6f)*(height+4)-height/2).coerceIn(0f,(viewport.height-height).coerceAtLeast(0f))
                            yield(androidx.compose.ui.geometry.Rect(x,y,x+width,y+height))
                        }
                    }
                    val rect=candidates.firstOrNull { box->placed.none { it.rect.inflate(2f).overlaps(box) } } ?: candidates.first()
                    val opening=mark.label=="IR" || mark.label=="CR"
                    // A GPS outage can split one walking period into several segments.
                    // Only use ordinal pairing when every period has exactly one segment.
                    val pairIndex=text.dropWhile { !it.isDigit() }.toIntOrNull()
                    val edge=when {
                        mark.label=="IR" -> endpoints.firstOrNull()?.first
                        mark.label=="FR" -> endpoints.lastOrNull()?.second
                        continuousPeriods && pairIndex!=null && opening -> endpoints.getOrNull(pairIndex)?.first
                        continuousPeriods && pairIndex!=null -> endpoints.getOrNull(pairIndex-1)?.second
                        else -> null
                    }
                    val near=edge ?: endpoints.asSequence().map { if(opening) it.first else it.second }.minByOrNull {
                        com.rutacomercial.app.domain.Geo.distanceM(it.latitude,it.longitude,mark.point.latitude,mark.point.longitude)
                    }
                    val end=near?.takeIf { com.rutacomercial.app.domain.Geo.distanceM(it.latitude,it.longitude,mark.point.latitude,mark.point.longitude)<=35 }?.let(::project)
                    val color=when(mark.label) { "P"->Color(0xFF854800); "CR"->Color(0xFF116658); else->Color(0xFF293463) }
                    placed+=Tag(caption,pin,rect,end,color)
                }
                }
                placed
            }
            Canvas(Modifier.fillMaxSize()) {
                labels.forEach { tag ->
                    tag.end?.let { end -> drawLine(tag.color,tag.pin,end,1.3.dp.toPx(),pathEffect=androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(5f,5f))) }
                    drawLine(tag.color,tag.pin,tag.rect.center,1.2.dp.toPx())
                    drawCircle(Color.White,4.dp.toPx(),tag.pin); drawCircle(tag.color,2.5.dp.toPx(),tag.pin)
                }
            }
            labels.forEach { tag ->
                Box(Modifier.offset { IntOffset(tag.rect.left.toInt(),tag.rect.top.toInt()) }
                    .size(with(density){tag.rect.width.toDp()},with(density){tag.rect.height.toDp()}).background(tag.color,MaterialTheme.shapes.small)
                    .border(1.dp,Color.White,MaterialTheme.shapes.small),contentAlignment=Alignment.Center) {
                    Text(tag.text,color=Color.White,style=labelStyle,maxLines=1,softWrap=false)
                }
            }
        }
        if (showHeading) {
            val ready=map
            val projection=remember(ready,cameraTick,currentLocation,viewport) {
                if(ready != null && currentLocation != null) ready.projection.toScreenLocation(LatLng(currentLocation.latitude,currentLocation.longitude)) else null
            }
            if(projection!=null && heading!=null && locationFresh && ready!=null) DirectionFan(Offset(projection.x,projection.y),heading,ready.cameraPosition.bearing)
        }
        if(fieldTools && !selectingZone) {
            val ready=map
            var flashRequest by remember { androidx.compose.runtime.mutableIntStateOf(0) }
            var showMode by remember { mutableStateOf(false) }
            LaunchedEffect(flashRequest) {
                if (flashRequest > 0) {
                    showMode = true
                    kotlinx.coroutines.delay(1800)
                    showMode = false
                }
            }
            androidx.compose.material3.FilledTonalIconButton(onClick={
                flashRequest++
                val bearing=ready?.cameraPosition?.bearing ?: 0.0
                compassMode = if(heading==null || compassMode==0 && kotlin.math.abs((bearing+540)%360-180)>1.0) 0 else (compassMode+1)%3
                if(compassMode!=2) ready?.moveCamera(CameraUpdateFactory.bearingTo(if(compassMode==1) heading?.degrees?.toDouble() ?: 0.0 else 0.0))
            }, colors=androidx.compose.material3.IconButtonDefaults.filledTonalIconButtonColors(
                containerColor=when(compassMode) { 1 -> Color(0xFF175D91); 2 -> Color(0xFF007463); else -> Color(0xFF202C34) }
            ),modifier=Modifier.align(Alignment.TopEnd).padding(top=8.dp,end=8.dp).size(48.dp)
                .border(2.dp,Color.White,CircleShape)) {
                val bearing=remember(cameraTick,ready) { ready?.cameraPosition?.bearing ?: 0.0 }
                val offNorth=kotlin.math.abs((bearing+540)%360-180)>1.0
                Canvas(Modifier.size(23.dp).graphicsLayer { rotationZ=-bearing.toFloat() }.semantics { contentDescription=when(compassMode) { 2 -> "Brújula: seguir giro"; 1 -> "Brújula: rumbo fijo"; else -> if(offNorth) "Brújula: giro libre, volver al norte" else "Brújula: norte arriba" } }) {
                    fun needle(tip:Float,color:Color) {
                        val path=androidx.compose.ui.graphics.Path().apply {
                            moveTo(size.width*.5f,tip); lineTo(size.width*.28f,size.height*.5f)
                            lineTo(size.width*.72f,size.height*.5f); close()
                        }
                        drawPath(path,color)
                    }
                    needle(0f,Color(0xFFFFAE80)); needle(size.height,Color.White)
                }
            }
            if(onRecenter!=null) androidx.compose.material3.FilledTonalIconButton(
                onClick={keepZoom=true;onRecenter()},
                modifier=Modifier.align(Alignment.TopEnd).padding(top=60.dp,end=8.dp).size(48.dp),
                colors=androidx.compose.material3.IconButtonDefaults.filledTonalIconButtonColors(
                    containerColor=if(followingLocation) Color(0xFF175D91) else MaterialTheme.colorScheme.surface,
                    contentColor=if(followingLocation) Color.White else MaterialTheme.colorScheme.onSurface)
            ) {
                androidx.compose.material3.Icon(androidx.compose.ui.res.painterResource(com.rutacomercial.app.R.drawable.worker_location),
                    contentDescription=if(followingLocation) "Siguiendo mi ubicación" else "Volver a centrar en mi ubicación",modifier=Modifier.size(23.dp))
            }
            val zone=sectors.firstOrNull { it.selected }
            if(zone!=null && destination==null) androidx.compose.runtime.key(zone.id) {
                WalkGuideControl(zone,currentLocation?.takeIf { locationFresh },previewGuide,ready,style,cameraTick,
                    explore={gesture()},previewChanged=onGuidePreview,accuracy=accuracyMeters,onGuideActive=onGuideActive,
                    walked=tracks.map { it.points })
            }
            if(heading?.movement==true && !showMode) Surface(modifier=Modifier.align(Alignment.TopEnd).padding(top=166.dp,end=8.dp),shape=MaterialTheme.shapes.small) {
                Text("Dirección de marcha",Modifier.padding(4.dp),style=MaterialTheme.typography.labelSmall)
            }
            if(showMode) Surface(
                modifier=Modifier.align(Alignment.TopEnd).padding(top=166.dp,end=8.dp),
                shape=MaterialTheme.shapes.medium,color=Color(0xFF202C34),contentColor=Color.White
            ) {
                Text(when { heading==null -> "Rumbo no disponible · norte arriba"; heading.spread>=100f -> "$compassLabel · brújula imprecisa"; else -> compassLabel },Modifier.padding(horizontal=12.dp,vertical=6.dp),style=MaterialTheme.typography.labelLarge)
            }
        }
        if (selectingZone && freehand && !moveWhileDrawing && map != null) FreehandZone(map!!,mapView,zoneColor,onSelectionChanged)
        if (selectingZone && !freehand) {
            Canvas(Modifier.fillMaxSize()) {
                val left = frame[0] * size.width; val top = frame[1] * size.height
                val right = frame[2] * size.width; val bottom = frame[3] * size.height
                drawRect(zoneColor.copy(alpha = 0.18f), Offset(left, top), Size(right-left, bottom-top))
                drawRect(zoneColor, Offset(left, top), Size(right-left, bottom-top), style = Stroke(3.dp.toPx()))
            }
            listOf(0 to 1, 2 to 1, 2 to 3, 0 to 3).forEachIndexed { index, (xIndex, yIndex) ->
                Box(Modifier.offset { IntOffset((frame[xIndex] * viewport.width).toInt(), (frame[yIndex] * viewport.height).toInt()) }
                    .offset((-22).dp, (-22).dp).size(44.dp)
                    .semantics { contentDescription = "Esquina ${index + 1} de la zona" }
                    .pointerInput(viewport, selectingZone) {
                        detectDragGestures { change, drag ->
                            change.consume()
                            if (viewport.width > 0 && viewport.height > 0) {
                                val next = frame.toMutableList()
                                next[xIndex] = (next[xIndex] + drag.x / viewport.width).coerceIn(if(xIndex == 0) 0.03f else frame[0]+0.12f, if(xIndex == 0) frame[2]-0.12f else 0.97f)
                                next[yIndex] = (next[yIndex] + drag.y / viewport.height).coerceIn(if(yIndex == 1) 0.04f else frame[1]+0.12f, if(yIndex == 1) frame[3]-0.12f else 0.90f)
                                frame = next
                            }
                        }
                    }, contentAlignment = Alignment.Center) {
                    Box(Modifier.size(18.dp).background(zoneColor, CircleShape))
                }
            }
        }
        if(navigation!=null) NavigationOverlay(navigation,map,currentLocation?.takeIf { locationFresh },accuracyMeters,cameraTick,
            { gesture() },onNavigateLocate,onDrawHere)
        (if(compactNavigation) null else if (!online) "Sin internet. El mapa solo está disponible en regiones descargadas. GPS y registros locales continúan." else warning)?.let {
            Surface(modifier = Modifier.align(Alignment.TopCenter).padding(start = 8.dp, end = 8.dp, top = 76.dp), color = MaterialTheme.colorScheme.errorContainer) {
                Text(it, modifier = Modifier.padding(8.dp), style = MaterialTheme.typography.bodySmall)
            }
        }
        Surface(modifier = Modifier.align(Alignment.BottomStart).padding(start = 4.dp, bottom = if(compactNavigation) 1.dp else 24.dp), color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f)) {
            Text("© OpenStreetMap · OpenMapTiles · OpenFreeMap", modifier = Modifier.padding(if(compactNavigation) 1.dp else 3.dp),
                style = if(compactNavigation) MaterialTheme.typography.labelSmall.copy(fontSize=8.sp,lineHeight=9.sp) else MaterialTheme.typography.labelSmall)
        }
    }
}

private fun coord(point: MapCoordinate) = JSONArray().put(point.longitude).put(point.latitude)
private fun coords(points: List<MapCoordinate>) = JSONArray().apply { points.forEach { put(coord(it)) } }
private fun feature(type: String, coordinates: JSONArray, id: String = "", color: String = "#087F75") = JSONObject()
    .put("type", "Feature").put("properties", JSONObject().put("id", id).put("color", color))
    .put("geometry", JSONObject().put("type", type).put("coordinates", coordinates))
private fun collection(features: List<JSONObject>) = JSONObject().put("type", "FeatureCollection")
    .put("features", JSONArray().apply { features.forEach { put(it) } }).toString()

private fun updateLayers(style: Style, tracks: List<MapTrack>, sectors: List<MapSector>, businesses: List<MapBusiness>, location: MapCoordinate?, draft: List<MapCoordinate>, inputs: MutableMap<String, Any?>, accuracy: Float?, fresh: Boolean, deliveries: List<MapCoordinate>) {
    fun update(id: String, input: Any?, features: () -> List<JSONObject>) {
        if (inputs.containsKey(id) && inputs[id] == input) return
        val json = collection(features())
        val source = style.getSourceAs<GeoJsonSource>("$id-source")
        if (source == null) style.addSource(GeoJsonSource("$id-source", json)) else source.setGeoJson(json)
        inputs[id] = input
    }
    update("rc-sectors", sectors) { sectors.filter { it.points.size >= 3 }.map {
        feature("Polygon", JSONArray().put(coords(it.points + it.points.first())), it.id, it.color).apply {
            getJSONObject("properties").put("completed",it.completed).put("opacity", if(it.completed) 0.18 else if(it.selected) 0.12 else 0.035).put("width", if(it.selected) 3.5 else 1.5)
        }
    } }
    update("rc-routes", tracks) { tracks.filter { it.points.size >= 2 }.map { feature("LineString", coords(it.points), it.id, it.color) } }
    update("rc-business", businesses) { businesses.map { feature("Point", coord(MapCoordinate(it.latitude, it.longitude)), it.id, it.color) } }
    update("rc-draft", draft) { if (draft.size > 1) listOf(feature("LineString", coords(if (draft.size > 2) draft + draft.first() else draft))) else emptyList() }
    update("rc-vertices", draft) { draft.map { feature("Point", coord(it)) } }
    update("rc-deliveries", deliveries) { deliveries.map { feature("Point", coord(it)) } }
    update("rc-accuracy", location to accuracy) {
        if (location != null && accuracy != null && accuracy.isFinite() && accuracy > 0) {
            val lat = location.latitude
            val ring = (0..48).map { index ->
                val angle = index * 2.0 * kotlin.math.PI / 48
                MapCoordinate(lat + accuracy * kotlin.math.sin(angle) / 111_320,
                    location.longitude + accuracy * kotlin.math.cos(angle) / (111_320 * kotlin.math.cos(Math.toRadians(lat)).coerceAtLeast(0.01)))
            }
            listOf(feature("Polygon", JSONArray().put(coords(ring))))
        } else emptyList()
    }
    update("rc-position", location to fresh) { location?.let { listOf(feature("Point", coord(it), color=if(fresh) "#1565C0" else "#787E87")) } ?: emptyList() }
    if (style.getLayer("rc-sectors") == null) {
        style.addLayer(FillLayer("rc-sectors", "rc-sectors-source").withProperties(fillColor(Expression.get("color")), fillOpacity(Expression.get("opacity"))))
        val hatch=android.graphics.Bitmap.createBitmap(16,16,android.graphics.Bitmap.Config.ARGB_8888)
        val pen=android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color=android.graphics.Color.rgb(62,140,72)
            strokeWidth=1.8f
        }
        android.graphics.Canvas(hatch).apply {
            for(offset in -16..16 step 16) drawLine(offset.toFloat(),16f,offset+16f,0f,pen)
        }
        style.addImage("rc-completed-hatch",hatch)
        style.addLayer(FillLayer("rc-completed","rc-sectors-source").withFilter(Expression.eq(Expression.get("completed"),Expression.literal(true)))
            .withProperties(fillPattern("rc-completed-hatch"),fillOpacity(.42f)))
        style.addLayer(LineLayer("rc-zone-border", "rc-sectors-source").withProperties(lineColor(Expression.get("color")), lineWidth(Expression.get("width"))))
        style.addLayer(LineLayer("rc-routes", "rc-routes-source").withProperties(lineColor(Expression.get("color")), lineWidth(2.2f), lineOpacity(0.98f)))
        style.addLayer(CircleLayer("rc-business", "rc-business-source").withProperties(circleColor(Expression.get("color")), circleRadius(8f), circleStrokeColor("#FFFFFF"), circleStrokeWidth(2f)))
        style.addLayer(LineLayer("rc-draft", "rc-draft-source").withProperties(lineColor("#7B3FC5"), lineWidth(4f), lineDasharray(arrayOf(2f, 2f))))
        style.addLayer(CircleLayer("rc-vertices", "rc-vertices-source").withProperties(circleColor("#7B3FC5"), circleRadius(5f), circleStrokeColor("#FFFFFF"), circleStrokeWidth(1f)))
        style.addLayer(CircleLayer("rc-deliveries", "rc-deliveries-source").withProperties(circleColor("#AF237D"), circleRadius(5f), circleStrokeColor("#FFFFFF"), circleStrokeWidth(2f)))
        style.addLayer(FillLayer("rc-accuracy", "rc-accuracy-source").withProperties(fillColor("#1565C0"), fillOpacity(0.12f), fillOutlineColor("#659DCD")))
        style.addLayer(CircleLayer("rc-position", "rc-position-source").withProperties(circleColor(Expression.get("color")), circleRadius(9f), circleStrokeColor("#FFFFFF"), circleStrokeWidth(3f)))
    }
}
