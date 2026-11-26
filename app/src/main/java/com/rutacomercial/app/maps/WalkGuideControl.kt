package com.rutacomercial.app.maps

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.PropertyFactory.*
import org.maplibre.android.style.sources.GeoJsonSource

private const val overviewLayer="rc-guide-overview"
private const val stepLayer="rc-guide-step"
private val guideInk=Color(0xFF9B410B)

private fun lineData(lines:List<List<MapCoordinate>>) = if(lines.isEmpty()) """{"type":"FeatureCollection","features":[]}""" else JSONObject().put("type","MultiLineString").put("coordinates",
    JSONArray().apply { for(line in lines) put(JSONArray().apply { for(p in line) put(JSONArray().put(p.longitude).put(p.latitude)) }) }
).toString()

private fun drawLines(style:Style,id:String,data:String,active:Boolean) {
    val source=style.getSourceAs<GeoJsonSource>("$id-source")
    if(source==null) style.addSource(GeoJsonSource("$id-source",data)) else source.setGeoJson(data)
    if(style.getLayer(id)!=null) return
    val layer=LineLayer(id,"$id-source").withProperties(lineColor(if(active) "#A33F00" else "#C36C22"),
        lineWidth(if(active) 4f else 2f),lineOpacity(if(active) .98f else .55f),lineDasharray(arrayOf(2f,1.4f)))
    when {
        !active && style.getLayer(stepLayer)!=null -> style.addLayerBelow(layer,stepLayer)
        style.getLayer("rc-routes")!=null -> style.addLayerBelow(layer,"rc-routes")
        else -> style.addLayer(layer)
    }
}

@Composable
internal fun BoxScope.WalkGuideControl(zone:MapSector,position:MapCoordinate?,preview:Boolean,map:MapLibreMap?,style:Style?,
    cameraTick:Int,explore:()->Unit,previewChanged:(Boolean)->Unit,accuracy:Float?=null,onGuideActive:(Boolean)->Unit={}) {
    val latestActive by rememberUpdatedState(onGuideActive)
    val context=LocalContext.current
    val prefs=remember { context.getSharedPreferences("walk_guide",0) }
    var automatic by rememberSaveable(zone.id) { mutableStateOf(preview && !prefs.getBoolean(zone.id,false)) }
    var visible by rememberSaveable(zone.id) { mutableStateOf(automatic) }
    var index by rememberSaveable(zone.id) { mutableIntStateOf(0) }
    var streets by remember(zone.points) { mutableStateOf<List<GuideStreet>?>(null) }
    var guide by remember(zone.points) { mutableStateOf<StreetGuide?>(null) }
    var error by remember(zone.points) { mutableStateOf<String?>(null) }
    val latestPosition by rememberUpdatedState(position)
    val latestPreviewChanged by rememberUpdatedState(previewChanged)
    var needsPosition by remember(zone.points) { mutableStateOf(false) }
    LaunchedEffect(visible,zone.points,needsPosition && position!=null) {
        if(!visible) return@LaunchedEffect
        error=null
        try {
            val network=streets ?: GuideStreets.load(context,zone.points).also { streets=it }
            val origin=latestPosition
            val plan=withContext(Dispatchers.Default) { planStreetGuide(network,zone.points,origin ?: requireNotNull(DownloadArea.from(zone.points)).center) }
            require(plan.steps.isNotEmpty()) { "No hay calles peatonales cartografiadas en esta zona." }
            guide=plan
            index=index.coerceIn(plan.steps.indices)
            needsPosition=origin==null
        } catch(e:CancellationException) { throw e }
        catch(e:Exception) {
            guide=null
            error=if(e is IllegalArgumentException) e.message else "No se pudieron cargar las calles. Revisá la conexión y volvé a tocar Guía."
        }
    }
    DisposableEffect(visible,automatic) {
        latestPreviewChanged(visible && automatic)
        onDispose { latestPreviewChanged(false) }
    }
    LaunchedEffect(visible,automatic,guide,error) {
        if(visible && automatic && (guide!=null || error!=null)) {
            if(guide!=null) prefs.edit().putBoolean(zone.id,true).apply()
            delay(9000)
            automatic=false;visible=false
        }
    }
    val plan=guide.takeIf { visible }
    val step=plan?.steps?.getOrNull(index)
    DisposableEffect(visible,automatic) {
        latestActive(visible && !automatic)
        onDispose { latestActive(false) }
    }
    val follower=remember(plan,index) { step?.let { StepFollower(it.points) } }
    var progress by remember(follower) { mutableStateOf<StepProgress?>(null) }
    var ended by remember(plan,index) { mutableStateOf(false) }
    LaunchedEffect(follower,position,accuracy,automatic) {
        progress=if(!automatic && position!=null) follower?.sample(position,accuracy) else null
        if(progress?.advance==true && !ended && plan!=null && step!=null) {
            val next=plan.steps.getOrNull(index+1)
            if(next==null) ended=true else if(next.section==step.section) index++
        }
    }
    val upcoming=step!=null && !ended && progress?.let { it.remaining<=40 && it.offRoute<=18 }==true
    val next=plan?.steps?.getOrNull(index+1)
    DisposableEffect(style) {
        onDispose {
            if(style?.isFullyLoaded==true) for(id in listOf(stepLayer,overviewLayer)) {
                style.removeLayer(id);style.removeSource("$id-source")
            }
        }
    }
    LaunchedEffect(style,plan) {
        val s=style ?: return@LaunchedEffect
        val data=withContext(Dispatchers.Default) { lineData(plan?.streets.orEmpty()) }
        withContext(Dispatchers.Main.immediate) {
            if(s.isFullyLoaded && (plan!=null || s.getSource("$overviewLayer-source")!=null))
                drawLines(s,overviewLayer,data,false)
        }
    }
    LaunchedEffect(style,step,plan) {
        val s=style ?: return@LaunchedEffect
        withContext(Dispatchers.Main.immediate) {
            if(s.isFullyLoaded && (step!=null || s.getSource("$stepLayer-source")!=null))
                drawLines(s,stepLayer,lineData(listOfNotNull(step?.points)),true)
        }
    }
    fun closeGuide() { visible=false;automatic=false }
    fun focus(step:GuideStep) {
        if(map==null) return
        explore()
        val bounds=LatLngBounds.Builder()
        step.points.forEach { bounds.include(LatLng(it.latitude,it.longitude)) }
        map.animateCamera(CameraUpdateFactory.newLatLngBounds(bounds.build(),(44*context.resources.displayMetrics.density).toInt()),380)
    }
    var focusRequest by remember { mutableIntStateOf(0) }
    LaunchedEffect(focusRequest,step) { if(focusRequest>0 && step!=null && !automatic) focus(step) }
    if(step!=null && map!=null) {
        GuideFlow(step,map,cameraTick)
        if(upcoming && next!=null && next.section==step.section) GuideFlow(next,map,cameraTick)
    }
    FilledTonalButton(onClick={
        if(visible) closeGuide() else { automatic=false;guide=null;error=null;index=0;visible=true;focusRequest++ }
    },modifier=Modifier.align(Alignment.TopEnd).padding(top=112.dp,end=8.dp).heightIn(min=48.dp),
        contentPadding=PaddingValues(horizontal=12.dp),
        colors=ButtonDefaults.filledTonalButtonColors(containerColor=if(visible) guideInk else MaterialTheme.colorScheme.surface,
            contentColor=if(visible) Color.White else MaterialTheme.colorScheme.onSurface)) {
        Text(if(visible) "Ocultar guía" else "Guía")
    }
    if(visible) Surface(Modifier.align(Alignment.BottomCenter).padding(start=8.dp,end=8.dp,bottom=48.dp),
        shape=MaterialTheme.shapes.medium,tonalElevation=4.dp,shadowElevation=2.dp) {
        Row(Modifier.padding(horizontal=6.dp,vertical=2.dp),verticalAlignment=Alignment.CenterVertically) {
            if(step!=null && index>0) TextButton(onClick={automatic=false;index--;focusRequest++},modifier=Modifier.size(48.dp),
                contentPadding=PaddingValues(0.dp)) { Text("‹",Modifier.semantics { contentDescription="Paso anterior" },style=MaterialTheme.typography.titleLarge) }
            Column(Modifier.weight(1f).padding(horizontal=6.dp,vertical=4.dp)) {
                if(step!=null) {
                    val section=if(plan.steps.last().section>1) " · sector "+step.section else ""
                    Text("Guía · paso "+(index+1)+" de "+plan.steps.size+section,style=MaterialTheme.typography.labelSmall)
                    if(ended) Text("⚑ Fin de la guía",style=MaterialTheme.typography.titleMedium)
                    else if(upcoming) Text(turnArrow(step,next)+"  "+(progress?.remaining?.toInt() ?: 0)+" m · "+
                        (next?.takeIf { it.section==step.section }?.street ?: "Fin del sector"),style=MaterialTheme.typography.titleMedium)
                    val prev=plan.steps.getOrNull(index-1)
                    val returning=prev?.points?.asReversed()==step.points
                    Text((if(returning) "Volvé por " else "Seguí por ")+step.street,maxLines=1,overflow=TextOverflow.Ellipsis,style=MaterialTheme.typography.titleSmall)
                } else Text(error ?: "Preparando guía de calles…",style=MaterialTheme.typography.bodySmall)
            }
            if(step!=null && index<plan.steps.lastIndex) TextButton(onClick={automatic=false;index++;focusRequest++}) { Text("Siguiente") }
        }
    }
}

private data class ScreenLeg(val origin:Offset,val direction:Offset,val length:Float,val start:Float)

@Composable
internal fun GuideFlow(step:GuideStep,map:MapLibreMap,cameraTick:Int) {
    val legs=remember(step,map,cameraTick) {
        val points=step.points.map { map.projection.toScreenLocation(LatLng(it.latitude,it.longitude)).let { p->Offset(p.x,p.y) } }
        var walked=0f
        points.zipWithNext().mapNotNull { (a,b) ->
            val length=(b-a).getDistance()
            if(length<0.1f) null else ScreenLeg(a,(b-a)/length,length,walked).also { walked+=length }
        }
    }
    val transition=rememberInfiniteTransition(label="Sentido del tramo")
    val phase=transition.animateFloat(0f,1f,infiniteRepeatable(tween(1800,easing=LinearEasing)),label="Flechas")
    Canvas(Modifier.fillMaxSize()) {
        val last=legs.lastOrNull() ?: return@Canvas
        val total=last.start+last.length
        val spacing=56.dp.toPx()
        val phaseValue=phase.value
        for(leg in legs) {
            val f=leg.direction
            val side=Offset(-f.y,f.x)
            val fromCenter=(center-leg.origin).let { it.x*f.x+it.y*f.y }
            val radius=size.width+size.height
            val low=maxOf(0f,fromCenter-radius)
            val high=minOf(leg.length,fromCenter+radius)
            val offset=phaseValue*spacing-leg.start
            var along=if(total<spacing) total*(.35f+.3f*phaseValue)-leg.start
                else offset+kotlin.math.ceil((low-offset)/spacing)*spacing
            while(along>=low && along<high) {
                val mid=leg.origin+f*along
                val tip=mid+f*4.dp.toPx()
                val back=mid-f*3.dp.toPx()
                for(sign in -1..1 step 2) {
                    val wing=back+side*(sign*3.5.dp.toPx())
                    drawLine(Color.White,wing,tip,5.dp.toPx())
                    drawLine(guideInk,wing,tip,2.5.dp.toPx())
                }
                along+=spacing
            }
        }
        legs.firstOrNull()?.origin?.let { start ->
            drawCircle(Color.White,5.dp.toPx(),start)
            drawCircle(guideInk,3.dp.toPx(),start)
        }
    }
}
