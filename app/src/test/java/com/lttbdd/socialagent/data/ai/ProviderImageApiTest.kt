package com.lttbdd.socialagent.data.ai

import com.lttbdd.socialagent.core.model.ChannelType
import com.lttbdd.socialagent.core.model.LlmProvider
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonObject
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderImageApiTest {
    @Test
    fun openAiSendsLandscapeRequestAndReadsBase64Image() = runTest {
        val http = FakeHttp("""{"data":[{"b64_json":"AQID"}]}""")
        val api = ProviderImageApi(http.client)
        val configuration = LlmModelConfiguration(LlmProvider.OPENAI, "gpt-image-2.5-flare")

        val bytes = api.generate(configuration, "test-key", request(ChannelType.FACEBOOK))

        assertArrayEquals(byteArrayOf(1, 2, 3), bytes)
        assertEquals("https://api.openai.com/v1/images/generations", http.request.url.toString())
        assertEquals("Bearer test-key", http.request.header("Authorization"))
        assertEquals("1536x1024", http.body()["size"].textOrNull())
        assertTrue(http.body()["prompt"].textOrNull().orEmpty().contains("Warm, approachable"))
    }

    @Test
    fun geminiRequestsSquareAndIgnoresInterimImage() = runTest {
        val http = FakeHttp("""
            {"candidates":[{"content":{"parts":[
                {"thought":true,"inlineData":{"data":"AA=="}},
                {"inlineData":{"data":"AQID"}}
            ]}}]}
        """.trimIndent())
        val api = ProviderImageApi(http.client)
        val configuration = LlmModelConfiguration(LlmProvider.GEMINI, "gemini-3.1-flash-lite-image")

        val bytes = api.generate(configuration, "test-key", request(ChannelType.LINKEDIN))

        assertArrayEquals(byteArrayOf(1, 2, 3), bytes)
        assertEquals("generativelanguage.googleapis.com", http.request.url.host)
        assertEquals("gemini-3.1-flash-lite-image:generateContent", http.request.url.pathSegments.last())
        assertNull(http.request.url.query)
        assertEquals("test-key", http.request.header("x-goog-api-key"))
        val imageConfig = http.body()["generationConfig"]?.jsonObject?.get("imageConfig")?.jsonObject
        assertEquals("1:1", imageConfig?.get("aspectRatio").textOrNull())
    }

    @Test
    fun rejectsHttpErrorsAndMalformedBase64() = runTest {
        val request = request(ChannelType.FACEBOOK)
        val config = LlmModelConfiguration(LlmProvider.OPENAI, "gpt-image-2.5-flare")
        val unavailable = FakeHttp("""{"data":[{"b64_json":"AQID"}]}""", status = 503)
        val malformed = FakeHttp("""{"data":[{"b64_json":"%%%"}]}""")

        assertNull(ProviderImageApi(unavailable.client).generate(config, "test-key", request))
        assertNull(ProviderImageApi(malformed.client).generate(config, "test-key", request))
    }

    @Test
    fun skipsNetworkForUnsupportedProviderRetiredModelOrNonAsciiKey() = runTest {
        val api = ProviderImageApi(object : Call.Factory {
            override fun newCall(request: Request): Call = throw AssertionError("Unexpected HTTP call")
        })
        val request = request(ChannelType.FACEBOOK)

        assertNull(api.generate(LlmModelConfiguration(LlmProvider.ANTHROPIC, "model"), "test-key", request))
        assertNull(api.generate(LlmModelConfiguration(LlmProvider.OPENAI, "dall-e-3"), "test-key", request))
        assertNull(api.generate(LlmModelConfiguration(LlmProvider.OPENAI, "gpt-image-2.5-flare"), "khóa", request))
    }

    private fun request(channel: ChannelType) =
        ChannelImageRequest("topic", channel, "A quiet lake at dawn", "Bài viết", "Tiêu đề")

    private class FakeHttp(payload: String, status: Int = 200) {
        lateinit var request: Request
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            request = chain.request()
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                .code(status).message("fixture").body(payload.toResponseBody()).build()
        }.build()

        fun body() = parseEvent(Buffer().also { requireNotNull(request.body).writeTo(it) }.readUtf8())
    }
}
