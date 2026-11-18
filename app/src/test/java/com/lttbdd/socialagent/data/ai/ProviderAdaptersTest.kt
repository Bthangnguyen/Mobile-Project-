package com.lttbdd.socialagent.data.ai

import com.lttbdd.socialagent.core.model.ChatMessage
import com.lttbdd.socialagent.core.model.ChatRole
import com.lttbdd.socialagent.core.model.LlmProvider
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderAdaptersTest {
    @Test
    fun `OpenAI sends Responses API request and streams deltas without duplicating final text`() = runTest {
        val transport = FakeSseTransport(openAiFrames)
        val key = syntheticKey()
        val chunks = OpenAiClient(transport).streamChat(
            chatRequest(), LlmModelConfiguration(LlmProvider.OPENAI, "configured-model"), key,
        ).toList()

        assertEquals(listOf("Xin ", "chào", ""), chunks.map { it.delta })
        assertTrue(chunks.dropLast(1).all { it.usage == null })
        assertEquals(12, chunks.last().usage!!.inputTokens)
        assertEquals(5, chunks.last().usage!!.outputTokens)
        assertTrue(chunks.last().usage!!.costUsd.isNaN())
        val request = transport.requests.single()
        assertEquals("https://api.openai.com/v1/responses", request.url.toString())
        assertEquals("POST", request.method)
        assertEquals("Bearer $key", request.header("Authorization"))
        val body = request.jsonBody()
        assertEquals("configured-model", body["model"]!!.jsonPrimitive.content)
        assertEquals("true", body["stream"]!!.jsonPrimitive.content)
        assertEquals("false", body["store"]!!.jsonPrimitive.content)
        assertEquals("system", (body["input"] as JsonArray)[0].jsonObject["role"]!!.jsonPrimitive.content)
        assertEquals("Xin chào", (body["input"] as JsonArray)[1].jsonObject["content"]!!.jsonPrimitive.content)
        assertFalse(request.url.toString().contains(key))
        assertFalse(body.toString().contains(key))
        assertTrue(transport.closed)
    }

    @Test
    fun `Gemini maps system and assistant roles and emits only final cumulative usage`() = runTest {
        val transport = FakeSseTransport(geminiFrames)
        val key = syntheticKey()
        val request = chatRequest().copy(messages = chatRequest().messages + ChatMessage(ChatRole.ASSISTANT, "Earlier"))
        val chunks = GeminiClient(transport).streamChat(
            request, LlmModelConfiguration(LlmProvider.GEMINI, "models/configured-model"), key,
        ).toList()

        assertEquals(listOf("Xin ", "chào", ""), chunks.map { it.delta })
        assertEquals(1, chunks.count { it.usage != null })
        assertEquals(12, chunks.last().usage!!.inputTokens)
        assertEquals(7, chunks.last().usage!!.outputTokens)
        assertTrue(chunks.last().usage!!.costUsd.isNaN())
        val http = transport.requests.single()
        assertEquals("generativelanguage.googleapis.com", http.url.host)
        assertEquals("/v1beta/models/configured-model:streamGenerateContent", http.url.encodedPath)
        assertEquals("sse", http.url.queryParameter("alt"))
        assertNull(http.url.queryParameter("key"))
        assertEquals(key, http.header("x-goog-api-key"))
        val body = http.jsonBody()
        assertEquals("Be helpful.", (body["systemInstruction"]!!.jsonObject["parts"] as JsonArray)[0].jsonObject["text"]!!.jsonPrimitive.content)
        assertEquals(listOf("user", "model"), (body["contents"] as JsonArray).map { it.jsonObject["role"]!!.jsonPrimitive.content })
        assertFalse(body.toString().contains(key))
    }

    @Test
    fun `temperature can be omitted for models that do not support it`() = runTest {
        for (provider in listOf(LlmProvider.OPENAI, LlmProvider.GEMINI)) {
            val transport = FakeSseTransport(if (provider == LlmProvider.OPENAI) openAiFrames else geminiFrames)
            val adapter = adapter(provider, transport)
            adapter.streamChat(chatRequest(), LlmModelConfiguration(provider, "model", false), syntheticKey()).toList()
            val body = transport.requests.single().jsonBody()
            val parameters = if (provider == LlmProvider.OPENAI) body else body["generationConfig"]!!.jsonObject
            assertFalse(parameters.containsKey("temperature"))
        }
    }

    @Test
    fun `both adapters allow absent usage without inventing tokens`() = runTest {
        val fixtures = mapOf(
            LlmProvider.OPENAI to listOf(openAiFrames[1], """{"type":"response.completed","response":{"status":"completed","usage":null}}"""),
            LlmProvider.GEMINI to listOf("""{"candidates":[{"content":{"parts":[{"text":"Hi"}]},"finishReason":"STOP"}]}"""),
        )
        for ((provider, frames) in fixtures) {
            val chunks = adapter(provider, FakeSseTransport(frames)).streamChat(
                chatRequest(), LlmModelConfiguration(provider, "model"), syntheticKey(),
            ).toList()
            assertTrue(chunks.isNotEmpty())
            assertTrue(chunks.all { it.usage == null })
        }
    }

    @Test
    fun `Gemini does not report intermediate usage when final counters are absent`() = runTest {
        val transport = FakeSseTransport(geminiFrames.dropLast(1))
        val chunks = GeminiClient(transport).streamChat(
            chatRequest(), LlmModelConfiguration(LlmProvider.GEMINI, "model"), syntheticKey(),
        ).toList()
        assertEquals("Xin chào", chunks.joinToString("") { it.delta })
        assertTrue(chunks.all { it.usage == null })
    }

    @Test
    fun `Gemini uses counters on the completion frame and isolates candidate zero`() = runTest {
        val frame = """{"candidates":[{"index":1,"content":{"parts":[{"text":"Other"}]}},{"index":0,"content":{"parts":[{"text":"Selected"}]},"finishReason":"STOP"}],"usageMetadata":{"promptTokenCount":3,"candidatesTokenCount":2}}"""
        val chunks = GeminiClient(FakeSseTransport(listOf(frame))).streamChat(
            chatRequest(), LlmModelConfiguration(LlmProvider.GEMINI, "model"), syntheticKey(),
        ).toList()
        assertEquals(listOf("Selected", ""), chunks.map { it.delta })
        assertEquals(3, chunks.last().usage!!.inputTokens)
        assertEquals(2, chunks.last().usage!!.outputTokens)
    }

    @Test
    fun `malformed JSON and wrong JSON root fail safely on either adapter`() = runTest {
        for (provider in listOf(LlmProvider.OPENAI, LlmProvider.GEMINI)) {
            for (frame in listOf("{broken", "[]", "null")) {
                expectFailure(LlmFailure.INVALID_RESPONSE) {
                    adapter(provider, FakeSseTransport(listOf(frame))).streamChat(
                        chatRequest(), LlmModelConfiguration(provider, "model"), syntheticKey(),
                    ).toList()
                }
            }
        }
    }

    @Test
    fun `EOF without completion is rejected including empty streams`() = runTest {
        for (provider in listOf(LlmProvider.OPENAI, LlmProvider.GEMINI)) {
            val frame = if (provider == LlmProvider.OPENAI) openAiFrames[1] else geminiFrames[1]
            for (frames in listOf(emptyList(), listOf(frame))) {
                expectFailure(LlmFailure.INCOMPLETE_RESPONSE) {
                    adapter(provider, FakeSseTransport(frames)).streamChat(
                        chatRequest(), LlmModelConfiguration(provider, "model"), syntheticKey(),
                    ).toList()
                }
            }
        }
    }

    @Test
    fun `completed response with no text is an empty response error`() = runTest {
        val fixtures = mapOf(
            LlmProvider.OPENAI to """{"type":"response.completed","response":{"status":"completed"}}""",
            LlmProvider.GEMINI to """{"candidates":[{"finishReason":"STOP"}]}""",
        )
        for ((provider, frame) in fixtures) {
            expectFailure(LlmFailure.EMPTY_RESPONSE) {
                adapter(provider, FakeSseTransport(listOf(frame))).streamChat(
                    chatRequest(), LlmModelConfiguration(provider, "model"), syntheticKey(),
                ).toList()
            }
        }
    }

    @Test
    fun `OpenAI incomplete refusal and API errors are not treated as success`() = runTest {
        val fixtures = mapOf(
            """{"type":"response.incomplete"}""" to LlmFailure.INCOMPLETE_RESPONSE,
            """{"type":"response.refusal.delta","delta":"No"}""" to LlmFailure.CONTENT_BLOCKED,
            """{"type":"error","code":"rate_limit_exceeded"}""" to LlmFailure.RATE_LIMIT,
            """{"type":"response.failed","response":{"error":{"code":"server_error"}}}""" to LlmFailure.SERVER,
        )
        for ((frame, failure) in fixtures) {
            expectFailure(failure) {
                OpenAiClient(FakeSseTransport(listOf(frame))).streamChat(
                    chatRequest(), LlmModelConfiguration(LlmProvider.OPENAI, "model"), syntheticKey(),
                ).toList()
            }
        }
    }

    @Test
    fun `Gemini handles safety max tokens and streamed API errors`() = runTest {
        val fixtures = mapOf(
            """{"promptFeedback":{"blockReason":"SAFETY"}}""" to LlmFailure.CONTENT_BLOCKED,
            """{"candidates":[{"finishReason":"SAFETY"}]}""" to LlmFailure.CONTENT_BLOCKED,
            """{"candidates":[{"finishReason":"MAX_TOKENS"}]}""" to LlmFailure.INCOMPLETE_RESPONSE,
            """{"error":{"code":403,"message":"not exposed"}}""" to LlmFailure.PERMISSION,
        )
        for ((frame, failure) in fixtures) {
            expectFailure(failure) {
                GeminiClient(FakeSseTransport(listOf(frame))).streamChat(
                    chatRequest(), LlmModelConfiguration(LlmProvider.GEMINI, "model"), syntheticKey(),
                ).toList()
            }
        }
    }

    @Test
    fun `invalid negative and overflowing token counts are rejected`() = runTest {
        for (tokens in listOf("-1", "2147483648", "\"12\"")) {
            val frame = """{"type":"response.completed","response":{"status":"completed","usage":{"input_tokens":$tokens,"output_tokens":1}}}"""
            expectFailure(LlmFailure.INVALID_RESPONSE) {
                OpenAiClient(FakeSseTransport(listOf(openAiFrames[1], frame))).streamChat(
                    chatRequest(), LlmModelConfiguration(LlmProvider.OPENAI, "model"), syntheticKey(),
                ).toList()
            }
        }
        expectFailure(LlmFailure.INVALID_RESPONSE) {
            GeminiClient(FakeSseTransport(listOf("""{"usageMetadata":{"promptTokenCount":1,"candidatesTokenCount":2147483647,"thoughtsTokenCount":1}}"""))).streamChat(
                chatRequest(), LlmModelConfiguration(LlmProvider.GEMINI, "model"), syntheticKey(),
            ).toList()
        }
    }

    @Test
    fun `stopping collection closes upstream and does not consume another response`() = runTest {
        for (provider in listOf(LlmProvider.OPENAI, LlmProvider.GEMINI)) {
            val transport = FakeSseTransport(if (provider == LlmProvider.OPENAI) openAiFrames else geminiFrames)
            val chunks = adapter(provider, transport).streamChat(
                chatRequest(), LlmModelConfiguration(provider, "model"), syntheticKey(),
            ).take(1).toList()
            assertEquals(1, chunks.size)
            assertTrue(transport.closed)
            assertEquals(1, transport.requests.size)
        }
    }

    private fun adapter(provider: LlmProvider, transport: SseTransport): ProviderAdapter =
        if (provider == LlmProvider.OPENAI) OpenAiClient(transport) else GeminiClient(transport)
}
