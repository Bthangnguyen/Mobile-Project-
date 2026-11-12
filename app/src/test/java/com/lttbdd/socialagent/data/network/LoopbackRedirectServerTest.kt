package com.lttbdd.socialagent.data.network

import java.net.HttpURLConnection
import java.net.URL
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LoopbackRedirectServerTest {

    @Test
    fun `parseRequestTarget chi nhan GET hop le`() {
        assertEquals("/callback?code=a", LoopbackRedirectServer.parseRequestTarget("GET /callback?code=a HTTP/1.1"))
        assertNull(LoopbackRedirectServer.parseRequestTarget("POST /callback HTTP/1.1"))
        assertNull(LoopbackRedirectServer.parseRequestTarget("GET http://evil/ HTTP/1.1"))
        assertNull(LoopbackRedirectServer.parseRequestTarget(""))
        assertNull(LoopbackRedirectServer.parseRequestTarget(null))
    }

    @Test
    fun `isCallbackTarget dung ranh gioi duong dan`() {
        assertTrue(LoopbackRedirectServer.isCallbackTarget("/callback"))
        assertTrue(LoopbackRedirectServer.isCallbackTarget("/callback?code=a"))
        assertFalse(LoopbackRedirectServer.isCallbackTarget("/callbackx"))
        assertFalse(LoopbackRedirectServer.isCallbackTarget("/favicon.ico"))
    }

    @Test
    fun `redirectUri la loopback 127_0_0_1 co cong`() {
        LoopbackRedirectServer.open().use { server ->
            assertTrue(Regex("http://127\\.0\\.0\\.1:\\d+/callback").matches(server.redirectUri))
        }
    }

    @Test
    fun `nhan redirect that qua socket va tra 200`() {
        LoopbackRedirectServer.open().use { server ->
            var code = -1
            val client = Thread {
                val conn = URL(server.redirectUri + "?code=ma&state=st").openConnection() as HttpURLConnection
                code = conn.responseCode
                conn.disconnect()
            }
            client.start()

            val url = server.awaitRedirect(5_000)
            client.join(5_000)

            assertEquals(server.redirectUri + "?code=ma&state=st", url)
            assertEquals(200, code)
        }
    }

    @Test
    fun `yeu cau khac duong dan bi 404 va bo qua, van cho redirect that`() {
        LoopbackRedirectServer.open().use { server ->
            val base = server.redirectUri.removeSuffix("/callback")
            val client = Thread {
                (URL("$base/favicon.ico").openConnection() as HttpURLConnection).apply { responseCode; disconnect() }
                (URL(server.redirectUri + "?code=ma").openConnection() as HttpURLConnection).apply { responseCode; disconnect() }
            }
            client.start()

            assertEquals(server.redirectUri + "?code=ma", server.awaitRedirect(5_000))
            client.join(5_000)
        }
    }

    @Test
    fun `het thoi gian thi tra null`() {
        LoopbackRedirectServer.open().use { server ->
            assertNull(server.awaitRedirect(200))
        }
    }

    @Test
    fun `dong cong thi awaitRedirect tra null`() {
        val server = LoopbackRedirectServer.open()
        server.close()
        assertNull(server.awaitRedirect(1_000))
    }
}
