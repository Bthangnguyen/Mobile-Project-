package com.lttbdd.socialagent.data.repository

import com.lttbdd.socialagent.core.model.ChannelCapability
import com.lttbdd.socialagent.core.model.ChannelType
import com.lttbdd.socialagent.core.model.ConnectionStatus
import com.lttbdd.socialagent.data.model.OAuthToken
import com.lttbdd.socialagent.data.security.TokenStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ChannelRepositoryTest {

    private lateinit var repository: ChannelRepositoryImpl

    @Before
    fun setUp() {
        val fakeTokenStore = object : TokenStore {
            private val storage = mutableMapOf<String, OAuthToken>()

            override suspend fun saveToken(channelType: ChannelType, accountId: String, token: OAuthToken) {
                storage["${channelType.name}_$accountId"] = token
            }

            override fun getToken(channelType: ChannelType, accountId: String): Flow<OAuthToken?> {
                return flowOf(storage["${channelType.name}_$accountId"])
            }

            override suspend fun removeToken(channelType: ChannelType, accountId: String) {
                storage.remove("${channelType.name}_$accountId")
            }
        }

        repository = ChannelRepositoryImpl(fakeTokenStore)
    }

    @Test
    fun `connectFacebookPage connects channel and stores token with computed expiresAt`() = runTest {
        val result = repository.connectFacebookPage(
            authCode = "code_fb_123",
            codeVerifier = "verifier_fb_456",
            redirectUri = "com.lttbdd.socialagent://oauth/callback",
        )

        assertTrue(result.isSuccess)
        val channel = result.getOrThrow()
        assertEquals(ChannelType.FACEBOOK, channel.type)
        assertEquals("fb_page_101", channel.accountId)
        assertEquals(ConnectionStatus.CONNECTED, channel.status)
        assertTrue(channel.capabilities.contains(ChannelCapability.PUBLISH))

        val connectedList = repository.getConnectedChannels().first()
        assertEquals(1, connectedList.size)
        assertEquals("fb_page_101", connectedList[0].accountId)
    }

    @Test
    fun `connectLinkedIn connects channel and stores token`() = runTest {
        val result = repository.connectLinkedIn(
            authCode = "code_li_123",
            codeVerifier = "verifier_li_456",
            redirectUri = "com.lttbdd.socialagent://oauth/callback",
        )

        assertTrue(result.isSuccess)
        val channel = result.getOrThrow()
        assertEquals(ChannelType.LINKEDIN, channel.type)
        assertEquals("li_org_202", channel.accountId)
        assertEquals(ConnectionStatus.CONNECTED, channel.status)

        val connectedList = repository.getConnectedChannels().first()
        assertEquals(1, connectedList.size)
    }

    @Test
    fun `disconnectChannel removes channel from list`() = runTest {
        repository.connectFacebookPage("code_fb", "verifier_fb", "uri")
        assertEquals(1, repository.getConnectedChannels().first().size)

        val disconnectResult = repository.disconnectChannel(ChannelType.FACEBOOK, "fb_page_101")
        assertTrue(disconnectResult.isSuccess)
        assertEquals(0, repository.getConnectedChannels().first().size)
    }

    @Test
    fun `parseFacebookProfile returns id and name for valid json`() {
        val json = """{"id":"1029384756","name":"Agent Fanpage"}"""
        val result = ChannelRepositoryImpl.parseFacebookProfile(json)

        assertEquals("1029384756" to "Agent Fanpage", result)
    }

    @Test
    fun `parseFacebookProfile returns null for empty string or invalid json`() {
        assertNull(ChannelRepositoryImpl.parseFacebookProfile(""))
        assertNull(ChannelRepositoryImpl.parseFacebookProfile("not_json"))
    }

    @Test
    fun `parseFacebookProfile returns null when id or name is missing or blank`() {
        assertNull(ChannelRepositoryImpl.parseFacebookProfile("""{"id":"123"}"""))
        assertNull(ChannelRepositoryImpl.parseFacebookProfile("""{"name":"Agent"}"""))
        assertNull(ChannelRepositoryImpl.parseFacebookProfile("""{"id":"","name":"Agent"}"""))
        assertNull(ChannelRepositoryImpl.parseFacebookProfile("""{"id":"123","name":" "}"""))
    }
}
