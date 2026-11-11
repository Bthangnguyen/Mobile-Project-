package com.lttbdd.socialagent.data.repository

import com.lttbdd.socialagent.BuildConfig
import com.lttbdd.socialagent.core.model.ChannelAccount
import com.lttbdd.socialagent.core.model.ChannelCapability
import com.lttbdd.socialagent.core.model.ChannelType
import com.lttbdd.socialagent.core.model.ConnectionStatus
import com.lttbdd.socialagent.data.model.FacebookPage
import com.lttbdd.socialagent.data.model.FacebookUserToken
import com.lttbdd.socialagent.data.model.OAuthToken
import com.lttbdd.socialagent.data.network.LinkedInNativePkce
import com.lttbdd.socialagent.data.security.TokenStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import javax.inject.Inject
import javax.inject.Singleton

/** LinkedIn từ chối đổi code bằng Native PKCE: app chưa được LinkedIn cấp quyền (trạng thái "blocked"). */
class LinkedInNativePkceBlockedException : Exception("LinkedIn Native PKCE chưa được cấp cho app này")

/**
 * Implementation quản lý kết nối kênh mạng xã hội, lưu token bảo mật và metadata tài khoản.
 * Nguồn tham chiếu:
 * - RFC 6749 Section 5.1: hạn token tính từ `expires_in` thật của nhà cung cấp (không viết cứng 60 ngày).
 * - RFC 7636: đổi authorization code kèm code_verifier (public client, không client_secret).
 * - Android Keystore: token mã hóa qua TokenStore; [ChannelAccountStore] chỉ giữ metadata không bí mật.
 */
@Singleton
class ChannelRepositoryImpl @Inject constructor(
    private val tokenStore: TokenStore,
    private val accountStore: ChannelAccountStore,
) : ChannelRepository {

    private val channelsFlow = MutableStateFlow<List<ChannelAccount>>(emptyList())
    private val httpClient = okhttp3.OkHttpClient()
    private val loadMutex = Mutex()
    private var restored = false

    override fun getConnectedChannels(): Flow<List<ChannelAccount>> = flow {
        ensureRestored()
        emitAll(channelsFlow)
    }

    /**
     * Khôi phục danh sách kênh từ metadata đã lưu, chỉ giữ tài khoản còn token đọc được
     * (token mất/hỏng, ví dụ Keystore bị đặt lại, thì tài khoản đó coi như chưa kết nối).
     */
    private suspend fun ensureRestored() {
        loadMutex.withLock {
            if (restored) return
            val accounts = try {
                accountStore.load()
            } catch (_: Exception) {
                emptyList()
            }
            val restoredAccounts = accounts
                .filter { stored -> tokenStore.getToken(stored.type, stored.accountId).first() != null }
                .map { toChannelAccount(it.type, it.accountId, it.displayName) }
            val connectedBeforeRestore = channelsFlow.value
            channelsFlow.value = restoredAccounts
                .filterNot { r -> connectedBeforeRestore.any { it.type == r.type && it.accountId == r.accountId } } +
                connectedBeforeRestore
            restored = true
        }
    }

    private fun toChannelAccount(type: ChannelType, accountId: String, displayName: String) = ChannelAccount(
        type = type,
        accountId = accountId,
        displayName = displayName,
        status = ConnectionStatus.CONNECTED,
        // Khả năng thật tính ở UI từ scope của token; ở đây chỉ liệt kê tập khả năng tối đa của kênh.
        capabilities = ALL_CAPABILITIES,
    )

    /**
     * Đường kết nối GIẢ cho chế độ demo (chưa cấu hình FACEBOOK_APP_ID). Luồng thật dùng
     * [connectFacebookProfile] / [connectFacebookSelectedPage] với token từ Meta SDK.
     */
    override suspend fun connectFacebookPage(
        authCode: String,
        codeVerifier: String,
        redirectUri: String,
    ): Result<ChannelAccount> = withContext(Dispatchers.IO) {
        try {
            val token = OAuthToken.createFromExpiresIn(
                accessToken = "fb_access_token_$authCode",
                expiresInSeconds = DEMO_EXPIRES_IN_SECONDS,
                scope = "pages_manage_posts,pages_read_engagement",
            )
            saveAndRegister(ChannelType.FACEBOOK, "fb_page_101", "Social Agent Official Page", token)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun connectFacebookProfile(user: FacebookUserToken): Result<ChannelAccount> =
        withContext(Dispatchers.IO) {
            try {
                val profile = fetchFacebookProfile(user.accessToken)
                    ?: return@withContext Result.failure(IllegalStateException("Không lấy được hồ sơ Facebook"))
                // Hồ sơ cá nhân không có Page token nên không có khả năng ở mức Page: lưu public_profile
                // để ma trận không báo "Hỗ trợ" cho thứ không dùng được. Hạn token là hạn thật từ SDK.
                val token = OAuthToken(
                    accessToken = user.accessToken,
                    expiresAt = user.expiresAtMillis,
                    scope = "public_profile",
                )
                saveAndRegister(ChannelType.FACEBOOK, profile.first, profile.second, token)
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

    override suspend fun fetchFacebookPages(userAccessToken: String): Result<List<FacebookPage>> =
        withContext(Dispatchers.IO) {
            try {
                val request = okhttp3.Request.Builder()
                    .url("https://graph.facebook.com/v20.0/me/accounts?fields=id,name,access_token&limit=100")
                    .header("Authorization", "Bearer $userAccessToken")
                    .build()
                httpClient.newCall(request).execute().use { response ->
                    val body = response.body?.string()
                    if (response.isSuccessful && !body.isNullOrEmpty()) {
                        Result.success(FacebookPagesParser.parse(body))
                    } else {
                        Result.failure(IllegalStateException("Không tải được danh sách Page (HTTP ${response.code})"))
                    }
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    override suspend fun connectFacebookSelectedPage(
        user: FacebookUserToken,
        page: FacebookPage,
    ): Result<ChannelAccount> = withContext(Dispatchers.IO) {
        try {
            // Quyền do Meta SDK báo là quyền thật đã cấp cho người dùng và áp dụng cho Page token.
            // Page token lấy từ user token nên cùng hạn với user token (Meta không cấp hạn riêng ở bước này);
            // nếu Meta rút token sớm hơn, lỗi 190 khi gọi API sẽ cho biết.
            val token = OAuthToken(
                accessToken = page.accessToken,
                expiresAt = user.expiresAtMillis,
                scope = user.grantedScope ?: "public_profile",
            )
            saveAndRegister(ChannelType.FACEBOOK, page.id, page.name, token)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun getGrantedScope(type: ChannelType, accountId: String): String? {
        return try {
            tokenStore.getToken(type, accountId).first()?.scope
        } catch (_: Exception) {
            null
        }
    }

    override suspend fun getTokenExpiresAt(type: ChannelType, accountId: String): Long? {
        return try {
            tokenStore.getToken(type, accountId).first()?.expiresAt
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Kết nối LinkedIn. Code thật được đổi bằng Native PKCE: `code_verifier` thay cho `client_secret`.
     * LinkedIn từ chối vì app chưa được cấp Native PKCE thì trả [LinkedInNativePkceBlockedException].
     */
    override suspend fun connectLinkedIn(
        authCode: String,
        codeVerifier: String,
        redirectUri: String,
    ): Result<ChannelAccount> = withContext(Dispatchers.IO) {
        try {
            var accessToken = "li_access_token_$authCode"
            var expiresInSeconds = DEMO_EXPIRES_IN_SECONDS
            var displayName = "Social Agent LinkedIn Company Page"
            var accountId = "li_org_202"
            var grantedScope: String? = LinkedInNativePkce.SCOPE

            val clientId = BuildConfig.LINKEDIN_CLIENT_ID

            if (!authCode.startsWith("demo_code") && !authCode.startsWith("code_li") && clientId.isNotBlank() && !clientId.contains("SOCIAL_AGENT")) {
                // Code thật: đổi code lấy token. Lỗi thì báo lỗi, KHÔNG lưu tài khoản giả.
                val formBody = okhttp3.FormBody.Builder()
                    .add("grant_type", "authorization_code")
                    .add("code", authCode)
                    .add("redirect_uri", redirectUri)
                    .add("client_id", clientId)
                    .add("code_verifier", codeVerifier)
                    .build()

                val tokenRequest = okhttp3.Request.Builder()
                    .url(LinkedInNativePkce.TOKEN_ENDPOINT)
                    .post(formBody)
                    .build()

                val tokenJson = httpClient.newCall(tokenRequest).execute().use { response ->
                    val body = response.body?.string()
                    if (!response.isSuccessful || body.isNullOrEmpty()) {
                        val blocked = isNativePkceBlocked(response.code, body)
                        return@withContext Result.failure(
                            if (blocked) {
                                LinkedInNativePkceBlockedException()
                            } else {
                                IllegalStateException("LinkedIn token exchange failed (HTTP ${response.code})")
                            },
                        )
                    }
                    json.parseToJsonElement(body) as? JsonObject
                }
                val tokenStr = tokenJson?.stringField("access_token")
                if (tokenStr.isNullOrEmpty()) {
                    return@withContext Result.failure(IllegalStateException("LinkedIn không trả về access_token"))
                }
                accessToken = tokenStr
                // Scope và hạn thật do LinkedIn trả về; thiếu thì không bịa: scope null, hạn lấy mặc định ngắn.
                grantedScope = tokenJson?.stringField("scope")?.takeIf { it.isNotBlank() }
                expiresInSeconds = tokenJson?.stringField("expires_in")?.toLongOrNull()?.takeIf { it > 0 }
                    ?: FALLBACK_EXPIRES_IN_SECONDS

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
                scope = grantedScope,
            )
            saveAndRegister(ChannelType.LINKEDIN, accountId, displayName, token)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun disconnectChannel(type: ChannelType, accountId: String): Result<Unit> {
        return try {
            ensureRestored()
            tokenStore.removeToken(type, accountId)
            channelsFlow.value = channelsFlow.value.filterNot { it.type == type && it.accountId == accountId }
            persistAccounts()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private suspend fun saveAndRegister(
        type: ChannelType,
        accountId: String,
        displayName: String,
        token: OAuthToken,
    ): Result<ChannelAccount> {
        ensureRestored()
        tokenStore.saveToken(type, accountId, token)
        val account = toChannelAccount(type, accountId, displayName)
        val current = channelsFlow.value.toMutableList()
        val index = current.indexOfFirst { it.type == type && it.accountId == accountId }
        if (index >= 0) current[index] = account else current.add(account)
        channelsFlow.value = current
        persistAccounts()
        return Result.success(account)
    }

    /** Lưu metadata (loại, id, tên); không có token. Lỗi lưu không làm hỏng kết nối đang có. */
    private suspend fun persistAccounts() {
        try {
            accountStore.save(channelsFlow.value.map { StoredChannelAccount(it.type, it.accountId, it.displayName) })
        } catch (_: Exception) {
            // Lần mở app sau sẽ phải kết nối lại kênh này; không chặn luồng kết nối hiện tại.
        }
    }

    private fun JsonObject.stringField(key: String): String? = (this[key] as? JsonPrimitive)?.content

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        private val ALL_CAPABILITIES = setOf(
            ChannelCapability.PUBLISH,
            ChannelCapability.READ_COMMENTS,
            ChannelCapability.REPLY,
            ChannelCapability.MENTION,
            ChannelCapability.METRICS,
        )

        /** Chỉ dùng cho đường demo; luồng thật luôn dùng hạn do nhà cung cấp trả. */
        private const val DEMO_EXPIRES_IN_SECONDS = 5_184_000L

        /** LinkedIn không trả expires_in: dùng hạn ngắn để buộc đăng nhập lại thay vì giả định 60 ngày. */
        private const val FALLBACK_EXPIRES_IN_SECONDS = 3_600L

        /**
         * LinkedIn báo app không được dùng Native PKCE: HTTP 4xx kèm lỗi client/không được phép
         * (`invalid_client`, `unauthorized_client`, `access_denied`). Các lỗi khác (code hết hạn…) là lỗi thường.
         */
        internal fun isNativePkceBlocked(httpCode: Int, body: String?): Boolean {
            if (httpCode !in 400..403 || body.isNullOrEmpty()) return false
            val error = try {
                ((json.parseToJsonElement(body) as? JsonObject)?.get("error") as? JsonPrimitive)?.content
            } catch (_: Exception) {
                null
            }
            return error == "invalid_client" || error == "unauthorized_client" || error == "access_denied"
        }

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