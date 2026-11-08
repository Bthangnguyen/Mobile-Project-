package com.lttbdd.socialagent.data.ai

import java.io.IOException
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Request
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener

fun interface SseTransport {
    fun events(request: Request): Flow<String>
}

/** OkHttp ghép frame SSE nhiều dòng; awaitClose hủy cả kết nối khi collector kết thúc. */
class OkHttpSseTransport(
    private val factory: EventSource.Factory,
    private val streamTimeoutMillis: Long = 120_000,
) : SseTransport {
    init {
        require(streamTimeoutMillis > 0)
    }

    override fun events(request: Request): Flow<String> = flow {
        // OkHttp SSE tắt callTimeout sau handshake; deadline này bao trọn cả stream.
        // withTimeoutOrNull chỉ bắt timeout của scope này, giữ cancellation từ bên gọi.
        val completed = withTimeoutOrNull(streamTimeoutMillis) {
            emitAll(openEvents(request))
            true
        }
        if (completed == null) throw LlmException(LlmFailure.TIMEOUT)
    }

    private fun openEvents(request: Request): Flow<String> = callbackFlow {
        val source = factory.newEventSource(
            request,
            object : EventSourceListener() {
                override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
                    // Backpressure có giới hạn; không bỏ mất delta khi UI collect chậm.
                    if (trySendBlocking(data).isFailure) eventSource.cancel()
                }

                override fun onClosed(eventSource: EventSource) {
                    close()
                }

                override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
                    close(
                        when {
                            response == null -> LlmException(LlmFailure.NETWORK)
                            !response.isSuccessful -> httpFailure(response.code)
                            t is IOException -> LlmException(LlmFailure.NETWORK)
                            else -> LlmException(LlmFailure.INVALID_RESPONSE)
                        },
                    )
                }
            },
        )
        awaitClose { source.cancel() }
    }
}
