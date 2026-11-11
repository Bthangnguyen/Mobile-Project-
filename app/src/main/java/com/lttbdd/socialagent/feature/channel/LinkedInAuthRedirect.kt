package com.lttbdd.socialagent.feature.channel

import java.net.URLDecoder
import java.nio.charset.StandardCharsets

/**
 * Logic thuần (không phụ thuộc Android) phân tích URL redirect của hộp thoại OAuth LinkedIn.
 *
 * Quy tắc an toàn (giống [FacebookAuthRedirect]):
 * - Chỉ coi URL là redirect khi nó **bắt đầu đúng** bằng [REDIRECT_URI] (đúng ranh giới),
 *   không nhận `code` từ bất kỳ URL nào khác trong WebView.
 * - Chỉ nhận `code` khi `state` khớp với giá trị app đã gửi đi (RFC 6749 Section 10.12, chống CSRF).
 * - Lỗi/hủy từ LinkedIn được chấp nhận không cần `state`, nhưng không bao giờ tạo ra `code`.
 */
object LinkedInAuthRedirect {

    /** Redirect URI đã đăng ký trong LinkedIn Developer app. WebView chặn URL này trước khi tải. */
    const val REDIRECT_URI = "https://www.linkedin.com/developers/tools/oauth/redirect"

    /** LinkedIn trả `user_cancelled_login` hoặc `user_cancelled_authorize` khi người dùng hủy. */
    private const val ERROR_USER_CANCELLED_PREFIX = "user_cancelled"
    private const val ERROR_ACCESS_DENIED = "access_denied"

    enum class FailureReason { OAUTH_ERROR, STATE_MISMATCH, MISSING_CODE }

    sealed interface Result {
        data class Code(val authCode: String) : Result
        data object Cancelled : Result
        data class Failure(val reason: FailureReason) : Result
    }

    /** URL có phải là redirect đích của luồng đăng nhập (đúng tiền tố, đúng ranh giới) hay không. */
    fun isRedirect(url: String?): Boolean {
        if (url.isNullOrBlank() || !url.startsWith(REDIRECT_URI)) return false
        return url.length == REDIRECT_URI.length || url[REDIRECT_URI.length] in "?#"
    }

    /**
     * Phân tích [url]. Trả về `null` nếu [url] không phải redirect (WebView cứ tiếp tục tải trang).
     * Thiếu hoặc sai [expectedState] thì không bao giờ trả về [Result.Code].
     */
    fun parse(url: String?, expectedState: String): Result? {
        if (url == null || !isRedirect(url)) return null

        val query = url.substringBefore('#').substringAfter('?', "")
        val fragment = url.substringAfter('#', "").substringBefore('#')
        val values = parseParams(query) + parseParams(fragment)

        val error = values["error"]
        if (!error.isNullOrEmpty()) {
            val cancelled = error == ERROR_ACCESS_DENIED || error.startsWith(ERROR_USER_CANCELLED_PREFIX)
            return if (cancelled) Result.Cancelled else Result.Failure(FailureReason.OAUTH_ERROR)
        }

        if (expectedState.isEmpty() || values["state"] != expectedState) {
            return Result.Failure(FailureReason.STATE_MISMATCH)
        }

        val code = values["code"]
        return if (code.isNullOrBlank()) Result.Failure(FailureReason.MISSING_CODE) else Result.Code(code)
    }

    private fun parseParams(raw: String): Map<String, String> {
        if (raw.isEmpty()) return emptyMap()
        val result = LinkedHashMap<String, String>()
        for (pair in raw.split('&')) {
            if (pair.isEmpty()) continue
            val separator = pair.indexOf('=')
            if (separator <= 0) continue
            val key = decode(pair.substring(0, separator))
            val value = decode(pair.substring(separator + 1))
            result.putIfAbsent(key, value)
        }
        return result
    }

    private fun decode(value: String): String = try {
        URLDecoder.decode(value, StandardCharsets.UTF_8.name())
    } catch (_: IllegalArgumentException) {
        value
    }
}
