package com.lttbdd.socialagent.feature.channel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lttbdd.socialagent.core.model.ChannelAccount
import com.lttbdd.socialagent.R
import com.lttbdd.socialagent.core.model.ChannelType
import com.lttbdd.socialagent.core.model.ConnectionStatus
import com.lttbdd.socialagent.data.model.FacebookPage
import com.lttbdd.socialagent.data.network.PkceUtil
import com.lttbdd.socialagent.data.repository.ChannelRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
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

    /** Danh sách kênh mới nhất từ repository (dùng khi UiState đang ở trạng thái khác Success). */
    private var knownChannels: List<ChannelAccount> = emptyList()

    private val _pagePicker = MutableStateFlow<FacebookPagePicker?>(null)

    /** Khác null khi cần người dùng chọn một trong nhiều Facebook Page. */
    val pagePicker: StateFlow<FacebookPagePicker?> = _pagePicker.asStateFlow()

    private val _notices = MutableSharedFlow<ChannelNotice>(extraBufferCapacity = 1)
    val notices: SharedFlow<ChannelNotice> = _notices.asSharedFlow()

    /** Token người dùng giữ tạm trong bộ nhớ trong lúc chờ chọn Page; xóa khi chọn xong hoặc hủy. */
    private var pendingFacebookUserToken: String? = null

    /** Đồng hồ để xác định token hết hạn; tách ra để unit test điều khiển được thời gian. */
    internal var clock: () -> Long = { System.currentTimeMillis() }

    init {
        loadChannels()
    }

    private fun loadChannels() {
        viewModelScope.launch {
            repository.getConnectedChannels().collect { channels ->
                // Nạp quyền thật và trạng thái hạn token trước khi phát UiState để ma trận không hiện sai.
                val resolved = channels.map { resolveAccount(it) }
                knownChannels = resolved
                val currentConnectedName = (_uiState.value as? ChannelUiState.Success)?.connectedName
                _uiState.value = ChannelUiState.Success(channels = resolved, connectedName = currentConnectedName)
            }
        }
    }

    /**
     * Nạp quyền thật của [account] vào bộ nhớ và trả về bản có trạng thái đúng:
     * token đã quá hạn thì đổi CONNECTED thành TOKEN_EXPIRED (lưu trạng thái này ở UI, không đụng token đã lưu).
     */
    private suspend fun resolveAccount(account: ChannelAccount): ChannelAccount {
        grantedScopes = grantedScopes + (scopeKey(account) to repository.getGrantedScope(account.type, account.accountId))
        val expiresAt = repository.getTokenExpiresAt(account.type, account.accountId)
        return if (account.status == ConnectionStatus.CONNECTED && expiresAt != null && clock() >= expiresAt) {
            account.copy(status = ConnectionStatus.TOKEN_EXPIRED)
        } else {
            account
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
        val scope = LinkedInAuthDialogFragment.SCOPE

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
            _uiState.value = ChannelUiState.Error(messageRes = R.string.channel_oauth_state_mismatch)
            return
        }
        if (channelType == ChannelType.FACEBOOK && redirectUri == ChannelRepository.FACEBOOK_SDK_REDIRECT) {
            connectFacebookWithPages(userAccessToken = authCode)
            return
        }
        val verifier = pendingCodeVerifier ?: PkceUtil.generateCodeVerifier()
        viewModelScope.launch {
            _uiState.value = ChannelUiState.Loading
            val result = when (channelType) {
                ChannelType.FACEBOOK -> repository.connectFacebookPage(authCode, verifier, redirectUri)
                ChannelType.LINKEDIN -> repository.connectLinkedIn(authCode, verifier, redirectUri)
            }
            applyConnectResult(result)
        }
    }

    /**
     * Sau khi có token người dùng Facebook: lấy các Page họ quản lý.
     * - 0 Page: kết nối bằng hồ sơ cá nhân và báo [ChannelNotice.NO_FACEBOOK_PAGES].
     * - 1 Page: dùng luôn Page đó.
     * - Nhiều Page: phát [pagePicker] để người dùng chọn.
     */
    private fun connectFacebookWithPages(userAccessToken: String) {
        viewModelScope.launch {
            _uiState.value = ChannelUiState.Loading
            val pagesResult = repository.fetchFacebookPages(userAccessToken)
            if (pagesResult.isFailure) {
                // Lỗi mạng/quyền khi tải Page KHÔNG đồng nghĩa "không có Page": không nối bằng hồ sơ cá nhân,
                // chỉ báo lỗi để người dùng thử lại.
                _notices.tryEmit(ChannelNotice.FACEBOOK_PAGES_LOAD_FAILED)
                _uiState.value = ChannelUiState.Success(channels = knownChannels)
                return@launch
            }
            val pages = pagesResult.getOrThrow()
            when {
                pages.size > 1 -> {
                    pendingFacebookUserToken = userAccessToken
                    _uiState.value = ChannelUiState.Success(channels = knownChannels)
                    _pagePicker.value = FacebookPagePicker(pages)
                }
                pages.size == 1 -> applyConnectResult(
                    repository.connectFacebookSelectedPage(userAccessToken, pages.first()),
                )
                else -> {
                    _notices.tryEmit(ChannelNotice.NO_FACEBOOK_PAGES)
                    val verifier = pendingCodeVerifier ?: PkceUtil.generateCodeVerifier()
                    applyConnectResult(
                        repository.connectFacebookPage(
                            userAccessToken,
                            verifier,
                            ChannelRepository.FACEBOOK_SDK_REDIRECT,
                        ),
                    )
                }
            }
        }
    }

    /** Người dùng chọn một Page trong hộp thoại: kết nối Page đó. */
    fun selectFacebookPage(page: FacebookPage) {
        val token = pendingFacebookUserToken ?: return
        pendingFacebookUserToken = null
        _pagePicker.value = null
        viewModelScope.launch {
            _uiState.value = ChannelUiState.Loading
            applyConnectResult(repository.connectFacebookSelectedPage(token, page))
        }
    }

    /** Người dùng đóng hộp thoại chọn Page mà không chọn: không kết nối gì. */
    fun cancelFacebookPagePicker() {
        pendingFacebookUserToken = null
        _pagePicker.value = null
    }

    override fun onCleared() {
        pendingFacebookUserToken = null
        super.onCleared()
    }

    /**
     * Phát trạng thái sau khi kết nối. Nạp quyền của kênh mới TRƯỚC khi phát để ma trận không đọc scope null
     * (luồng [loadChannels] nạp bất đồng bộ nên có thể chậm hơn kết quả kết nối).
     */
    private suspend fun applyConnectResult(result: Result<ChannelAccount>) {
        result.onSuccess { connected ->
            val account = resolveAccount(connected)
            val updatedChannels = knownChannels
                .filterNot { it.type == account.type && it.accountId == account.accountId } + account
            knownChannels = updatedChannels
            _uiState.value = ChannelUiState.Success(
                channels = updatedChannels,
                connectedName = account.displayName,
            )
        }.onFailure { error ->
            _uiState.value = ChannelUiState.Error(message = error.message)
        }
    }

    fun disconnectChannel(type: ChannelType, accountId: String) {
        viewModelScope.launch {
            repository.disconnectChannel(type, accountId)
        }
    }
}
