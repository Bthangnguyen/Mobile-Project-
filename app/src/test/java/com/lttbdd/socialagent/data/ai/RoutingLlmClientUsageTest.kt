package com.lttbdd.socialagent.data.ai

import com.lttbdd.socialagent.core.model.ChannelType
import com.lttbdd.socialagent.core.model.ChatChunk
import com.lttbdd.socialagent.core.model.ChatRequest
import com.lttbdd.socialagent.core.model.Comment
import com.lttbdd.socialagent.core.model.LlmProvider
import com.lttbdd.socialagent.core.model.LlmTask
import com.lttbdd.socialagent.core.model.TokenUsage
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** #74: router báo usage cuối của từng lượt gọi cho [LlmUsageRecorder]. Không gọi mạng thật. */
class RoutingLlmClientUsageTest {
    private val settings = InMemoryLlmConfiguration()
    private val keys = ApiKeySource { syntheticKey() }
    private val recorder = FakeUsageRecorder()

    @Test
    fun `records final OpenAI usage once with task provider and model`() = runTest {
        settings.set(LlmTask.WRITE_POST, LlmModelConfiguration(LlmProvider.OPENAI, "gpt-6-luna"))
        val client = RoutingLlmClient(settings, keys, setOf(OpenAiClient(FakeSseTransport(openAiFrames))), recorder)

        val chunks = client.streamChat(chatRequest()).toList()

        assertEquals("Xin chào", chunks.joinToString("") { it.delta })
        val record = recorder.records.single()
        assertEquals(LlmTask.WRITE_POST, record.task)
        assertEquals(LlmModelConfiguration(LlmProvider.OPENAI, "gpt-6-luna"), record.configuration)
        assertEquals(12, record.usage.inputTokens)
        assertEquals(5, record.usage.outputTokens)
    }

    @Test
    fun `Gemini usage counts thinking tokens as output and ignores mid-stream snapshots`() = runTest {
        settings.set(LlmTask.WRITE_POST, LlmModelConfiguration(LlmProvider.GEMINI, "gemini-2.5-flash"))
        val client = RoutingLlmClient(settings, keys, setOf(GeminiClient(FakeSseTransport(geminiFrames))), recorder)

        client.streamChat(chatRequest()).toList()

        val usage = recorder.records.single().usage
        assertEquals(12, usage.inputTokens)
        assertEquals(7, usage.outputTokens)
    }

    @Test
    fun `comment classification is recorded under its own task`() = runTest {
        settings.set(LlmTask.CLASSIFY_COMMENT, LlmModelConfiguration(LlmProvider.GEMINI, "gemini-2.5-flash-lite"))
        val adapter = ScriptedAdapter(LlmProvider.GEMINI) {
            emit(ChatChunk("PRAISE"))
            emit(ChatChunk("", TokenUsage(30, 1, Double.NaN)))
        }
        val client = RoutingLlmClient(settings, keys, setOf(adapter), recorder)

        client.classifyComment(Comment("c", ChannelType.FACEBOOK, "p", "Lan", "Hay quá", createdAtMillis = 0))

        assertEquals(LlmTask.CLASSIFY_COMMENT, recorder.records.single().task)
        assertEquals(30, recorder.records.single().usage.inputTokens)
    }

    @Test
    fun `no usage from provider means no record and no estimate from text length`() = runTest {
        configure()
        val adapter = ScriptedAdapter(LlmProvider.OPENAI) { emit(ChatChunk("một đoạn văn khá dài")) }
        val client = RoutingLlmClient(settings, keys, setOf(adapter), recorder)

        client.streamChat(chatRequest()).toList()

        assertTrue(recorder.records.isEmpty())
    }

    @Test
    fun `failed or incomplete streams are not recorded`() = runTest {
        configure()
        val incomplete = openAiFrames.dropLast(1)
        val client = RoutingLlmClient(settings, keys, setOf(OpenAiClient(FakeSseTransport(incomplete))), recorder)
        expectFailure(LlmFailure.INCOMPLETE_RESPONSE) { client.streamChat(chatRequest()).toList() }

        val failing = ScriptedAdapter(LlmProvider.OPENAI) {
            emit(ChatChunk("Xin "))
            throw IOException("mất mạng")
        }
        val other = RoutingLlmClient(settings, keys, setOf(failing), recorder)
        expectFailure(LlmFailure.NETWORK) { other.streamChat(chatRequest()).toList() }

        assertTrue(recorder.records.isEmpty())
    }

    @Test
    fun `recorder failure does not break the answer`() = runTest {
        configure()
        val broken = LlmUsageRecorder { _, _, _ -> throw IllegalStateException("disk full") }
        val client = RoutingLlmClient(settings, keys, setOf(OpenAiClient(FakeSseTransport(openAiFrames))), broken)

        val chunks = client.streamChat(chatRequest()).toList()

        assertEquals("Xin chào", chunks.joinToString("") { it.delta })
        assertEquals(12, chunks.last().usage?.inputTokens)
    }

    @Test
    fun `usage is still recorded when the caller stops right after the final chunk`() = runTest {
        configure()
        val client = RoutingLlmClient(settings, keys, setOf(OpenAiClient(FakeSseTransport(openAiFrames))), recorder)

        // first {} hủy upstream ngay khi nhận chunk có usage; chi phí đã phát sinh nên vẫn phải ghi.
        client.streamChat(chatRequest()).first { it.usage != null }

        assertEquals(1, recorder.records.size)
    }

    @Test
    fun `cancelling the caller job does not interrupt a slow recording`() = runTest {
        configure()
        val slow = object : LlmUsageRecorder {
            val records = mutableListOf<TokenUsage>()

            override suspend fun record(task: LlmTask, configuration: LlmModelConfiguration, usage: TokenUsage) {
                delay(100) // Giống ghi Room: có điểm suspend nên sẽ bị hủy nếu không chạy NonCancellable.
                records += usage
            }
        }
        val client = RoutingLlmClient(settings, keys, setOf(OpenAiClient(FakeSseTransport(openAiFrames))), slow)
        val received = CompletableDeferred<Unit>()

        val job = launch {
            client.streamChat(chatRequest()).collect { chunk ->
                if (chunk.usage != null) {
                    received.complete(Unit)
                    awaitCancellation()
                }
            }
        }
        received.await()
        job.cancelAndJoin()

        assertEquals(1, slow.records.size)
    }

    @Test
    fun `each collection is a separate call and is recorded separately`() = runTest {
        configure()
        val client = RoutingLlmClient(settings, keys, setOf(OpenAiClient(FakeSseTransport(openAiFrames))), recorder)
        val stream = client.streamChat(chatRequest())

        stream.toList()
        stream.toList()

        assertEquals(2, recorder.records.size)
    }

    private fun configure() {
        settings.set(LlmTask.WRITE_POST, LlmModelConfiguration(LlmProvider.OPENAI, "gpt-6-luna"))
    }

    private data class Recorded(val task: LlmTask, val configuration: LlmModelConfiguration, val usage: TokenUsage)

    private class FakeUsageRecorder : LlmUsageRecorder {
        val records = mutableListOf<Recorded>()

        override suspend fun record(task: LlmTask, configuration: LlmModelConfiguration, usage: TokenUsage) {
            records += Recorded(task, configuration, usage)
        }
    }

    private class ScriptedAdapter(
        override val provider: LlmProvider,
        private val script: suspend FlowCollector<ChatChunk>.() -> Unit,
    ) : ProviderAdapter {
        override fun streamChat(
            request: ChatRequest,
            configuration: LlmModelConfiguration,
            apiKey: String,
        ): Flow<ChatChunk> = flow { script() }
    }
}
