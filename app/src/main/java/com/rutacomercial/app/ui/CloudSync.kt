package com.rutacomercial.app.ui

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.*
import com.rutacomercial.app.BuildConfig
import com.rutacomercial.app.RutaApplication
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import java.security.MessageDigest

/** Accepts the bare code or the whole message the worker shares ("Mi código …: CODE"). */
internal fun deviceCode(text: String): String =
    Regex("[A-Za-z0-9_-]{20,128}").findAll(text).lastOrNull()?.value ?: text.trim().take(128)

internal class CloudSync(private val app: RutaApplication) {
    private val folder = File(app.filesDir, "assignments")
    private val prefs = app.getSharedPreferences("online", Context.MODE_PRIVATE)
    private val assignmentPrefs = app.getSharedPreferences("assignments", Context.MODE_PRIVATE)
    val enabled = FirebaseApp.getApps(app).isNotEmpty()
    val device = MutableStateFlow("")
    val status = MutableStateFlow("Conectando…")
    val revision = MutableStateFlow(0)
    private val db by lazy { FirebaseFirestore.getInstance() }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val wake = MutableStateFlow(0)
    private val subscriptions = mutableMapOf<String, Job>()

    fun start() {
        if (!enabled) return
        scope.launch {
            val auth = FirebaseAuth.getInstance()
            retry {
                device.value = auth.currentUser?.uid ?: requireNotNull(auth.signInAnonymously().await().user).uid
            }
            val uid = device.value
            val worker = BuildConfig.WORKER_EDITION
            status.value = "Conectado"
            launch { outgoingLoop(uid, worker) }
            if (!worker) launch { retry { listen(db.collection("proposals").whereEqualTo("owner", uid)) { receiveProposal(it) } } }
            val field = if (worker) "worker" else "owner"
            retry {
                listen(db.collection("tasks").whereEqualTo(field, uid)) { doc ->
                    if (worker) receiveTask(doc)
                    else if (doc.id !in subscriptions) {
                        subscriptions[doc.id] = scope.launch {
                            retry { listen(doc.reference.collection("reports")) { receiveReport(doc.id, it) } }
                        }
                    }
                }
            }
        }
    }

    private suspend fun retry(block: suspend () -> Unit) {
        while (currentCoroutineContext().isActive) {
            try { block(); return }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { status.value = "Sin sincronizar · reintentando"; delay(20_000) }
        }
    }

    private suspend fun listen(query: Query, handle: suspend (DocumentSnapshot) -> Unit) {
        callbackFlow<QuerySnapshot> {
            val listener = query.addSnapshotListener { snapshot, error ->
                if (error != null) close(error) else if (snapshot != null) trySend(snapshot)
            }
            awaitClose { listener.remove() }
        }.buffer(kotlinx.coroutines.channels.Channel.UNLIMITED).collect { snapshot ->
            for (change in snapshot.documentChanges) {
                if (change.type != DocumentChange.Type.REMOVED) {
                    try { handle(change.document) }
                    catch (_: IllegalArgumentException) { status.value = "Se recibió un documento inválido" }
                    catch (_: org.json.JSONException) { status.value = "Se recibió un documento incompleto" }
                }
            }
        }
    }

    fun recipient(name: String): String = prefs.getString("person:"+name.trim().lowercase(), "").orEmpty()

    fun assign(task: JSONObject, recipient: String) {
        require(recipient.matches(Regex("[A-Za-z0-9_-]{20,128}"))) { "Revisá el código del teléfono." }
        require(recipient != device.value) { "Ese es el código de este teléfono. Pegá el del repartidor." }
        val id = task.getString("id")
        val assigned = prefs.getString("target:"+id,null)
        require(assigned==null || assigned==recipient) { "Para cambiar de teléfono, creá otra asignación." }
        check(prefs.edit().putString("target:"+id,recipient).putString("person:"+task.getString("worker").trim().lowercase(),recipient)
            .putString("send:"+id, recipient).commit())
        status.value = "Tarea pendiente de sincronizar"
        scheduleDelivery()
    }

    /** The worker learns the Owner's account from the first verified online task. */
    fun canPropose() = enabled && prefs.contains("ownerUid")

    fun propose(proposal: JSONObject) {
        require(canPropose() && validProposal(proposal))
        save(local(proposal.getString("id"),"outgoing"), proposal)
        check(prefs.edit().putBoolean("propose:"+proposal.getString("id"), true).commit())
        scheduleDelivery()
        status.value = "Propuesta pendiente de enviar"
    }

    /** Worker account that sent a proposal online, so the accepted task returns to that phone. */
    fun proposer(proposalId: String): String? = prefs.getString("proposer:"+proposalId, null)

    fun onlineTask(id: String) = prefs.contains("linked:"+id)
    fun pending(): List<String> = prefs.all.keys.filter { it.startsWith("pending:") }.map { it.substringAfter(':') }
    fun accepted(id: String) {
        val edit = prefs.edit().remove("pending:"+id)
        prefs.getString("taskOwner:"+id, null)?.let { edit.putString("ownerUid", it) }
        edit.apply(); revision.update { it+1 }
    }

    private suspend fun outgoingLoop(uid: String, worker: Boolean) {
        var seen = wake.value
        var lastStates = ""
        var lastSync = 0L
        while (currentCoroutineContext().isActive) {
            try {
                val now = android.os.SystemClock.elapsedRealtime()
                val states = if(worker) app.repository.dao.journeys().joinToString { it.id+it.state } else ""
                if (!worker || states != lastStates || now-lastSync >= 60_000 || lastSync==0L) {
                    flush(uid,worker)
                    lastStates = states
                    lastSync = now
                }
            } catch (e: CancellationException) { throw e }
              catch (_: Exception) { status.value = "Pendiente de enviar · reintentando" }
            withTimeoutOrNull(if(worker) 8_000L else 30_000L) { wake.first { it!=seen } }
            seen = wake.value
        }
    }

    private val sending = Mutex()
    private suspend fun flush(uid: String, worker: Boolean) = sending.withLock {
        if (!worker) {
            for ((key,value) in prefs.all.filterKeys { it.startsWith("send:") }) {
                val id = key.substringAfter(':')
                val task = JSONObject(local(id,"task").readText())
                publish(db.collection("tasks").document(id), task, mapOf("owner" to uid,"worker" to value.toString()))
                prefs.edit().remove(key).apply()
                status.value = "Tarea sincronizada"
            }
        } else {
            val owner = prefs.getString("ownerUid", null)
            for (key in prefs.all.keys.filter { it.startsWith("propose:") }) {
                if (owner == null) break
                val id = key.substringAfter(':')
                publish(db.collection("proposals").document(id), JSONObject(local(id,"outgoing").readText()), mapOf("owner" to owner, "worker" to uid))
                prefs.edit().remove(key).apply()
                status.value = "Propuesta enviada al Owner"
            }
        }
        if (worker && prefs.all.keys.any { it.startsWith("linked:") }) {
                val data = app.repository.snapshot()
                for ((id,events) in data.audit.filter { it.action=="ASSIGNMENT" && onlineTask(it.detail) }.groupBy { it.detail }) {
                    val sessions = events.map { it.entityId }
                    val taskFile = local(id,"task")
                    if (!taskFile.exists()) continue
                    val task = JSONObject(taskFile.readText())
                    for (j in data.journeys.filter { it.id in sessions }) {
                        val stamp = listOf(j.state,j.activeMs,j.distanceM,data.points.count { it.journeyId==j.id },
                            data.audit.count { it.entityId==j.id },data.incidents.count { it.journeyId==j.id }).joinToString("/")
                        val key = "sent:"+j.id
                        if (prefs.getString(key,null)==stamp && j.state!="ACTIVE") continue
                        val report = sessionReport(data,j,sessions,task)
                        publish(db.collection("tasks").document(id).collection("reports").document(report.getString("id")), report, emptyMap())
                        prefs.edit().putString(key,stamp).apply()
                        status.value = "Recorrido sincronizado"
                    }
                }
        }
    }

    suspend fun flushPending() {
        if (!enabled) return
        val uid = device.first { it.isNotBlank() }
        flush(uid, BuildConfig.WORKER_EDITION)
    }

    fun scheduleDelivery() {
        if (!enabled) return
        val scheduler = app.getSystemService(android.app.job.JobScheduler::class.java)
        val job = android.app.job.JobInfo.Builder(18025, android.content.ComponentName(app, CloudDeliveryJob::class.java))
            .setRequiredNetworkType(android.app.job.JobInfo.NETWORK_TYPE_ANY).setPersisted(true).build()
        scheduler.schedule(job)
        // Shown until the server confirms, so an offline phone never reads as synced.
        if (BuildConfig.WORKER_EDITION && prefs.all.keys.any { it.startsWith("linked:") }) status.value = "Recorrido guardado · pendiente de enviar"
        wake.update { it+1 }
    }

    private fun local(id: String, suffix: String) = File(folder, UUID.fromString(id).toString()+"."+suffix)
    private fun save(file: File, obj: JSONObject) {
        folder.mkdirs()
        val tmp = File.createTempFile("online",null,folder)
        try {
            tmp.writeText(obj.toString())
            Files.move(tmp.toPath(),file.toPath(),StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE)
        } finally { tmp.delete() }
        revision.update { it+1 }
    }

    private suspend fun receiveTask(doc: DocumentSnapshot) {
        val existing = local(doc.id,"task")
        val ownerUid = requireNotNull(doc.getString("owner"))
        if (existing.exists() && onlineTask(doc.id)) { prefs.edit().putString("ownerUid", ownerUid).apply(); return }
        val task = unpack(doc)
        require(task.getString("id")==doc.id && OwnerIdentity.verified(task))
        val owner = assignmentPrefs.getString(OwnerIdentity.binding,null)
        if (owner!=null && owner!=task.getString("ownerKey")) return
        if (existing.exists() && JSONObject(existing.readText()).optString("signature")!=task.getString("signature")) return
        val edit = prefs.edit().putBoolean("linked:"+doc.id,true).putString("taskOwner:"+doc.id, ownerUid)
        if (owner==task.getString("ownerKey")) edit.putString("ownerUid", ownerUid)
        if (!existing.exists()) {
            save(local(doc.id,"received"),task)
            edit.putBoolean("pending:"+doc.id,true)
        }
        edit.commit()
        revision.update { it+1 }
        status.value = "Tarea recibida"
    }

    private suspend fun receiveReport(taskId: String, doc: DocumentSnapshot) {
        if (!local(taskId,"task").exists()) return
        val report = unpack(doc)
        require(report.getString("id")==doc.id && report.getString("assignmentId")==taskId && validateBundle(report).size==1)
        if (doc.id!=taskId && local(doc.id,"task").exists()) return
        val file = local(doc.id,"result")
        val old = file.takeIf { it.exists() }?.let { JSONObject(it.readText()) }
        require(old==null || old.optString("assignmentId",doc.id)==taskId)
        if (newerReport(old,report)===report && old?.toString()!=report.toString()) save(file,report)
        status.value = "Último avance recibido"
    }

    private suspend fun receiveProposal(doc: DocumentSnapshot) {
        val file = local(doc.id,"proposal")
        if (file.exists()) return
        val proposal = unpack(doc)
        require(proposal.getString("id")==doc.id && validProposal(proposal))
        check(prefs.edit().putString("proposer:"+doc.id, requireNotNull(doc.getString("worker"))).commit())
        save(file, proposal)
        status.value = "Propuesta recibida · "+proposal.getString("name")
    }

    private fun digest(bytes: ByteArray) = android.util.Base64.encodeToString(MessageDigest.getInstance("SHA-256").digest(bytes),android.util.Base64.NO_WRAP)

    private suspend fun publish(ref: DocumentReference, obj: JSONObject, fields: Map<String,String>) {
        val raw = obj.toString().toByteArray()
        require(raw.size<=5_000_000)
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { it.write(raw) }
        val bytes = out.toByteArray()
        val count = (bytes.size+499_999)/500_000
        val header = mutableMapOf<String,Any>("parts" to count,"digest" to digest(bytes))
        header.putAll(fields)
        val batch = db.batch()
        if (count==1) header["body"] = Blob.fromBytes(bytes)
        else for (i in 0 until count) {
            val chunk = bytes.copyOfRange(i*500_000,minOf(bytes.size,(i+1)*500_000))
            batch.set(ref.collection("parts").document(i.toString()),mapOf("body" to Blob.fromBytes(chunk)))
        }
        batch.set(ref,header).commit().await()
    }

    private suspend fun unpack(doc: DocumentSnapshot): JSONObject {
        val count = requireNotNull(doc.getLong("parts"))
        require(count in 1..11)
        val bytes = ByteArrayOutputStream()
        for (i in 0 until count.toInt()) {
            val part = if (count==1L) doc else doc.reference.collection("parts").document(i.toString()).get().await()
            val chunk = requireNotNull(part.getBlob("body")).toBytes()
            require(chunk.size<=500_000)
            bytes.write(chunk)
        }
        val compressed = bytes.toByteArray()
        require(digest(compressed)==doc.getString("digest"))
        val raw = ByteArrayOutputStream()
        GZIPInputStream(compressed.inputStream()).use { stream ->
            val buf = ByteArray(8192)
            while (true) {
                val n = stream.read(buf)
                if (n<0) break
                require(raw.size()+n<=5_000_000)
                raw.write(buf,0,n)
            }
        }
        return JSONObject(raw.toString("UTF-8"))
    }
}
