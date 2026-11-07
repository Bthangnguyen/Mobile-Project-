package com.lttbdd.socialagent.data.repository

import com.lttbdd.socialagent.core.model.ChannelAccount
import com.lttbdd.socialagent.core.model.ChannelCapability
import com.lttbdd.socialagent.core.model.ChannelType
import com.lttbdd.socialagent.core.model.ConnectionStatus
import com.lttbdd.socialagent.data.model.OAuthToken
import com.lttbdd.socialagent.data.security.TokenStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
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
    private val httpClient = okhttp3.OkHttpClient()

    override fun getConnectedChannels(): Flow<List<ChannelAccount>> = channelsFlow.asStateFlow()

    override suspend fun connectFacebookPage(
        authCode: String,
        codeVerifier: String,
        redirectUri: String,
    ): Result<ChannelAccount> = withContext(Dispatchers.IO) {
        try {
            var accessToken = "fb_access_token_$authCode"
            var accountId = "fb_page_101"
            var accountName = "Social Agent Official Page"
            var scope = "pages_manage_posts,pages_read_engagement"

            // Luồng Facebook Login SDK: authCode thực chất là access token thật của người dùng.
            // Gọi Graph API /me để lấy đúng id và tên tài khoản thay vì dùng tên giả.
            if (redirectUri == ChannelRepository.FACEBOOK_SDK_REDIRECT) {
                val profile = fetchFacebookProfile(authCode)
                    ?: return@withContext Result.failure(
                        IllegalStateException("Không lấy được thông tin tài khoản Facebook"),
                    )
                accessToken = authCode
                accountId = profile.first
                accountName = profile.second
                scope = "public_profile"
            }

            // Facebook trả về expires_in = 5184000s (~60 ngày) cho long-lived token
            val token = OAuthToken.createFromExpiresIn(
                accessToken = accessToken,
                expiresInSeconds = 5184000L,
                scope = scope,
            )

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

    /**
     * Gọi Graph API /me bằng access token để lấy (id, name). Trả null nếu lỗi.
     * Token gửi qua header Authorization để không nằm trong URL/log.
     */
    private fun fetchFacebookProfile(accessToken: String): Pair<String, String>? {
        return try {
            val request = okhttp3.Request.Builder()
                .url("https://graph.facebook.com/v20.0/me?fields=id,name")
                .header("Authorization", "Bearer $accessToken")
                .build()
            httpClient.newCall(request).execute().use { response ->
                val body = response.body?.string()
                if (response.isSuccessful && !body.isNullOrEmpty()) parseFacebookProfile(body) else null
            }
        } catch (_: Exception) {
            null
        }
    }

    override suspend fun connectLinkedIn(
        authCode: String,
        codeVerifier: String,
        redirectUri: String,
    ): Result<ChannelAccount> = withContext(Dispatchers.IO) {
        try {
            var accessToken = "li_access_token_$authCode"
            var expiresInSeconds = 5184000L
            var displayName = "Social Agent LinkedIn Company Page"
            var accountId = "li_org_202"

            val clientId = com.lttbdd.socialagent.BuildConfig.LINKEDIN_CLIENT_ID
            val clientSecret = com.lttbdd.socialagent.BuildConfig.LINKEDIN_CLIENT_SECRET
            val effectiveRedirectUri = if (redirectUri.isBlank()) "https://www.linkedin.com/developers/tools/oauth/redirect" else redirectUri

            if (!authCode.startsWith("demo_code") && !authCode.startsWith("code_li") && clientId.isNotBlank() && !clientId.contains("SOCIAL_AGENT")) {
                // Code thật: đổi code lấy token. Lỗi thì báo lỗi, KHÔNG lưu tài khoản giả.
                val formBody = okhttp3.FormBody.Builder()
                    .add("grant_type", "authorization_code")
                    .add("code", authCode)
                    .add("redirect_uri", effectiveRedirectUri)
                    .add("client_id", clientId)
                    .add("client_secret", clientSecret)
                    .build()

                val tokenRequest = okhttp3.Request.Builder()
                    .url("https://www.linkedin.com/oauth/v2/accessToken")
                    .post(formBody)
                    .build()

                val tokenJson = httpClient.newCall(tokenRequest).execute().use { response ->
                    val body = response.body?.string()
                    if (!response.isSuccessful || body.isNullOrEmpty()) {
                        return@withContext Result.failure(
                            IllegalStateException("LinkedIn token exchange failed (HTTP ${response.code})"),
                        )
                    }
                    json.parseToJsonElement(body) as? JsonObject
                }
                val tokenStr = tokenJson?.stringField("access_token")
                if (tokenStr.isNullOrEmpty()) {
                    return@withContext Result.failure(IllegalStateException("LinkedIn không trả về access_token"))
                }
                accessToken = tokenStr
                tokenJson?.stringField("expires_in")?.toLongOrNull()?.takeIf { it > 0 }?.let { expiresInSeconds = it }

                // Gọi UserInfo API (OpenID Connect) để lấy tên thật của người dùng
                val userRequest = okhttp3.Request.Builder()
                    .url("https://api.linkedin.com/v2/userinfo")
                    .header("Authorization", "Bearer $accessToken")
                    .build()
                val userObj = httpClient.newCall(userRequest).execute().use { response ->
                    val body = response.body?.string()
                    if (!response.isSuccessful || body.isNullOrEmpty()) {
                        return@withContext Result.failure(
                            IllegalStateException("Không lấy được thông tin tài khoản LinkedIn (HTTP ${response.code})"),
                        )
                    }
                    json.parseToJsonElement(body) as? JsonObject
                }
                userObj?.stringField("name")?.takeIf { it.isNotEmpty() }?.let { displayName = it }
                userObj?.stringField("sub")?.takeIf { it.isNotEmpty() }?.let { accountId = it }
            }

            val token = OAuthToken.createFromExpiresIn(
                accessToken = accessToken,
                expiresInSeconds = expiresInSeconds,
                scope = "openid profile email w_member_social",
            )

            tokenStore.saveToken(ChannelType.LINKEDIN, accountId, token)

            val channelAccount = ChannelAccount(
                type = ChannelType.LINKEDIN,
                accountId = accountId,
                displayName = displayName,
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
            if (type == ChannelType.LINKEDIN) {
                com.lttbdd.socialagent.feature.channel.LinkedInAuthDialogFragment.clearLinkedInCookies()
            }
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

    private fun JsonObject.stringField(key: String): String? = (this[key] as? JsonPrimitive)?.content

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        /**
         * Parse JSON của Graph API /me thành (id, name). Trả null nếu thiếu trường.
         */
        internal fun parseFacebookProfile(body: String): Pair<String, String>? {
            return try {
                val obj = json.parseToJsonElement(body) as? JsonObject ?: return null
                val id = (obj["id"] as? JsonPrimitive)?.content
                val name = (obj["name"] as? JsonPrimitive)?.content
                if (id.isNullOrBlank() || name.isNullOrBlank()) null else id to name
            } catch (_: Exception) {
                null
            }
        }
    }
}