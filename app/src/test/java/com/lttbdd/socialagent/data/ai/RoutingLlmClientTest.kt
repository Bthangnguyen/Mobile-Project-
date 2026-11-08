package com.lttbdd.socialagent.data.ai

import com.lttbdd.socialagent.core.model.ChannelType
import com.lttbdd.socialagent.core.model.ChatChunk
import com.lttbdd.socialagent.core.model.ChatRequest
import com.lttbdd.socialagent.core.model.Comment
import com.lttbdd.socialagent.core.model.CommentCategory
import com.lttbdd.socialagent.core.model.LlmProvider
import com.lttbdd.socialagent.core.model.LlmTask
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RoutingLlmClientTest {
    private val settings = InMemoryLlmConfiguration()
    private var key: String? = syntheticKey()
    private val keys = ApiKeySource { key }
    private val openAi = RecordingAdapter(LlmProvider.OPENAI)
    private val gemini = RecordingAdapter(LlmProvider.GEMINI)
    private val client = RoutingLlmClient(settings, keys, setOf(openAi, gemini))
    private val comment = Comment("c", ChannelType.FACEBOOK, "p", "Lan", "Có nguồn không?", createdAtMillis = 0)

    @Test
    fun `routes provider and model by task and refreshes selection on each collection`() = runTest {
        settings.set(LlmTask.WRITE_POST, LlmModelConfiguration(LlmProvider.OPENAI, "openai-selected"))
        settings.set(LlmTask.CLASSIFY_COMMENT, LlmModelConfiguration(LlmProvider.GEMINI, "gemini-selected"))
        val stream = client.streamChat(chatRequest())
        assertTrue(openAi.calls.isEmpty())
        stream.toList()
        client.streamChat(chatRequest(LlmTask.CLASSIFY_COMMENT)).toList()
        assertEquals("openai-selected", openAi.calls.single().second.model)
        assertEquals("gemini-selected", gemini.calls.single().second.model)
        settings.set(LlmTask.WRITE_POST, LlmModelConfiguration(LlmProvider.GEMINI, "new-model"))
        stream.toList()
        assertEquals("new-model", gemini.calls.last().second.model)
        assertEquals(1, openAi.calls.size)
    }

    @Test
    fun `reads replacement key and refuses deleted key rather than caching it`() = runTest {
        configure(LlmProvider.OPENAI)
        client.streamChat(chatRequest()).toList()
        key = syntheticKey()
        client.streamChat(chatRequest()).toList()
        assertEquals(key, openAi.lastKey)
        key = null
        expectFailure(LlmFailure.MISSING_API_KEY) { client.streamChat(chatRequest()).toList() }
        assertEquals(2, openAi.calls.size)
    }

    @Test
    fun `missing blank or unsupported configuration produces no provider requests`() = runTest {
        expectFailure(LlmFailure.MISSING_CONFIGURATION) { client.streamChat(chatRequest()).toList() }
        settings.set(LlmTask.WRITE_POST, LlmModelConfiguration(LlmProvider.OPENAI, " "))
        expectFailure(LlmFailure.MISSING_CONFIGURATION) { client.streamChat(chatRequest()).toList() }
        settings.set(LlmTask.WRITE_POST, LlmModelConfiguration(LlmProvider.ANTHROPIC, "model"))
        expectFailure(LlmFailure.UNSUPPORTED_PROVIDER) { client.streamChat(chatRequest()).toList() }
        assertTrue(openAi.calls.isEmpty() && gemini.calls.isEmpty())
    }

    @Test
    fun `blank keys and header injection are rejected before HTTP`() = runTest {
        configure(LlmProvider.OPENAI)
        for (value in listOf(null, "", " ", "x\r\nInjected: y")) {
            key = value
            expectFailure(LlmFailure.MISSING_API_KEY) { client.streamChat(chatRequest()).toList() }
        }
        assertTrue(openAi.calls.isEmpty())
    }

    @Test
    fun `invalid input image task and unsafe Gemini model create no requests`() = runTest {
        configure(LlmProvider.GEMINI)
        expectFailure(LlmFailure.INVALID_REQUEST) { client.streamChat(chatRequest().copy(messages = emptyList())).toList() }
        expectFailure(LlmFailure.INVALID_REQUEST) { client.streamChat(chatRequest().copy(temperature = Double.NaN)).toList() }
        expectFailure(LlmFailure.UNSUPPORTED_TASK) { client.streamChat(chatRequest(LlmTask.GENERATE_IMAGE)).toList() }
        settings.set(LlmTask.WRITE_POST, LlmModelConfiguration(LlmProvider.GEMINI, "../other-endpoint"))
        expectFailure(LlmFailure.INVALID_REQUEST) { client.streamChat(chatRequest()).toList() }
        assertTrue(gemini.calls.isEmpty())
    }

    @Test
    fun `classifies every category with either provider using classification task`() = runTest {
        for (provider in listOf(LlmProvider.OPENAI, LlmProvider.GEMINI)) {
            configure(provider)
            val adapter = if (provider == LlmProvider.OPENAI) openAi else gemini
            for (category in CommentCategory.entries) {
                adapter.answer = category.name
                assertEquals(category, client.classifyComment(comment))
                assertEquals(LlmTask.CLASSIFY_COMMENT, adapter.calls.last().first.task)
            }
        }
    }

    @Test
    fun `rejects invalid classification instead of guessing a category`() = runTest {
        configure(LlmProvider.OPENAI)
        openAi.answer = "not a category"
        expectFailure(LlmFailure.INVALID_RESPONSE) { client.classifyComment(comment) }
    }

    @Test
    fun `draft reply uses writing task and includes escaped post and comment context`() = runTest {
        for (provider in listOf(LlmProvider.OPENAI, LlmProvider.GEMINI)) {
            configure(provider)
            val adapter = if (provider == LlmProvider.OPENAI) openAi else gemini
            adapter.answer = "  Cảm ơn bạn!  "
            assertEquals("Cảm ơn bạn!", client.draftReply(comment, "Bài viết \"gốc\""))
            val request = adapter.calls.last().first
            assertEquals(LlmTask.WRITE_POST, request.task)
            assertTrue(request.messages.last().content.contains("Có nguồn không?"))
            assertTrue(request.messages.last().content.contains("\\\"gốc\\\""))
        }
    }

    @Test
    fun `empty reply is rejected`() = runTest {
        configure(LlmProvider.GEMINI)
        gemini.answer = "  "
        expectFailure(LlmFailure.EMPTY_RESPONSE) { client.draftReply(comment, "Post") }
    }

    @Test
    fun `network and HTTP failures never fallback or automatically retry`() = runTest {
        configure(LlmProvider.OPENAI)
        for (error in listOf(IOException(syntheticKey()), httpFailure(401), httpFailure(403), httpFailure(429), httpFailure(503))) {
            openAi.error = error
            val expected = if (error is LlmException) error.failure else LlmFailure.NETWORK
            val result = expectFailure(expected) { client.streamChat(chatRequest()).toList() }
            assertNull(result.cause)
        }
        assertEquals(5, openAi.calls.size)
        assertTrue(gemini.calls.isEmpty())
    }

    @Test
    fun `source exceptions do not leak secret values`() = runTest {
        val secret = syntheticKey()
        val client = RoutingLlmClient(
            LlmConfigurationSource { throw IllegalStateException(secret) }, keys, setOf(openAi),
        )
        val configurationError = expectFailure(LlmFailure.MISSING_CONFIGURATION) { client.streamChat(chatRequest()).toList() }
        configure(LlmProvider.OPENAI)
        val other = RoutingLlmClient(settings, ApiKeySource { throw IOException(secret) }, setOf(openAi))
        val keyError = expectFailure(LlmFailure.MISSING_API_KEY) { other.streamChat(chatRequest()).toList() }
        assertFalse(configurationError.toString().contains(secret))
        assertFalse(keyError.toString().contains(secret))
        assertNull(configurationError.cause)
        assertNull(keyError.cause)
    }

    @Test
    fun `cancellation of key read propagates without becoming a provider error`() = runTest {
        configure(LlmProvider.OPENAI)
        val cancellation = CancellationException("cancel")
        val client = RoutingLlmClient(settings, ApiKeySource { throw cancellation }, setOf(openAi))
        try {
            client.streamChat(chatRequest()).toList()
            throw AssertionError("Expected cancellation")
        } catch (actual: CancellationException) {
            // Stacktrace recovery qua flowOn có thể sao chép exception, giữ bản gốc trong cause.
            assertEquals(cancellation.message, actual.message)
            assertTrue(generateSequence<Throwable>(actual) { it.cause }.any { it === cancellation })
        }
        assertTrue(openAi.calls.isEmpty())
    }

    @Test
    fun `cancelling active provider collection closes the upstream flow`() = runTest {
        val started = CompletableDeferred<Unit>()
        val closed = CompletableDeferred<Unit>()
        val adapter = object : ProviderAdapter {
            override val provider = LlmProvider.OPENAI
            override fun streamChat(request: ChatRequest, configuration: LlmModelConfiguration, apiKey: String) = flow<ChatChunk> {
                try {
                    started.complete(Unit)
                    awaitCancellation()
                } finally {
                    closed.complete(Unit)
                }
            }
        }
        configure(LlmProvider.OPENAI)
        val client = RoutingLlmClient(settings, keys, setOf(adapter))
        val collection = launch(start = CoroutineStart.UNDISPATCHED) { client.streamChat(chatRequest()).toList() }
        started.await()
        collection.cancel()
        collection.join()
        assertTrue(closed.isCompleted)
    }

    @Test
    fun `clearing selection removes configuration`() = runTest {
        configure(LlmProvider.GEMINI)
        settings.clear(LlmTask.WRITE_POST)
        assertNull(settings.configurationFor(LlmTask.WRITE_POST))
    }

    private fun configure(provider: LlmProvider) {
        for (task in listOf(LlmTask.WRITE_POST, LlmTask.CLASSIFY_COMMENT)) {
            settings.set(task, LlmModelConfiguration(provider, "selected-model"))
        }
    }

    private class RecordingAdapter(override val provider: LlmProvider) : ProviderAdapter {
        val calls = mutableListOf<Pair<ChatRequest, LlmModelConfiguration>>()
        var lastKey: String? = null
        var answer = "ok"
        var error: Exception? = null

        override fun streamChat(request: ChatRequest, configuration: LlmModelConfiguration, apiKey: String): Flow<ChatChunk> = flow {
            calls += request to configuration
            lastKey = apiKey
            error?.let { throw it }
            emit(ChatChunk(answer))
        }
    }
}
