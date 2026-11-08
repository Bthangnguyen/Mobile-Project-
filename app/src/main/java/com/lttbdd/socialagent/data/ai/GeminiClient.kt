package com.lttbdd.socialagent.data.ai

import com.lttbdd.socialagent.core.model.ChatChunk
import com.lttbdd.socialagent.core.model.ChatRequest
import com.lttbdd.socialagent.core.model.ChatRole
import com.lttbdd.socialagent.core.model.LlmProvider
import com.lttbdd.socialagent.core.model.TokenUsage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** GenerateContent SSE; key nằm trong header, không xuất hiện trên URL. */
class GeminiClient(private val transport: SseTransport) : ProviderAdapter {
    override val provider = LlmProvider.GEMINI

    override fun streamChat(
        request: ChatRequest,
        configuration: LlmModelConfiguration,
        apiKey: String,
    ): Flow<ChatChunk> = flow {
        val body = buildJsonObject {
            val instructions = request.messages.filter { it.role == ChatRole.SYSTEM }
            if (instructions.isNotEmpty()) {
                put("systemInstruction", buildJsonObject {
                    put("parts", buildJsonArray {
                        instructions.forEach { add(buildJsonObject { put("text", it.content) }) }
                    })
                })
            }
            put("contents", buildJsonArray {
                request.messages.filter { it.role != ChatRole.SYSTEM }.forEach { message ->
                    add(buildJsonObject {
                        put("role", if (message.role == ChatRole.ASSISTANT) "model" else "user")
                        put("parts", buildJsonArray { add(buildJsonObject { put("text", message.content) }) })
                    })
                }
            })
            put("generationConfig", buildJsonObject {
                put("candidateCount", 1)
                if (configuration.supportsTemperature) put("temperature", request.temperature)
            })
        }
        val model = configuration.model.removePrefix("models/")
        val url = "https://generativelanguage.googleapis.com/v1beta/models/".toHttpUrl().newBuilder()
            .addPathSegment("$model:streamGenerateContent")
            .addQueryParameter("alt", "sse")
            .build()
        val httpRequest = Request.Builder()
            .url(url)
            .header("x-goog-api-key", apiKey)
            .header("Accept", "text/event-stream")
            .post(body.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()
        var completed = false
        var hasText = false
        var usage: TokenUsage? = null
        transport.events(httpRequest).collect { data ->
            val event = parseEvent(data)
            event.optionalObject("error")?.let { error ->
                val status = (error["code"] as? JsonPrimitive)?.content?.toIntOrNull()
                throw if (status != null) httpFailure(status) else LlmException(LlmFailure.PROVIDER)
            }
            val block = event.optionalObject("promptFeedback")?.get("blockReason").textOrNull()
            if (block != null && block != "BLOCK_REASON_UNSPECIFIED") {
                throw LlmException(LlmFailure.CONTENT_BLOCKED)
            }
            val frameUsage = event.optionalObject("usageMetadata")?.let {
                // Frame đầu có thể chỉ có promptTokenCount, chưa có số token đầu ra.
                if (it.containsKey("promptTokenCount") && it.containsKey("candidatesTokenCount")) {
                    val output = it.tokenCount("candidatesTokenCount").toLong() +
                        if (it.containsKey("thoughtsTokenCount")) it.tokenCount("thoughtsTokenCount") else 0
                    if (output > Int.MAX_VALUE) throw LlmException(LlmFailure.INVALID_RESPONSE)
                    tokenUsage(it.tokenCount("promptTokenCount"), output.toInt())
                } else {
                    null
                }
            }
            val candidates = when (val value = event["candidates"]) {
                null -> emptyList()
                is JsonArray -> value.map { it as? JsonObject ?: throw LlmException(LlmFailure.INVALID_RESPONSE) }
                else -> throw LlmException(LlmFailure.INVALID_RESPONSE)
            }
            candidates.firstOrNull { !it.containsKey("index") || it.tokenCount("index") == 0 }
                ?.let { candidate ->
                    val reason = candidate["finishReason"].textOrNull()
                    if (reason != null && reason != "STOP" && reason != "FINISH_REASON_UNSPECIFIED") {
                        throw LlmException(
                            if (reason == "MAX_TOKENS") LlmFailure.INCOMPLETE_RESPONSE else LlmFailure.CONTENT_BLOCKED,
                        )
                    }
                    val parts = candidate.optionalObject("content")?.get("parts")
                    if (parts != null && parts !is JsonArray) throw LlmException(LlmFailure.INVALID_RESPONSE)
                    parts?.forEach { element ->
                        val part = element as? JsonObject ?: throw LlmException(LlmFailure.INVALID_RESPONSE)
                        if ((part["thought"] as? JsonPrimitive)?.booleanOrNull != true) {
                            val text = part["text"].textOrNull()
                            if (part.containsKey("text") && text == null) {
                                throw LlmException(LlmFailure.INVALID_RESPONSE)
                            }
                            if (text != null && text.isNotEmpty()) {
                                if (completed) throw LlmException(LlmFailure.INVALID_RESPONSE)
                                hasText = true
                                emit(ChatChunk(text))
                            }
                        }
                    }
                    if (reason == "STOP") completed = true
                }
            // Chỉ counters trong frame hoàn tất hoặc đến sau nó mới là usage cuối.
            // Không dùng snapshot giữa stream nếu API bỏ usage ở cuối.
            if (completed && frameUsage != null) usage = frameUsage
        }
        if (!completed) throw LlmException(LlmFailure.INCOMPLETE_RESPONSE)
        if (!hasText) throw LlmException(LlmFailure.EMPTY_RESPONSE)
        usage?.let { emit(ChatChunk(delta = "", usage = it)) }
    }
}
