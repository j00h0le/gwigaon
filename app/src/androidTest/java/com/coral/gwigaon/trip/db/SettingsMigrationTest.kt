package com.coral.gwigaon.trip.db

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.coral.gwigaon.trip.ArrivalInstructions
import com.coral.gwigaon.trip.PinAttempts
import com.coral.gwigaon.trip.PinHasher
import com.coral.gwigaon.trip.Settings
import com.coral.gwigaon.vehicle.Destination
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.security.SecureRandom

/** Schema 4 → 5 (설정, ADR 0015): an existing database keeps its trips and gains an empty settings table. */
@RunWith(AndroidJUnit4::class)
class SettingsMigrationTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val name = "migration-test.db"

    @Before
    @After
    fun clean() {
        context.deleteDatabase(name)
    }

    /** Builds the database exactly as schema 4 was exported (app/schemas, packed as test assets). */
    private fun createVersion4() {
        val schema = InstrumentationRegistry.getInstrumentation().context.assets.open("${GwigaonDatabase::class.java.name}/4.json").bufferedReader().use { it.readText() }
        val db = Json.parseToJsonElement(schema).jsonObject.getValue("database").jsonObject
        val file = context.getDatabasePath(name).also { it.parentFile?.mkdirs() }
        SQLiteDatabase.openOrCreateDatabase(file, null).use { sql ->
            for (entity in db.getValue("entities").jsonArray) {
                val e = entity.jsonObject
                val table = e.getValue("tableName").jsonPrimitive.content
                sql.execSQL(e.getValue("createSql").jsonPrimitive.content.replace("\${TABLE_NAME}", table))
                e["indices"]?.jsonArray?.forEach {
                    sql.execSQL(it.jsonObject.getValue("createSql").jsonPrimitive.content.replace("\${TABLE_NAME}", table))
                }
            }
            db.getValue("setupQueries").jsonArray.forEach { sql.execSQL(it.jsonPrimitive.content) }
            sql.execSQL(
                "INSERT INTO trips (id, status, destination_label, destination_lat, destination_lng, contact_label, " +
                    "pin_hash, pin_failures, started_at, last_seen_at) VALUES ('t1', 'ended', '집', 37.5, 127.0, '언니', 'x', 0, 1, 1)",
            )
            sql.version = 4
        }
    }

    @Test
    fun version4MigratesToSettingsKeepingTrips() = runTest {
        createVersion4()
        val db = Room.databaseBuilder(context, GwigaonDatabase::class.java, name).build()
        try {
            assertNotNull(db.trips().byId("t1"))
            val store = RoomSettingsStore(db)
            assertNull(store.current())
            assertEquals(PinAttempts(), store.pinAttempts())

            val hash = PinHasher(SecureRandom(), iterations = 1).hash("2580")
            val saved = Settings(Destination("집", 37.5, 127.0), listOf("언니", "엄마"), ArrivalInstructions("지하 2층"), true, hash)
            store.put(saved)
            store.setPinAttempts(PinAttempts(failures = 2))
            store.put(saved.copy(cues = false))
            assertEquals(saved.copy(cues = false), store.current())
            assertEquals(2, store.pinAttempts().failures)

            assertNull(store.atLastTrip())
            store.putAtLastTrip(saved)
            assertEquals(saved, store.atLastTrip())
            assertEquals(saved.copy(cues = false), store.current())
        } finally {
            db.close()
        }
    }
}
