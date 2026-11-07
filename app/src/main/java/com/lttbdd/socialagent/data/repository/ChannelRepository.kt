package com.lttbdd.socialagent.data.repository

import com.lttbdd.socialagent.core.model.ChannelAccount
import com.lttbdd.socialagent.core.model.ChannelType
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
}
