package com.lttbdd.socialagent.feature.channel

import com.lttbdd.socialagent.core.model.ChannelAccount
import com.lttbdd.socialagent.core.model.ChannelCapability
import com.lttbdd.socialagent.core.model.ChannelType
import com.lttbdd.socialagent.core.model.ConnectionStatus
import com.lttbdd.socialagent.data.model.FacebookPage
import com.lttbdd.socialagent.data.repository.ChannelRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
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
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `prepareFacebookOAuthUrl returns URL with S256 code_challenge per RFC 7636`() {
        val redirectUri = "com.lttbdd.socialagent://oauth/callback"
        val url = viewModel.prepareFacebookOAuthUrl(redirectUri)

        assertTrue("URL contains facebook oauth endpoint", url.contains("https://www.facebook.com/v20.0/dialog/oauth"))
        assertTrue("URL contains code_challenge_method=S256", url.contains("code_challenge_method=S256"))
        assertTrue("URL contains code_challenge", url.contains("code_challenge="))
    }

    @Test
    fun `prepareLinkedInOAuthUrl returns standard authorization URL per LinkedIn OAuth 2`() {
        val redirectUri = "com.lttbdd.socialagent://oauth/callback"
        val url = viewModel.prepareLinkedInOAuthUrl(redirectUri)

        assertTrue("URL contains linkedin oauth endpoint", url.contains("https://www.linkedin.com/oauth/v2/authorization"))
        assertTrue("URL contains response_type=code", url.contains("response_type=code"))
        assertTrue("URL contains client_id", url.contains("client_id="))
    }

    @Test
    fun `LinkedIn xin quyen dang bai w_member_social`() {
        val url = viewModel.prepareLinkedInOAuthUrl("com.lttbdd.socialagent://oauth/callback")

        assertTrue("URL xin w_member_social", url.contains("w_member_social"))
        assertTrue("Dialog va ViewModel dung chung scope", LinkedInAuthDialogFragment.SCOPE.contains("w_member_social"))
    }

    @Test
    fun `handleOAuthCallback connects channel successfully and updates UiState`() = runTest {
        viewModel.handleOAuthCallback(
            channelType = ChannelType.FACEBOOK,
            authCode = "code_fb_999",
            redirectUri = "com.lttbdd.socialagent://oauth/callback",
        )
        testDispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue("State is Success", state is ChannelUiState.Success)
        val successState = state as ChannelUiState.Success
        assertEquals(1, successState.channels.size)
        assertEquals(ChannelType.FACEBOOK, successState.channels[0].type)
    }

    @Test
    fun `getScopeForAccount tra ve quyen that, khong viet cung`() = runTest {
        viewModel.handleOAuthCallback(
            channelType = ChannelType.FACEBOOK,
            authCode = "code_fb_999",
            redirectUri = "com.lttbdd.socialagent://oauth/callback",
        )
        testDispatcher.scheduler.advanceUntilIdle()

        val facebook = (viewModel.uiState.value as ChannelUiState.Success).channels.first()
        assertEquals("public_profile", viewModel.getScopeForAccount(facebook))
    }

    private fun connectWithFacebookToken() {
        viewModel.handleOAuthCallback(
            channelType = ChannelType.FACEBOOK,
            authCode = "user_token_gia",
            redirectUri = ChannelRepository.FACEBOOK_SDK_REDIRECT,
        )
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
    fun `state khong khop thi bao loi bang chuoi da dich, khong hard-code`() = runTest {
        viewModel.prepareFacebookOAuthUrl("com.lttbdd.socialagent://oauth/callback")

        viewModel.handleOAuthCallback(
            channelType = ChannelType.FACEBOOK,
            authCode = "code_fb_999",
            state = "state_sai",
            redirectUri = "com.lttbdd.socialagent://oauth/callback",
        )

        val state = viewModel.uiState.value as ChannelUiState.Error
        assertNull(state.message)
        assertEquals(com.lttbdd.socialagent.R.string.channel_oauth_state_mismatch, state.messageRes)
    }

    @Test
    fun `ket noi thanh cong tra ve ten kenh de UI ghep chuoi da dich`() = runTest {
        fakeRepository.pages = listOf(FacebookPage("p1", "Page Mot", "t1"))

        connectWithFacebookToken()

        assertEquals("Page Mot", (viewModel.uiState.value as ChannelUiState.Success).connectedName)
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

        override suspend fun connectFacebookSelectedPage(
            userAccessToken: String,
            page: FacebookPage,
        ): Result<ChannelAccount> {
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
            if (type == ChannelType.FACEBOOK) "public_profile" else null

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
