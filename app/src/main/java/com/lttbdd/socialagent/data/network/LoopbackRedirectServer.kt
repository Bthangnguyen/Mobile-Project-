package com.lttbdd.socialagent.data.network

import java.io.Closeable
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException

/** Nguồn nhận redirect OAuth; tách interface để ViewModel test được không cần mở socket thật. */
interface RedirectListener : Closeable {
    val redirectUri: String

    /** Chặn tới khi có URL redirect đầy đủ hoặc hết thời gian ([timeoutMillis]); null nếu hết hạn/đã đóng. */
    fun awaitRedirect(timeoutMillis: Int): String?
}

/**
 * Máy chủ HTTP tối giản nghe trên loopback (127.0.0.1) để nhận redirect OAuth từ trình duyệt hệ thống
 * theo RFC 8252 Section 7.3. Chỉ nhận đúng một yêu cầu GET tới [CALLBACK_PATH], rồi đóng.
 *
 * Chỉ bind vào 127.0.0.1 (không bind 0.0.0.0) nên thiết bị khác trong mạng không gọi tới được.
 * Class này không log URL hay tham số vì chúng chứa authorization code và state.
 */
class LoopbackRedirectServer private constructor(
    private val serverSocket: ServerSocket,
) : RedirectListener {

    /** Redirect URI để gửi cho nhà cung cấp OAuth, ví dụ `http://127.0.0.1:54321/callback`. */
    override val redirectUri: String = "http://127.0.0.1:${serverSocket.localPort}$CALLBACK_PATH"

    /**
     * Chặn luồng gọi (hãy gọi trên Dispatchers.IO) tới khi nhận được redirect hợp lệ hoặc hết [timeoutMillis].
     * Trả về URL đầy đủ của redirect, hoặc `null` nếu hết thời gian/đã đóng.
     * Yêu cầu tới đường dẫn khác (ví dụ /favicon.ico) được trả 404 và bị bỏ qua.
     */
    override fun awaitRedirect(timeoutMillis: Int): String? {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (true) {
            val remaining = (deadline - System.currentTimeMillis()).toInt()
            if (remaining <= 0 || serverSocket.isClosed) return null
            try {
                serverSocket.soTimeout = remaining
                serverSocket.accept().use { socket ->
                    socket.soTimeout = READ_TIMEOUT_MILLIS
                    val requestLine = socket.getInputStream().bufferedReader(Charsets.ISO_8859_1).readLine()
                    val target = parseRequestTarget(requestLine)
                    val matched = target != null && isCallbackTarget(target)
                    val out = socket.getOutputStream()
                    out.write((if (matched) RESPONSE_OK else RESPONSE_NOT_FOUND).toByteArray(Charsets.UTF_8))
                    out.flush()
                    if (matched) return "http://127.0.0.1:${serverSocket.localPort}$target"
                }
            } catch (_: SocketTimeoutException) {
                return null
            } catch (_: java.io.IOException) {
                if (serverSocket.isClosed) return null
                // Kết nối đọc lỗi/ngắt giữa chừng: bỏ qua và chờ yêu cầu kế tiếp.
            }
        }
    }

    override fun close() {
        try {
            serverSocket.close()
        } catch (_: java.io.IOException) {
            // Đóng thất bại không ảnh hưởng gì thêm.
        }
    }

    companion object {
        const val CALLBACK_PATH = "/callback"

        private const val READ_TIMEOUT_MILLIS = 5_000

        private val RESPONSE_BODY = "<!doctype html><html><head><meta charset=\"utf-8\">" +
            "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\"><title>Social Agent</title></head>" +
            "<body><p>Social Agent: đăng nhập xong. Bạn có thể đóng trang này và quay lại ứng dụng.</p>" +
            "</body></html>"

        private val RESPONSE_OK = "HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\n" +
            "Content-Length: ${RESPONSE_BODY.toByteArray(Charsets.UTF_8).size}\r\nConnection: close\r\n\r\n$RESPONSE_BODY"

        private const val RESPONSE_NOT_FOUND =
            "HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"

        /** Mở cổng ngẫu nhiên trên 127.0.0.1. */
        fun open(): RedirectListener =
            LoopbackRedirectServer(ServerSocket(0, 1, InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))))

        /** Lấy request-target từ dòng đầu `GET /callback?code=... HTTP/1.1`; null nếu không phải GET hợp lệ. */
        internal fun parseRequestTarget(requestLine: String?): String? {
            if (requestLine.isNullOrBlank()) return null
            val parts = requestLine.split(' ')
            if (parts.size < 2 || parts[0] != "GET") return null
            return parts[1].takeIf { it.startsWith("/") }
        }

        internal fun isCallbackTarget(target: String): Boolean =
            target == CALLBACK_PATH || target.startsWith("$CALLBACK_PATH?")
    }
}
