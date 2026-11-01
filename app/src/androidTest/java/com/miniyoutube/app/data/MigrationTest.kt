package com.miniyoutube.app.data

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Guards the upgrade path for the database holding the followed channels and the record
 * of what has been watched - which exists nowhere else, and which destructive fallback is
 * deliberately never allowed to wipe.
 *
 * The schema JSONs under `app/schemas` are what [MigrationTestHelper] replays against, so
 * they are committed.
 */
@RunWith(AndroidJUnit4::class)
class MigrationTest {
    private companion object {
        const val TEST_DB = "migration-test.db"

        val MIGRATIONS = VideoDatabase.MIGRATIONS.toTypedArray()
    }

    @get:Rule
    val helper =
        MigrationTestHelper(
            InstrumentationRegistry.getInstrumentation(),
            VideoDatabase::class.java,
            emptyList(),
            FrameworkSQLiteOpenHelperFactory(),
        )

    /**
     * Adding the high-water mark and pending-availability columns must leave every
     * existing row - above all, what has been watched - exactly as it was, with the new
     * columns arriving as "no mark yet" and "watchable now".
     */
    @Test
    fun version2KeepsChannelsAndWatchedStateAndAddsNullColumns() {
        helper.createDatabase(TEST_DB, 1).use { db ->
            db.execSQL(
                "INSERT INTO channels (id, title, avatarUrl, followedAt, lastCheckedAt) " +
                    "VALUES ('UCa', 'A', NULL, 1, 5)",
            )
            db.execSQL(
                "INSERT INTO videos (id, channelId, title, publishedAt, addedAt, watchedAt, " +
                    "resumeAtSeconds) VALUES ('v1', 'UCa', 'Video', 1, 2, 3, 125)",
            )
        }

        helper.runMigrationsAndValidate(TEST_DB, 2, true, *MIGRATIONS).use { db ->
            db.query("SELECT followedAt, lastCheckedAt, feedHighWater FROM channels").use { c ->
                assertTrue("the channel did not survive", c.moveToFirst())
                assertEquals(1L, c.getLong(0))
                assertEquals(5L, c.getLong(1))
                assertTrue("feedHighWater should arrive null", c.isNull(2))
            }
            db.query("SELECT watchedAt, resumeAtSeconds, availableAt FROM videos").use { c ->
                assertTrue("the video did not survive", c.moveToFirst())
                assertEquals("watched state was disturbed", 3L, c.getLong(0))
                assertEquals(125, c.getInt(1))
                assertTrue("availableAt should arrive null", c.isNull(2))
            }
        }
    }

    /** Room itself opens the upgraded file with every migration registered. */
    @Test
    fun roomOpensTheLatestSchemaFromTheOldest() {
        helper.createDatabase(TEST_DB, 1).close()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        Room
            .databaseBuilder(context, VideoDatabase::class.java, TEST_DB)
            .addMigrations(*MIGRATIONS)
            .build()
            .apply { openHelper.writableDatabase.close() }
            .close()
    }
}
