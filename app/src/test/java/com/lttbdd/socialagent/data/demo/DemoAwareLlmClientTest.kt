package com.lttbdd.socialagent.data.demo

import com.lttbdd.socialagent.core.ai.LlmClient
import com.lttbdd.socialagent.core.model.ChannelType
import com.lttbdd.socialagent.core.model.ChatChunk
import com.lttbdd.socialagent.core.model.ChatMessage
import com.lttbdd.socialagent.core.model.ChatRequest
import com.lttbdd.socialagent.core.model.ChatRole
import com.lttbdd.socialagent.core.model.Comment
import com.lttbdd.socialagent.core.model.CommentCategory
import com.lttbdd.socialagent.core.model.LlmTask
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class DemoAwareLlmClientTest {

    private val store = FakeDemoModeStore()
    private val real = RecordingClient("real")
    private val demo = RecordingClient("demo")
    private val client = DemoAwareLlmClient(real = real, demo = demo, demoMode = store)

    private val request = ChatRequest(
        task = LlmTask.WRITE_POST,
        messages = listOf(ChatMessage(ChatRole.USER, "topic")),
    )
    private val comment = Comment("c1", ChannelType.FACEBOOK, "p1", "An", "Hay quá", 0)

    @Test
    fun `demo off uses the real router`() = runTest {
        assertEquals(listOf("real"), client.streamChat(request).toList().map { it.delta })
        assertEquals(CommentCategory.QUESTION, client.classifyComment(comment))
        assertEquals("real reply", client.draftReply(comment, "post"))
        assertEquals(3, real.calls)
        assertEquals(0, demo.calls)
    }

    @Test
    fun `demo on never touches the real router`() = runTest {
        store.enabled.value = true

        assertEquals(listOf("demo"), client.streamChat(request).toList().map { it.delta })
        assertEquals(CommentCategory.PRAISE, client.classifyComment(comment))
        assertEquals("demo reply", client.draftReply(comment, "post"))
        assertEquals("Không đọc key, không gọi nhà cung cấp", 0, real.calls)
        assertEquals(3, demo.calls)
    }

    @Test
    fun `toggling takes effect on the next call without restarting`() = runTest {
        assertEquals("real reply", client.draftReply(comment, "post"))
        store.enabled.value = true
        assertEquals("demo reply", client.draftReply(comment, "post"))
        store.enabled.value = false
        assertEquals("real reply", client.draftReply(comment, "post"))
    }

    @Test
    fun `stream picks its source when collected, not when created`() = runTest {
        val flow = client.streamChat(request)
        store.enabled.value = true

        assertEquals(listOf("demo"), flow.toList().map { it.delta })
        assertEquals(0, real.calls)
    }

    private class FakeDemoModeStore : DemoModeStore {
        val enabled = MutableStateFlow(false)

        override fun observe(): Flow<Boolean> = enabled

        override suspend fun setEnabled(enabled: Boolean) {
            this.enabled.value = enabled
        }
    }

    private class RecordingClient(private val name: String) : LlmClient {
        var calls = 0

        override fun streamChat(request: ChatRequest): Flow<ChatChunk> {
            calls++
            return flowOf(ChatChunk(name))
        }

        override suspend fun classifyComment(comment: Comment): CommentCategory {
            calls++
            return if (name == "real") CommentCategory.QUESTION else CommentCategory.PRAISE
        }

        override suspend fun draftReply(comment: Comment, postText: String): String {
            calls++
            return "$name reply"
        }
    }
}
