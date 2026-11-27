package com.lttbdd.socialagent.data.usage

import com.lttbdd.socialagent.core.model.LlmProvider
import com.lttbdd.socialagent.core.model.LlmTask
import com.lttbdd.socialagent.core.model.TokenUsage
import com.lttbdd.socialagent.data.ai.LlmModelConfiguration
import com.lttbdd.socialagent.data.ai.LlmPricing
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RoomLlmUsageRecorderTest {
    private val dao = FakeLlmUsageDao()
    private val time = FixedTimeSource(millisAt("2026-11-27T10:00:00"))
    private var nextId = 0
    private val recorder = RoomLlmUsageRecorder(dao, time) { "id-${++nextId}" }

    @Test
    fun `stores tokens estimated cost and pricing version for a priced model`() = runTest {
        recorder.record(
            LlmTask.CLASSIFY_COMMENT,
            LlmModelConfiguration(LlmProvider.GEMINI, "gemini-2.5-flash-lite"),
            TokenUsage(1_000, 500, Double.NaN),
        )

        val row = dao.rows.value.single()
        assertEquals("id-1", row.id)
        assertEquals(time.now, row.occurredAtMillis)
        assertEquals(LlmProvider.GEMINI, row.provider)
        assertEquals("gemini-2.5-flash-lite", row.model)
        assertEquals(LlmTask.CLASSIFY_COMMENT, row.task)
        assertEquals(1_000, row.inputTokens)
        assertEquals(500, row.outputTokens)
        assertEquals(300L, row.costMicros)
        assertEquals(LlmPricing.VERSION, row.pricingVersion)
    }

    @Test
    fun `unpriced model keeps tokens and marks cost unknown instead of zero`() = runTest {
        recorder.record(
            LlmTask.WRITE_POST,
            LlmModelConfiguration(LlmProvider.OPENAI, "ft:gpt-6-luna:org::abc"),
            TokenUsage(40, 20, Double.NaN),
        )

        val row = dao.rows.value.single()
        assertEquals(40, row.inputTokens)
        assertNull(row.costMicros)
        assertNull(row.pricingVersion)
    }

    @Test
    fun `Gemini models prefix is stored as the canonical model id`() = runTest {
        recorder.record(
            LlmTask.WRITE_POST,
            LlmModelConfiguration(LlmProvider.GEMINI, "models/gemini-2.5-flash"),
            TokenUsage(1_000, 500, Double.NaN),
        )

        assertEquals("gemini-2.5-flash", dao.rows.value.single().model)
        assertEquals(1_550L, dao.rows.value.single().costMicros)
    }

    @Test
    fun `adapter cost value is ignored even when it is a number`() = runTest {
        // FakeLlmClient phát costUsd = 0.0; chi phí phải lấy từ bảng giá chứ không tin số này.
        recorder.record(
            LlmTask.WRITE_POST,
            LlmModelConfiguration(LlmProvider.GEMINI, "gemini-2.5-flash"),
            TokenUsage(1_000, 500, 0.0),
        )

        assertEquals(1_550L, dao.rows.value.single().costMicros)
    }

    @Test
    fun `negative token counts are not stored`() = runTest {
        val configuration = LlmModelConfiguration(LlmProvider.GEMINI, "gemini-2.5-flash")
        recorder.record(LlmTask.WRITE_POST, configuration, TokenUsage(-1, 5, Double.NaN))
        recorder.record(LlmTask.WRITE_POST, configuration, TokenUsage(5, -1, Double.NaN))

        assertTrue(dao.rows.value.isEmpty())
    }

    @Test
    fun `each call gets its own id`() = runTest {
        val configuration = LlmModelConfiguration(LlmProvider.GEMINI, "gemini-2.5-flash")
        val default = RoomLlmUsageRecorder(dao, time)
        default.record(LlmTask.WRITE_POST, configuration, TokenUsage(1, 1, Double.NaN))
        default.record(LlmTask.WRITE_POST, configuration, TokenUsage(1, 1, Double.NaN))

        assertEquals(2, dao.rows.value.map { it.id }.distinct().size)
    }
}
