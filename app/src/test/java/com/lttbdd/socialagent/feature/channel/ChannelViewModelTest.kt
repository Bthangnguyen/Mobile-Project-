package com.lttbdd.socialagent.feature.channel

import com.lttbdd.socialagent.core.model.ChannelAccount
import com.lttbdd.socialagent.core.model.ChannelCapability
import com.lttbdd.socialagent.core.model.ChannelType
import com.lttbdd.socialagent.core.model.ConnectionStatus
import com.lttbdd.socialagent.data.model.FacebookPage
import com.lttbdd.socialagent.data.model.FacebookUserToken
import com.lttbdd.socialagent.data.network.RedirectListener
import com.lttbdd.socialagent.data.repository.LinkedInNativePkceBlockedException
import com.lttbdd.socialagent.data.repository.ChannelRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ChannelViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var fakeRepository: FakeChannelRepository
    private lateinit var viewModel: ChannelViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        fakeRepository = FakeChannelRepository()
        viewModel = ChannelViewModel(fakeRepository)
        viewModel.ioDispatcher = testDispatcher
        viewModel.linkedInNativePkceEnabled = true
        viewModel.linkedInClientId = "client_that"
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun user(expiresAt: Long = 9_000_000L, scope: String? = "public_profile,pages_show_list") =
        FacebookUserToken("user_token_gia", expiresAt, scope)

    @Test
    fun `getScopeForAccount tra ve quyen that cua token, khong viet cung`() = runTest {
        fakeRepository.pages = listOf(FacebookPage("p1", "Page Mot", "t1"))
        fakeRepository.scope = "pages_show_list"

        viewModel.onFacebookLoggedIn(user())
        testDispatcher.scheduler.advanceUntilIdle()

        val facebook = (viewModel.uiState.value as ChannelUiState.Success).channels.first()
        assertEquals("pages_show_list", viewModel.getScopeForAccount(facebook))
    }

    private fun connectWithFacebookToken() {
        viewModel.onFacebookLoggedIn(user())
        testDispatcher.scheduler.advanceUntilIdle()
    }

    @Test
    fun `nhieu Page thi hien hop thoai chon va chua ket noi cho den khi chon`() = runTest {
        fakeRepository.pages = listOf(FacebookPage("p1", "Page Mot", "t1"), FacebookPage("p2", "Page Hai", "t2"))

        connectWithFacebookToken()

        assertEquals(2, viewModel.pagePicker.value?.pages?.size)
        assertTrue((viewModel.uiState.value as ChannelUiState.Success).channels.isEmpty())

        viewModel.selectFacebookPage(fakeRepository.pages[1])
        testDispatcher.scheduler.advanceUntilIdle()

        assertNull(viewModel.pagePicker.value)
        val channels = (viewModel.uiState.value as ChannelUiState.Success).channels
        assertEquals(listOf("Page Hai"), channels.map { it.displayName })
        assertEquals("p2", channels.first().accountId)
    }

    @Test
    fun `mot Page thi tu dong ket noi Page do`() = runTest {
        fakeRepository.pages = listOf(FacebookPage("p1", "Page Duy Nhat", "t1"))

        connectWithFacebookToken()

        assertNull(viewModel.pagePicker.value)
        val channels = (viewModel.uiState.value as ChannelUiState.Success).channels
        assertEquals(listOf("Page Duy Nhat"), channels.map { it.displayName })
    }

    @Test
    fun `khong co Page thi ket noi bang ho so va phat thong bao`() = runTest {
        val notices = mutableListOf<ChannelNotice>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.notices.collect { notices.add(it) } }

        connectWithFacebookToken()

        assertEquals(listOf(ChannelNotice.NO_FACEBOOK_PAGES), notices)
        val channels = (viewModel.uiState.value as ChannelUiState.Success).channels
        assertEquals(listOf("Facebook Page Test"), channels.map { it.displayName })
    }

    @Test
    fun `huy hop thoai chon Page thi khong ket noi va xoa token tam`() = runTest {
        fakeRepository.pages = listOf(FacebookPage("p1", "A", "t1"), FacebookPage("p2", "B", "t2"))
        connectWithFacebookToken()
        assertNotNull(viewModel.pagePicker.value)

        viewModel.cancelFacebookPagePicker()
        viewModel.selectFacebookPage(fakeRepository.pages[0])
        testDispatcher.scheduler.advanceUntilIdle()

        assertNull(viewModel.pagePicker.value)
        assertTrue((viewModel.uiState.value as ChannelUiState.Success).channels.isEmpty())
    }

    @Test
    fun `loi tai danh sach Page thi bao loi va khong ket noi bang ho so`() = runTest {
        val notices = mutableListOf<ChannelNotice>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.notices.collect { notices.add(it) } }
        fakeRepository.pagesResult = Result.failure(java.io.IOException("mat mang"))

        connectWithFacebookToken()

        assertEquals(listOf(ChannelNotice.FACEBOOK_PAGES_LOAD_FAILED), notices)
        assertTrue((viewModel.uiState.value as ChannelUiState.Success).channels.isEmpty())
    }

    @Test
    fun `token qua han thi kenh hien TOKEN_EXPIRED`() = runTest {
        fakeRepository.pages = listOf(FacebookPage("p1", "Page Mot", "t1"))
        fakeRepository.expiresAt = 1_000L
        viewModel.clock = { 2_000L }

        connectWithFacebookToken()

        val channel = (viewModel.uiState.value as ChannelUiState.Success).channels.single()
        assertEquals(ConnectionStatus.TOKEN_EXPIRED, channel.status)
    }

    @Test
    fun `token con han thi kenh giu CONNECTED`() = runTest {
        fakeRepository.pages = listOf(FacebookPage("p1", "Page Mot", "t1"))
        fakeRepository.expiresAt = 5_000L
        viewModel.clock = { 2_000L }

        connectWithFacebookToken()

        val channel = (viewModel.uiState.value as ChannelUiState.Success).channels.single()
        assertEquals(ConnectionStatus.CONNECTED, channel.status)
    }

    @Test
    fun `khong doc duoc han token thi khong bao het han`() = runTest {
        fakeRepository.pages = listOf(FacebookPage("p1", "Page Mot", "t1"))
        fakeRepository.expiresAt = null
        viewModel.clock = { Long.MAX_VALUE }

        connectWithFacebookToken()

        val channel = (viewModel.uiState.value as ChannelUiState.Success).channels.single()
        assertEquals(ConnectionStatus.CONNECTED, channel.status)
    }

    @Test
    fun `ngay sau khi ket noi Page, getScopeForAccount da co quyen`() = runTest {
        fakeRepository.pages = listOf(FacebookPage("p1", "Page Mot", "t1"))

        connectWithFacebookToken()

        val channel = (viewModel.uiState.value as ChannelUiState.Success).channels.single()
        assertEquals("public_profile", viewModel.getScopeForAccount(channel))
    }

    @Test
    fun `ket noi thanh cong tra ve ten kenh de UI ghep chuoi da dich`() = runTest {
        fakeRepository.pages = listOf(FacebookPage("p1", "Page Mot", "t1"))

        connectWithFacebookToken()

        assertEquals("Page Mot", (viewModel.uiState.value as ChannelUiState.Success).connectedName)
    }

    // ---- Facebook: hạn và quyền thật từ Meta SDK ----

    @Test
    fun `Page duoc ket noi bang han va quyen that cua phien SDK`() = runTest {
        fakeRepository.pages = listOf(FacebookPage("p1", "Page Mot", "t1"))

        viewModel.onFacebookLoggedIn(user(expiresAt = 123_456L, scope = "pages_show_list,pages_manage_posts"))
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(123_456L, fakeRepository.lastUser?.expiresAtMillis)
        assertEquals("pages_show_list,pages_manage_posts", fakeRepository.lastUser?.grantedScope)
    }

    @Test
    fun `huy dang nhap Facebook phat thong bao huy`() = runTest {
        val notices = mutableListOf<ChannelNotice>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.notices.collect { notices.add(it) } }

        viewModel.onFacebookLoginCancelled()

        assertEquals(listOf(ChannelNotice.FACEBOOK_LOGIN_CANCELLED), notices)
    }

    // ---- LinkedIn Native PKCE ----

    private class FakeRedirectListener(private val respond: (redirectUri: String, state: String) -> String?) : RedirectListener {
        override val redirectUri = "http://127.0.0.1:50000/callback"
        var closed = false
        var authorizationUrl: String? = null

        override fun awaitRedirect(timeoutMillis: Int): String? {
            val state = Regex("state=([^&]+)").find(authorizationUrl.orEmpty())?.groupValues?.get(1).orEmpty()
            return respond(redirectUri, state)
        }

        override fun close() {
            closed = true
        }
    }

    /** Chạy startLinkedInLogin với listener giả; trả về (listener, các URL mở trình duyệt, thông báo). */
    private fun TestScope.runLinkedIn(
        listener: FakeRedirectListener,
    ): Pair<MutableList<String>, MutableList<ChannelNotice>> {
        val urls = mutableListOf<String>()
        val notices = mutableListOf<ChannelNotice>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.browserRequests.collect { listener.authorizationUrl = it; urls.add(it) }
        }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.notices.collect { notices.add(it) } }
        viewModel.openRedirectListener = { listener }
        viewModel.startLinkedInLogin()
        testDispatcher.scheduler.advanceUntilIdle()
        return urls to notices
    }

    @Test
    fun `LinkedIn chua duoc cap Native PKCE thi bao blocked va khong mo trinh duyet`() = runTest {
        viewModel.linkedInNativePkceEnabled = false
        val listener = FakeRedirectListener { _, _ -> null }

        val (urls, notices) = runLinkedIn(listener)

        assertTrue(urls.isEmpty())
        assertEquals(listOf(ChannelNotice.LINKEDIN_PKCE_BLOCKED), notices)
    }

    @Test
    fun `LinkedIn mo URL Native PKCE co S256 va khong co client_secret`() = runTest {
        val listener = FakeRedirectListener { _, _ -> null }

        val (urls, _) = runLinkedIn(listener)

        val url = urls.single()
        assertTrue(url.startsWith("https://www.linkedin.com/oauth/native-pkce/authorization?"))
        assertTrue(url.contains("code_challenge_method=S256"))
        assertTrue(url.contains("code_challenge="))
        assertTrue(url.contains("client_id=client_that"))
        assertTrue(!url.contains("secret"))
    }

    @Test
    fun `LinkedIn redirect hop le thi doi code kem verifier va redirect URI`() = runTest {
        val listener = FakeRedirectListener { redirect, state -> "$redirect?code=ma_code&state=$state" }

        runLinkedIn(listener)

        val sent = fakeRepository.lastLinkedIn!!
        assertEquals("ma_code", sent.first)
        assertTrue("verifier dai 43..128 ky tu", sent.second.length in 43..128)
        assertEquals(listener.redirectUri, sent.third)
        assertEquals("LinkedIn Org Test", (viewModel.uiState.value as ChannelUiState.Success).connectedName)
        assertTrue("cong loopback da dong", listener.closed)
    }

    @Test
    fun `LinkedIn state sai thi bao loi va khong doi code`() = runTest {
        val listener = FakeRedirectListener { redirect, _ -> "$redirect?code=ma_code&state=state_sai" }

        runLinkedIn(listener)

        assertNull(fakeRepository.lastLinkedIn)
        val state = viewModel.uiState.value as ChannelUiState.Error
        assertEquals(com.lttbdd.socialagent.R.string.channel_oauth_state_mismatch, state.messageRes)
    }

    @Test
    fun `LinkedIn nguoi dung huy thi bao huy va khong ket noi`() = runTest {
        val listener = FakeRedirectListener { redirect, state -> "$redirect?error=user_cancelled_login&state=$state" }

        val (_, notices) = runLinkedIn(listener)

        assertEquals(listOf(ChannelNotice.LINKEDIN_LOGIN_CANCELLED), notices)
        assertNull(fakeRepository.lastLinkedIn)
    }

    @Test
    fun `LinkedIn het thoi gian cho thi bao timeout`() = runTest {
        val listener = FakeRedirectListener { _, _ -> null }

        val (_, notices) = runLinkedIn(listener)

        assertEquals(listOf(ChannelNotice.LINKEDIN_LOGIN_TIMEOUT), notices)
        assertTrue(listener.closed)
    }

    @Test
    fun `LinkedIn tu choi Native PKCE luc doi code thi bao blocked`() = runTest {
        fakeRepository.linkedInResult = Result.failure(LinkedInNativePkceBlockedException())
        val listener = FakeRedirectListener { redirect, state -> "$redirect?code=ma_code&state=$state" }

        val (_, notices) = runLinkedIn(listener)

        assertEquals(listOf(ChannelNotice.LINKEDIN_PKCE_BLOCKED), notices)
        assertTrue(viewModel.uiState.value is ChannelUiState.Success)
    }

    private class FakeChannelRepository : ChannelRepository {
        private val channels = MutableStateFlow<List<ChannelAccount>>(emptyList())

        var pages: List<FacebookPage> = emptyList()

        /** Khác null thì [fetchFacebookPages] trả kết quả này thay cho [pages]. */
        var pagesResult: Result<List<FacebookPage>>? = null

        /** Thời điểm hết hạn token giả; null nghĩa là repository không đọc được. */
        var expiresAt: Long? = null

        override suspend fun getTokenExpiresAt(type: ChannelType, accountId: String): Long? = expiresAt

        override suspend fun fetchFacebookPages(userAccessToken: String): Result<List<FacebookPage>> =
            pagesResult ?: Result.success(pages)

        /** Phiên Facebook mà repository nhận lần cuối (để kiểm hạn/quyền thật được chuyển xuống). */
        var lastUser: FacebookUserToken? = null

        var scope: String? = "public_profile"

        var linkedInResult: Result<ChannelAccount>? = null
        var lastLinkedIn: Triple<String, String, String>? = null

        override suspend fun connectFacebookProfile(user: FacebookUserToken): Result<ChannelAccount> {
            lastUser = user
            return connectFacebookPage("", "", "")
        }

        override suspend fun connectFacebookSelectedPage(
            user: FacebookUserToken,
            page: FacebookPage,
        ): Result<ChannelAccount> {
            lastUser = user
            val account = ChannelAccount(
                type = ChannelType.FACEBOOK,
                accountId = page.id,
                displayName = page.name,
                status = ConnectionStatus.CONNECTED,
                capabilities = setOf(ChannelCapability.PUBLISH),
            )
            channels.value = channels.value + account
            return Result.success(account)
        }

        override fun getConnectedChannels(): Flow<List<ChannelAccount>> = channels.asStateFlow()

        override suspend fun getGrantedScope(type: ChannelType, accountId: String): String? =
            if (type == ChannelType.FACEBOOK) scope else null

        override suspend fun connectFacebookPage(
            authCode: String,
            codeVerifier: String,
            redirectUri: String,
        ): Result<ChannelAccount> {
            val account = ChannelAccount(
                type = ChannelType.FACEBOOK,
                accountId = "fb_101",
                displayName = "Facebook Page Test",
                status = ConnectionStatus.CONNECTED,
                capabilities = setOf(ChannelCapability.PUBLISH, ChannelCapability.READ_COMMENTS),
            )
            channels.value = channels.value + account
            return Result.success(account)
        }

        override suspend fun connectLinkedIn(
            authCode: String,
            codeVerifier: String,
            redirectUri: String,
        ): Result<ChannelAccount> {
            lastLinkedIn = Triple(authCode, codeVerifier, redirectUri)
            linkedInResult?.let { return it }
            val account = ChannelAccount(
                type = ChannelType.LINKEDIN,
                accountId = "li_202",
                displayName = "LinkedIn Org Test",
                status = ConnectionStatus.CONNECTED,
                capabilities = setOf(ChannelCapability.PUBLISH, ChannelCapability.READ_COMMENTS),
            )
            channels.value = channels.value + account
            return Result.success(account)
        }

        override suspend fun disconnectChannel(type: ChannelType, accountId: String): Result<Unit> {
            channels.value = channels.value.filterNot { it.type == type && it.accountId == accountId }
            return Result.success(Unit)
        }
    }
}
