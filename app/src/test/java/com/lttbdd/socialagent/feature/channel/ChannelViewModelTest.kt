package com.lttbdd.socialagent.feature.channel

import com.lttbdd.socialagent.core.model.ChannelAccount
import com.lttbdd.socialagent.core.model.ChannelCapability
import com.lttbdd.socialagent.core.model.ChannelType
import com.lttbdd.socialagent.core.model.ConnectionStatus
import com.lttbdd.socialagent.data.repository.ChannelRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
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

    private class FakeChannelRepository : ChannelRepository {
        private val channels = MutableStateFlow<List<ChannelAccount>>(emptyList())

        override fun getConnectedChannels(): Flow<List<ChannelAccount>> = channels.asStateFlow()

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
