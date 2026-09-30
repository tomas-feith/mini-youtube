package com.miniyoutube.app.data

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Guards the upgrade path for the database holding the followed channels and the record
 * of what has been watched.
 *
 * At version 1 there is nothing to migrate yet, so this pins the committed schema: the
 * JSON under `app/schemas` must describe exactly what the entities build, or the first
 * real migration will be written against a schema that never existed. When version 2
 * arrives, add a test here that writes rows at 1 and reads them back after
 * `runMigrationsAndValidate(..., 2, ...)`.
 */
@RunWith(AndroidJUnit4::class)
class MigrationTest {
    private companion object {
        const val TEST_DB = "migration-test.db"
    }

    @get:Rule
    val helper =
        MigrationTestHelper(
            InstrumentationRegistry.getInstrumentation(),
            VideoDatabase::class.java,
            emptyList(),
            FrameworkSQLiteOpenHelperFactory(),
        )

    @Test
    fun committedSchemaMatchesTheEntities() {
        helper.createDatabase(TEST_DB, 1).use { db ->
            db.execSQL(
                "INSERT INTO channels (id, title, avatarUrl, followedAt, lastCheckedAt) " +
                    "VALUES ('UCa', 'A', NULL, 1, NULL)",
            )
            db.execSQL(
                "INSERT INTO videos (id, channelId, title, publishedAt, addedAt, watchedAt) " +
                    "VALUES ('v1', 'UCa', 'Video', 1, 2, NULL)",
            )
        }

        helper
            .runMigrationsAndValidate(
                TEST_DB,
                VideoDatabase.VERSION,
                true,
                *VideoDatabase.MIGRATIONS.toTypedArray(),
            ).use { db ->
                db.query("SELECT resumeAtSeconds FROM videos WHERE id = 'v1'").use { cursor ->
                    cursor.moveToFirst()
                    assertEquals("the column default did not apply", 0, cursor.getInt(0))
                }
            }

        // And Room itself opens the file the helper built, with every migration registered.
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        Room
            .databaseBuilder(context, VideoDatabase::class.java, TEST_DB)
            .addMigrations(*VideoDatabase.MIGRATIONS.toTypedArray())
            .build()
            .apply { openHelper.writableDatabase.close() }
            .close()
    }
}
