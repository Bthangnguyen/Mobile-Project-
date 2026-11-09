package com.lttbdd.socialagent.feature.channel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Test chọn cookie phiên Facebook cần xóa; không gọi mạng, không dùng cookie thật. */
class FacebookSessionCookiesTest {

    @Test
    fun `xoa cookie phien, giu datr va sb`() {
        val header = "datr=A; sb=B; c_user=1; xs=2; fr=3"

        assertEquals(listOf("c_user", "xs", "fr"), FacebookSessionCookies.namesToExpire(header))
    }

    @Test
    fun `chuoi rong hoac null khong co gi de xoa`() {
        assertTrue(FacebookSessionCookies.namesToExpire(null).isEmpty())
        assertTrue(FacebookSessionCookies.namesToExpire("  ").isEmpty())
    }

    @Test
    fun `bo qua phan tu rong va loai trung ten`() {
        assertEquals(listOf("xs"), FacebookSessionCookies.namesToExpire("xs=1; ; xs=2; datr=x"))
    }

    @Test
    fun `chuoi het han co Max-Age 0 va domain`() {
        val cookie = FacebookSessionCookies.expireCookie("xs", ".facebook.com")

        assertTrue(cookie.startsWith("xs=;"))
        assertTrue(cookie.contains("Max-Age=0"))
        assertTrue(cookie.contains("Domain=.facebook.com"))
    }
}
