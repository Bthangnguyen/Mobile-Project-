package com.lttbdd.socialagent.feature.channel

import java.net.URLDecoder
import java.nio.charset.StandardCharsets

/**
 * Logic thuần (không phụ thuộc Android) phân tích URL redirect loopback của luồng OAuth LinkedIn (RFC 8252 §7.3).
 *
 * Quy tắc an toàn:
 * - Chỉ coi URL là redirect khi nó **bắt đầu đúng** bằng redirect URI app đã gửi đi (đúng ranh giới),
 *   không nhận `code` từ bất kỳ URL nào khác.
 * - Chỉ nhận `code` khi `state` khớp với giá trị app đã gửi đi (RFC 6749 Section 10.12, chống CSRF).
 * - Lỗi/hủy từ LinkedIn được chấp nhận không cần `state`, nhưng không bao giờ tạo ra `code`.
 */
object LinkedInAuthRedirect {

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
    fun isRedirect(url: String?, redirectUri: String): Boolean {
        if (url.isNullOrBlank() || redirectUri.isEmpty() || !url.startsWith(redirectUri)) return false
        return url.length == redirectUri.length || url[redirectUri.length] in "?#"
    }

    /**
     * Phân tích [url]. Trả về `null` nếu [url] không phải redirect tới [redirectUri].
     * Thiếu hoặc sai [expectedState] thì không bao giờ trả về [Result.Code].
     */
    fun parse(url: String?, redirectUri: String, expectedState: String): Result? {
        if (url == null || !isRedirect(url, redirectUri)) return null

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
