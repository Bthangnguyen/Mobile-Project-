package com.lttbdd.socialagent.core.database

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.lttbdd.socialagent.core.database.entity.LlmUsageEntity
import com.lttbdd.socialagent.core.model.LlmProvider
import com.lttbdd.socialagent.core.model.LlmTask
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** DAO usage LLM (#74) trên DB in-memory, chạy bằng Robolectric. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class LlmUsageDaoTest {
    private lateinit var db: AppDatabase

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun usage(id: String, at: Long, costMicros: Long? = 100) = LlmUsageEntity(
        id = id,
        occurredAtMillis = at,
        provider = LlmProvider.GEMINI,
        model = "gemini-2.5-flash",
        task = LlmTask.WRITE_POST,
        inputTokens = 10,
        outputTokens = 5,
        costMicros = costMicros,
        pricingVersion = costMicros?.let { "2026-11-27" },
    )

    @Test
    fun `observe between is half open and ordered by time`() = runTest {
        val dao = db.llmUsageDao()
        dao.insert(usage("late", 200))
        dao.insert(usage("start", 100))
        dao.insert(usage("end", 300))
        dao.insert(usage("before", 99))

        val rows = dao.observeBetween(100, 300).first()

        assertEquals(listOf("start", "late"), rows.map { it.id })
    }

    @Test
    fun `duplicate id is ignored so a call is not counted twice`() = runTest {
        val dao = db.llmUsageDao()
        dao.insert(usage("same", 100))
        dao.insert(usage("same", 100).copy(inputTokens = 999))

        val rows = dao.observeBetween(0, 1_000).first()

        assertEquals(1, rows.size)
        assertEquals(10, rows.single().inputTokens)
    }

    @Test
    fun `unknown cost is stored as null not zero`() = runTest {
        val dao = db.llmUsageDao()
        dao.insert(usage("unknown", 100, costMicros = null))

        val row = dao.observeBetween(0, 1_000).first().single()

        assertNull(row.costMicros)
        assertNull(row.pricingVersion)
    }

    @Test
    fun `delete all removes every record`() = runTest {
        val dao = db.llmUsageDao()
        dao.insert(usage("a", 100))
        dao.insert(usage("b", 200))

        assertEquals(2, dao.deleteAll())
        assertTrue(dao.observeBetween(0, 1_000).first().isEmpty())
    }
}
