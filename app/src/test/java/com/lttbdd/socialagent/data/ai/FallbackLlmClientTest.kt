package com.lttbdd.socialagent.data.ai

import com.lttbdd.socialagent.core.ai.LlmClient
import com.lttbdd.socialagent.core.model.ChannelType
import com.lttbdd.socialagent.core.model.ChatChunk
import com.lttbdd.socialagent.core.model.ChatMessage
import com.lttbdd.socialagent.core.model.ChatRequest
import com.lttbdd.socialagent.core.model.ChatRole
import com.lttbdd.socialagent.core.model.Comment
import com.lttbdd.socialagent.core.model.CommentCategory
import com.lttbdd.socialagent.core.model.LlmProvider
import com.lttbdd.socialagent.core.model.LlmTask
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class FallbackLlmClientTest {

    private val preference = FakeFallbackPreference(enabled = true)
    private val primary = ScriptedClient("primary")
    private val secondary = ScriptedClient("secondary")
    private var fallbackConfiguration: LlmModelConfiguration? = LlmModelConfiguration(LlmProvider.OPENAI, "gpt-6-luna")
    private val client = FallbackLlmClient(
        primary = primary,
        fallback = secondary,
        fallbackConfigurations = { fallbackConfiguration },
        preference = preference,
    )

    private val request = ChatRequest(LlmTask.WRITE_POST, listOf(ChatMessage(ChatRole.USER, "topic")))
    private val comment = Comment("c1", ChannelType.FACEBOOK, "p1", "An", "Giá bao nhiêu?", 0)

    private suspend fun stream(): List<String> = client.streamChat(request).toList().map { it.delta }

    private suspend fun expectFailure(failure: LlmFailure, block: suspend () -> Unit) {
        try {
            block()
            fail("Phải báo lỗi $failure")
        } catch (error: LlmException) {
            assertEquals(failure, error.failure)
        }
    }

    @Test
    fun `provider side failures switch to the backup provider once`() = runTest {
        FallbackLlmClient.FALLBACK_FAILURES.forEach { failure ->
            primary.failure = failure
            secondary.calls = 0

            assertEquals(failure.name, listOf("secondary"), stream())
            assertEquals(1, secondary.calls)
        }
    }

    @Test
    fun `request, configuration, network and blocked content errors never switch`() = runTest {
        val kept = LlmFailure.entries - FallbackLlmClient.FALLBACK_FAILURES
        assertTrue(LlmFailure.NETWORK in kept && LlmFailure.CONTENT_BLOCKED in kept)
        kept.forEach { failure ->
            primary.failure = failure
            expectFailure(failure) { stream() }
        }
        assertEquals(0, secondary.calls)
    }

    @Test
    fun `switch is off by default and keeps the original error`() = runTest {
        preference.enabled.value = false
        primary.failure = LlmFailure.SERVER

        expectFailure(LlmFailure.SERVER) { stream() }
        assertEquals(0, secondary.calls)
    }

    @Test
    fun `no backup provider keeps the original error`() = runTest {
        fallbackConfiguration = null
        primary.failure = LlmFailure.RATE_LIMIT

        expectFailure(LlmFailure.RATE_LIMIT) { stream() }
        assertEquals(0, secondary.calls)
    }

    @Test
    fun `stream never switches after the first chunk`() = runTest {
        primary.failure = LlmFailure.SERVER
        primary.chunksBeforeFailure = 1

        val received = mutableListOf<String>()
        expectFailure(LlmFailure.SERVER) { client.streamChat(request).collect { received += it.delta } }
        assertEquals("Không ghép nội dung của hai nhà cung cấp", listOf("primary"), received)
        assertEquals(0, secondary.calls)
    }

    @Test
    fun `backup failure is reported and not retried again`() = runTest {
        primary.failure = LlmFailure.SERVER
        secondary.failure = LlmFailure.RATE_LIMIT

        expectFailure(LlmFailure.RATE_LIMIT) { stream() }
        assertEquals(1, primary.calls)
        assertEquals(1, secondary.calls)
    }

    @Test
    fun `cancellation is passed through and does not switch`() = runTest {
        primary.cancel = true

        try {
            stream()
            fail("Phải truyền tiếp CancellationException")
        } catch (_: CancellationException) {
        }
        assertEquals(0, secondary.calls)
    }

    @Test
    fun `classify and reply also switch on provider failures`() = runTest {
        primary.failure = LlmFailure.TIMEOUT

        assertEquals(CommentCategory.QUESTION, client.classifyComment(comment))
        assertEquals("secondary reply", client.draftReply(comment, "post"))
        assertEquals(2, secondary.calls)
    }

    @Test
    fun `image generation never switches`() = runTest {
        primary.failure = LlmFailure.SERVER

        expectFailure(LlmFailure.SERVER) {
            client.streamChat(request.copy(task = LlmTask.GENERATE_IMAGE)).toList()
        }
        assertEquals(0, secondary.calls)
    }

    @Test
    fun `success never calls the backup provider`() = runTest {
        assertEquals(listOf("primary"), stream())
        assertEquals(0, secondary.calls)
    }

    // ---------- Nguồn cấu hình nhà cung cấp phụ ----------

    @Test
    fun `backup is the other provider with a key and its first suggested model`() = runTest {
        val main = InMemoryLlmConfiguration().apply {
            set(LlmTask.WRITE_POST, LlmModelConfiguration(LlmProvider.GEMINI, "gemini-2.5-flash"))
            set(LlmTask.CLASSIFY_COMMENT, LlmModelConfiguration(LlmProvider.OPENAI, "gpt-6-luna"))
        }
        val keys = FakeKeyAvailability(LlmProvider.GEMINI, LlmProvider.OPENAI)
        val source = FallbackConfigurationSource(main, keys)

        assertEquals(
            LlmModelConfiguration(LlmProvider.OPENAI, LlmModelCatalog.firstSuggestion(LlmProvider.OPENAI, LlmTask.WRITE_POST)!!),
            source.configurationFor(LlmTask.WRITE_POST),
        )
        assertEquals(
            LlmModelConfiguration(
                LlmProvider.GEMINI,
                LlmModelCatalog.firstSuggestion(LlmProvider.GEMINI, LlmTask.CLASSIFY_COMMENT)!!,
            ),
            source.configurationFor(LlmTask.CLASSIFY_COMMENT),
        )
    }

    @Test
    fun `no backup when the other provider has no key or the task is not configured`() = runTest {
        val main = InMemoryLlmConfiguration().apply {
            set(LlmTask.WRITE_POST, LlmModelConfiguration(LlmProvider.GEMINI, "gemini-2.5-flash"))
        }
        val keys = FakeKeyAvailability(LlmProvider.GEMINI)
        val source = FallbackConfigurationSource(main, keys)

        assertNull("Nhà cung cấp còn lại chưa có key", source.configurationFor(LlmTask.WRITE_POST))
        assertNull("Loại việc chưa có cấu hình chính", source.configurationFor(LlmTask.CLASSIFY_COMMENT))
    }

    private class FakeFallbackPreference(enabled: Boolean) : FallbackPreferenceStore {
        val enabled = MutableStateFlow(enabled)

        override fun observe(): Flow<Boolean> = enabled

        override suspend fun setEnabled(enabled: Boolean) {
            this.enabled.value = enabled
        }
    }

    private class ScriptedClient(private val name: String) : LlmClient {
        var calls = 0
        var failure: LlmFailure? = null
        var chunksBeforeFailure = 0
        var cancel = false

        private fun fail() {
            if (cancel) throw CancellationException("cancelled")
            failure?.let { throw LlmException(it) }
        }

        override fun streamChat(request: ChatRequest): Flow<ChatChunk> = flow {
            calls++
            repeat(chunksBeforeFailure) { emit(ChatChunk(name)) }
            fail()
            if (chunksBeforeFailure == 0) emit(ChatChunk(name))
        }

        override suspend fun classifyComment(comment: Comment): CommentCategory {
            calls++
            fail()
            return CommentCategory.QUESTION
        }

        override suspend fun draftReply(comment: Comment, postText: String): String {
            calls++
            fail()
            return "$name reply"
        }
    }
}
