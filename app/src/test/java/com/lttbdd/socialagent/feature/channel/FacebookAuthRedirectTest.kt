package com.lttbdd.socialagent.feature.channel

import com.lttbdd.socialagent.feature.channel.FacebookAuthRedirect.FailureReason
import com.lttbdd.socialagent.feature.channel.FacebookAuthRedirect.Result
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit test cho bộ phân tích redirect của hộp thoại đăng nhập Facebook.
 * Không gọi mạng, không dùng App ID hay token thật.
 */
class FacebookAuthRedirectTest {

    private val appId = "1234567890"
    private val state = "state-abc"
    private val base = FacebookAuthRedirect.REDIRECT_URI

    @Test
    fun `trả về token khi state khớp (fragment)`() {
        val url = "$base#access_token=EAAB123&data_access_expiration_time=1&expires_in=5183999&state=$state"

        assertEquals(Result.Token("EAAB123"), FacebookAuthRedirect.parse(url, state, appId))
    }

    @Test
    fun `giải mã token và bỏ qua đoạn _=_ ở cuối`() {
        val url = "$base#access_token=EAAB%7C123&state=$state#_=_"

        assertEquals(Result.Token("EAAB|123"), FacebookAuthRedirect.parse(url, state, appId))
    }

    @Test
    fun `nhận redirect dạng fb appId authorize`() {
        val url = "fb$appId://authorize/#access_token=T1&state=$state"

        assertEquals(Result.Token("T1"), FacebookAuthRedirect.parse(url, state, appId))
    }

    @Test
    fun `từ chối khi state không khớp, thiếu hoặc expectedState rỗng`() {
        assertEquals(
            Result.Failure(FailureReason.STATE_MISMATCH),
            FacebookAuthRedirect.parse("$base#access_token=T&state=khac", state, appId),
        )
        assertEquals(
            Result.Failure(FailureReason.STATE_MISMATCH),
            FacebookAuthRedirect.parse("$base#access_token=T", state, appId),
        )
        assertEquals(
            Result.Failure(FailureReason.STATE_MISMATCH),
            FacebookAuthRedirect.parse("$base#access_token=T&state=", "", appId),
        )
    }

    @Test
    fun `không coi code là access token`() {
        val url = "$base#code=abc&state=$state"

        assertEquals(Result.Failure(FailureReason.MISSING_TOKEN), FacebookAuthRedirect.parse(url, state, appId))
    }

    @Test
    fun `access_denied là người dùng hủy, không cần state`() {
        val url = "$base?error=access_denied&error_code=200&error_reason=user_denied#_=_"

        assertEquals(Result.Cancelled, FacebookAuthRedirect.parse(url, state, appId))
    }

    @Test
    fun `lỗi OAuth khác trả về Failure OAUTH_ERROR`() {
        val url = "$base?error=server_error&error_description=x&state=$state"

        assertEquals(Result.Failure(FailureReason.OAUTH_ERROR), FacebookAuthRedirect.parse(url, state, appId))
    }

    @Test
    fun `redirect trơn không có tham số bị từ chối vì thiếu state, không phải token`() {
        val result = FacebookAuthRedirect.parse(base, state, appId)

        assertEquals(Result.Failure(FailureReason.STATE_MISMATCH), result)
    }

    @Test
    fun `URL không phải redirect trả về null dù chứa code hoặc access_token`() {
        // Hồi quy: regex cũ khớp cả "error_code=" và "code=" ở bất kỳ URL nào trong WebView.
        assertNull(FacebookAuthRedirect.parse("https://m.facebook.com/login/?error_code=1&code=2", state, appId))
        assertNull(FacebookAuthRedirect.parse("https://m.facebook.com/checkpoint/?next=x&code=123456", state, appId))
        assertNull(FacebookAuthRedirect.parse("https://m.facebook.com/v20.0/dialog/oauth?access_token=x", state, appId))
        assertNull(FacebookAuthRedirect.parse(null, state, appId))
    }

    @Test
    fun `chỉ khớp đúng tiền tố và ranh giới`() {
        assertFalse(FacebookAuthRedirect.isRedirect("$base.evil.example/#access_token=x&state=$state", appId))
        assertFalse(FacebookAuthRedirect.isRedirect("https://evil.example/?next=$base#access_token=x", appId))
        assertFalse(FacebookAuthRedirect.isRedirect("fb$appId://authorizeevil/#access_token=x", appId))
        assertFalse(FacebookAuthRedirect.isRedirect("fb999://authorize/#access_token=x", appId))
        assertTrue(FacebookAuthRedirect.isRedirect(base, appId))
        assertTrue(FacebookAuthRedirect.isRedirect("fb$appId://authorize", appId))
    }

    @Test
    fun `appId rỗng thì không nhận redirect dạng fb`() {
        assertFalse(FacebookAuthRedirect.isRedirect("fb://authorize/#access_token=x", ""))
        assertTrue(FacebookAuthRedirect.isRedirect("$base#access_token=x", ""))
    }
}
