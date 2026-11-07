package com.lttbdd.socialagent.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit test kiểm tra tính toán expiresAt theo RFC 6749 Section 5.1 & 4.2.2.
 */
class OAuthTokenTest {

    @Test
    fun `expiresAt is computed correctly from expiresInSeconds`() {
        val currentTime = 1_000_000L
        val expiresInSeconds = 3600L // 1 hour

        val token = OAuthToken.createFromExpiresIn(
            accessToken = "token_abc_123",
            expiresInSeconds = expiresInSeconds,
            scope = "public_profile",
            currentTimeMillis = currentTime,
        )

        val expectedExpiresAt = currentTime + (expiresInSeconds * 1000L)
        assertEquals(expectedExpiresAt, token.expiresAt)
        assertEquals("token_abc_123", token.accessToken)
        assertEquals("public_profile", token.scope)
    }

    @Test
    fun `isExpired returns true when current time exceeds expiresAt`() {
        val currentTime = 1_000_000L
        val token = OAuthToken(
            accessToken = "token_abc",
            expiresAt = currentTime + 5000L,
        )

        assertFalse(token.isExpired(currentTime + 1000L))
        assertTrue(token.isExpired(currentTime + 5000L))
        assertTrue(token.isExpired(currentTime + 10000L))
    }
}
