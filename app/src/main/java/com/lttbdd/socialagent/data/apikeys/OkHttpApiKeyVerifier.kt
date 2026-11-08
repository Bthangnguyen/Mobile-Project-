package com.lttbdd.socialagent.data.apikeys

import com.lttbdd.socialagent.core.model.LlmProvider
import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/** Chỉ GET metadata tại endpoint cố định, không sinh nội dung và không gửi key trong URL. */
class OkHttpApiKeyVerifier(private val calls: Call.Factory) : ApiKeyVerifier {
    override suspend fun verify(provider: LlmProvider, key: String): KeyVerification {
        requireKeyProvider(provider)
        if (!isSafeApiKey(key)) return KeyVerification(VerificationStatus.INVALID, VerificationReason.INPUT)
        return try {
            val builder = Request.Builder().get().header("Accept", "application/json")
            when (provider) {
                LlmProvider.OPENAI -> builder.url("https://api.openai.com/v1/models")
                    .header("Authorization", "Bearer $key")
                LlmProvider.GEMINI -> builder.url("https://generativelanguage.googleapis.com/v1beta/models?pageSize=1")
                    .header("x-goog-api-key", key)
                else -> throw ApiKeyStorageException(KeyStorageFailure.UNSUPPORTED_PROVIDER)
            }
            val call = calls.newCall(builder.build())
            suspendCancellableCoroutine { continuation ->
                continuation.invokeOnCancellation { call.cancel() }
                call.enqueue(object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        continuation.resume(temporary(if (e is InterruptedIOException) VerificationReason.TIMEOUT else VerificationReason.NETWORK))
                    }

                    override fun onResponse(call: Call, response: Response) {
                        val result = try {
                            response.use { classify(provider, it) }
                        } catch (_: InterruptedIOException) {
                            temporary(VerificationReason.TIMEOUT)
                        } catch (_: IOException) {
                            temporary(VerificationReason.NETWORK)
                        } catch (_: Exception) {
                            temporary(VerificationReason.RESPONSE)
                        }
                        continuation.resume(result)
                    }
                })
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            temporary(VerificationReason.NETWORK)
        }
    }

    private fun classify(provider: LlmProvider, response: Response): KeyVerification {
        val code = response.code
        if (code == 401) return KeyVerification(VerificationStatus.INVALID, VerificationReason.AUTHENTICATION)
        if (code == 429) return temporary(VerificationReason.RATE_LIMIT)
        if (code in 500..599) return temporary(VerificationReason.SERVICE)
        if (code !in listOf(200, 400, 403)) return temporary(VerificationReason.RESPONSE)
        val bytes = response.body.byteStream().readBytesBounded(1_048_576)
        val body = try {
            Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)) as? JsonObject
        } catch (_: Exception) {
            null
        }
        if (code == 200) {
            val valid = body != null && !body.containsKey("error") && when (provider) {
                LlmProvider.OPENAI -> body["data"] is JsonArray && body["object"].text() == "list"
                LlmProvider.GEMINI -> body["models"] == null || body["models"] is JsonArray
                else -> false
            }
            return if (valid) KeyVerification(VerificationStatus.VALID, VerificationReason.ACCEPTED)
                else temporary(VerificationReason.RESPONSE)
        }
        val error = body?.get("error") as? JsonObject
        val invalid = when (provider) {
            LlmProvider.OPENAI -> error?.get("code").text() == "invalid_api_key"
            LlmProvider.GEMINI -> (error?.get("details") as? JsonArray)?.any { detail ->
                val info = detail as? JsonObject
                info?.get("@type").text() == "type.googleapis.com/google.rpc.ErrorInfo" &&
                    info?.get("domain").text() == "googleapis.com" && info?.get("reason").text() == "API_KEY_INVALID"
            } == true
            else -> false
        }
        return if (invalid) KeyVerification(VerificationStatus.INVALID, VerificationReason.AUTHENTICATION)
            else temporary(if (code == 403) VerificationReason.PERMISSION else VerificationReason.RESPONSE)
    }

    private fun kotlinx.serialization.json.JsonElement?.text(): String? = (this as? JsonPrimitive)?.contentOrNull

    private fun temporary(reason: VerificationReason) = KeyVerification(VerificationStatus.UNVERIFIED, reason)

    companion object {
        /** Client riêng không log, cache, cookie, redirect hoặc retry tự động. */
        fun newHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .followRedirects(false)
            .followSslRedirects(false)
            .retryOnConnectionFailure(false)
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .callTimeout(20, TimeUnit.SECONDS)
            .build()
    }
}
