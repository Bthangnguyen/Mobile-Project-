package com.lttbdd.socialagent.data.ai

import com.lttbdd.socialagent.core.model.LlmProvider
import com.lttbdd.socialagent.data.apikeys.readBytesBounded
import java.io.IOException
import java.util.Base64
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

/** Gọi API sinh ảnh OpenAI hoặc Gemini và đọc phản hồi có giới hạn kích thước. */
internal class ProviderImageApi(private val calls: Call.Factory) : ImageApi {
    override suspend fun generate(
        configuration: LlmModelConfiguration,
        key: String,
        request: ChannelImageRequest,
    ): ByteArray? {
        if (key.any { it.code !in 33..126 }) return null
        val httpRequest = when (configuration.provider) {
            LlmProvider.OPENAI -> {
                if (!LlmModelCatalog.supportsOpenAiImage(configuration.model)) return null
                openAi(configuration.model, key, request)
            }
            LlmProvider.GEMINI -> gemini(configuration.model, key, request)
            else -> return null
        }
        val payload = load(httpRequest) ?: return null
        val encoded = when (configuration.provider) {
            LlmProvider.OPENAI -> payload.objects("data").firstOrNull()?.get("b64_json").textOrNull()
            LlmProvider.GEMINI -> payload.objects("candidates")
                .flatMap { it.optionalObject("content")?.objects("parts").orEmpty() }
                .filterNot { (it["thought"] as? JsonPrimitive)?.booleanOrNull == true }
                .firstNotNullOfOrNull { it.optionalObject("inlineData")?.get("data").textOrNull() }
        }
        return encoded?.let {
            withContext(Dispatchers.Default) {
                try {
                    Base64.getDecoder().decode(it)
                } catch (_: IllegalArgumentException) {
                    null
                }
            }
        }
    }

    private fun openAi(model: String, key: String, request: ChannelImageRequest): Request =
        Request.Builder()
            .url("https://api.openai.com/v1/images/generations")
            .header("Authorization", "Bearer $key")
            .post(buildJsonObject {
                put("model", model)
                put("prompt", request.prompt())
                put("size", request.layout.openAiSize)
            }.toBody())
            .build()

    private fun gemini(model: String, key: String, request: ChannelImageRequest): Request =
        Request.Builder()
            .url("https://generativelanguage.googleapis.com/v1beta/models/".toHttpUrl().newBuilder()
                .addPathSegment("${model.removePrefix("models/")}:generateContent")
                .build())
            .header("x-goog-api-key", key)
            .post(buildJsonObject {
                put("contents", buildJsonArray {
                    add(buildJsonObject {
                        put("parts", buildJsonArray { add(buildJsonObject { put("text", request.prompt()) }) })
                    })
                })
                put("generationConfig", buildJsonObject {
                    put("responseModalities", buildJsonArray { add(JsonPrimitive("IMAGE")) })
                    put("imageConfig", buildJsonObject { put("aspectRatio", request.layout.geminiRatio) })
                })
            }.toBody())
            .build()

    private suspend fun load(request: Request): JsonObject? = suspendCancellableCoroutine { continuation ->
        val call = calls.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                continuation.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                val payload = try {
                    response.use {
                        if (!it.isSuccessful) null
                        else it.body.byteStream().readBytesBounded(16 * 1024 * 1024)
                            .toString(Charsets.UTF_8).let(::parseEvent)
                    }
                } catch (error: Exception) {
                    continuation.resumeWithException(error)
                    return
                }
                continuation.resume(payload)
            }
        })
    }

    private fun JsonObject.objects(key: String): List<JsonObject> =
        (get(key) as? JsonArray).orEmpty().filterIsInstance<JsonObject>()

    private fun JsonObject.toBody() = toString().toRequestBody("application/json".toMediaType())
}
