package com.lttbdd.socialagent.data.repository

import com.lttbdd.socialagent.core.model.ChannelAccount
import com.lttbdd.socialagent.core.model.ChannelType
import com.lttbdd.socialagent.data.model.FacebookPage
import kotlinx.coroutines.flow.Flow

/**
 * Repository quản lý việc kết nối kênh mạng xã hội qua OAuth.
 * Nguồn tham chiếu:
 * - RFC 6749 Section 5.1 & 4.2.2: Tính expiresAt từ expires_in thực tế.
 * - RFC 7636: Sử dụng PKCE code_verifier khi đổi token.
 * - RFC 8252: Xác thực qua External User-Agent.
 */
interface ChannelRepository {

    /**
     * Lấy danh sách các kênh đã kết nối.
     */
    fun getConnectedChannels(): Flow<List<ChannelAccount>>

    /**
     * Kết nối kênh Facebook Page qua OAuth code và PKCE verifier.
     */
    suspend fun connectFacebookPage(
        authCode: String,
        codeVerifier: String,
        redirectUri: String,
    ): Result<ChannelAccount>

    /**
     * Kết nối kênh LinkedIn qua OAuth code và PKCE verifier.
     */
    suspend fun connectLinkedIn(
        authCode: String,
        codeVerifier: String,
        redirectUri: String,
    ): Result<ChannelAccount>

    /**
     * Ngắt kết nối một kênh và xóa token khỏi Keystore/SecureTokenStore.
     */
    suspend fun disconnectChannel(type: ChannelType, accountId: String): Result<Unit>

    /**
     * Quyền (scope) thật mà token của kênh đã được cấp, lưu lúc kết nối.
     * Trả về null nếu chưa có token hoặc không đọc được.
     */
    suspend fun getGrantedScope(type: ChannelType, accountId: String): String? = null

    /**
     * Thời điểm (epoch millis) token của kênh hết hạn, đọc từ token đã lưu.
     * Trả về null nếu chưa có token hoặc không đọc được (khi đó coi như chưa xác định, không báo hết hạn).
     */
    suspend fun getTokenExpiresAt(type: ChannelType, accountId: String): Long? = null

    /**
     * Lấy danh sách Facebook Page mà người dùng quản lý (Graph API `/me/accounts`).
     * Thất bại (lỗi mạng, thiếu quyền `pages_show_list`) trả về Result.failure.
     */
    suspend fun fetchFacebookPages(userAccessToken: String): Result<List<FacebookPage>> =
        Result.success(emptyList())

    /**
     * Kết nối một Page đã chọn: lưu Page access token và hiển thị tên Page làm tài khoản đang dùng.
     */
    suspend fun connectFacebookSelectedPage(
        userAccessToken: String,
        page: FacebookPage,
    ): Result<ChannelAccount> = Result.failure(UnsupportedOperationException("Chưa hỗ trợ chọn Page"))

    companion object {
        /** redirectUri đánh dấu authCode là access token lấy từ Facebook Login SDK. */
        const val FACEBOOK_SDK_REDIRECT = "facebook_sdk"
    }
}
