package com.lttbdd.socialagent.data.ai

import com.lttbdd.socialagent.core.model.ChatChunk
import com.lttbdd.socialagent.core.model.ChatRequest
import com.lttbdd.socialagent.core.model.LlmProvider
import com.lttbdd.socialagent.core.model.TokenUsage
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

interface ProviderAdapter {
    val provider: LlmProvider

    fun streamChat(request: ChatRequest, configuration: LlmModelConfiguration, apiKey: String): Flow<ChatChunk>
}

internal val providerJson = Json { ignoreUnknownKeys = true }

internal fun parseEvent(data: String): JsonObject = try {
    providerJson.parseToJsonElement(data) as? JsonObject ?: throw LlmException(LlmFailure.INVALID_RESPONSE)
} catch (_: SerializationException) {
    throw LlmException(LlmFailure.INVALID_RESPONSE)
} catch (_: IllegalArgumentException) {
    throw LlmException(LlmFailure.INVALID_RESPONSE)
}

internal fun JsonElement?.textOrNull(): String? =
    (this as? JsonPrimitive)?.takeIf { it.isString }?.content

internal fun JsonObject.optionalObject(name: String): JsonObject? = when (val element = this[name]) {
    null, JsonNull -> null
    is JsonObject -> element
    else -> throw LlmException(LlmFailure.INVALID_RESPONSE)
}

internal fun JsonObject.tokenCount(name: String): Int {
    val primitive = this[name] as? JsonPrimitive
    val count = primitive?.takeUnless { it.isString }?.intOrNull
    return count?.takeIf { it >= 0 } ?: throw LlmException(LlmFailure.INVALID_RESPONSE)
}

/** Hai API không trả USD. NaN nghĩa là chưa biết, tuyệt đối không hiểu là miễn phí. */
internal fun tokenUsage(input: Int, output: Int) = TokenUsage(input, output, Double.NaN)
