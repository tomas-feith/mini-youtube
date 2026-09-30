package com.miniyoutube.app.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration

@Database(
    entities = [ChannelEntity::class, VideoEntity::class],
    version = VideoDatabase.VERSION,
    exportSchema = true,
)
abstract class VideoDatabase : RoomDatabase() {
    abstract fun videoDao(): VideoDao

    companion object {
        const val VERSION = 1

        const val NAME = "mini_youtube.db"

        /**
         * Schema migrations, oldest first. None yet.
         *
         * Destructive fallback is deliberately never enabled: the followed channels and
         * which videos have been watched exist nowhere but here.
         *
         * To add one:
         *  1. Change the entities and bump [VERSION].
         *  2. Add a `Migration(n, n + 1)` here with the SQL.
         *  3. `MigrationTest` fails until the committed schema JSONs and the SQL agree.
         */
        val MIGRATIONS: List<Migration> = emptyList()

        @Volatile
        private var instance: VideoDatabase? = null

        fun get(context: Context): VideoDatabase =
            instance ?: synchronized(this) {
                instance ?: build(context).also { instance = it }
            }

        private fun build(context: Context): VideoDatabase =
            Room
                .databaseBuilder(
                    context.applicationContext,
                    VideoDatabase::class.java,
                    NAME,
                ).apply { MIGRATIONS.forEach { addMigrations(it) } }
                // TRUNCATE rather than WAL. Android's Auto Backup copies the database file,
                // and under WAL everything since the last checkpoint lives in a sidecar
                // file, so the cloud copy can be arbitrarily stale. The sibling
                // show_tracker needed a backup agent to checkpoint first; this database is
                // written a handful of times an hour, so WAL's concurrency buys nothing and
                // dropping it removes the problem instead.
                .setJournalMode(JournalMode.TRUNCATE)
                .build()
    }
}
