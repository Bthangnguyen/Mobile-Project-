package com.lttbdd.socialagent.feature.channel

import com.lttbdd.socialagent.feature.channel.LinkedInAuthRedirect.FailureReason
import com.lttbdd.socialagent.feature.channel.LinkedInAuthRedirect.Result
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit test cho bộ phân tích redirect của hộp thoại đăng nhập LinkedIn.
 * Không gọi mạng, không dùng client id hay code thật.
 */
class LinkedInAuthRedirectTest {

    private val state = "state-abc"
    private val base = LinkedInAuthRedirect.REDIRECT_URI

    @Test
    fun `tra ve code khi state khop`() {
        val url = "$base?code=AQT123&state=$state"

        assertEquals(Result.Code("AQT123"), LinkedInAuthRedirect.parse(url, state))
    }

    @Test
    fun `giai ma code da percent-encode`() {
        val url = "$base?code=AQ%2FT%3D123&state=$state"

        assertEquals(Result.Code("AQ/T=123"), LinkedInAuthRedirect.parse(url, state))
    }

    @Test
    fun `state sai thi that bai STATE_MISMATCH va khong tra code`() {
        val url = "$base?code=AQT123&state=ke-tan-cong"

        assertEquals(Result.Failure(FailureReason.STATE_MISMATCH), LinkedInAuthRedirect.parse(url, state))
    }

    @Test
    fun `thieu state trong redirect thi that bai STATE_MISMATCH`() {
        val url = "$base?code=AQT123"

        assertEquals(Result.Failure(FailureReason.STATE_MISMATCH), LinkedInAuthRedirect.parse(url, state))
    }

    @Test
    fun `expectedState rong thi khong bao gio nhan code`() {
        val url = "$base?code=AQT123&state="

        assertEquals(Result.Failure(FailureReason.STATE_MISMATCH), LinkedInAuthRedirect.parse(url, ""))
    }

    @Test
    fun `state khop nhung thieu code thi that bai MISSING_CODE`() {
        val url = "$base?state=$state"

        assertEquals(Result.Failure(FailureReason.MISSING_CODE), LinkedInAuthRedirect.parse(url, state))
    }

    @Test
    fun `code rong thi that bai MISSING_CODE`() {
        val url = "$base?code=&state=$state"

        assertEquals(Result.Failure(FailureReason.MISSING_CODE), LinkedInAuthRedirect.parse(url, state))
    }

    @Test
    fun `huy dang nhap hoac huy cap quyen duoc nhan dien la Cancelled khong can state`() {
        listOf("user_cancelled_login", "user_cancelled_authorize", "access_denied").forEach { error ->
            val url = "$base?error=$error&error_description=x"

            assertEquals(error, Result.Cancelled, LinkedInAuthRedirect.parse(url, state))
        }
    }

    @Test
    fun `loi OAuth khac Cancelled thi that bai OAUTH_ERROR`() {
        val url = "$base?error=invalid_scope&state=$state"

        assertEquals(Result.Failure(FailureReason.OAUTH_ERROR), LinkedInAuthRedirect.parse(url, state))
    }

    @Test
    fun `loi di kem code khong bao gio tra ve code`() {
        val url = "$base?error=server_error&code=AQT123&state=$state"

        assertEquals(Result.Failure(FailureReason.OAUTH_ERROR), LinkedInAuthRedirect.parse(url, state))
    }

    @Test
    fun `URL khac redirect dung chua code thi bi bo qua`() {
        // Trang bat ky trong WebView co tham so code khong duoc coi la redirect.
        assertNull(LinkedInAuthRedirect.parse("https://www.linkedin.com/login?code=AQT123&state=$state", state))
        assertNull(LinkedInAuthRedirect.parse("https://ke-tan-cong.example/cb?code=AQT123&state=$state", state))
    }

    @Test
    fun `scheme tuy chinh cu cua app khong con duoc coi la redirect`() {
        assertNull(LinkedInAuthRedirect.parse("com.lttbdd.socialagent://oauth/linkedin?code=AQT123&state=$state", state))
    }

    @Test
    fun `tien to gan giong nhung sai ranh gioi bi tu choi`() {
        assertFalse(LinkedInAuthRedirect.isRedirect("${base}evil?code=AQT123&state=$state"))
        assertFalse(LinkedInAuthRedirect.isRedirect("${base}/../x?code=AQT123&state=$state"))
        assertNull(LinkedInAuthRedirect.parse("${base}evil?code=AQT123&state=$state", state))
    }

    @Test
    fun `isRedirect nhan dung URL va xu ly gia tri rong`() {
        assertTrue(LinkedInAuthRedirect.isRedirect(base))
        assertTrue(LinkedInAuthRedirect.isRedirect("$base?code=a"))
        assertTrue(LinkedInAuthRedirect.isRedirect("$base#code=a"))
        assertFalse(LinkedInAuthRedirect.isRedirect(null))
        assertFalse(LinkedInAuthRedirect.isRedirect(""))
        assertFalse(LinkedInAuthRedirect.isRedirect("   "))
    }

    @Test
    fun `tham so lap lai lay gia tri dau tien`() {
        val url = "$base?code=AQT123&code=ke-tan-cong&state=$state"

        assertEquals(Result.Code("AQT123"), LinkedInAuthRedirect.parse(url, state))
    }
}
