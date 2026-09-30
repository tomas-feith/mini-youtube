package com.miniyoutube.app.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [ChannelEntity::class, VideoEntity::class],
    version = VideoDatabase.VERSION,
    exportSchema = true,
)
abstract class VideoDatabase : RoomDatabase() {
    abstract fun videoDao(): VideoDao

    companion object {
        const val VERSION = 2

        const val NAME = "mini_youtube.db"

        /**
         * Adds `channels.feedHighWater` and `videos.availableAt`.
         *
         * Both nullable with no default, so existing rows arrive as "no mark yet" and
         * "watchable now" - exactly what was true before the columns existed. The mark is
         * set by the next check; until then overflow detection simply has nothing to
         * compare against, which is the same state a newly followed channel starts in.
         */
        private val MIGRATION_1_2 =
            object : Migration(1, 2) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL("ALTER TABLE channels ADD COLUMN feedHighWater INTEGER")
                    db.execSQL("ALTER TABLE videos ADD COLUMN availableAt INTEGER")
                }
            }

        /**
         * Schema migrations, oldest first.
         *
         * Destructive fallback is deliberately never enabled: the followed channels and
         * which videos have been watched exist nowhere but here.
         *
         * To add one:
         *  1. Change the entities and bump [VERSION].
         *  2. Add a `Migration(n, n + 1)` here with the SQL.
         *  3. `MigrationTest` fails until the committed schema JSONs and the SQL agree.
         */
        val MIGRATIONS: List<Migration> = listOf(MIGRATION_1_2)

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
