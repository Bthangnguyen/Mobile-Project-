package com.lttbdd.socialagent.feature.channel

import java.net.URLDecoder
import java.nio.charset.StandardCharsets

/**
 * Logic thuần (không phụ thuộc Android) phân tích URL redirect của hộp thoại OAuth Facebook.
 *
 * Quy tắc an toàn:
 * - Chỉ coi URL là redirect khi nó **bắt đầu đúng** bằng [REDIRECT_URI] hoặc `fb<appId>://authorize`,
 *   không dò chuỗi `code=`/`access_token=` ở bất kỳ URL nào trong WebView.
 * - Chỉ nhận `access_token` khi `state` khớp với giá trị app đã gửi đi (chống CSRF).
 * - `code` không phải access token nên không bao giờ được trả về như token.
 */
object FacebookAuthRedirect {

    /**
     * Redirect URI của luồng đăng nhập thủ công. Facebook không cho đăng ký URL `facebook.com`
     * vào "Valid OAuth Redirect URIs", nên dùng URL thuộc miền của dự án. URL này phải khớp chính xác
     * với mục Valid OAuth Redirect URIs và miền của nó phải có trong App Domains (Settings → Basic).
     * WebView chặn URL này trước khi tải, nên máy chủ không cần xử lý yêu cầu.
     */
    const val REDIRECT_URI = "https://social-agent-backend.onrender.com/oauth/facebook/callback"

    private const val ERROR_ACCESS_DENIED = "access_denied"

    enum class FailureReason { OAUTH_ERROR, STATE_MISMATCH, MISSING_TOKEN }

    sealed interface Result {
        data class Token(val accessToken: String) : Result
        data object Cancelled : Result
        data class Failure(val reason: FailureReason) : Result
    }

    /** URL có phải là redirect đích của luồng đăng nhập (đúng tiền tố, đúng ranh giới) hay không. */
    fun isRedirect(url: String?, appId: String): Boolean {
        if (url.isNullOrBlank()) return false
        if (matchesPrefix(url, REDIRECT_URI, "?#")) return true
        return appId.isNotBlank() && matchesPrefix(url, "fb$appId://authorize", "/?#")
    }

    /**
     * Phân tích [url]. Trả về `null` nếu [url] không phải redirect (WebView cứ tiếp tục tải trang).
     * Lỗi/hủy từ Facebook được chấp nhận không cần `state`; token thì bắt buộc `state` khớp [expectedState].
     */
    fun parse(url: String?, expectedState: String, appId: String): Result? {
        if (url == null || !isRedirect(url, appId)) return null

        val query = url.substringBefore('#').substringAfter('?', "")
        // Facebook đôi khi thêm "#_=_" ở cuối, chỉ lấy đoạn fragment đầu tiên.
        val fragment = url.substringAfter('#', "").substringBefore('#')
        val values = parseParams(query) + parseParams(fragment)

        val error = values["error"]
        if (!error.isNullOrEmpty()) {
            return if (error == ERROR_ACCESS_DENIED) Result.Cancelled else Result.Failure(FailureReason.OAUTH_ERROR)
        }

        if (expectedState.isEmpty() || values["state"] != expectedState) {
            return Result.Failure(FailureReason.STATE_MISMATCH)
        }

        val token = values["access_token"]
        return if (token.isNullOrBlank()) Result.Failure(FailureReason.MISSING_TOKEN) else Result.Token(token)
    }

    private fun matchesPrefix(url: String, prefix: String, boundaryChars: String): Boolean {
        if (!url.startsWith(prefix)) return false
        return url.length == prefix.length || url[prefix.length] in boundaryChars
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
