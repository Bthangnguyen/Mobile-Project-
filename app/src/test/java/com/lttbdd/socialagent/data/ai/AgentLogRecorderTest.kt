package com.lttbdd.socialagent.data.ai

import com.lttbdd.socialagent.core.model.AgentEvent
import com.lttbdd.socialagent.core.model.AgentStep
import com.lttbdd.socialagent.core.model.ChannelType
import com.lttbdd.socialagent.core.model.ContentLanguage
import com.lttbdd.socialagent.core.model.Draft
import com.lttbdd.socialagent.core.model.TokenUsage
import com.lttbdd.socialagent.core.model.TopicBrief
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AgentLogRecorderTest {

    private val brief = TopicBrief(
        id = "t1",
        topic = "Ra mắt sản phẩm mới",
        goal = "Tăng tương tác",
        audience = "Khách hàng trẻ",
        tone = "Thân thiện",
        language = ContentLanguage.VI,
        channels = setOf(ChannelType.FACEBOOK),
    )

    @Test
    fun `recordStream captures steps, outputs, tokens, and finite vs unknown cost`() = runTest {
        val store = InMemoryAgentLogStore()
        val recorder = AgentLogRecorder(store, clock = { 1_000L }, idGenerator = { "run-1" })
        val agent = ContentAgentImpl(
            mapOf(
                ChannelType.FACEBOOK to ChannelSubAgent { _, emit ->
                    emit(AgentEvent.StepStarted(ChannelType.FACEBOOK, AgentStep.OUTLINE))
                    emit(AgentEvent.TextDelta(ChannelType.FACEBOOK, AgentStep.OUTLINE, "- Mở bài\n"))
                    emit(AgentEvent.TextDelta(ChannelType.FACEBOOK, AgentStep.OUTLINE, "- Thân bài"))
                    emit(
                        AgentEvent.StepFinished(
                            ChannelType.FACEBOOK,
                            AgentStep.OUTLINE,
                            TokenUsage(inputTokens = 100, outputTokens = 50, costUsd = 0.0025),
                        ),
                    )
                    emit(AgentEvent.StepStarted(ChannelType.FACEBOOK, AgentStep.DRAFT))
                    emit(AgentEvent.TextDelta(ChannelType.FACEBOOK, AgentStep.DRAFT, "Nội dung hoàn chỉnh"))
                    emit(
                        AgentEvent.StepFinished(
                            ChannelType.FACEBOOK,
                            AgentStep.DRAFT,
                            TokenUsage(inputTokens = 200, outputTokens = 150, costUsd = Double.NaN),
                        ),
                    )
                    emit(
                        AgentEvent.ChannelDone(
                            Draft("d1", "t1", ChannelType.FACEBOOK, "Nội dung hoàn chỉnh"),
                        ),
                    )
                },
            ),
        )

        val events = recorder.recordStream(brief, agent.generate(brief)).toList()
        assertTrue(events.any { it is AgentEvent.ChannelDone })

        val log = store.getLogs().single()
        assertEquals("run-1", log.id)
        assertEquals(2, log.steps.size)
        assertEquals("- Mở bài\n- Thân bài", log.steps[0].output)
        assertEquals(0.0025, log.steps[0].costUsd ?: 0.0, 0.00001)
        assertNull(log.steps[1].costUsd)
        assertEquals(300, log.totalInputTokens)
        assertEquals(200, log.totalOutputTokens)
        assertTrue(log.hasUnknownCost)
        assertFalse(log.hasFailure)
    }

    @Test
    fun `recordStream records failed step with safe error code and swallows store errors`() = runTest {
        val store = InMemoryAgentLogStore()
        val recorder = AgentLogRecorder(store, clock = { 2_000L })
        val agent = ContentAgentImpl(
            mapOf(
                ChannelType.FACEBOOK to ChannelSubAgent { _, emit ->
                    emit(AgentEvent.StepStarted(ChannelType.FACEBOOK, AgentStep.DRAFT))
                    throw ChannelSubAgentException(AgentStep.DRAFT, "LLM_RATE_LIMIT")
                },
            ),
        )

        val events = recorder.recordStream(brief, agent.generate(brief)).toList()
        assertTrue(events.any { it is AgentEvent.ChannelFailed })

        val log = store.getLogs().single()
        assertTrue(log.hasFailure)
        assertEquals(AgentStepStatus.FAILED, log.steps.single().status)
        assertEquals("LLM_RATE_LIMIT", log.steps.single().errorCode)

        // Lỗi ghi store không làm gãy stream
        store.failOnRecord = true
        val secondRunEvents = recorder.recordStream(brief, agent.generate(brief)).toList()
        assertTrue(secondRunEvents.any { it is AgentEvent.ChannelFailed })
    }

    @Test
    fun `recordStream persists cancelled step on flow cancellation without swallowing CancellationException`() = runTest {
        val store = InMemoryAgentLogStore()
        val recorder = AgentLogRecorder(store, clock = { 3_000L })
        val started = CompletableDeferred<Unit>()
        val agent = ContentAgentImpl(
            mapOf(
                ChannelType.FACEBOOK to ChannelSubAgent { _, emit ->
                    emit(AgentEvent.StepStarted(ChannelType.FACEBOOK, AgentStep.DRAFT))
                    emit(AgentEvent.TextDelta(ChannelType.FACEBOOK, AgentStep.DRAFT, "Đang viết..."))
                    started.complete(Unit)
                    awaitCancellation()
                },
            ),
        )

        val job = launch {
            recorder.recordStream(brief, agent.generate(brief)).collect()
        }
        runCurrent()
        assertTrue(started.isCompleted)

        job.cancel(CancellationException("user cancelled"))
        advanceUntilIdle()

        assertTrue(job.isCancelled)
        val log = store.getLogs().single()
        assertEquals(AgentStepStatus.CANCELLED, log.steps.single().status)
        assertEquals("Đang viết...", log.steps.single().output)
    }

    private class InMemoryAgentLogStore : AgentLogStore {
        private val logs = MutableStateFlow<List<AgentRunLog>>(emptyList())
        var failOnRecord = false

        override fun observeLogs(): Flow<List<AgentRunLog>> = logs
        override suspend fun getLogs(): List<AgentRunLog> = logs.value
        override suspend fun recordRun(log: AgentRunLog) {
            if (failOnRecord) throw IllegalStateException("Simulated disk error")
            logs.value = listOf(log) + logs.value
        }

        override suspend fun clearAll() {
            logs.value = emptyList()
        }
    }
}
