package com.lttbdd.socialagent.data.model

import kotlinx.serialization.Serializable

/**
 * Model lưu trữ Token OAuth sau khi xác thực thành công.
 * Nguồn tham chiếu:
 * - RFC 6749 Section 5.1 (Successful Response) & Section 4.2.2 (Access Token Response):
 *   `expires_in` được trả về theo đơn vị giây.
 * - Công thức tính mốc thời điểm hết hạn tuyệt đối:
 *   expiresAt = currentTimeMillis + (expiresInSeconds * 1000L)
 */
@Serializable
data class OAuthToken(
    val accessToken: String,
    val expiresAt: Long,
    val scope: String? = null,
    val tokenType: String = "Bearer",
) {
    /**
     * Kiểm tra xem token đã hết hạn chưa.
     */
    fun isExpired(currentTimeMillis: Long = System.currentTimeMillis()): Boolean {
        return currentTimeMillis >= expiresAt
    }

    companion object {
        /**
         * Khởi tạo OAuthToken từ giá trị `expires_in` (tính bằng giây) trả về từ OAuth Server.
         */
        fun createFromExpiresIn(
            accessToken: String,
            expiresInSeconds: Long,
            scope: String? = null,
            tokenType: String = "Bearer",
            currentTimeMillis: Long = System.currentTimeMillis(),
        ): OAuthToken {
            val expiresAt = currentTimeMillis + (expiresInSeconds * 1000L)
            return OAuthToken(
                accessToken = accessToken,
                expiresAt = expiresAt,
                scope = scope,
                tokenType = tokenType,
            )
        }
    }
}
