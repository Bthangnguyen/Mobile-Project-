package com.lttbdd.socialagent.data.ai

import com.lttbdd.socialagent.core.model.ChatMessage
import com.lttbdd.socialagent.core.model.ChatRequest
import com.lttbdd.socialagent.core.model.ChatRole
import com.lttbdd.socialagent.core.model.LlmTask
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import okhttp3.Request
import okio.Buffer
import org.junit.Assert.assertEquals

/** Key giả sinh khi chạy, không dùng secret hay kết nối mạng thật. */
internal fun syntheticKey(): String = UUID.randomUUID().toString()

internal fun chatRequest(task: LlmTask = LlmTask.WRITE_POST) = ChatRequest(
    task,
    listOf(ChatMessage(ChatRole.SYSTEM, "Be helpful."), ChatMessage(ChatRole.USER, "Xin chào")),
)

internal class FakeSseTransport(var frames: List<String> = emptyList()) : SseTransport {
    val requests = mutableListOf<Request>()
    var error: Exception? = null
    var closed = false

    override fun events(request: Request): Flow<String> = flow {
        requests += request
        try {
            error?.let { throw it }
            frames.forEach { emit(it) }
        } finally {
            closed = true
        }
    }
}

internal fun Request.jsonBody(): JsonObject {
    val buffer = Buffer()
    requireNotNull(body).writeTo(buffer)
    return providerJson.parseToJsonElement(buffer.readUtf8()).jsonObject
}

internal suspend fun expectFailure(failure: LlmFailure, block: suspend () -> Unit): LlmException {
    val error = try {
        block()
        throw AssertionError("Expected LlmException")
    } catch (error: LlmException) {
        error
    }
    assertEquals(failure, error.failure)
    return error
}

internal val openAiFrames = listOf(
    """{"type":"response.created","response":{"usage":null}}""",
    """{"type":"response.output_text.delta","delta":"Xin "}""",
    """{"type":"response.output_text.delta","delta":"chào"}""",
    """{"type":"response.output_text.done","text":"Xin chào"}""",
    """{"type":"response.completed","response":{"status":"completed","usage":{"input_tokens":12,"output_tokens":5}}}""",
)

internal val geminiFrames = listOf(
    """{"usageMetadata":{"promptTokenCount":12}}""",
    """{"candidates":[{"index":0,"content":{"parts":[{"text":"private thought","thought":true},{"text":"Xin "}]}}],"usageMetadata":{"promptTokenCount":12,"candidatesTokenCount":1}}""",
    """{"candidates":[{"index":0,"content":{"parts":[{"text":"chào"}]},"finishReason":"STOP"}]}""",
    """{"usageMetadata":{"promptTokenCount":12,"candidatesTokenCount":5,"thoughtsTokenCount":2}}""",
)
