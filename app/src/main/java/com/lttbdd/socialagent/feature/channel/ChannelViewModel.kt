package com.lttbdd.socialagent.feature.channel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lttbdd.socialagent.core.model.ChannelAccount
import com.lttbdd.socialagent.core.model.ChannelType
import com.lttbdd.socialagent.data.network.PkceUtil
import com.lttbdd.socialagent.data.repository.ChannelRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ViewModel xử lý màn hình Kênh của tôi (F1).
 * Nguồn tham chiếu:
 * - RFC 8252: Mở external user-agent với OAuth URL và PKCE.
 * - RFC 7636: Sinh code_verifier và code_challenge S256.
 * - RFC 6749: Trao đổi code đổi token và tính expiresAt.
 */
@HiltViewModel
class ChannelViewModel @Inject constructor(
    private val repository: ChannelRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow<ChannelUiState>(ChannelUiState.Loading)
    val uiState: StateFlow<ChannelUiState> = _uiState.asStateFlow()

    // Lưu tạm verifier và state theo phiên OAuth
    private var pendingCodeVerifier: String? = null
    private var pendingState: String? = null

    /** Quyền token đã được cấp thật, theo từng kênh. Rỗng/null nghĩa là chưa xác định được. */
    private var grantedScopes: Map<String, String?> = emptyMap()

    init {
        loadChannels()
    }

    private fun loadChannels() {
        viewModelScope.launch {
            repository.getConnectedChannels().collect { channels ->
                // Nạp quyền thật của từng kênh trước khi phát UiState để ma trận không hiện sai.
                grantedScopes = channels.associate { account ->
                    scopeKey(account) to repository.getGrantedScope(account.type, account.accountId)
                }
                _uiState.value = ChannelUiState.Success(channels = channels)
            }
        }
    }

    private fun scopeKey(account: ChannelAccount) = "${account.type.name}_${account.accountId}"

    /**
     * Trả về quyền thật của token đã lưu; null nếu chưa đọc được
     * (khi đó ma trận hiển thị "Cần cấp thêm quyền", không giả định đã đủ quyền).
     */
    fun getScopeForAccount(account: ChannelAccount): String? = grantedScopes[scopeKey(account)]

    fun isFacebookDemoMode(): Boolean {
        val id = com.lttbdd.socialagent.BuildConfig.FACEBOOK_APP_ID
        return id.isBlank() || id.contains("SOCIAL_AGENT")
    }

    fun isLinkedInDemoMode(): Boolean {
        val id = com.lttbdd.socialagent.BuildConfig.LINKEDIN_CLIENT_ID
        return id.isBlank() || id.contains("SOCIAL_AGENT")
    }

    fun connectFacebookDemo(redirectUri: String) {
        val verifier = PkceUtil.generateCodeVerifier()
        val state = PkceUtil.generateState()
        pendingCodeVerifier = verifier
        pendingState = state
        handleOAuthCallback(
            channelType = ChannelType.FACEBOOK,
            authCode = "demo_code_fb_${System.currentTimeMillis()}",
            state = state,
            redirectUri = redirectUri,
        )
    }

    fun connectLinkedInDemo(redirectUri: String) {
        val verifier = PkceUtil.generateCodeVerifier()
        val state = PkceUtil.generateState()
        pendingCodeVerifier = verifier
        pendingState = state
        handleOAuthCallback(
            channelType = ChannelType.LINKEDIN,
            authCode = "demo_code_li_${System.currentTimeMillis()}",
            state = state,
            redirectUri = redirectUri,
        )
    }

    /**
     * Tạo tham số PKCE chuẩn bị mở luồng OAuth Facebook Page trên Custom Tabs (RFC 8252 & RFC 7636).
     */
    fun prepareFacebookOAuthUrl(redirectUri: String): String {
        val verifier = PkceUtil.generateCodeVerifier()
        val state = PkceUtil.generateState()
        pendingCodeVerifier = verifier
        pendingState = state
        val challenge = PkceUtil.generateCodeChallengeS256(verifier)

        val baseUrl = "https://www.facebook.com/v20.0/dialog/oauth"
        val appId = com.lttbdd.socialagent.BuildConfig.FACEBOOK_APP_ID
        val scope = "public_profile"
        val encodedScope = java.net.URLEncoder.encode(scope, java.nio.charset.StandardCharsets.UTF_8.name())
        val encodedRedirectUri = java.net.URLEncoder.encode(redirectUri, java.nio.charset.StandardCharsets.UTF_8.name())

        return "$baseUrl?client_id=$appId&redirect_uri=$encodedRedirectUri&scope=$encodedScope&response_type=code&state=$state&code_challenge=$challenge&code_challenge_method=S256"
    }

    /**
     * Tạo tham số chuẩn bị mở luồng OAuth 2.0 LinkedIn (Authorization Code Flow).
     */
    fun prepareLinkedInOAuthUrl(redirectUri: String): String {
        val state = java.util.UUID.randomUUID().toString()
        pendingState = state

        val effectiveRedirectUri = if (redirectUri.isBlank()) "https://www.linkedin.com/developers/tools/oauth/redirect" else redirectUri
        val baseUrl = "https://www.linkedin.com/oauth/v2/authorization"
        val clientId = com.lttbdd.socialagent.BuildConfig.LINKEDIN_CLIENT_ID.trim()
        val scope = "openid profile email"

        val encodedScope = java.net.URLEncoder.encode(scope, java.nio.charset.StandardCharsets.UTF_8.name())
        val encodedRedirectUri = java.net.URLEncoder.encode(effectiveRedirectUri, java.nio.charset.StandardCharsets.UTF_8.name())

        return "$baseUrl?response_type=code&client_id=$clientId&redirect_uri=$encodedRedirectUri&scope=$encodedScope&state=$state"
    }

    /**
     * Nhận authorization code từ Redirect URI callback và trao đổi token qua Repository.
     */
    fun handleOAuthCallback(
        channelType: ChannelType,
        authCode: String,
        state: String? = null,
        redirectUri: String,
    ) {
        if (state != null && pendingState != null && state != pendingState) {
            _uiState.value = ChannelUiState.Error("CSRF error: State mismatch")
            return
        }
        val verifier = pendingCodeVerifier ?: PkceUtil.generateCodeVerifier()
        viewModelScope.launch {
            _uiState.value = ChannelUiState.Loading
            val result = when (channelType) {
                ChannelType.FACEBOOK -> repository.connectFacebookPage(authCode, verifier, redirectUri)
                ChannelType.LINKEDIN -> repository.connectLinkedIn(authCode, verifier, redirectUri)
            }

            result.onSuccess { account ->
                val currentChannels = (uiState.value as? ChannelUiState.Success)?.channels ?: emptyList()
                val updatedChannels = currentChannels.filterNot { it.type == account.type && it.accountId == account.accountId } + account
                _uiState.value = ChannelUiState.Success(
                    channels = updatedChannels,
                    userMessage = "Connected ${account.displayName}",
                )
            }.onFailure { error ->
                _uiState.value = ChannelUiState.Error(error.message ?: "OAuth error")
            }
        }
    }

    fun disconnectChannel(type: ChannelType, accountId: String) {
        viewModelScope.launch {
            repository.disconnectChannel(type, accountId)
        }
    }
}
