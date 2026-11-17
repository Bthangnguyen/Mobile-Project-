package com.lttbdd.socialagent.data.ai

import java.io.IOException
import java.lang.reflect.Proxy
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import okio.Pipe
import okio.Timeout
import okio.buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class OkHttpSseTransportTest {
    private val request = Request.Builder().url("https://api.openai.com/v1/responses").build()

    @Test
    fun `delivers frames in order and cancels source after normal close`() = runTest {
        val factory = FakeEventSourceFactory()
        val result = async(start = CoroutineStart.UNDISPATCHED) { OkHttpSseTransport(factory).events(request).toList() }
        factory.started.await()
        factory.listener.onEvent(factory, null, null, "first")
        factory.listener.onEvent(factory, null, null, "second")
        factory.listener.onClosed(factory)
        assertEquals(listOf("first", "second"), result.await())
        assertTrue(factory.cancelled)
        assertEquals(1, factory.calls)
    }

    @Test
    fun `cancelling collection cancels pending request before any response arrives`() = runTest {
        val factory = FakeEventSourceFactory()
        val result = async(start = CoroutineStart.UNDISPATCHED) { OkHttpSseTransport(factory).events(request).toList() }
        factory.started.await()
        result.cancel()
        result.join()
        assertTrue(factory.cancelled)
        assertEquals(1, factory.calls)
    }

    @Test
    fun `deadline cancels pending request even before response headers`() = runTest {
        val factory = FakeEventSourceFactory()
        val result = async {
            expectFailure(LlmFailure.TIMEOUT) {
                OkHttpSseTransport(factory, streamTimeoutMillis = 1_000).events(request).toList()
            }
        }
        runCurrent()
        advanceTimeBy(1_001)
        runCurrent()
        val error = result.await()
        assertNull(error.cause)
        assertTrue(factory.cancelled)
        assertEquals(1, factory.calls)
    }

    @Test
    fun `caller timeout stays cancellation rather than a transport timeout error`() = runTest {
        val factory = FakeEventSourceFactory()
        val result = async {
            withTimeoutOrNull(100) {
                OkHttpSseTransport(factory, streamTimeoutMillis = 1_000).events(request).toList()
            }
        }
        runCurrent()
        advanceTimeBy(101)
        runCurrent()
        assertNull(result.await())
        assertTrue(factory.cancelled)
    }

    @Test
    fun `deadline cancels real SSE request after OkHttp disables full call timeout`() = runTest {
        val pipe = Pipe(4_096)
        val sink = pipe.sink.buffer()
        val input = pipe.source.buffer()
        sink.writeUtf8(": keepalive\n\n").flush()
        val callTimeoutDisabled = CountDownLatch(1)
        val cancelled = AtomicBoolean(false)
        val timeout = object : Timeout() {
            override fun cancel() {
                callTimeoutDisabled.countDown()
                super.cancel()
            }
        }
        var callback: Callback? = null
        // Proxy chỉ giả HTTP Call; EventSource, parser SSE và bridge coroutine đều là bản thật.
        val call = Proxy.newProxyInstance(Call::class.java.classLoader, arrayOf(Call::class.java)) { _, method, args ->
            when (method.name) {
                "enqueue" -> { callback = args!![0] as Callback; null }
                "request" -> request
                "timeout" -> timeout
                "cancel" -> { cancelled.set(true); sink.close(); null }
                "isExecuted" -> true
                "isCanceled" -> cancelled.get()
                "toString" -> "FakeCall"
                else -> error("Unexpected Call method: ${method.name}")
            }
        } as Call
        val factory = Call.Factory { call }
        val response = Response.Builder()
            .request(request).protocol(Protocol.HTTP_1_1).code(200).message("fixture")
            .body(object : ResponseBody() {
                override fun contentType() = "text/event-stream".toMediaType()
                override fun contentLength() = -1L
                override fun source() = input
            })
            .build()
        val executor = Executors.newSingleThreadExecutor()
        try {
            val result = async {
                expectFailure(LlmFailure.TIMEOUT) {
                    OkHttpSseTransport(EventSources.createFactory(factory), streamTimeoutMillis = 1_000)
                        .events(request).toList()
                }
            }
            runCurrent()
            val reader = executor.submit { requireNotNull(callback).onResponse(call, response) }
            assertTrue(callTimeoutDisabled.await(5, TimeUnit.SECONDS))
            assertFalse(cancelled.get())
            advanceTimeBy(1_001)
            runCurrent()
            result.await()
            assertTrue(cancelled.get())
            reader.get(5, TimeUnit.SECONDS)
        } finally {
            sink.close()
            executor.shutdownNow()
            response.close()
        }
    }

    @Test
    fun `maps HTTP errors and never exposes provider response body`() = runTest {
        for ((status, failure) in listOf(
            401 to LlmFailure.AUTHENTICATION,
            403 to LlmFailure.PERMISSION,
            429 to LlmFailure.RATE_LIMIT,
            500 to LlmFailure.SERVER,
            503 to LlmFailure.SERVER,
            302 to LlmFailure.HTTP,
        )) {
            val secret = syntheticKey()
            val response = response(status, secret)
            val factory = FakeEventSourceFactory { listener -> listener.onFailure(this, IOException(secret), response) }
            val error = expectFailure(failure) { OkHttpSseTransport(factory).events(request).toList() }
            assertEquals(status, error.httpStatus)
            assertFalse(error.toString().contains(secret))
            assertNull(error.cause)
            assertTrue(factory.cancelled)
        }
    }

    @Test
    fun `distinguishes network failure and successful HTTP with invalid content type`() = runTest {
        for ((response, expected) in listOf(null to LlmFailure.NETWORK, response(200, "") to LlmFailure.INVALID_RESPONSE)) {
            val factory = FakeEventSourceFactory { listener -> listener.onFailure(this, IllegalStateException(syntheticKey()), response) }
            val error = expectFailure(expected) { OkHttpSseTransport(factory).events(request).toList() }
            assertNull(error.cause)
        }
    }

    private fun response(code: Int, body: String) = Response.Builder()
        .request(request).protocol(Protocol.HTTP_1_1).code(code).message("fixture")
        .body(body.toResponseBody()).build()

    private class FakeEventSourceFactory(
        val callback: (FakeEventSourceFactory.(EventSourceListener) -> Unit)? = null,
    ) : EventSource.Factory, EventSource {
        val started = CompletableDeferred<Unit>()
        lateinit var listener: EventSourceListener
        private lateinit var original: Request
        var cancelled = false
        var calls = 0

        override fun newEventSource(request: Request, listener: EventSourceListener): EventSource {
            calls++
            original = request
            this.listener = listener
            started.complete(Unit)
            callback?.invoke(this, listener)
            return this
        }

        override fun request(): Request = original
        override fun cancel() { cancelled = true }
    }
}
