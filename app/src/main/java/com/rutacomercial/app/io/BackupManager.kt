package com.rutacomercial.app.io

import android.content.Context
import android.net.Uri
import com.google.gson.Gson
import com.google.gson.JsonParser
import com.rutacomercial.app.data.DataSnapshot
import com.rutacomercial.app.data.Repository
import java.io.File
import java.io.InputStream
import java.util.Base64

data class BackupAttachment(val path: String, val content: String)
/** v3 adds the assignments folder (tasks, received reports, proposals) and its preferences; v1-v2 restore without them. */
data class BackupPayload(val schemaVersion: Int = 3, val createdAt: Long, val data: DataSnapshot, val attachments: List<BackupAttachment>,
    val assignments: List<BackupAttachment>? = null, val assignmentPrefs: Map<String, Any>? = null)

class BackupManager(private val context: Context, private val repository: Repository) {
    private val gson = Gson()
    private val evidencePattern = Regex("evidence/[0-9a-fA-F-]{36}\\.jpg")
    private val assignmentPattern = Regex("[0-9a-f-]{36}\\.(task|result|received|proposal|outgoing)")
    private val assignmentDir get() = File(context.filesDir, "assignments")
    private val assignmentPreferences get() = context.getSharedPreferences("assignments", Context.MODE_PRIVATE)

    suspend fun create(uri: Uri, password: CharArray) {
        var plain: ByteArray? = null
        try {
            val snapshot = repository.snapshot()
            val paths = evidencePaths(snapshot)
            var totalBytes = 0L
            val attachments = paths.map { path ->
                val file = evidenceFile(path)
                require(file.isFile) { "Falta una fotografía del respaldo. No se creó un respaldo incompleto." }
                totalBytes += file.length()
                require(totalBytes <= 20 * 1024 * 1024) { "Las fotografías superan 20 MB. Conservá un respaldo anterior y reducí las fotografías antes de volver a intentar." }
                BackupAttachment(path, Base64.getEncoder().encodeToString(file.readBytes()))
            }
            val assignments = assignmentDir.listFiles().orEmpty().filter { it.isFile && assignmentPattern.matches(it.name) }
                .map { BackupAttachment(it.name, it.readText()) }
            val prefs = assignmentPreferences.all.filterValues { it is String || it is Boolean }.mapValues { it.value!! }
            plain = gson.toJson(BackupPayload(createdAt = System.currentTimeMillis(), data = snapshot, attachments = attachments,
                assignments = assignments, assignmentPrefs = prefs)).toByteArray(Charsets.UTF_8)
            val encrypted = BackupCrypto.encrypt(plain, password)
            context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(encrypted) }
                ?: error("No se pudo abrir el destino del respaldo.")
        } finally { password.fill('\u0000'); plain?.fill(0) }
    }

    suspend fun restore(uri: Uri, password: CharArray) {
        val staged = mutableListOf<File>()
        var plain: ByteArray? = null
        try {
            // The repository repeats this check atomically immediately before importing.
            require(isEmpty(repository.snapshot())) { "La restauración requiere una instalación sin registros. Tus datos actuales se conservaron. Guardá primero un respaldo y usá una instalación vacía." }
            val encrypted = context.contentResolver.openInputStream(uri)?.use { readLimited(it, BackupCrypto.MAX_FILE_BYTES) }
                ?: error("No se pudo abrir el respaldo.")
            plain = BackupCrypto.decrypt(encrypted, password)
            val tree = JsonParser.parseString(plain.toString(Charsets.UTF_8)).asJsonObject
            require(tree.get("schemaVersion")?.asInt in 1..3 && tree.has("data") && tree.has("attachments")) { "Versión de respaldo incompatible." }
            val dataObject = tree.getAsJsonObject("data")
            listOf("areas", "sectors", "sectorRevisions", "businesses", "campaigns", "visits", "movements", "journeys", "points", "incidents", "routeStops", "audit").forEach {
                require(dataObject.get(it)?.isJsonArray == true) { "Respaldo incompleto: falta $it." }
            }
            if (tree.get("schemaVersion").asInt == 1 && !dataObject.has("quickDeliveries")) dataObject.add("quickDeliveries", com.google.gson.JsonArray())
            require(dataObject.get("quickDeliveries")?.isJsonArray == true) { "Respaldo incompleto: entregas rápidas." }
            val payload = gson.fromJson(tree, BackupPayload::class.java)
            val required = evidencePaths(payload.data)
            require(payload.attachments.map { it.path }.toSet() == required && payload.attachments.map { it.path }.distinct().size == payload.attachments.size) { "Las evidencias del respaldo están incompletas o duplicadas." }
            var totalBytes = 0
            // Files are created before the transaction, only at fresh paths; failure removes only these new files.
            payload.attachments.forEach { attachment ->
                val destination = evidenceFile(attachment.path)
                require(!destination.exists()) { "Ya existe una evidencia con el mismo identificador. No se reemplazó." }
                val bytes = Base64.getDecoder().decode(attachment.content)
                totalBytes += bytes.size
                require(bytes.size <= 10 * 1024 * 1024 && totalBytes <= 20 * 1024 * 1024) { "Evidencias demasiado grandes." }
                require(bytes.size >= 3 && bytes[0] == 0xff.toByte() && bytes[1] == 0xd8.toByte()) { "Fotografía de respaldo inválida." }
                destination.parentFile?.mkdirs()
                staged += destination
                destination.writeBytes(bytes)
            }
            repository.restoreIntoEmpty(payload.data)
            staged.clear()
            // Tasks and reports received from workers: written after the records, never over existing files.
            payload.assignments.orEmpty().filter { assignmentPattern.matches(it.path) }.forEach {
                val file = File(assignmentDir.apply { mkdirs() }, it.path)
                if (!file.exists()) file.writeText(it.content)
            }
            payload.assignmentPrefs?.let { saved ->
                val edit = assignmentPreferences.edit()
                saved.forEach { (k, v) -> when (v) { is Boolean -> edit.putBoolean(k, v); is String -> edit.putString(k, v) } }
                edit.apply()
            }
        } finally {
            staged.forEach { it.delete() }
            password.fill('\u0000'); plain?.fill(0)
        }
    }

    private fun evidencePaths(data: DataSnapshot) = (data.businesses.mapNotNull { it.photoUri } + data.visits.mapNotNull { it.evidenceUri }).filter { it.isNotBlank() }.toSet()
    private fun evidenceFile(path: String): File {
        require(evidencePattern.matches(path)) { "Ruta de fotografía inválida en el respaldo." }
        val root = context.filesDir.canonicalFile
        val file = File(root, path).canonicalFile
        require(file.toPath().startsWith(root.toPath())) { "Ruta de respaldo fuera del almacenamiento privado." }
        return file
    }
    private fun isEmpty(s: DataSnapshot) = s.areas.isEmpty() && s.sectors.isEmpty() && s.businesses.isEmpty() && s.campaigns.isEmpty() && s.visits.isEmpty() && s.movements.isEmpty() && s.journeys.isEmpty() && s.points.isEmpty() && s.incidents.isEmpty() && s.routeStops.isEmpty() && s.sectorRevisions.isEmpty() && s.quickDeliveries.isEmpty()
    private fun readLimited(input: InputStream, limit: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            require(out.size() + count <= limit) { "El archivo supera el límite de 40 MB." }
            out.write(buffer, 0, count)
        }
        return out.toByteArray()
    }
}
