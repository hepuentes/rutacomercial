package com.rutacomercial.app.io

import android.content.Context
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rutacomercial.app.data.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class BackupIntegrationTest {
    @Test fun encryptedExportRestoreAndNonDestructiveRefusal() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val sourceDb = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        val targetDb = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        val file = File(context.cacheDir, "test-backup-${UUID.randomUUID()}.rcb")
        try {
            val source = Repository(sourceDb)
            val target = Repository(targetDb)
            val campaign = source.saveCampaign(Campaign(name = "Campaña de prueba"), 10)
            source.moveStock(StockMovement(campaignId = campaign.id, type = "ASSIGN", quantity = 3))
            val business = source.saveBusiness(Business(name = "Negocio de prueba", latitude = -34.6, longitude = -58.4))
            source.recordVisit(Visit(businessId = business.id, campaignId = campaign.id, status = "CARD_DELIVERED", cards = 2))
            BackupManager(context, source).create(Uri.fromFile(file), "una contraseña de prueba".toCharArray())
            assertFalse(file.readText().contains("Negocio de prueba"))
            BackupManager(context, target).restore(Uri.fromFile(file), "una contraseña de prueba".toCharArray())
            assertEquals(1, target.snapshot().visits.size)
            assertEquals(7L, target.stockBalance(campaign.id).depot)
            assertEquals(1L, target.stockBalance(campaign.id).carried)
            val before = target.snapshot()
            try {
                BackupManager(context, target).restore(Uri.fromFile(file), "una contraseña de prueba".toCharArray())
                fail("Restaurar sobre datos existentes debe fallar")
            } catch (_: IllegalArgumentException) { }
            assertEquals(before, target.snapshot())
        } finally { sourceDb.close(); targetDb.close(); file.delete() }
    }

    @Test fun schemaOneWithoutQuickDeliveriesAndSchemaTwoWithThemRestore() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val sourceDb = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        val file = File(context.cacheDir, "test-compatible-${UUID.randomUUID()}.rcb")
        val password = "respaldo compatible de prueba"
        try {
            val repo = Repository(sourceDb)
            val campaign = repo.prepareQuickCampaign("Conservar", 4)
            val json = com.google.gson.Gson().toJsonTree(BackupPayload(createdAt=System.currentTimeMillis(),data=repo.snapshot(),attachments=emptyList())).asJsonObject
            json.addProperty("schemaVersion",1)
            json.getAsJsonObject("data").remove("quickDeliveries")
            file.writeBytes(BackupCrypto.encrypt(json.toString().toByteArray(),password.toCharArray()))
            val legacyDb = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
            try {
                val legacy = Repository(legacyDb)
                BackupManager(context,legacy).restore(Uri.fromFile(file),password.toCharArray())
                assertEquals(4L,legacy.stockBalance(campaign.id).carried)
                assertTrue(legacy.snapshot().quickDeliveries.isEmpty())
            } finally { legacyDb.close() }
            val q = repo.recordQuickDelivery(QuickDelivery(campaignId=campaign.id))
            BackupManager(context,repo).create(Uri.fromFile(file),password.toCharArray())
            val modernDb = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
            try {
                val modern = Repository(modernDb)
                BackupManager(context,modern).restore(Uri.fromFile(file),password.toCharArray())
                assertEquals(q,modern.snapshot().quickDeliveries.single())
                assertEquals(3L,modern.stockBalance(campaign.id).carried)
            } finally { modernDb.close() }
        } finally { sourceDb.close(); file.delete() }
    }

    @Test fun alteredBackupDoesNotWriteRows() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val sourceDb = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        val targetDb = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        val file = File(context.cacheDir, "test-corrupt-${UUID.randomUUID()}.rcb")
        try {
            BackupManager(context, Repository(sourceDb)).create(Uri.fromFile(file), "otra contraseña de prueba".toCharArray())
            val bytes = file.readBytes()
            bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte()
            file.writeBytes(bytes)
            val target = Repository(targetDb)
            try {
                BackupManager(context, target).restore(Uri.fromFile(file), "otra contraseña de prueba".toCharArray())
                fail("El archivo alterado debe rechazarse")
            } catch (_: IllegalArgumentException) { }
            assertTrue(target.snapshot().isEmpty)
        } finally { sourceDb.close(); targetDb.close(); file.delete() }
    }
}
