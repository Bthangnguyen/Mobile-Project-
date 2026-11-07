package com.lttbdd.socialagent.data.repository

import com.lttbdd.socialagent.core.model.ChannelAccount
import com.lttbdd.socialagent.core.model.ChannelCapability
import com.lttbdd.socialagent.core.model.ChannelType
import com.lttbdd.socialagent.core.model.ConnectionStatus
import com.lttbdd.socialagent.data.model.OAuthToken
import com.lttbdd.socialagent.data.security.TokenStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Implementation quản lý kết nối kênh mạng xã hội và lưu trữ token bảo mật.
 * Nguồn tham chiếu kỹ thuật:
 * - RFC 6749 Section 5.1 & 4.2.2: Tính expiresAt = System.currentTimeMillis() + (expiresInSeconds * 1000L).
 * - RFC 7636: Trao đổi authorization code kèm code_verifier.
 * - Android Keystore system: Token được mã hóa và lưu thông qua TokenStore / SecureTokenStore.
 */
@Singleton
class ChannelRepositoryImpl @Inject constructor(
    private val tokenStore: TokenStore,
) : ChannelRepository {

    private val channelsFlow = MutableStateFlow<List<ChannelAccount>>(emptyList())

    override fun getConnectedChannels(): Flow<List<ChannelAccount>> = channelsFlow.asStateFlow()

    override suspend fun connectFacebookPage(
        authCode: String,
        codeVerifier: String,
        redirectUri: String,
    ): Result<ChannelAccount> {
        return try {
            // Giả lập/Thực thi trao đổi authCode đổi lấy OAuthToken (Ví dụ: Facebook trả về expires_in = 5184000s ~60 ngày)
            val fakeExpiresInSeconds = 5184000L
            val token = OAuthToken.createFromExpiresIn(
                accessToken = "fb_access_token_$authCode",
                expiresInSeconds = fakeExpiresInSeconds,
                scope = "pages_manage_posts,pages_read_engagement",
            )

            val accountId = "fb_page_101"
            val accountName = "Social Agent Official Page"

            tokenStore.saveToken(ChannelType.FACEBOOK, accountId, token)

            val channelAccount = ChannelAccount(
                type = ChannelType.FACEBOOK,
                accountId = accountId,
                displayName = accountName,
                status = ConnectionStatus.CONNECTED,
                capabilities = setOf(
                    ChannelCapability.PUBLISH,
                    ChannelCapability.READ_COMMENTS,
                    ChannelCapability.REPLY,
                    ChannelCapability.MENTION,
                    ChannelCapability.METRICS,
                ),
            )

            updateOrAddChannel(channelAccount)
            Result.success(channelAccount)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun connectLinkedIn(
        authCode: String,
        codeVerifier: String,
        redirectUri: String,
    ): Result<ChannelAccount> {
        return try {
            // Giả lập/Thực thi trao đổi authCode đổi lấy OAuthToken (Ví dụ: LinkedIn trả về expires_in = 5184000s ~60 ngày)
            val fakeExpiresInSeconds = 5184000L
            val token = OAuthToken.createFromExpiresIn(
                accessToken = "li_access_token_$authCode",
                expiresInSeconds = fakeExpiresInSeconds,
                scope = "w_organization_social,r_organization_social_feed",
            )

            val accountId = "li_org_202"
            val accountName = "Social Agent LinkedIn Company Page"

            tokenStore.saveToken(ChannelType.LINKEDIN, accountId, token)

            val channelAccount = ChannelAccount(
                type = ChannelType.LINKEDIN,
                accountId = accountId,
                displayName = accountName,
                status = ConnectionStatus.CONNECTED,
                capabilities = setOf(
                    ChannelCapability.PUBLISH,
                    ChannelCapability.READ_COMMENTS,
                    ChannelCapability.REPLY,
                    ChannelCapability.MENTION,
                    ChannelCapability.METRICS,
                ),
            )

            updateOrAddChannel(channelAccount)
            Result.success(channelAccount)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun disconnectChannel(type: ChannelType, accountId: String): Result<Unit> {
        return try {
            tokenStore.removeToken(type, accountId)
            val currentList = channelsFlow.value.toMutableList()
            currentList.removeAll { it.type == type && it.accountId == accountId }
            channelsFlow.value = currentList
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun updateOrAddChannel(account: ChannelAccount) {
        val currentList = channelsFlow.value.toMutableList()
        val index = currentList.indexOfFirst { it.type == account.type && it.accountId == account.accountId }
        if (index >= 0) {
            currentList[index] = account
        } else {
            currentList.add(account)
        }
        channelsFlow.value = currentList
    }
}
