package com.frynetworks.fryapp.data

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.frynetworks.fryapp.data.dashboard.db.ALL_MIGRATIONS
import com.frynetworks.fryapp.data.dashboard.db.MIGRATION_1_2
import com.frynetworks.fryapp.data.dashboard.db.RemoteMinerEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Proves `MIGRATION_1_2` on a real SQLite: a v1 database with a provisioned device migrates to the
 * exported v2 schema (validated column-for-column by Room against `app/schemas/.../2.json`),
 * keeps that device row byte-identical, and gains the four empty dashboard cache tables.
 */
@RunWith(AndroidJUnit4::class)
class MigrationTest {

    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), FryDatabase::class.java)

    private val newTables = listOf("remote_miners", "miner_detail_cache", "reward_summary_cache", "asset_totals_cache")

    @Test
    fun migrate1To2_keepsDevicesRowsAndCreatesTheCacheTables() {
        val name = "migration-1-2-${System.nanoTime()}.db"
        helper.createDatabase(name, 1).apply {
            execSQL(
                "INSERT INTO devices (minerKey, name, chip, fwVersion, wallet, transport, lastSeen, status) " +
                    "VALUES ('IOT-741755294F897EAF140D2E9FB79222A6', 'Porch', 'ESP32', '0.2.0', 'HXWYLLZDPTM5OXS3DPARMTG52RSBMMCQNKT4L2LZRRXYPNAWJBT6VIW6WU', 'ble', 1757548800000, 1)",
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(name, 2, true, MIGRATION_1_2)

        db.query("SELECT minerKey, name, chip, fwVersion, wallet, transport, lastSeen, status FROM devices").use { c ->
            assertEquals(1, c.count)
            assertTrue(c.moveToFirst())
            assertEquals("IOT-741755294F897EAF140D2E9FB79222A6", c.getString(0))
            assertEquals("Porch", c.getString(1))
            assertEquals("ESP32", c.getString(2))
            assertEquals("0.2.0", c.getString(3))
            assertEquals("HXWYLLZDPTM5OXS3DPARMTG52RSBMMCQNKT4L2LZRRXYPNAWJBT6VIW6WU", c.getString(4))
            assertEquals("ble", c.getString(5))
            assertEquals(1757548800000L, c.getLong(6))
            assertEquals(1, c.getInt(7))
        }
        for (table in newTables) {
            db.query("SELECT COUNT(*) FROM $table").use { c ->
                assertTrue(c.moveToFirst())
                assertEquals("$table should start empty", 0, c.getInt(0))
            }
        }
        db.close()
    }

    @Test
    fun openingAV1DatabaseThroughRoomAppliesTheMigrationAndTheDaosWork() = runBlocking {
        val name = "migration-open-${System.nanoTime()}.db"
        helper.createDatabase(name, 1).apply {
            execSQL(
                "INSERT INTO devices (minerKey, name, chip, fwVersion, wallet, transport, lastSeen, status) " +
                    "VALUES ('FEM-ABCDEFGHIJKLMNOPQRSTUVWXYZ012345', 'Backyard', 'ESP32', '0.2.0', 'ADDR', 'softap', 5, 0)",
            )
            close()
        }

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val room = Room.databaseBuilder(context, FryDatabase::class.java, name)
            .addMigrations(*ALL_MIGRATIONS)
            .build()
        try {
            val devices = room.deviceDao().getAllOnce()
            assertEquals(listOf("FEM-ABCDEFGHIJKLMNOPQRSTUVWXYZ012345"), devices.map { it.minerKey })
            assertEquals("Backyard", devices[0].name)

            room.remoteMinerDao().upsertAll(
                listOf(RemoteMinerEntity("FEM-ABCDEFGHIJKLMNOPQRSTUVWXYZ012345", "Backyard FEM", "Fry Edge Miner", "active", true, false, true, true, true, false, false, false, 7L)),
            )
            val remote = room.remoteMinerDao().observeAll().first()
            assertEquals("Backyard FEM", remote.single().nickname)
            room.remoteMinerDao().updateNickname("FEM-ABCDEFGHIJKLMNOPQRSTUVWXYZ012345", "Roof FEM")
            assertEquals("Roof FEM", room.remoteMinerDao().getAllOnce().single().nickname)
            room.remoteMinerDao().deleteNotIn(listOf("OTHER"))
            assertTrue(room.remoteMinerDao().getAllOnce().isEmpty())
            // the local devices table is untouched by any of that
            assertEquals(1, room.deviceDao().getAllOnce().size)
        } finally {
            room.close()
            helper.closeWhenFinished(room)
        }
    }
}
