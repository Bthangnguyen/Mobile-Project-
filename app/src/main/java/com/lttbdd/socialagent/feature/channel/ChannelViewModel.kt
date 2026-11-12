package com.lttbdd.socialagent.feature.channel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lttbdd.socialagent.core.model.ChannelAccount
import com.lttbdd.socialagent.BuildConfig
import com.lttbdd.socialagent.R
import com.lttbdd.socialagent.core.model.ChannelType
import com.lttbdd.socialagent.core.model.ConnectionStatus
import com.lttbdd.socialagent.data.model.FacebookPage
import com.lttbdd.socialagent.data.model.FacebookUserToken
import com.lttbdd.socialagent.data.network.LinkedInNativePkce
import com.lttbdd.socialagent.data.network.LoopbackRedirectServer
import com.lttbdd.socialagent.data.network.PkceUtil
import com.lttbdd.socialagent.data.network.RedirectListener
import com.lttbdd.socialagent.data.repository.ChannelRepository
import com.lttbdd.socialagent.data.repository.LinkedInNativePkceBlockedException
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext
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
 * - RFC 8252: LinkedIn mở trình duyệt hệ thống + redirect loopback; Facebook dùng Meta SDK (LoginManager).
 * - RFC 7636: Sinh code_verifier và code_challenge S256.
 * - RFC 6749: Trao đổi code đổi token; hạn token lấy từ nhà cung cấp.
 */
@HiltViewModel
class ChannelViewModel @Inject constructor(
    private val repository: ChannelRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow<ChannelUiState>(ChannelUiState.Loading)
    val uiState: StateFlow<ChannelUiState> = _uiState.asStateFlow()

    /** Quyền token đã được cấp thật, theo từng kênh. Rỗng/null nghĩa là chưa xác định được. */
    private var grantedScopes: Map<String, String?> = emptyMap()

    /** Danh sách kênh mới nhất từ repository (dùng khi UiState đang ở trạng thái khác Success). */
    private var knownChannels: List<ChannelAccount> = emptyList()

    private val _pagePicker = MutableStateFlow<FacebookPagePicker?>(null)

    /** Khác null khi cần người dùng chọn một trong nhiều Facebook Page. */
    val pagePicker: StateFlow<FacebookPagePicker?> = _pagePicker.asStateFlow()

    private val _notices = MutableSharedFlow<ChannelNotice>(extraBufferCapacity = 1)
    val notices: SharedFlow<ChannelNotice> = _notices.asSharedFlow()

    private val _browserRequests = MutableSharedFlow<String>(extraBufferCapacity = 1)

    /** URL cần mở bằng trình duyệt hệ thống (đăng nhập LinkedIn). */
    val browserRequests: SharedFlow<String> = _browserRequests.asSharedFlow()

    /** Phiên Facebook giữ tạm trong bộ nhớ trong lúc chờ chọn Page; xóa khi chọn xong hoặc hủy. */
    private var pendingFacebookUser: FacebookUserToken? = null

    private var linkedInLoginJob: Job? = null

    /** Cấu hình và nguồn redirect tách ra để unit test điều khiển được. */
    internal var linkedInNativePkceEnabled: Boolean = BuildConfig.LINKEDIN_NATIVE_PKCE_ENABLED
    internal var linkedInClientId: String = BuildConfig.LINKEDIN_CLIENT_ID.trim()
    internal var ioDispatcher: CoroutineDispatcher = Dispatchers.IO
    internal var openRedirectListener: () -> RedirectListener = { LoopbackRedirectServer.open() }

    /** Đồng hồ để xác định token hết hạn; tách ra để unit test điều khiển được thời gian. */
    internal var clock: () -> Long = { System.currentTimeMillis() }

    init {
        loadChannels()
    }

    companion object {
        /** Thời gian chờ người dùng hoàn tất đăng nhập LinkedIn trong trình duyệt. */
        const val LINKEDIN_LOGIN_TIMEOUT_MILLIS = 5 * 60 * 1000
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
        val id = BuildConfig.FACEBOOK_APP_ID
        return id.isBlank() || id.contains("SOCIAL_AGENT")
    }

    fun isLinkedInDemoMode(): Boolean {
        val id = linkedInClientId
        return id.isBlank() || id.contains("SOCIAL_AGENT")
    }

    fun connectFacebookDemo() {
        viewModelScope.launch {
            _uiState.value = ChannelUiState.Loading
            applyConnectResult(repository.connectFacebookPage("demo_code_fb_${clock()}", "", ""))
        }
    }

    fun connectLinkedInDemo() {
        viewModelScope.launch {
            _uiState.value = ChannelUiState.Loading
            applyConnectResult(repository.connectLinkedIn("demo_code_li_${clock()}", "", ""))
        }
    }

    /**
     * Bắt đầu đăng nhập LinkedIn bằng Native PKCE (RFC 8252 §7.3 + RFC 7636):
     * mở cổng loopback, phát URL cho Fragment mở bằng trình duyệt hệ thống, chờ redirect, kiểm `state`,
     * rồi đổi code kèm code_verifier. App chưa được LinkedIn cấp Native PKCE thì báo "blocked".
     */
    fun startLinkedInLogin() {
        if (!linkedInNativePkceEnabled) {
            _notices.tryEmit(ChannelNotice.LINKEDIN_PKCE_BLOCKED)
            return
        }
        linkedInLoginJob?.cancel()
        linkedInLoginJob = viewModelScope.launch {
            val listener = try {
                withContext(ioDispatcher) { openRedirectListener() }
            } catch (_: java.io.IOException) {
                _notices.tryEmit(ChannelNotice.LINKEDIN_LOGIN_FAILED)
                return@launch
            }
            // Huỷ coroutine (đăng nhập lại, ViewModel bị huỷ) thì đóng cổng để accept() đang chặn thoát ra.
            coroutineContext.job.invokeOnCompletion { listener.close() }
            val verifier = PkceUtil.generateCodeVerifier()
            val state = PkceUtil.generateState()
            val redirectUri = listener.redirectUri
            _browserRequests.emit(
                LinkedInNativePkce.authorizationUrl(
                    clientId = linkedInClientId,
                    redirectUri = redirectUri,
                    state = state,
                    codeChallenge = PkceUtil.generateCodeChallengeS256(verifier),
                ),
            )
            val callbackUrl = withContext(ioDispatcher) { listener.awaitRedirect(LINKEDIN_LOGIN_TIMEOUT_MILLIS) }
            listener.close()
            when (val result = LinkedInAuthRedirect.parse(callbackUrl, redirectUri, state)) {
                null -> _notices.tryEmit(ChannelNotice.LINKEDIN_LOGIN_TIMEOUT)
                LinkedInAuthRedirect.Result.Cancelled -> _notices.tryEmit(ChannelNotice.LINKEDIN_LOGIN_CANCELLED)
                is LinkedInAuthRedirect.Result.Failure -> {
                    if (result.reason == LinkedInAuthRedirect.FailureReason.STATE_MISMATCH) {
                        _uiState.value = ChannelUiState.Error(messageRes = R.string.channel_oauth_state_mismatch)
                    } else {
                        _notices.tryEmit(ChannelNotice.LINKEDIN_LOGIN_FAILED)
                    }
                }
                is LinkedInAuthRedirect.Result.Code -> {
                    _uiState.value = ChannelUiState.Loading
                    val connected = repository.connectLinkedIn(result.authCode, verifier, redirectUri)
                    if (connected.exceptionOrNull() is LinkedInNativePkceBlockedException) {
                        _notices.tryEmit(ChannelNotice.LINKEDIN_PKCE_BLOCKED)
                        _uiState.value = ChannelUiState.Success(channels = knownChannels)
                    } else {
                        applyConnectResult(connected)
                    }
                }
            }
        }
    }

    /** Người dùng bấm Hủy trong màn Facebook Login: không thay đổi gì, chỉ báo để Fragment hỏi đổi tài khoản. */
    fun onFacebookLoginCancelled() {
        _notices.tryEmit(ChannelNotice.FACEBOOK_LOGIN_CANCELLED)
    }

    fun onFacebookLoginFailed() {
        _notices.tryEmit(ChannelNotice.FACEBOOK_LOGIN_FAILED)
    }

    /**
     * Meta SDK đã trả phiên đăng nhập (token, hạn và quyền thật): lấy các Page người dùng quản lý.
     * - 0 Page: kết nối bằng hồ sơ cá nhân và báo [ChannelNotice.NO_FACEBOOK_PAGES].
     * - 1 Page: dùng luôn Page đó.
     * - Nhiều Page: phát [pagePicker] để người dùng chọn.
     */
    fun onFacebookLoggedIn(user: FacebookUserToken) {
        viewModelScope.launch {
            _uiState.value = ChannelUiState.Loading
            val pagesResult = repository.fetchFacebookPages(user.accessToken)
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
                    pendingFacebookUser = user
                    _uiState.value = ChannelUiState.Success(channels = knownChannels)
                    _pagePicker.value = FacebookPagePicker(pages)
                }
                pages.size == 1 -> applyConnectResult(repository.connectFacebookSelectedPage(user, pages.first()))
                else -> {
                    _notices.tryEmit(ChannelNotice.NO_FACEBOOK_PAGES)
                    applyConnectResult(repository.connectFacebookProfile(user))
                }
            }
        }
    }

    /** Người dùng chọn một Page trong hộp thoại: kết nối Page đó. */
    fun selectFacebookPage(page: FacebookPage) {
        val user = pendingFacebookUser ?: return
        pendingFacebookUser = null
        _pagePicker.value = null
        viewModelScope.launch {
            _uiState.value = ChannelUiState.Loading
            applyConnectResult(repository.connectFacebookSelectedPage(user, page))
        }
    }

    /** Người dùng đóng hộp thoại chọn Page mà không chọn: không kết nối gì. */
    fun cancelFacebookPagePicker() {
        pendingFacebookUser = null
        _pagePicker.value = null
    }

    override fun onCleared() {
        pendingFacebookUser = null
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
