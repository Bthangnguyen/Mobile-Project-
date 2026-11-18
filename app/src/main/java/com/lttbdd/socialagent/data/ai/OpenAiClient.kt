package com.lttbdd.socialagent.data.ai

import com.lttbdd.socialagent.core.model.ChatChunk
import com.lttbdd.socialagent.core.model.ChatRequest
import com.lttbdd.socialagent.core.model.LlmProvider
import com.lttbdd.socialagent.core.model.TokenUsage
import java.util.Locale
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.takeWhile
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** Responses API chính thức, chỉ gọi trực tiếp api.openai.com, không lưu response phía API. */
class OpenAiClient(private val transport: SseTransport) : ProviderAdapter {
    override val provider = LlmProvider.OPENAI

    override fun streamChat(
        request: ChatRequest,
        configuration: LlmModelConfiguration,
        apiKey: String,
    ): Flow<ChatChunk> = flow {
        val body = buildJsonObject {
            put("model", configuration.model)
            put("stream", true)
            put("store", false)
            if (configuration.supportsTemperature) put("temperature", request.temperature)
            put("input", buildJsonArray {
                request.messages.forEach { message ->
                    add(buildJsonObject {
                        put("role", message.role.name.lowercase(Locale.ROOT))
                        put("content", message.content)
                    })
                }
            })
        }
        val httpRequest = Request.Builder()
            .url("https://api.openai.com/v1/responses")
            .header("Authorization", "Bearer $apiKey")
            .header("Accept", "text/event-stream")
            .post(body.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()
        var completed = false
        var hasText = false
        var usage: TokenUsage? = null
        transport.events(httpRequest).takeWhile { data ->
            val event = parseEvent(data)
            when (event["type"].textOrNull() ?: throw LlmException(LlmFailure.INVALID_RESPONSE)) {
                "response.output_text.delta" -> {
                    val delta = event["delta"].textOrNull() ?: throw LlmException(LlmFailure.INVALID_RESPONSE)
                    if (delta.isNotEmpty()) {
                        hasText = true
                        emit(ChatChunk(delta))
                    }
                }
                "response.completed" -> {
                    val response = event.optionalObject("response")
                        ?: throw LlmException(LlmFailure.INVALID_RESPONSE)
                    if (response["status"].textOrNull() != "completed") {
                        throw LlmException(LlmFailure.INVALID_RESPONSE)
                    }
                    usage = response.optionalObject("usage")?.let {
                        tokenUsage(it.tokenCount("input_tokens"), it.tokenCount("output_tokens"))
                    }
                    completed = true
                }
                "response.incomplete" -> throw LlmException(LlmFailure.INCOMPLETE_RESPONSE)
                "response.failed", "error" -> {
                    val error = event.optionalObject("response")?.optionalObject("error") ?: event
                    throw LlmException(
                        when (error["code"].textOrNull()) {
                            "invalid_api_key" -> LlmFailure.AUTHENTICATION
                            "rate_limit_exceeded", "insufficient_quota" -> LlmFailure.RATE_LIMIT
                            "server_error" -> LlmFailure.SERVER
                            else -> LlmFailure.PROVIDER
                        },
                    )
                }
                "response.refusal.delta", "response.refusal.done" -> throw LlmException(LlmFailure.CONTENT_BLOCKED)
                // Các event lifecycle/metadata không chứa delta cần bỏ qua để tránh nối text hai lần.
            }
            !completed
        }.collect { }
        if (!completed) throw LlmException(LlmFailure.INCOMPLETE_RESPONSE)
        if (!hasText) throw LlmException(LlmFailure.EMPTY_RESPONSE)
        usage?.let { emit(ChatChunk(delta = "", usage = it)) }
    }
}
