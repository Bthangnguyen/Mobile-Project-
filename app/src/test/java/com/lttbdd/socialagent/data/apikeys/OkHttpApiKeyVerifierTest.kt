package com.lttbdd.socialagent.data.apikeys

import com.lttbdd.socialagent.core.model.LlmProvider
import java.io.IOException
import java.io.InterruptedIOException
import java.lang.reflect.Proxy
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Protocol
import okhttp3.MediaType
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okio.Buffer
import okio.ForwardingSource
import okio.Timeout
import okio.buffer
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class OkHttpApiKeyVerifierTest {
    private val factory = FakeCalls()
    private val verifier = OkHttpApiKeyVerifier(factory)

    @Test
    fun `uses separate official HTTPS URLs and auth headers with no key in query or body`() = runTest {
        for (provider in listOf(LlmProvider.OPENAI, LlmProvider.GEMINI)) {
            val key = fakeKey()
            factory.body = if (provider == LlmProvider.OPENAI) """{"object":"list","data":[]}""" else """{"models":[]}"""
            assertEquals(VerificationStatus.VALID, verifier.verify(provider, key).status)
            val request = factory.requests.last()
            assertEquals("GET", request.method)
            assertEquals("https", request.url.scheme)
            assertNull(request.body)
            assertFalse(request.url.toString().contains(key))
            if (provider == LlmProvider.OPENAI) {
                assertEquals("https://api.openai.com/v1/models", request.url.toString())
                assertEquals("Bearer $key", request.header("Authorization"))
                assertNull(request.header("x-goog-api-key"))
            } else {
                assertEquals("https://generativelanguage.googleapis.com/v1beta/models?pageSize=1", request.url.toString())
                assertEquals(key, request.header("x-goog-api-key"))
                assertNull(request.header("Authorization"))
            }
        }
    }

    @Test
    fun `401 is invalid while generic 403 429 and 5xx remain unverified for both providers`() = runTest {
        for (provider in listOf(LlmProvider.OPENAI, LlmProvider.GEMINI)) {
            for ((code, status, reason) in listOf(
                Triple(401, VerificationStatus.INVALID, VerificationReason.AUTHENTICATION),
                Triple(403, VerificationStatus.UNVERIFIED, VerificationReason.PERMISSION),
                Triple(429, VerificationStatus.UNVERIFIED, VerificationReason.RATE_LIMIT),
                Triple(500, VerificationStatus.UNVERIFIED, VerificationReason.SERVICE),
                Triple(503, VerificationStatus.UNVERIFIED, VerificationReason.SERVICE),
                Triple(302, VerificationStatus.UNVERIFIED, VerificationReason.RESPONSE),
            )) {
                factory.code = code
                factory.body = """{"error":{"message":"${fakeKey()}"}}"""
                assertEquals(KeyVerification(status, reason), verifier.verify(provider, fakeKey()))
            }
        }
    }

    @Test
    fun `only structured provider authentication evidence maps 400 or 403 to invalid`() = runTest {
        for (code in listOf(400, 403)) {
            factory.code = code
            factory.body = """{"error":{"code":"invalid_api_key"}}"""
            assertEquals(VerificationStatus.INVALID, verifier.verify(LlmProvider.OPENAI, fakeKey()).status)
            assertEquals(VerificationStatus.UNVERIFIED, verifier.verify(LlmProvider.GEMINI, fakeKey()).status)
            factory.body = """{"error":{"details":[{"@type":"type.googleapis.com/google.rpc.ErrorInfo","reason":"API_KEY_INVALID","domain":"googleapis.com"}]}}"""
            assertEquals(VerificationStatus.INVALID, verifier.verify(LlmProvider.GEMINI, fakeKey()).status)
            assertEquals(VerificationStatus.UNVERIFIED, verifier.verify(LlmProvider.OPENAI, fakeKey()).status)
        }
    }

    @Test
    fun `restrictions and misleading error messages never prove invalidity`() = runTest {
        factory.code = 403
        for (body in listOf(
            """{"error":{"message":"API_KEY_INVALID"}}""",
            """{"error":{"details":[{"@type":"type.googleapis.com/google.rpc.ErrorInfo","domain":"googleapis.com","reason":"API_KEY_ANDROID_APP_BLOCKED"}]}}""",
            """{"error":{"details":[{"reason":"API_KEY_INVALID"}]}}""",
            "not json",
        )) {
            factory.body = body
            assertEquals(VerificationStatus.UNVERIFIED, verifier.verify(LlmProvider.GEMINI, fakeKey()).status)
        }
    }

    @Test
    fun `network timeout malformed response and echoed secrets are sanitized`() = runTest {
        val secret = fakeKey()
        for ((error, reason) in listOf(IOException(secret) to VerificationReason.NETWORK, InterruptedIOException(secret) to VerificationReason.TIMEOUT)) {
            factory.failure = error
            val result = verifier.verify(LlmProvider.OPENAI, secret)
            assertEquals(KeyVerification(VerificationStatus.UNVERIFIED, reason), result)
            assertFalse(result.toString().contains(secret))
        }
        factory.failure = null
        for (body in listOf(secret, "[]", """{"error":{"message":"$secret"}}""", "{}")) {
            factory.body = body
            val result = verifier.verify(LlmProvider.OPENAI, secret)
            assertEquals(VerificationStatus.UNVERIFIED, result.status)
            assertFalse(result.toString().contains(secret))
        }
    }

    @Test
    fun `blank and header-injection input never makes an HTTP call`() = runTest {
        for (key in listOf("", " ", "a\nHeader: b", "a b", "a".repeat(4097))) {
            assertEquals(VerificationReason.INPUT, verifier.verify(LlmProvider.OPENAI, key).reason)
        }
        assertTrue(factory.requests.isEmpty())
    }

    @Test
    fun `cancellation cancels pending HTTP call and is not returned as invalid key`() = runTest {
        factory.pending = true
        val deferred = async { verifier.verify(LlmProvider.GEMINI, fakeKey()) }
        runCurrent()
        assertEquals(1, factory.requests.size)
        deferred.cancel()
        deferred.join()
        assertTrue(deferred.isCancelled)
        assertTrue(factory.cancelled)
    }

    @Test
    fun `client disables redirects retries caching cookies and logging interceptors`() {
        val http = OkHttpApiKeyVerifier.newHttpClient()
        assertFalse(http.followRedirects)
        assertFalse(http.followSslRedirects)
        assertFalse(http.retryOnConnectionFailure)
        assertNull(http.cache)
        assertTrue(http.interceptors.isEmpty())
        assertTrue(http.networkInterceptors.isEmpty())
        assertEquals(okhttp3.CookieJar.NO_COOKIES, http.cookieJar)
        assertTrue(http.callTimeoutMillis in 1..20_000)
    }

    @Test
    fun `all HTTP outcomes close response bodies and body read failures are sanitized`() = runTest {
        for (code in listOf(200, 400, 401, 403, 429, 503)) {
            factory.code = code
            factory.body = """{"object":"list","data":[]}"""
            verifier.verify(LlmProvider.OPENAI, fakeKey())
            assertTrue(factory.responseClosed)
        }
        val secret = fakeKey()
        factory.code = 200
        factory.readError = IOException(secret)
        val result = verifier.verify(LlmProvider.OPENAI, secret)
        assertEquals(KeyVerification(VerificationStatus.UNVERIFIED, VerificationReason.NETWORK), result)
        assertFalse(result.toString().contains(secret))
        assertTrue(factory.responseClosed)
    }

    @Test
    fun `temporary HTTP errors stay unverified even when bodies claim invalid key`() = runTest {
        factory.body = """{"error":{"code":"invalid_api_key","details":[{"@type":"type.googleapis.com/google.rpc.ErrorInfo","reason":"API_KEY_INVALID","domain":"googleapis.com"}]}}"""
        for (provider in listOf(LlmProvider.OPENAI, LlmProvider.GEMINI)) {
            for (code in listOf(429, 500, 503)) {
                factory.code = code
                assertEquals(VerificationStatus.UNVERIFIED, verifier.verify(provider, fakeKey()).status)
            }
        }
    }

    private class FakeCalls : Call.Factory {
        var code = 200
        var body = "{}"
        var failure: IOException? = null
        var pending = false
        var cancelled = false
        var responseClosed = false
        var readError: IOException? = null
        val requests = mutableListOf<Request>()

        override fun newCall(request: Request): Call {
            requests += request
            responseClosed = false
            return Proxy.newProxyInstance(Call::class.java.classLoader, arrayOf(Call::class.java)) { proxy, method, args ->
                when (method.name) {
                    "enqueue" -> {
                        val callback = args!![0] as Callback
                        if (!pending) {
                            val error = failure
                            if (error != null) callback.onFailure(proxy as Call, error) else {
                                val source = object : ForwardingSource(Buffer().writeUtf8(body)) {
                                    override fun read(sink: Buffer, byteCount: Long): Long {
                                        readError?.let { throw it }
                                        return super.read(sink, byteCount)
                                    }
                                    override fun close() { responseClosed = true; super.close() }
                                }.buffer()
                                val responseBody = object : ResponseBody() {
                                    override fun contentType(): MediaType? = null
                                    override fun contentLength(): Long = -1
                                    override fun source() = source
                                }
                                callback.onResponse(proxy as Call, Response.Builder().request(request)
                                    .protocol(Protocol.HTTP_1_1).code(code).message("fixture").body(responseBody).build())
                            }
                        }
                        null
                    }
                    "request" -> request
                    "cancel" -> { cancelled = true; null }
                    "isExecuted" -> true
                    "isCanceled" -> cancelled
                    "timeout" -> Timeout.NONE
                    "toString" -> "FakeCall"
                    else -> error("Unexpected Call method")
                }
            } as Call
        }
    }
}
