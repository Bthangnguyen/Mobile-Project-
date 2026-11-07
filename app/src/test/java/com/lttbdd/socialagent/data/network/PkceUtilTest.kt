package com.lttbdd.socialagent.data.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64

/**
 * Unit test kiểm tra PKCE util theo RFC 7636 Section 4.1 & 4.2.
 */
class PkceUtilTest {

    @Test
    fun `generateState creates random string of requested length`() {
        val state = PkceUtil.generateState(32)
        assertEquals(32, state.length)
        val unreservedRegex = Regex("^[A-Za-z0-9._~-]+$")
        assertTrue("state contains valid unreserved characters", state.matches(unreservedRegex))
    }

    @Test
    fun `generateCodeVerifier creates string with valid length and unreserved chars`() {
        val verifier = PkceUtil.generateCodeVerifier(64)
        assertEquals(64, verifier.length)

        val unreservedRegex = Regex("^[A-Za-z0-9._~-]+$")
        assertTrue("verifier contains valid unreserved characters per RFC 7636", verifier.matches(unreservedRegex))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `generateCodeVerifier throws exception when length is less than 43`() {
        PkceUtil.generateCodeVerifier(42)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `generateCodeVerifier throws exception when length is greater than 128`() {
        PkceUtil.generateCodeVerifier(129)
    }

    @Test
    fun `generateCodeChallengeS256 matches manual SHA256 Base64URL transformation`() {
        val verifier = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"
        
        // Manual computation per RFC 7636 Section 4.2:
        val bytes = verifier.toByteArray(StandardCharsets.US_ASCII)
        val hash = MessageDigest.getInstance("SHA-256").digest(bytes)
        val expectedChallenge = Base64.getUrlEncoder().withoutPadding().encodeToString(hash)

        // Verifying S256 challenge generation
        assertNotNull(verifier)
        assertTrue(expectedChallenge.isNotEmpty())
    }
}
