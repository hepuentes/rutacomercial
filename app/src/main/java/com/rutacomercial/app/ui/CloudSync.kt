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

    /** Google account backing the Owner identity, or null while it is only anonymous. */
    val account = MutableStateFlow<String?>(null)
    private var session: Job? = null

    fun start() {
        if (!enabled) return
        session = scope.launch {
            val auth = FirebaseAuth.getInstance()
            retry {
                device.value = auth.currentUser?.uid ?: requireNotNull(auth.signInAnonymously().await().user).uid
            }
            account.value = auth.currentUser?.takeUnless { it.isAnonymous }?.email
            val uid = device.value
            val worker = BuildConfig.WORKER_EDITION
            status.value = "Conectado"
            launch { outgoingLoop(uid, worker) }
            if (!worker) launch { retry { listen(db.collection("proposals").whereEqualTo("owner", uid)) { receiveProposal(it) } } }
            if (!worker) launch { retry { listen(db.collection("links").whereEqualTo("owner", uid)) { receiveLink(it) } } }
            val field = if (worker) "worker" else "owner"
            retry {
                listen(db.collection("tasks").whereEqualTo(field, uid)) { doc ->
                    if (worker) receiveTask(doc)
                    else if (doc.id !in subscriptions) {
                        // A task the worker uploaded after receiving it by file is online from now on.
                        doc.getString("worker")?.let { w -> if (!prefs.contains("target:"+doc.id)) prefs.edit().putString("target:"+doc.id, w).apply() }
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

    /**
     * Owner: attach a Google account. Linking keeps the current identity; if that Google account
     * already owns one (a reinstalled phone), sign into it so workers and tasks follow the Owner.
     */
    suspend fun useGoogle(idToken: String) {
        val auth = FirebaseAuth.getInstance()
        val credential = com.google.firebase.auth.GoogleAuthProvider.getCredential(idToken, null)
        val user = try { requireNotNull(auth.currentUser).linkWithCredential(credential).await().user }
            catch (_: com.google.firebase.auth.FirebaseAuthUserCollisionException) { auth.signInWithCredential(credential).await().user }
        val uid = requireNotNull(user).uid
        account.value = user.email
        if (uid != device.value) { session?.cancel(); subscriptions.clear(); start() }
    }

    /** Owner: back to an anonymous identity. Signing in again with the same Google account restores it. */
    suspend fun signOutGoogle() {
        FirebaseAuth.getInstance().signOut()
        runCatching { androidx.credentials.CredentialManager.create(app).clearCredentialState(androidx.credentials.ClearCredentialStateRequest()) }
        account.value = null
        session?.cancel(); subscriptions.clear(); device.value = ""
        start()
    }

    /** Worker: trust the scanned Owner and tell it this phone's code and name. */
    fun link(invite: LinkInvite, name: String) {
        require(enabled && name.isNotBlank() && name.length <= 100)
        require(invite.owner != device.value) { "Ese QR es de este mismo teléfono." }
        OwnerIdentity.bind(assignmentPrefs, invite.key)
        check(prefs.edit().putString("ownerUid", invite.owner).putString("linkName", name).putBoolean("linkPending", true).commit())
        scheduleDelivery()
        status.value = "Vinculando con tu Owner…"
    }

    /** Owner: phones that linked by QR, as name to code. */
    fun linkedWorkers(): List<Pair<String, String>> =
        prefs.all.filterKeys { it.startsWith("worker:") }.map { (k, v) -> v.toString() to k.substringAfter(':') }.sortedBy { it.first.lowercase() }

    /** Worker account that sent a proposal online, so the accepted task returns to that phone. */
    fun proposer(proposalId: String): String? = prefs.getString("proposer:"+proposalId, null)

    fun onlineTask(id: String) = prefs.contains("linked:"+id)

    /** Owner: this task already went to a phone through Firestore. */
    fun sentOnline(id: String) = prefs.contains("target:"+id)

    /** True when the server confirmed this journey in its current state (the stamp starts with the state). */
    fun delivered(journeyId: String, state: String) = prefs.getString("sent:"+journeyId, null)?.startsWith("$state/") == true
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
                // Denied means the doc already exists: the linked worker uploaded this file task itself.
                denied { publish(db.collection("tasks").document(id), task, mapOf("owner" to uid,"worker" to value.toString())) }
                prefs.edit().remove(key).apply()
                status.value = "Tarea sincronizada"
            }
        } else {
            val owner = prefs.getString("ownerUid", null)
            if (owner != null && prefs.getBoolean("linkPending", false)) {
                db.collection("links").document(uid).set(mapOf("owner" to owner, "worker" to uid,
                    "name" to prefs.getString("linkName", "").orEmpty(), "at" to FieldValue.serverTimestamp())).await()
                prefs.edit().remove("linkPending").apply()
                status.value = "✓ Vinculado con tu Owner"
            }
            if (owner != null && prefs.contains("linkName")) {
                claimLocalTasks()
                for (key in prefs.all.keys.filter { it.startsWith("claim:") }) {
                    val id = key.substringAfter(':')
                    denied { publish(db.collection("tasks").document(id), JSONObject(local(id,"task").readText()), mapOf("owner" to owner, "worker" to uid)) }
                    prefs.edit().remove(key).apply()
                    status.value = "Tareas sincronizadas con tu Owner"
                }
            }
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
                        if (denied { publish(db.collection("tasks").document(id).collection("reports").document(report.getString("id")), report, emptyMap()) }) {
                            prefs.edit().remove("linked:$id").apply(); break
                        }
                        prefs.edit().putString(key,stamp).apply()
                        status.value = "Recorrido sincronizado"
                    }
                }
        }
    }

    /** Runs a write; true when Firestore refused it for permissions (other errors still propagate to retry). */
    private suspend fun denied(write: suspend () -> Unit): Boolean = try { write(); false }
        catch (e: FirebaseFirestoreException) { if (e.code == FirebaseFirestoreException.Code.PERMISSION_DENIED) true else throw e }

    /** Worker: tasks accepted from this Owner (by file or online) start syncing once the phone is linked. */
    private fun claimLocalTasks() {
        val key = assignmentPrefs.getString(OwnerIdentity.binding, null) ?: return
        val edit = prefs.edit()
        for (file in folder.listFiles().orEmpty().filter { it.extension == "task" }) {
            val id = file.nameWithoutExtension
            if (onlineTask(id)) continue
            val task = runCatching { JSONObject(file.readText()) }.getOrNull() ?: continue
            if (task.optString("ownerKey") != key || task.optString("id") != id) continue
            edit.putBoolean("linked:$id", true).putBoolean("claim:$id", true)
        }
        edit.commit()
    }

    /** Owner: a short code the worker can type when they are not together to scan the QR. */
    suspend fun createInvite(): String {
        val alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        val random = java.security.SecureRandom()
        val code = (1..6).map { alphabet[random.nextInt(alphabet.length)] }.joinToString("")
        db.collection("invites").document(code).set(mapOf("owner" to device.first { it.isNotBlank() },
            "key" to OwnerIdentity.publicKey(), "expires" to com.google.firebase.Timestamp(java.util.Date(System.currentTimeMillis() + 30 * 60_000L)))).await()
        return code
    }

    /** Worker: resolves a typed code into the same invite the QR carries. */
    suspend fun lookupInvite(raw: String): LinkInvite? {
        val code = raw.uppercase().filter { it.isLetterOrDigit() }
        if (code.length != 6) return null
        val doc = try { db.collection("invites").document(code).get().await() }
            catch (e: FirebaseFirestoreException) { if (e.code == FirebaseFirestoreException.Code.PERMISSION_DENIED) return null else throw e }
        val owner = doc.getString("owner") ?: return null
        val key = doc.getString("key") ?: return null
        return parseLinkQr(linkQr(owner, key))
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
        // Firestore rules guarantee doc.owner is the sender's account, so the same Owner account
        // with a new signing key (reinstalled phone) keeps its workers instead of stranding them.
        if (owner!=null && owner!=task.getString("ownerKey")) {
            if (prefs.getString("ownerUid", null) != ownerUid) return
            OwnerIdentity.rebind(assignmentPrefs, task.getString("ownerKey"))
        }
        if (existing.exists() && JSONObject(existing.readText()).optString("signature")!=task.getString("signature")) return
        val edit = prefs.edit().putBoolean("linked:"+doc.id,true).putString("taskOwner:"+doc.id, ownerUid)
        if (owner==task.getString("ownerKey")) edit.putString("ownerUid", ownerUid)
        if (!existing.exists()) {
            val fresh = !prefs.contains("pending:"+doc.id)
            save(local(doc.id,"received"),task)
            edit.putBoolean("pending:"+doc.id,true)
            if (fresh) Notifier.notify(app, "task:"+doc.id, "Tenés una tarea nueva", task.optString("name")+" · tocá para verla")
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
        if (newerReport(old,report)===report && old?.toString()!=report.toString()) {
            save(file,report)
            if (report.optString("state")=="FINISHED" && old?.optString("state")!="FINISHED")
                Notifier.notify(app, "done:"+doc.id, report.optString("worker")+" terminó el recorrido",
                    report.optString("name")+" · "+number(report.optDouble("meters")/1000,2)+" km") { putExtra("taskId", taskId) }
        }
        status.value = "Último avance recibido"
    }

    private fun receiveLink(doc: DocumentSnapshot) {
        val name = requireNotNull(doc.getString("name")).trim()
        val worker = requireNotNull(doc.getString("worker"))
        require(name.length in 1..100 && worker == doc.id && worker.matches(Regex("[A-Za-z0-9_-]{20,128}")))
        if (prefs.getString("worker:$worker", null) == name) return
        check(prefs.edit().putString("worker:$worker", name).putString("person:"+name.lowercase(), worker).commit())
        revision.update { it+1 }
        status.value = "Repartidor vinculado · $name"
        Notifier.notify(app, "link:$worker", "$name se vinculó", "Ya podés asignarle zonas en línea")
    }

    private suspend fun receiveProposal(doc: DocumentSnapshot) {
        val file = local(doc.id,"proposal")
        if (file.exists()) return
        val proposal = unpack(doc)
        require(proposal.getString("id")==doc.id && validProposal(proposal))
        check(prefs.edit().putString("proposer:"+doc.id, requireNotNull(doc.getString("worker"))).commit())
        save(file, proposal)
        status.value = "Propuesta recibida · "+proposal.getString("name")
        Notifier.notify(app, "proposal:"+doc.id, "Propuesta de zona de "+proposal.getString("worker"), proposal.getString("name")+" · tocá para revisarla")
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
