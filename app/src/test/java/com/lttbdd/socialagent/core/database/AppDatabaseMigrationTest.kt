package com.lttbdd.socialagent.core.database

import android.app.Application
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.lttbdd.socialagent.core.database.entity.LlmUsageEntity
import com.lttbdd.socialagent.core.model.ChannelType
import com.lttbdd.socialagent.core.model.LlmProvider
import com.lttbdd.socialagent.core.model.LlmTask
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Migration 3 → 4 (#74): dựng DB version 3 đúng theo `app/schemas/.../3.json` rồi mở bằng Room version 4.
 * Dữ liệu cũ (kể cả bảng mới có từ version 3) phải còn nguyên và bảng `llm_usage` dùng được.
 * Chuỗi 1 → 4 đã được [PostRetryAfterMigrationTest] chạy qua vì test đó mở DB version 1 bằng Room hiện tại.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class AppDatabaseMigrationTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val name = "migration-test.db"
    private var room: AppDatabase? = null

    @Before
    fun setUp() {
        context.deleteDatabase(name)
    }

    @After
    fun tearDown() {
        room?.close()
        context.deleteDatabase(name)
    }

    @Test
    fun `migrating from version 3 keeps existing data and adds usage table`() = runTest {
        createDatabase(version = 3)

        val db = Room.databaseBuilder(context, AppDatabase::class.java, name)
            .allowMainThreadQueries()
            .build()
            .also { room = it }

        db.openHelper.writableDatabase.query("SELECT text, revision FROM drafts WHERE id = 'd1'").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("Bản nháp trước khi nâng cấp", cursor.getString(0))
            assertEquals(3, cursor.getInt(1))
        }
        assertEquals("op-1", db.channelCleanupDao().getCompletedOperation(ChannelType.FACEBOOK))
        assertEquals(4, db.openHelper.writableDatabase.version)

        val usage = LlmUsageEntity(
            id = "u1",
            occurredAtMillis = 1_000,
            provider = LlmProvider.GEMINI,
            model = "gemini-2.5-flash",
            task = LlmTask.WRITE_POST,
            inputTokens = 10,
            outputTokens = 5,
            costMicros = null,
            pricingVersion = null,
        )
        db.llmUsageDao().insert(usage)
        assertEquals(listOf(usage), db.llmUsageDao().observeBetween(0, 2_000).first())
    }

    /** Dựng DB đúng schema [version] từ file JSON Room đã commit, kèm dữ liệu của người dùng trước khi nâng cấp. */
    private fun createDatabase(version: Int) {
        val schema = Json.parseToJsonElement(schemaFile(version).readText())
            .jsonObject.getValue("database").jsonObject
        val statements = buildList {
            schema.getValue("entities").jsonArray.forEach { element ->
                val entity = element.jsonObject
                val table = entity.getValue("tableName").jsonPrimitive.content
                add(entity.getValue("createSql").jsonPrimitive.content.replace("\${TABLE_NAME}", table))
                entity["indices"]?.jsonArray?.forEach { index ->
                    add(index.jsonObject.getValue("createSql").jsonPrimitive.content.replace("\${TABLE_NAME}", table))
                }
            }
            schema.getValue("setupQueries").jsonArray.forEach { add(it.jsonPrimitive.content) }
        }
        val file = context.getDatabasePath(name).also { it.parentFile?.mkdirs() }
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            statements.forEach(db::execSQL)
            db.execSQL(
                "INSERT INTO drafts (id, topicId, channel, text, imageUri, imageAltText, approved, updatedAtMillis, " +
                    "revision) VALUES ('d1', 't1', 'FACEBOOK', 'Bản nháp trước khi nâng cấp', NULL, NULL, 0, 1, 3)",
            )
            db.execSQL("INSERT INTO channel_cleanup_receipts (channel, operationId) VALUES ('FACEBOOK', 'op-1')")
            db.version = version
        }
    }

    /** Gradle chạy unit test trong thư mục module `app`, còn IDE có thể chạy từ thư mục gốc repo: thử cả hai. */
    private fun schemaFile(version: Int): File {
        val relative = "schemas/${AppDatabase::class.java.name}/$version.json"
        return listOf(File(relative), File("app", relative)).firstOrNull { it.isFile }
            ?: error("Không tìm thấy $relative (thư mục làm việc: ${File("").absolutePath})")
    }
}
