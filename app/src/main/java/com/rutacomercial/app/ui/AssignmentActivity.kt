package com.rutacomercial.app.ui

import androidx.compose.ui.Alignment
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import com.rutacomercial.app.R
import android.content.*
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rutacomercial.app.RutaApplication
import com.rutacomercial.app.data.*
import com.rutacomercial.app.domain.Geo
import com.rutacomercial.app.domain.ZoneCoverage
import com.rutacomercial.app.maps.*
import com.rutacomercial.app.tracking.TrackingController
import com.rutacomercial.app.tracking.TrackingRuntime
import org.json.*
import java.io.File
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.withLock

private val proposalLock=kotlinx.coroutines.sync.Mutex()

class AssignmentActivity : TrackingMapActivity() {
    private val prefs by lazy { getSharedPreferences("assignments",MODE_PRIVATE) }
    private val repo get()=(application as RutaApplication).repository
    private val directory get()=File(filesDir,"assignments").apply { mkdirs() }
    private fun file(id:String,kind:String)=File(directory,"${UUID.fromString(id)}.$kind")
    private fun save(id:String,kind:String,json:JSONObject) {
        val dest=file(id,kind)
        val temp=File.createTempFile("incoming",null,directory)
        try { temp.writeText(json.toString()); java.nio.file.Files.move(temp.toPath(),dest.toPath(),java.nio.file.StandardCopyOption.ATOMIC_MOVE,java.nio.file.StandardCopyOption.REPLACE_EXISTING) } finally { temp.delete() }
    }
    private fun read(id:String,kind:String)=file(id,kind).takeIf { it.exists() }?.let { JSONObject(it.readText()) }
    private fun signedTask(task:JSONObject):JSONObject {
        if(task.optInt("version")==3 && OwnerIdentity.verified(task)) return task
        read(task.getString("id"),"task")?.takeIf { it.optInt("version")==3 && OwnerIdentity.verified(it) }?.let { return it }
        val signed=OwnerIdentity.sign(task)
        save(signed.getString("id"),"task",signed)
        return signed
    }
    private fun shareName(doc:JSONObject):String {
        val prefix = when {
            doc.optString("kind") == "proposal" -> "Propuesta"
            doc.optString("kind") == "task" -> "Tarea"
            doc.optString("state","FINISHED") == "FINISHED" -> "Recorrido-final"
            else -> "Avance"
        }
        val date = java.time.Instant.ofEpochMilli(doc.optLong("capturedAt",doc.optLong("createdAt",System.currentTimeMillis())))
            .atZone(java.time.ZoneId.systemDefault()).format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
        fun part(value:String) = value.replace(Regex("[^\\p{L}\\p{N}]+"),"-").trim('-').take(24)
        return listOf(prefix,part(doc.optString("name")),part(doc.optString("worker")),date,doc.getString("id").take(8))
            .filter { it.isNotBlank() }.joinToString("-") + ".ruta.json"
    }
    private fun share(json:JSONObject) {
        val folder=File(cacheDir,"task-share").apply { mkdirs() }
        val folderForId=File(folder,UUID.fromString(json.getString("id")).toString()).apply { mkdirs() }
        val f=File(folderForId,shareName(json))
        val bytes=json.toString().toByteArray(Charsets.UTF_8)
        require(bytes.size <= 5_000_000) { "El archivo supera el tamaño admitido." }
        f.writeBytes(bytes)
        val uri=FileProvider.getUriForFile(this,"$packageName.tasks",f)
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("application/vnd.rutacomercial."+json.getString("kind")).putExtra(Intent.EXTRA_STREAM,uri)
            .apply { clipData = ClipData.newRawUri("RutaComercial",uri) }.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),"Compartir archivo"))
    }
    private fun geometry(zone:JSONObject)=Geo.parsePolygon(zone.getString("polygon")).map { MapCoordinate(it.first,it.second) }
    private fun tracks(result:JSONObject?):List<MapTrack> {
        return result?.let { decodeLines(it.getJSONArray("segments"), it.getString("id")) }.orEmpty()
    }
    private var incomingRequest by mutableIntStateOf(0)
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        incomingRequest++
    }

    override fun onCreate(state:Bundle?) {
        super.onCreate(state)
        setContent { RutaTheme { Surface(Modifier.fillMaxSize(),color=MaterialTheme.colorScheme.surfaceContainerLow) { MapWindow { Panel() } } } }
    }
    @Composable private fun Panel() {
        val vm:RutaViewModel=viewModel(factory=remember { RutaViewModel.factory(repo) })
        val data by vm.data.collectAsStateWithLifecycle()
        val tracking by TrackingRuntime.state.collectAsStateWithLifecycle()
        val action=rememberLocationAction(vm)
        val scope=rememberCoroutineScope()
        val worker = com.rutacomercial.app.BuildConfig.WORKER_EDITION
        var reportId by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(intent.getStringExtra("reportId")?.takeIf { runCatching { UUID.fromString(it) }.isSuccess }) }
        var singleReport by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(intent.getBooleanExtra("individual",false)) }
        var showHistory by remember { mutableStateOf(false) }
        var id by remember { mutableStateOf(if(worker) prefs.getString("selected",null) else intent.getStringExtra("taskId")?.takeIf { runCatching { UUID.fromString(it) }.isSuccess }) }
        var revision by remember { mutableIntStateOf(0) }
        var msg by remember { mutableStateOf("") }
        var loading by remember { mutableStateOf(intent.data!=null || intent.action==Intent.ACTION_SEND) }
        var drawingProposal by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
        var incoming by remember { mutableStateOf<JSONObject?>(null) }
        val cloud = (application as RutaApplication).cloud
        val onlineRevision by cloud.revision.collectAsStateWithLifecycle()
        val onlineStatus by cloud.status.collectAsStateWithLifecycle()
        val deviceId by cloud.device.collectAsStateWithLifecycle()
        val pendingOnline = remember(onlineRevision) { cloud.pending() }
        val zone=remember(id,revision) { id?.let { read(it,"task") } }
        val work=zoneWork(revision)
        val taskReports=remember(work,id) { work.reports.filter { it.taskId==id } }
        val result=remember(id,revision,taskReports) { (taskReports.firstOrNull()?.id ?: id)?.let { read(it,"result") ?: read(it,"received")?.takeIf { doc -> doc.optString("kind")=="result" } } }
        val ids=remember(revision) { directory.listFiles().orEmpty().filter { it.extension=="task" }.sortedByDescending { it.lastModified() }.map { it.nameWithoutExtension } }
        val polygon=remember(zone) { zone?.let(::geometry).orEmpty() }
        val trustedZone=remember(zone,revision) { worker && zone!=null && zone.optString("ownerKey")==prefs.getString(OwnerIdentity.binding,null) && OwnerIdentity.verified(zone) }

        val sessions=remember(data.audit,id) { data.audit.filter { it.action=="ASSIGNMENT" && it.detail==id }.map { it.entityId } }
        var historyJourney by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf<String?>(null) }
        val journeyId=historyJourney?.takeIf { it in sessions } ?: sessions.lastOrNull()
        val journey=data.journeys.find { it.id==journeyId }
        val busyJourney=data.activeJourney
        LaunchedEffect(Unit) { TrackingController.recoverIfNeeded(this@AssignmentActivity) }
        LaunchedEffect(vm) { vm.events.collect { msg=it } }
        fun open(uri:Uri) { loading=true; msg=""; scope.launch {
            try {
                val obj=withContext(Dispatchers.IO) {
                    require(uri.scheme=="content")
                    val bytes=contentResolver.openInputStream(uri)!!.use { stream ->
                        val out=java.io.ByteArrayOutputStream(); val buf=ByteArray(8192)
                        while(true) { val n=stream.read(buf); if(n<0) break; require(out.size()+n<=5_000_000); out.write(buf,0,n) }; out.toByteArray()
                    }
                    JSONObject(String(bytes,Charsets.UTF_8)).also { o ->
                        require(o.getInt("version") in 1..3); UUID.fromString(o.getString("id"))
                        when(o.getString("kind")) {
                            "proposal" -> require(validProposal(o))
                            "task" -> { require(o.getString("name").length in 1..300 && o.getString("worker").length in 1..100 && o.optString("note").length<=500)
                                require(o.getString("polygon").length<=100000 && Geo.validPolygon(Geo.parsePolygon(o.getString("polygon")))) }
                            "result" -> { validateBundle(o) }
                            else -> error("Archivo desconocido")
                        }
                    }
                }
                if(obj.getString("kind")=="proposal") {
                    if(worker) { msg="Abrí esta propuesta en RutaComercial Owner.";return@launch }
                    val key=obj.getString("id")
                    val previous=read(key,"proposal")
                    require(previous==null || listOf("polygon","worker","name").all { previous.getString(it)==obj.getString(it) })
                    if(previous==null) save(key,"proposal",obj)
                    incoming=obj;revision++;return@launch
                }
                val isTask = obj.getString("kind") == "task"
                if(worker != isTask) {
                    msg=if(isTask) "Abrí esta tarea en RutaComercial Repartidor." else "Abrí este informe en RutaComercial Owner."
                    return@launch
                }
                if(worker && busyJourney!=null && prefs.getString("selected",null)!=obj.getString("id")) {
                    msg="Terminá tu recorrido antes de aceptar otra tarea."
                    return@launch
                }
                if(isTask) {
                    if(obj.optInt("version")!=3 || !OwnerIdentity.verified(obj)) {
                        msg="Pedile al Owner que vuelva a compartir la tarea desde la versión 0.9 o posterior."
                        return@launch
                    }
                    val trusted=prefs.getString(OwnerIdentity.binding,null)
                    if(trusted!=null && trusted!=obj.getString("ownerKey")) {
                        msg="Esta tarea pertenece a otro Owner."
                        return@launch
                    }
                }
                val key=obj.getString("id")
                if(!isTask) {
                    require(obj.optString("state","FINISHED") in setOf("ACTIVE","PAUSED","FINISHED"))
                    if(obj.has("assignmentId")) UUID.fromString(obj.getString("assignmentId"))
                } else {
                    if(obj.has("previousSegments")) decodeLines(JSONArray(obj.getString("previousSegments")),"previous")
                    previousMarks(obj)
                }
                val doc=if(isTask) obj else {
                    val newest=newerReport(read(key,"result"),newerReport(read(key,"received"),obj))
                    // An older envelope can still deliver a missing earlier session.
                    JSONObject(newest.toString()).apply {
                        obj.optJSONArray("previousReports")?.let { put("previousReports",it) }
                    }
                }
                save(key,"received",doc)
                revision++;incoming=doc
            } catch(e:Exception) { msg="No se pudo abrir el archivo de RutaComercial." } finally { loading=false }
        } }
        val picker=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let(::open) }
        var exportId by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf<String?>(null) }
        val saveCopy=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
            val key=exportId
            if(uri!=null && key!=null) scope.launch {
                msg=runCatching { withContext(Dispatchers.IO) {
                    val received=requireNotNull(read(key,"received"))
                    requireNotNull(contentResolver.openOutputStream(uri,"wt")).use { it.write(received.toString().toByteArray()) }
                }; "Copia guardada" }.getOrDefault("No se pudo guardar la copia.")
            }
        }
        var inbox by remember { mutableStateOf(false) }
        if(inbox) AlertDialog(onDismissRequest={inbox=false},title={Text("Archivos recibidos")},text={
            Column(Modifier.verticalScroll(rememberScrollState())) {
                val received=remember(revision) { directory.listFiles().orEmpty().filter { it.extension=="received" || !worker && it.extension=="proposal" }.sortedByDescending { it.lastModified() } }
                if(received.isEmpty()) Text("Abrí el documento desde el chat de WhatsApp y elegí RutaComercial. Después lo encontrarás acá.")
                received.forEach { entry ->
                    val doc=remember(entry,revision) { read(entry.nameWithoutExtension,entry.extension) }
                    if(doc!=null) TextButton(onClick={incoming=doc;msg="";inbox=false}) {
                        Text(if(doc.optString("kind")=="proposal") "Propuesta · "+doc.optString("name") else if(worker) doc.optString("name")+" · "+doc.optString("worker") else "Informe · "+(read(entry.nameWithoutExtension,"task")?.optString("name") ?: entry.nameWithoutExtension.take(8)))
                    }
                }
            }
        },confirmButton={Button(onClick={inbox=false;picker.launch(arrayOf("*/*"))}) { Text("Buscar en el teléfono") }},dismissButton={TextButton(onClick={inbox=false}) { Text("Cerrar") }})
        var sharedText by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf<String?>(null) }
        var referenceLat by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf<Double?>(null) }
        var referenceLon by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf<Double?>(null) }
        var referenceRequest by androidx.compose.runtime.saveable.rememberSaveable { mutableIntStateOf(0) }
        fun proposeAt(p:MapCoordinate) {
            referenceLat=p.latitude;referenceLon=p.longitude;referenceRequest++;drawingProposal=true
        }
        if(sharedText!=null && worker) SharedLocationDialog(sharedText!!,{sharedText=null}) { location->
            sharedText=null;incoming=null;proposeAt(location.point)
        }
        LaunchedEffect(incomingRequest) {
            if(worker && intent.action==Intent.ACTION_SEND && intent.type=="text/plain") {
                val text=intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
                if(text!=null && text.length in 1..8192) sharedText=text else msg="Compartí un lugar o sus coordenadas."
                loading=false;setIntent(Intent());return@LaunchedEffect
            }
            val uri=if(intent.action==Intent.ACTION_SEND) intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM) else intent.data
            uri?.let(::open)
        }
        var qrTask by remember { mutableStateOf<JSONObject?>(null) }
        qrTask?.let { task -> OwnerPairingCard(task) { qrTask=null } }
        var person by remember { mutableStateOf("") }; var note by remember { mutableStateOf("") }
        var recipient by remember(person) { mutableStateOf(cloud.recipient(person)) }
        @Composable fun LinkCard() = WorkerLinkCard(deviceId,onLink={ invite,name ->
            runCatching { cloud.link(invite,name) }
                .onSuccess { msg="✓ Vinculado. Tus tareas y recorridos se sincronizan con tu Owner." }
                .onFailure { msg=it.localizedMessage ?: "No se pudo vincular." }
        },onShareCode={
            startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain")
                .putExtra(Intent.EXTRA_TEXT,"Mi código de RutaComercial Repartidor: "+deviceId),"Enviar mi código al Owner"))
        },lookup={ cloud.lookupInvite(it) })
        var sector by remember { mutableStateOf(intent.getStringExtra("sector")) }
        val workerMap = worker && zone!=null && incoming==null
        var options by remember(id) { mutableStateOf(false) }
        var confirmNew by remember(id) { mutableStateOf(false) }
        val nav=remember(id) { PlaceNavigation() }
        if(worker && drawingProposal && incoming==null && !loading) {
            val references=remember(work.tasks,data.journeys,data.audit) {
                val finished=data.journeys.filter { it.state=="FINISHED" }.map { it.id }.toSet()
                val walked=data.audit.filter { it.action=="ASSIGNMENT" && it.entityId in finished }.map { it.detail }.toSet()
                work.tasks.distinctBy { it.polygon }.map { t->
                    val done=t.id in walked
                    MapSector(t.id,t.name,if(done) "#68B75F" else "#527A80",Geo.parsePolygon(t.polygon).map { MapCoordinate(it.first,it.second) },completed=done)
                }
            }
            val referenceTracks=remember(data.points,data.audit) {
                data.audit.filter { it.action=="ASSIGNMENT" }.flatMap { mapTracks(data,it.entityId) }
            }
            WorkerZoneProposal(zone?.optString("worker").orEmpty(),
                tracking.position?.takeIf { System.currentTimeMillis()-(tracking.fixAt ?: 0L) in 0..30000 },
                polygon,references,referenceTracks,referenceLat?.let { lat->referenceLon?.let { MapCoordinate(lat,it) } },referenceRequest,
                onImport={sharedText=""},onLocate={action("locate")},online=cloud.canPropose(),onShare={ doc->
                    if(cloud.canPropose()) runCatching { cloud.propose(doc) }
                        .onSuccess { msg="Propuesta enviada. Te avisamos cuando el Owner la acepte.";drawingProposal=false;referenceLat=null;referenceLon=null }
                        .onFailure { android.widget.Toast.makeText(this,"No se pudo enviar la propuesta.",android.widget.Toast.LENGTH_LONG).show() }
                    else runCatching { share(doc) }.onFailure { android.widget.Toast.makeText(this,"No se pudo compartir la propuesta.",android.widget.Toast.LENGTH_LONG).show() }
                },onClose={drawingProposal=false;referenceLat=null;referenceLon=null})
            return
        }
        Column(Modifier.fillMaxSize().systemBarsPadding().then(if((worker || reportId!=null) && incoming==null) Modifier else Modifier.verticalScroll(rememberScrollState())).padding(8.dp),verticalArrangement=Arrangement.spacedBy(5.dp)) {
            if(loading) { LinearProgressIndicator(); return@Column }
            if(workerMap) Surface(shape=MaterialTheme.shapes.medium,color=MaterialTheme.colorScheme.primaryContainer) {
                Row(Modifier.fillMaxWidth().padding(start=12.dp,end=4.dp),verticalAlignment=Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("RutaComercial Repartidor",style=MaterialTheme.typography.labelSmall)
                        Text(zone!!.optString("name")+" · "+zone.optString("worker"),style=MaterialTheme.typography.titleSmall,
                            maxLines=1,overflow=androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                    }
                    TextButton(onClick={options=true}) { Text("Opciones") }
                }
            } else AssignmentBanner(
                title=when { incoming?.optString("kind")=="proposal" -> "Zona propuesta"; incoming?.optString("kind")=="task" -> "Zona recibida"; worker && zone==null -> "Esperando una tarea"; worker -> "Mi recorrido"; else -> "Asignaciones" },
                detail=when { incoming!=null -> incoming?.optString("name").orEmpty(); zone!=null -> zone.optString("name")+" · "+zone.optString("worker"); worker -> if(cloud.enabled) "Cuando tu Owner te asigne una zona, aparece acá." else "Abrí la zona que te envió tu Owner."; else -> "RutaComercial Owner" },
                large=incoming!=null || worker && zone==null
            )
            if(msg.isNotBlank()) Text(msg)
            if(cloud.enabled && incoming==null && worker && !workerMap && cloud.canPropose())
                Text("✓ Vinculado con tu Owner · "+onlineStatus,style=MaterialTheme.typography.labelMedium)
            else if(cloud.enabled && incoming==null && worker && !workerMap) {
                LinkCard()
                Text(onlineStatus,style=MaterialTheme.typography.labelMedium)
            }
            if(worker && busyJourney==null && incoming==null) pendingOnline.forEach { key ->
                val doc=remember(key,onlineRevision) { read(key,"received") }
                if(doc!=null) Surface(shape=MaterialTheme.shapes.large,color=MaterialTheme.colorScheme.primaryContainer) {
                    Column(Modifier.fillMaxWidth().padding(12.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                        Text("Tenés una tarea nueva",style=MaterialTheme.typography.titleMedium)
                        Button(onClick={incoming=doc},modifier=Modifier.fillMaxWidth().heightIn(min=56.dp)) { Text("Ver tarea · "+doc.optString("name"),fontSize=18.sp) }
                    }
                }
            }
            incoming?.let { obj ->
                if(obj.optString("kind")=="proposal" && !worker) {
                    val proposal=obj.getString("id")
                    val taskKey=proposalKey(proposal,"task")
                    val zoneKey=proposalKey(proposal,"zone")
                    val contour=remember(obj) { geometry(obj) }
                    val existing=remember(obj,revision) { read(taskKey,"task") }
                    var zoneTitle by androidx.compose.runtime.saveable.rememberSaveable(proposal) { mutableStateOf(existing?.optString("name") ?: obj.getString("name")) }
                    var saving by remember(obj) { mutableStateOf(false) }
                    Text("Propuesta de "+obj.getString("worker"))
                    TerritoryMap(Modifier.fillMaxWidth().mapHeight(.55f),sectors=listOf(MapSector(zoneKey,zoneTitle,"#087F75",contour,true)),fitArea=contour)
                    if(existing==null) Field("Nombre de la zona",zoneTitle,{zoneTitle=it.take(200)})
                    else Text("Tarea guardada · "+existing.getString("name"))
                    Button(modifier=Modifier.fillMaxWidth(),enabled=!saving && zoneTitle.isNotBlank(),onClick={
                        saving=true
                        scope.launch {
                            try {
                                val task=withContext(Dispatchers.IO) {
                                    proposalLock.withLock {
                                        read(taskKey,"task") ?: run {
                                            val area=repo.dao.sector(zoneKey) ?: repo.saveSector(Sector(id=zoneKey,name=zoneTitle.trim(),polygonJson=obj.getString("polygon"),status="ACTIVE"))
                                            require(area.polygonJson==obj.getString("polygon"))
                                            signedTask(JSONObject().put("kind","task").put("id",taskKey).put("name",area.name)
                                                .put("worker",obj.getString("worker")).put("note","").put("polygon",area.polygonJson).put("sectorId",area.id))
                                        }
                                    }
                                }
                                prefs.edit().putBoolean("owner:"+taskKey,true).apply()
                                id=taskKey;reportId=null;incoming=null;revision++;setIntent(Intent())
                                val phone=cloud.proposer(proposal)
                                if(phone!=null) { cloud.assign(task,phone);msg="Zona guardada. La tarea se envió en línea a "+obj.getString("worker")+"." }
                                else { msg="Zona y tarea guardadas. Ya podés enviar el QR o el código.";share(task) }
                            } catch(e:Exception) { msg=e.localizedMessage ?: "No se pudo preparar la tarea." }
                            finally { saving=false }
                        }
                    }) { Text(when { cloud.proposer(proposal)!=null && existing==null -> "Aceptar y enviar tarea"; existing==null -> "Aceptar y compartir tarea"; else -> "Reenviar tarea" }) }
                    TextButton(enabled=!saving,onClick={incoming=null;setIntent(Intent())}) { Text("Volver") }
                    return@Column
                }
                val key=obj.getString("id");val invitation=obj.getString("kind")=="task"
                Surface(shape=MaterialTheme.shapes.large,color=MaterialTheme.colorScheme.surface) {
                    Column(Modifier.fillMaxWidth().padding(16.dp)) {
                        Text(if(invitation) "Repartidor" else "Informe recibido",style=MaterialTheme.typography.labelMedium)
                        if(invitation) Text(obj.getString("worker"),style=MaterialTheme.typography.titleLarge)
                        if(obj.optString("note").isNotBlank()) Text(obj.optString("note"))
                    }
                }
                var confirmed by remember(obj) { mutableStateOf(false) }
                val needsPairing=invitation && !(prefs.getString(OwnerIdentity.binding,null)==obj.optString("ownerKey") && (cloud.onlineTask(key) || read(key,"task")?.optString("signature")==obj.optString("signature")))
                if(invitation && (confirmed || !needsPairing)) {
                    val preview=remember(obj) { geometry(obj) }
                    TerritoryMap(Modifier.fillMaxWidth().mapHeight(.45f),sectors=listOf(MapSector(key,obj.getString("name"),"#087F75",preview,true)),fitArea=preview)
                }
                if(needsPairing && !confirmed) PairFirstTask(obj,prefs) { confirmed=true;msg="" }
                Button(modifier=Modifier.fillMaxWidth().heightIn(min=54.dp),enabled=!needsPairing || confirmed,onClick={
                    try {
                        if(invitation) {


                            val previous=read(key,"task")
                            require(previous==null || listOf("name","worker","polygon","note").all { previous.optString(it)==obj.optString(it) }) { "La tarea existente tiene otros datos." }
                            OwnerIdentity.accept(prefs,obj,confirmed || !needsPairing)
                            save(key,"task",obj);cloud.accepted(key);historyJourney=null;prefs.edit().putString("selected",key).apply()
                        } else {
                            require(!worker)
                            for(entry in validateBundle(obj)) {
                                val k=entry.getString("id")
                                val clean=JSONObject(entry.toString()).apply { remove("previousReports") }
                                val saved=read(k,"result")
                                val staged=read(k,"received")
                                val assignment=clean.optString("assignmentId",k)
                                require(listOfNotNull(saved,staged).all { it.optString("assignmentId",k)==assignment }) {
                                    "La jornada guardada pertenece a otra asignación."
                                }
                                val newest=newerReport(saved,newerReport(staged,clean))
                                save(k,"result",JSONObject(newest.toString()).apply { remove("previousReports") })
                            }
                            singleReport=false;reportId=key
                        }
                        id=key;revision++;incoming=null;msg="";setIntent(Intent())
                    } catch(e:Exception) { msg=e.localizedMessage ?: "No se pudo guardar la tarea. Volvé a intentarlo." }
                }) { Text(if(invitation) "Aceptar tarea" else "Abrir informe") }
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
                    TextButton(onClick={incoming=null;if(intent.data!=null || intent.action==Intent.ACTION_SEND) finish()}) { Text("Cancelar") }
                    TextButton(onClick={exportId=key;saveCopy.launch(shareName(obj))}) { Text("Guardar copia") }
                }
                return@Column
            }

            if(!worker && reportId!=null) {
                val report=remember(reportId,revision) { read(reportId!!,"result") ?: read(reportId!!,"received") }
                if(report!=null) {
                    val lines=remember(report) { tracks(report) }
                    val markers=remember(report) { resultMarks(report,lines).map { it.copy(group=report.getString("id")) } }
                    val taskKey=report.optString("assignmentId",reportId!!)
                    val area=remember(reportId,report) { read(taskKey,"task") }
                    val savedZone=work.tasks.find { it.id==taskKey }?.let { task -> data.sectors.firstOrNull { task.belongsTo(it,data.sectors) } }
                    val outline=remember(area) { area?.let(::geometry).orEmpty() }
                    val related=remember(work,savedZone,taskKey,data.sectors) {
                        if(savedZone!=null) work.reportsFor(work.tasksFor(savedZone,data.sectors))
                        else work.reports.filter { it.taskId==taskKey }
                    }
                    if(related.size>1 && !singleReport) {
                        ZoneSummary(related,outline,savedZone?.name ?: report.optString("name"),savedZone?.status=="COMPLETED") { entry ->
                            singleReport=true;reportId=entry.id
                        }
                    } else {
                        if(related.size>1) FilledTonalButton(onClick={singleReport=false}) { Text("Ver zona acumulada") }
                        if(report.optString("state","FINISHED")!="FINISHED") Text("Avance recibido · "+dateText(report.optLong("capturedAt")))
                        if(area==null) Text("Informe recibido · sin zona local asociada")
                        val points=remember(lines,outline) { lines.flatMap { it.points }+outline+markers.map { it.point } }
                        if(points.isNotEmpty()) TerritoryMap(Modifier.fillMaxWidth().weight(1f),tracks=lines,marks=markers,fitArea=points,
                            sectors=if(outline.isEmpty()) emptyList() else listOf(MapSector(reportId!!,area!!.optString("name"),if(savedZone?.status=="COMPLETED") "#68B75F" else "#087F75",outline,true,completed=savedZone?.status=="COMPLETED")))
                        else Text("El informe no contiene posiciones GPS.")
                        Text(number(report.getDouble("meters")/1000,2)+" km · "+hours(report.getLong("duration")))
                        val duration=report.getLong("duration")
                        if(duration>0) Text("Velocidad media · "+number(report.getDouble("meters")*3600/duration,2)+" km/h")
                        var details by remember(reportId) { mutableStateOf(false) }
                        FilledTonalButton(onClick={details=true}) { Text("Ver trazabilidad") }
                        if(details) AlertDialog(onDismissRequest={details=false},title={Text("Trazabilidad del recorrido")},text={
                            Column(Modifier.verticalScroll(rememberScrollState())) {
                                Text("La guía punteada une la posición de la marca con un extremo GPS cercano.")
                                numberedMarks(markers).forEach { (m,label) -> Text(label+" · "+when(m.label) {"IR"->"Inicio";"FR"->"Fin";"P"->"Pausa";else->"Reanudación"}+
                                    if(m.at>0) " · "+dateText(m.at) else "") }
                                report.optJSONArray("speedSegments")?.let { parts ->
                                    for(i in 0 until parts.length()) {
                                        val part=parts.optJSONObject(i) ?: continue
                                        val kmh=part.optDouble("kmh")
                                        if(kmh.isFinite() && kmh>=0) Text("Tramo "+(i+1)+" · "+dateText(part.optLong("from"))+" – "+dateText(part.optLong("to"))+" · "+number(kmh,2)+" km/h")
                                    }
                                }
                                val obs=report.optJSONArray("observations")
                                if(obs==null) Text("Este archivo incluye la media global. Los episodios de velocidad se incluyen al reenviar desde Repartidor actualizado.")
                                else {
                                    val events=(0 until obs.length()).mapNotNull { obs.optJSONObject(it) }
                                    val fast=events.filter { it.optString("kind")=="FAST_INTERVAL" }
                                    Text("Ritmo alto sostenido · "+fast.size+" episodios")
                                    fast.forEach { Text(dateText(it.optLong("at"))+" · indicio de más de 12 km/h durante al menos 30 s") }
                                    Text("Paradas GPS observadas · "+events.count { it.optString("kind")=="WALK_STOP" })
                                    Text("Interrupciones GPS registradas · "+events.count { it.optString("kind") in setOf("GPS","GPS_DISABLED","LOCATION_UNAVAILABLE") })
                                    Text("El GPS permite revisar el ritmo. El medio de transporte requiere corroboración.")
                                }
                            }
                        },confirmButton={TextButton(onClick={details=false}) { Text("Cerrar") }})
                        if(outline.isNotEmpty()) {
                            val coverage=ZoneCoverage.percent(outline.map { it.latitude to it.longitude },lines.flatMap { it.points }.map { it.latitude to it.longitude })
                            Row(verticalAlignment=Alignment.CenterVertically) {
                                coverage?.let { Text("Cobertura estimada · "+it+" %",Modifier.weight(1f)) }
                                if(savedZone!=null && report.optString("state","FINISHED")=="FINISHED") {
                                    if(savedZone.status=="COMPLETED") Text("Zona completada")
                                    else TextButton(onClick={ vm.perform("Zona completada") { repo.saveSector(savedZone.copy(status="COMPLETED")) } }) { Text("Completar zona") }
                                }
                            }
                        }
                    }
                }
                fun backToTasks() { reportId=null;id=null }
                androidx.activity.compose.BackHandler { if(singleReport && work.reports.count { it.taskId==report?.optString("assignmentId",reportId!!) }>1) singleReport=false else backToTasks() }
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(modifier=Modifier.weight(1f),onClick={backToTasks()}) { Text("Asignaciones") }
                    Button(modifier=Modifier.weight(1f),onClick={
                        val linked=work.reports.find { it.id==reportId }?.let { r -> work.tasks.find { it.id==r.taskId } }
                        val targetZone=data.sectors.firstOrNull { linked?.belongsTo(it,data.sectors)==true }
                        if(targetZone!=null && data.activeJourney==null) getSharedPreferences("map_workspace",MODE_PRIVATE).edit().putString("sector",targetZone.id).apply()
                        startActivity(Intent(this@AssignmentActivity,com.rutacomercial.app.MainActivity::class.java).putExtra("returnMap",true))
                        finish()
                    }) { Text("Inicio · Mapa") }
                }
                return@Column
            }
            if(zone!=null) {
                FloatingMapWhileTracking(worker && journey?.state=="ACTIVE" && journey.id==busyJourney?.id && !loading && !showHistory)

                if(!worker && zone.optString("note").isNotBlank()) Text(zone.getString("note"),Modifier.padding(horizontal=10.dp,vertical=4.dp),style=MaterialTheme.typography.bodyMedium)
                val live=remember(data.points,journeyId) { data.points.filter { journeyId!=null && it.journeyId==journeyId }.groupBy { it.segment }.map { (s,v)->MapTrack(s.toString(),v.map { MapCoordinate(it.latitude,it.longitude) },group=journeyId.orEmpty()) } }
                val marks=remember(data.points,data.audit,journeyId) {
                    journeyMarks(data,journeyId)
                }
                var locateRequested by remember(id) { mutableStateOf(false) }
                var target by remember(id) { mutableStateOf<MapCoordinate?>(null) }
                var cameraRequest by remember(id) { mutableIntStateOf(0) }
                val now by vm.now.collectAsStateWithLifecycle()
                val activeMap=worker && journey?.state!="FINISHED"

                val allowed=TrackingController.hasLocationPermission(this@AssignmentActivity)
                LaunchedEffect(id,activeMap) {
                    if(activeMap && (!allowed || !getSystemService(android.location.LocationManager::class.java).isProviderEnabled(android.location.LocationManager.GPS_PROVIDER))) action("locate")
                }
                var guiding by remember(id) { mutableStateOf(false) }
                NavigationLocation(activeMap || (worker && (nav.routing || guiding)),tracking.isTracking,allowed)
                val displayed=tracking.position
                val fresh=tracking.fixAt?.let { now-it in -2000L..30000L }==true
                val position=displayed?.takeIf { worker && fresh }
                var following by remember(id) { mutableStateOf(false) }
                var showingPreview by remember(id) { mutableStateOf(false) }
                var userCamera by remember(id) { mutableStateOf(false) }
                var overview by remember(id) { mutableStateOf<MapCoordinate?>(null) }
                val latestPosition by rememberUpdatedState(position)
                LaunchedEffect(id,activeMap,position!=null,userCamera,showingPreview) {
                    if(activeMap && !showingPreview && !userCamera && position!=null && target==null) {
                        overview=position
                        delay(3200)
                        target=latestPosition
                        cameraRequest++
                    }
                }
                LaunchedEffect(locateRequested, position, tracking.fixAt) {
                    if (locateRequested && position != null) {
                        target = position
                        cameraRequest++
                        locateRequested = false
                    }
                }
                LaunchedEffect(following,position) { if(following && position!=null) target=position }
                val boundaryPoint = remember(position,polygon,journey?.id) {
                    position?.takeIf { journey==null }?.let { here ->
                        if (Geo.contains(polygon.map { it.latitude to it.longitude },here.latitude,here.longitude)) null
                        else zoneEntrance(polygon,here)
                    }
                }
                val previous=remember(zone,sessions,journeyId,data.points) {
                    val carried=zone.optString("previousSegments").takeIf { it.isNotBlank() }?.let { decodeLines(JSONArray(it),"previous") }.orEmpty()
                    val sources=zone.optString("previousGroups").takeIf { it.isNotBlank() }?.let(::JSONArray)
                    val grouped=carried.mapIndexed { i,t -> t.copy(group="prior-"+(sources?.optString(i) ?: "")) }
                    val earlier=sessions.takeWhile { it!=journeyId }.flatMap { mapTracks(data,it) }
                    (grouped+earlier).map { it.copy(color="#79658E") }
                }
                val previousPins=remember(zone,sessions,journeyId,data.audit,data.points) {
                    previousMarks(zone)+sessions.takeWhile { it!=journeyId }.flatMap { journeyMarks(data,it) }
                }
                Box(Modifier.fillMaxWidth().then(if(worker) Modifier.weight(1f) else Modifier.mapHeight(.62f))) {
                TerritoryMap(Modifier.fillMaxSize(),sectors=listOf(MapSector(id!!,zone.getString("name"),"#087F75",polygon,true)),tracks=if(worker) previous+live else previous+taskReports.flatMap { it.lines },
                    marks=if(worker) previousPins+marks else previousMarks(zone)+taskReports.flatMap { it.marks },onMapGesture={userCamera=true;locateRequested=false;following=false},fieldTools=worker,
                    onGuideActive={guiding=it},navigation=nav.takeIf { worker },onDrawHere={p->nav.close();proposeAt(p)},onNavigateLocate={action("locate")},
                    previewGuide=worker && journey==null,onGuidePreview={showingPreview=it},
                    followingLocation=following && fresh,onRecenter=if(worker) ({ userCamera=true;following=true;locateRequested=true;action("locate") }) else null,center=target,centerRequest=cameraRequest,fitArea=if(target==null) polygon+listOfNotNull(overview) else emptyList(),
                    currentLocation=if(worker) displayed else null,locationFresh=fresh,accuracyMeters=tracking.accuracyMeters,
                    businesses=boundaryPoint?.let { listOf(MapBusiness("arrival","Punto de llegada sugerido",it.latitude,it.longitude,"#BA5018")) }.orEmpty())
                // Floating over the map so the map keeps the space on small phones.
                if(worker) Column(Modifier.align(Alignment.TopStart).padding(8.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                    SmallFloatingAction("Ver zona") { userCamera=true;following=false;overview=null;locateRequested=false;target=null;cameraRequest++ }
                    if(boundaryPoint!=null) SmallFloatingAction("Cómo llegar") { nav.startZone(zone.optString("name"),polygon) }
                }
                }
                if(worker) {
                    if (boundaryPoint!=null && position!=null) {
                        val meters=Geo.distanceM(position.latitude,position.longitude,boundaryPoint.latitude,boundaryPoint.longitude)
                        Text("Punto naranja · llegada sugerida a ${if(meters>=1000) number(meters/1000,1)+" km" else meters.toInt().toString()+" m"} en línea recta",style=MaterialTheme.typography.bodySmall)
                    }
                    if(android.os.Build.VERSION.SDK_INT>=28 && activeMap && getSystemService(android.app.ActivityManager::class.java).isBackgroundRestricted) {
                        TextButton(onClick={startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,Uri.parse("package:"+packageName)))}) {
                            Text("Android restringe esta app · Revisar batería")
                        }
                    }
                    tracking.locationProblem?.let { Text(it,style=MaterialTheme.typography.bodySmall) }
                    fun sendReport(journey:Journey) {
                        runCatching {
                            val doc=sessionReport(data,journey,sessions,zone)
                            val prior=JSONArray()
                            var bytes=doc.toString().toByteArray().size+64
                            val byId=data.journeys.associateBy { it.id }
                            for(key in sessions.takeWhile { it!=journey.id }.asReversed()) {
                                val earlier=byId[key] ?: continue
                                val entry=sessionReport(data,earlier,sessions,zone)
                                val size=entry.toString().toByteArray().size+1
                                if(bytes+size>5_000_000) {
                                    android.widget.Toast.makeText(this@AssignmentActivity,"Las jornadas más antiguas se pueden enviar desde Historial.",android.widget.Toast.LENGTH_LONG).show()
                                    break
                                }
                                bytes+=size
                                prior.put(entry)
                            }
                            if(prior.length()>0) doc.put("previousReports",prior)
                            share(doc)
                        }.onFailure { msg=it.localizedMessage ?: "No se pudo compartir el recorrido." }
                    }
                    if(options) AlertDialog(onDismissRequest={options=false},title={Text(zone.optString("name"))},text={
                        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                            Text(zone.optString("worker"),style=MaterialTheme.typography.titleMedium)
                            if(cloud.enabled) {
                                Text(onlineStatus)
                                if(!cloud.canPropose()) { Text("Este teléfono todavía no está vinculado con tu Owner.",style=MaterialTheme.typography.bodySmall); LinkCard() }
                                else Text("✓ Vinculado con tu Owner",style=MaterialTheme.typography.bodySmall)
                            }
                            if(zone.optString("note").isNotBlank()) Text(zone.optString("note"))
                            if(previous.isNotEmpty()) Text("Violeta · recorridos anteriores",style=MaterialTheme.typography.bodySmall)
                            if(marks.isNotEmpty()) MarkLegend(marks)
                            if(journey!=null && journey.id==busyJourney?.id && !cloud.onlineTask(zone.getString("id"))) TextButton(onClick={options=false;sendReport(journey)}) { Text("Compartir avance") }
                            if(journey?.state=="FINISHED" && busyJourney==null && trustedZone)
                                TextButton(onClick={options=false;confirmNew=true}) { Text("Nueva jornada en esta zona") }
                            if(busyJourney==null && data.journeys.any { it.state=="FINISHED" })
                                TextButton(onClick={options=false;showHistory=true}) { Text("Historial de recorridos") }
                            TextButton(onClick={options=false;nav.startZone(zone.optString("name"),polygon)}) { Text("Cómo llegar a la zona") }
                            TextButton(onClick={options=false;nav.search=true}) { Text("Buscar un lugar") }
                            TextButton(onClick={options=false;drawingProposal=true}) { Text("Proponer zona") }
                            TextButton(onClick={options=false;sharedText=""}) { Text("Traer ubicación de Google Maps") }
                            TextButton(onClick={options=false;consultGoogleMap(this@AssignmentActivity,position ?: polygon.firstOrNull())}) { Text("Consultar en Google Maps") }
                            if(busyJourney==null) TextButton(onClick={options=false;inbox=true}) { Text("Abrir tarea recibida por archivo") }
                        }
                    },confirmButton={TextButton(onClick={options=false}) { Text("Volver al mapa") }})
                    if(journey==null && zone.optString("note").isNotBlank())
                        Text(zone.optString("note"),style=MaterialTheme.typography.bodySmall,maxLines=2,overflow=androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                    when {
                        busyJourney!=null && busyJourney?.id!=journeyId -> { Text("Terminá el recorrido anterior para iniciar esta tarea."); Button(onClick={TrackingController.finish(this@AssignmentActivity)}) { Text("Terminar recorrido anterior") } }
                        journey==null && !trustedZone ->
                            Text("Pedile al Owner que vuelva a compartir esta tarea para vincularla.")
                        journey==null -> Button(onClick={prefs.edit().putString("selected",id).commit();action("start")},modifier=Modifier.fillMaxWidth().heightIn(min=64.dp)) { Text("Iniciar recorrido",fontSize=20.sp) }
                        journey.state=="FINISHED" -> {
                            WorkerFinishedPanel(journey,if(cloud.onlineTask(zone.getString("id"))) workerSyncText(cloud.delivered(journey.id,journey.state),true) else null,
                                onNewDay=if(busyJourney==null && trustedZone && historyJourney==null) ({ confirmNew=true }) else null,onShare={sendReport(journey)})
                            if(busyJourney==null && trustedZone) {
                                if(confirmNew) AlertDialog(onDismissRequest={confirmNew=false},title={Text("¿Iniciar otra jornada?")},
                                    text={Text("El recorrido anterior queda guardado con sus marcas. El tiempo y la distancia de la nueva jornada empiezan en cero.")},
                                    dismissButton={TextButton(onClick={confirmNew=false}) { Text("Cancelar") }},
                                    confirmButton={Button(onClick={
                                    confirmNew=false
                                    historyJourney=null
                                    prefs.edit().putString("selected",id).commit()
                                    action("start")
                                }) { Text("Iniciar jornada") }})
                            }
                        }
                        else -> WorkerJourneyPanel(journey,tracking.message,if(cloud.onlineTask(zone.getString("id"))) workerSyncText(cloud.delivered(journey.id,journey.state),false) else null,
                            onPause={TrackingController.pause(this@AssignmentActivity)},onResume={action("resume")},
                            onFinish={TrackingController.finish(this@AssignmentActivity)})
                    }
                } else {
                    val online=cloud.enabled && cloud.sentOnline(zone.getString("id"))
                    if(online) Text("✓ Enviada en línea a "+zone.optString("worker")+" · "+onlineStatus,style=MaterialTheme.typography.labelMedium)
                    else if(cloud.enabled) {
                        var targetDevice by remember(id) { mutableStateOf(cloud.recipient(zone.getString("worker"))) }
                        Text(onlineStatus,style=MaterialTheme.typography.labelMedium)
                        Field("Código del teléfono",targetDevice,{targetDevice=deviceCode(it)})
                        FilledTonalButton(enabled=targetDevice.matches(Regex("[A-Za-z0-9_-]{20,128}")),onClick={
                            runCatching { cloud.assign(signedTask(zone),targetDevice) }
                                .onSuccess { msg="Tarea enviada a "+zone.optString("worker")+"." }
                                .onFailure { msg=it.localizedMessage?.takeIf { m->m.isNotBlank() } ?: "No se pudo enviar la tarea." }
                        }) { Text("Enviar tarea en línea") }
                    }
                    if(!online) {
                        Button(onClick={runCatching { share(signedTask(zone)) }.onFailure { msg="No se pudo firmar o compartir la tarea." }}) { Text("Compartir tarea por archivo") }
                        FilledTonalButton(onClick={runCatching { qrTask=signedTask(zone) }.onFailure { msg="No se pudo preparar la vinculación." }}) { Text("QR y código de tarea") }
                    }
                    if(result!=null) {
                        val coverage=remember(taskReports,polygon) { ZoneCoverage.percent(polygon.map { it.latitude to it.longitude },taskReports.flatMap { it.lines }.flatMap { it.points }.map { it.latitude to it.longitude }) }
                        Text("Total recibido · ${number(taskReports.sumOf { it.meters }/1000,2)} km · ${hours(taskReports.sumOf { it.duration })} · cobertura ${coverage?.let { "$it %" } ?: "sin estimación"}")
                        taskReports.forEach { entry ->
                            FilledTonalButton(onClick={singleReport=true;reportId=entry.id}) { Text(dateText(entry.at)+" · "+if(entry.state=="FINISHED") "Ver informe" else "Ver avance") }
                        }
                    } else Text("Pendiente de recibir informe")
                }
            }
            if(!worker && id==null && incoming==null) {
                val proposals=remember(revision,onlineRevision) { directory.listFiles().orEmpty()
                    .filter { it.extension=="proposal" && !file(proposalKey(it.nameWithoutExtension,"task"),"task").exists() }
                    .sortedByDescending { it.lastModified() }.mapNotNull { read(it.nameWithoutExtension,"proposal") } }
                if(proposals.isNotEmpty()) Surface(shape=MaterialTheme.shapes.medium,color=MaterialTheme.colorScheme.tertiaryContainer) {
                    Column(Modifier.fillMaxWidth().padding(12.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                        Text("Propuestas de zona por revisar",style=MaterialTheme.typography.titleSmall)
                        proposals.forEach { p -> Button(modifier=Modifier.fillMaxWidth(),onClick={incoming=p;msg=""}) { Text(p.optString("name")+" · "+p.optString("worker")) } }
                    }
                }
            }
            if(!worker && id==null && cloud.enabled) {
                OwnerGoogleAccount(cloud)
                var showLinkQr by remember { mutableStateOf(false) }
                val linked=remember(revision,onlineRevision) { cloud.linkedWorkers() }
                if(linked.isNotEmpty()) {
                    Text("Repartidores vinculados",style=MaterialTheme.typography.titleSmall)
                    Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                        linked.forEach { (name,_) -> FilterChip(selected=person.trim().equals(name,true),onClick={person=name},label={Text(name)}) }
                    }
                }
                var linkCode by remember { mutableStateOf<String?>(null) }
                var linkCodeError by remember { mutableStateOf(false) }
                OutlinedButton(enabled=deviceId.isNotBlank(),onClick={
                    showLinkQr=true;linkCode=null;linkCodeError=false
                    scope.launch { runCatching { cloud.createInvite() }.onSuccess { linkCode=it }.onFailure { linkCodeError=true } }
                }) { Text("Vincular repartidor") }
                if(showLinkQr) OwnerLinkQrDialog(deviceId,linkCode,linkCodeError) { showLinkQr=false }
            }
            if(!worker && id==null) {
                Choice("Zona guardada",sector,data.sectors.map { it.id to it.name }) { sector=it }
                Field("Nombre del repartidor",person,{person=it.take(100)})
                val linkedPhone=remember(recipient,onlineRevision) { cloud.linkedWorkers().any { it.second==recipient } }
                if(cloud.enabled && linkedPhone) Text("✓ Teléfono vinculado · la tarea le llega en línea",style=MaterialTheme.typography.bodySmall)
                else if(cloud.enabled) Field("Código del teléfono (si no está vinculado)",recipient,{recipient=deviceCode(it)})
                Field("Indicación opcional",note,{note=it.take(500)})
                val selected=data.sectors.find { it.id==sector }
                if(selected!=null && (previousJourneys(data,selected.id).isNotEmpty() || work.reportsFor(work.tasksFor(selected,data.sectors)).isNotEmpty()))
                    Text("La tarea incluirá los tramos anteriores para retomar esta zona.",style=MaterialTheme.typography.bodySmall)
                Button(enabled=work.loaded && selected!=null && person.isNotBlank() && (recipient.isBlank() || recipient.matches(Regex("[A-Za-z0-9_-]{20,128}"))),onClick={
                    val key=newId();val o=JSONObject().put("version",1).put("kind","task").put("id",key).put("worker",person.trim()).put("name",selected!!.name).put("polygon",selected.polygonJson).put("note",note).put("sectorId",selected.id)
                    val prior=work.reportsFor(work.tasksFor(selected,data.sectors)).flatMap { it.lines } +
                        previousJourneys(data,selected.id).flatMap { mapTracks(data,it) }
                    val pins=work.reportsFor(work.tasksFor(selected,data.sectors)).flatMap { it.marks } +
                        previousJourneys(data,selected.id).flatMap { journeyMarks(data,it) }
                    if(pins.isNotEmpty()) o.put("previousEvents",encodedMarks(pins).toString())
                    if(prior.isNotEmpty()) {
                        o.put("previousSegments",previousSegments(prior).toString())
                        o.put("previousGroups",JSONArray(prior.filter { it.points.size>=2 }.map { it.group }).toString())
                    }
                    runCatching { val signed=signedTask(o)
                        if(selected.status!="ACTIVE") vm.perform { repo.saveSector(selected.copy(status="ACTIVE")) }
                        prefs.edit().putBoolean("owner:$key",true).commit();id=key;revision++
                        if(cloud.enabled && recipient.isNotBlank()) { cloud.assign(signed,recipient);msg="Tarea enviada a "+person.trim()+". Le aparece en su app en unos segundos." } else { msg="";share(signed) } }
                        .onFailure { msg=it.localizedMessage?.takeIf { m->m.isNotBlank() } ?: "No se pudo preparar o compartir la tarea." }
                }) { Text(if(cloud.enabled && recipient.isNotBlank()) "Asignar en línea" else "Asignar y compartir") }
            }
            if(!worker && id!=null) TextButton(onClick={id=null}) { Text("Otra asignación") }
            if(!worker && id==null) ids.filter { it!=id }.forEach { key ->
                val o=remember(key) { read(key,"task")!! }
                TextButton(onClick={id=key;if(worker)prefs.edit().putString("selected",key).apply()}) { Text(o.getString("name")+" · "+o.getString("worker")) }
            }
            if(worker && busyJourney==null && data.journeys.any { it.state=="FINISHED" }) {
                if(!workerMap) TextButton(onClick={showHistory=true}) { Text("Historial de recorridos") }
                if(showHistory) AlertDialog(onDismissRequest={showHistory=false},title={Text("Historial de recorridos")},text={
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        var from by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf("") }
                        var until by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf("") }
                        RouteExport(data,work,from,until) { a,b -> from=a;until=b }
                        val since=dayBound(from)
                        val before=dayBound(until,true)
                        data.journeys.filter { it.state=="FINISHED" && (since==null || it.startedAt>=since) && (before==null || it.startedAt<before) }.sortedByDescending { it.startedAt }.forEach { saved ->
                            val key=data.audit.firstOrNull { it.entityId==saved.id && it.action=="ASSIGNMENT" }?.detail
                            if(key!=null && key in ids) {
                                val name=remember(key) { read(key,"task")?.optString("name").orEmpty() }
                                TextButton(onClick={id=key;historyJourney=saved.id;showHistory=false}) {
                                    Text("$name · ${dateOnly(saved.startedAt)} · ${number(saved.distanceM/1000,2)} km · ${hours(saved.activeMs)}")
                                }
                            }
                        }
                    }
                },confirmButton={TextButton(onClick={showHistory=false}) { Text("Cerrar") }})
            }
            if(worker && (id!=prefs.getString("selected",null) || historyJourney!=null)) TextButton(onClick={historyJourney=null;id=prefs.getString("selected",null)}) { Text("Volver a mi tarea") }
            if(worker && zone==null) {
                Spacer(Modifier.weight(1f))
                FilledTonalButton(modifier=Modifier.fillMaxWidth(),onClick={drawingProposal=true}) { Text("Proponer zona") }
            }
            // Online workers get tasks automatically; the file inbox stays as a quiet fallback.
            if(busyJourney==null && !workerMap && worker && cloud.enabled) TextButton(modifier=Modifier.fillMaxWidth(),onClick={inbox=true}) { Text("Abrir tarea recibida por archivo") }
            else if(busyJourney==null && !workerMap && cloud.enabled) TextButton(modifier=Modifier.fillMaxWidth(),onClick={inbox=true}) { Text("Abrir archivo recibido") }
            else if(busyJourney==null && !workerMap) FilledTonalButton(modifier=Modifier.fillMaxWidth().heightIn(min=48.dp),onClick={inbox=true}) { Text(if(worker) "Abrir tarea recibida" else "Abrir archivo recibido") }
            if(!worker) TextButton(onClick={startActivity(Intent(this@AssignmentActivity,com.rutacomercial.app.MainActivity::class.java));finish()}) { Text("Volver al mapa") }
        }
    }
}

@Composable
private fun SmallFloatingAction(label: String, onClick: () -> Unit) {
    Surface(onClick=onClick,shape=MaterialTheme.shapes.extraLarge,color=MaterialTheme.colorScheme.surface,shadowElevation=3.dp) {
        Text(label,Modifier.padding(horizontal=14.dp,vertical=10.dp),style=MaterialTheme.typography.labelLarge)
    }
}


@Composable
private fun MarkLegend(marks:List<RouteMark>) {
    val names=mapOf("IR" to "inicio","P" to "pausa","CR" to "reanudación","FR" to "fin")
    Text(marks.map { it.label }.distinct().joinToString(" · ") { it+" "+names[it] },style=MaterialTheme.typography.labelSmall)
}
