package com.lttbdd.socialagent.core.database

import android.app.Application
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.lttbdd.socialagent.core.database.entity.LlmUsageEntity
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
 * Migration 1 → 2 (#74): dựng DB version 1 đúng theo `app/schemas/.../1.json` rồi mở bằng Room version 2.
 * Dữ liệu cũ phải còn nguyên và bảng `llm_usage` dùng được. Thư mục làm việc khi chạy unit test là module `app`.
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
    fun `migrating from version 1 keeps existing data and adds usage table`() = runTest {
        createVersion1Database()

        val db = Room.databaseBuilder(context, AppDatabase::class.java, name)
            .allowMainThreadQueries()
            .build()
            .also { room = it }

        db.openHelper.writableDatabase.query("SELECT text FROM drafts WHERE id = 'd1'").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("Bản nháp trước khi nâng cấp", cursor.getString(0))
        }
        assertEquals(2, db.openHelper.writableDatabase.version)

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

    private fun createVersion1Database() {
        val schema = Json.parseToJsonElement(File(SCHEMA_V1).readText()).jsonObject.getValue("database").jsonObject
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
                "INSERT INTO drafts (id, topicId, channel, text, imageUri, imageAltText, approved, updatedAtMillis) " +
                    "VALUES ('d1', 't1', 'FACEBOOK', 'Bản nháp trước khi nâng cấp', NULL, NULL, 0, 1)",
            )
            db.version = 1
        }
    }

    private companion object {
        const val SCHEMA_V1 = "schemas/com.lttbdd.socialagent.core.database.AppDatabase/1.json"
    }
}
