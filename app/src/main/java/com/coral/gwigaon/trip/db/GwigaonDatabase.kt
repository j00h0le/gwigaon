package com.coral.gwigaon.trip.db

import android.content.Context
import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Local store for trips, the event log and the upload queue (SPEC: Room). Lives in app-private storage;
 * backups are off in the manifest. Every schema change gets a migration (schemas in app/schemas/).
 * 2: arrival instructions on trips. 3: share token and cloud upload state on trips, the cloud session (T16).
 * 4: `failed_at` on queue entries the cloud refused for good (Checkpoint D review). 5: 설정 (ADR 0015).
 * 6: the account's standing contact link (ADR 0019). 7: the spoken arrival sentence on 설정 and trips (ADR 0021).
 */
@Database(
    entities = [
        TripEntity::class, EventEntity::class, UploadQueueEntity::class, CloudSessionEntity::class, SettingsEntity::class,
        OwnerLinkEntity::class,
    ],
    version = 7,
    exportSchema = true,
    autoMigrations = [
        AutoMigration(from = 1, to = 2), AutoMigration(from = 2, to = 3), AutoMigration(from = 3, to = 4),
        AutoMigration(from = 4, to = 5), AutoMigration(from = 5, to = 6), AutoMigration(from = 6, to = 7),
    ],
)
abstract class GwigaonDatabase : RoomDatabase() {
    abstract fun trips(): TripDao
    abstract fun events(): EventDao
    abstract fun uploadQueue(): UploadQueueDao
    abstract fun cloudSession(): CloudSessionDao
    abstract fun settings(): SettingsDao
    abstract fun ownerLink(): OwnerLinkDao

    companion object {
        const val NAME = "gwigaon.db"

        fun create(context: Context): GwigaonDatabase =
            Room.databaseBuilder(context, GwigaonDatabase::class.java, NAME).addCallback(AppendOnly).build()

        fun inMemory(context: Context): GwigaonDatabase =
            Room.inMemoryDatabaseBuilder(context, GwigaonDatabase::class.java).addCallback(AppendOnly).build()
    }

    /** The event log is evidence: SQLite itself refuses to change or remove an event, whatever the caller. */
    private object AppendOnly : Callback() {
        override fun onOpen(db: SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE TRIGGER IF NOT EXISTS events_no_update BEFORE UPDATE ON events " +
                    "BEGIN SELECT RAISE(ABORT, 'events are append-only'); END",
            )
            db.execSQL(
                "CREATE TRIGGER IF NOT EXISTS events_no_delete BEFORE DELETE ON events " +
                    "BEGIN SELECT RAISE(ABORT, 'events are append-only'); END",
            )
        }
    }
}
