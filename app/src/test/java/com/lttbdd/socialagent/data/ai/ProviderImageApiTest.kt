package com.lttbdd.socialagent.data.ai

import com.lttbdd.socialagent.core.model.ChannelType
import com.lttbdd.socialagent.core.model.Draft
import com.lttbdd.socialagent.core.model.LlmProvider
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonObject
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
        val configuration = LlmModelConfiguration(LlmProvider.OPENAI, "gpt-image-1")

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

    private fun request(channel: ChannelType) =
        DraftImageRequest(Draft("draft", "topic", channel, "Bài viết"), "Tiêu đề", ChannelImage.forChannel(channel))

    private class FakeHttp(payload: String) {
        lateinit var request: Request
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            request = chain.request()
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                .code(200).message("fixture").body(payload.toResponseBody()).build()
        }.build()

        fun body() = parseEvent(Buffer().also { requireNotNull(request.body).writeTo(it) }.readUtf8())
    }
}
