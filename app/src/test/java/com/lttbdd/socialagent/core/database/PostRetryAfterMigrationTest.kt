package com.lttbdd.socialagent.core.database

import android.app.Application
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.lttbdd.socialagent.core.model.PostStatus
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Migration v1 → v2 (#39, thêm `posts.nextAttemptAtMillis`): bài đang chờ trong hàng đợi của người dùng
 * không được mất khi nâng cấp app, và cột mới mặc định null (được thử ngay).
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class PostRetryAfterMigrationTest {

    @Test
    fun `queued posts survive the upgrade and can be published immediately`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "retry-after-migration.db"
        val path = context.getDatabasePath(name)
        path.parentFile?.mkdirs()
        try {
            createVersionOneDatabase(path)

            val migrated = Room.databaseBuilder(context, AppDatabase::class.java, name).build()
            try {
                val post = migrated.postDao().getById("p1")
                assertEquals(PostStatus.QUEUED, post?.status)
                assertEquals("key-1", post?.idempotencyKey)
                assertNull(post?.nextAttemptAtMillis)
                val claimed = migrated.postDao().claimForPublishing("p1", nowMillis = 10)
                assertEquals("Bài cũ giữ chỗ được ngay sau khi nâng cấp", 1, claimed)
            } finally {
                migrated.close()
            }
        } finally {
            context.deleteDatabase(name)
        }
    }

    /**
     * Gradle chạy unit test trong thư mục module `app`, còn IDE có thể chạy từ thư mục gốc repo: thử cả hai.
     */
    private fun schemaFile(version: Int): File {
        val relative = "schemas/${AppDatabase::class.java.name}/$version.json"
        return listOf(File(relative), File("app", relative)).firstOrNull { it.isFile }
            ?: error("Không tìm thấy $relative (thư mục làm việc: ${File("").absolutePath})")
    }

    /** Dựng DB đúng schema v1 từ `schemas/.../1.json` đã commit, kèm một bài đang chờ đăng. */
    private fun createVersionOneDatabase(path: File) {
        val schema = JSONObject(schemaFile(version = 1).readText()).getJSONObject("database")
        SQLiteDatabase.openOrCreateDatabase(path, null).use { db ->
            val entities = schema.getJSONArray("entities")
            for (index in 0 until entities.length()) {
                val entity = entities.getJSONObject(index)
                val table = entity.getString("tableName")
                db.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", table))
                val indices = entity.getJSONArray("indices")
                for (i in 0 until indices.length()) {
                    db.execSQL(indices.getJSONObject(i).getString("createSql").replace("\${TABLE_NAME}", table))
                }
            }
            val setup = schema.getJSONArray("setupQueries")
            for (index in 0 until setup.length()) db.execSQL(setup.getString(index))
            db.execSQL(
                "INSERT INTO posts (id, draftId, channel, status, idempotencyKey, attemptCount, createdAtMillis, " +
                    "updatedAtMillis, outcomeUnknown) VALUES ('p1', NULL, 'FACEBOOK', 'QUEUED', 'key-1', 0, 1, 1, 0)",
            )
            db.version = 1
        }
    }
}
