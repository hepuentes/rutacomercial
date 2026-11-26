package com.rutacomercial.app.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.rutacomercial.app.domain.Inventory
import com.rutacomercial.app.io.Exports
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class QuickDeliveryTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    @Test fun retriesUndoIdentifyAndRestoreKeepOneStockExit() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        val target = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            val repo = Repository(db)
            val c = repo.prepareQuickCampaign("Tarjetas", 3)
            val q = QuickDelivery(campaignId=c.id)
            coroutineScope { (1..8).map { async(Dispatchers.IO) { repo.recordQuickDelivery(q) } }.awaitAll() }
            assertEquals(1, repo.snapshot().activeQuickDeliveries.size)
            assertEquals(2L, repo.stockBalance(c.id).carried)
            assertTrue(repo.snapshot().businesses.isEmpty())
            val business = repo.saveBusiness(Business(name="Kiosco",latitude=-32.89,longitude=-68.84))
            repo.identifyQuickDelivery(q.id, business.id)
            assertTrue(repo.snapshot().visits.isEmpty())
            assertEquals(1L, repo.stockBalance(c.id).delivered)
            repo.undoQuickDelivery(q.id); repo.undoQuickDelivery(q.id); repo.recordQuickDelivery(q)
            assertEquals(3L, repo.stockBalance(c.id).carried)
            assertTrue(repo.snapshot().activeQuickDeliveries.isEmpty())
            val snapshot = repo.snapshot()
            Validation.snapshot(snapshot)
            Repository(target).restoreIntoEmpty(snapshot)
            assertEquals(snapshot.quickDeliveries, Repository(target).snapshot().quickDeliveries)
            assertEquals(Inventory.balance(snapshot.movements), Inventory.balance(Repository(target).snapshot().movements))
            assertTrue(Exports.commercialCsv(snapshot).contains("Anulada"))
        } finally { db.close(); target.close() }
    }

    @Test fun failedDeliveryDoesNotConsumeStockOrCreateRow() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            val repo = Repository(db); val c = repo.prepareQuickCampaign("Una", 1)
            repo.recordQuickDelivery(QuickDelivery(campaignId=c.id))
            val before = repo.snapshot()
            try { repo.recordQuickDelivery(QuickDelivery(campaignId=c.id)); fail("Stock negativo") } catch (_: IllegalArgumentException) { }
            assertEquals(before, repo.snapshot())
            try { repo.moveStock(StockMovement(campaignId=c.id,type="QUICK_UNDO",quantity=1)); fail("Anulación manual") } catch (_: IllegalArgumentException) { }
            assertEquals(before, repo.snapshot())
        } finally { db.close() }
    }

    @Test fun migrationPreservesVersionOneRecordsAndAssignsDistinctZoneColors() = runBlocking {
        val name = "migration-${newId()}.db"
        val path = context.getDatabasePath(name); path.parentFile!!.mkdirs()
        val campaign = newId(); val sector1 = newId(); val sector2 = newId()
        val schema = InstrumentationRegistry.getInstrumentation().context.assets.open("com.rutacomercial.app.data.AppDatabase/1.json")
            .bufferedReader().use { JSONObject(it.readText()).getJSONObject("database") }
        SQLiteDatabase.openOrCreateDatabase(path, null).use { old ->
            val entities = schema.getJSONArray("entities")
            for (i in 0 until entities.length()) {
                val entity = entities.getJSONObject(i)
                fun sql(value: String) = value.replace("\${TABLE_NAME}", entity.getString("tableName"))
                old.execSQL(sql(entity.getString("createSql")))
                val indices = entity.getJSONArray("indices")
                for (j in 0 until indices.length()) old.execSQL(sql(indices.getJSONObject(j).getString("createSql")))
            }
            old.execSQL("INSERT INTO campaigns VALUES(?, 'Material anterior', 'material anterior', '', 1000, NULL, NULL, 1000, 1000)", arrayOf(campaign))
            for ((index,id) in listOf(sector1,sector2).withIndex()) old.execSQL("INSERT INTO sectors VALUES(?, ?, ?, ?, '#087F75', '[[0,0],[1,0],[1,1]]', NULL, 'PENDING', 1, 1000)", arrayOf(id,"Zona $index","zona $index","S-$index"))
            old.version = 1
        }
        val db = Room.databaseBuilder(context,AppDatabase::class.java,name).addMigrations(AppDatabase.MIGRATION_1_2).build()
        try {
            assertEquals(campaign, db.dao().campaigns().single().id)
            assertEquals(setOf(sector1,sector2), db.dao().sectors().map { it.id }.toSet())
            assertEquals(2,db.dao().sectors().map { it.color }.distinct().size)
            assertTrue(db.dao().quickDeliveries().isEmpty())
            assertEquals(2, db.openHelper.readableDatabase.version)
        } finally { db.close(); context.deleteDatabase(name) }
    }
}
