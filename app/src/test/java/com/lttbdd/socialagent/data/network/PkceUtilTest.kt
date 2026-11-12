package com.lttbdd.socialagent.data.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit test kiểm tra PKCE util theo RFC 7636 Section 4.1 & 4.2.
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
    fun `generateCodeChallengeS256 khop vector kiem thu RFC 7636 Phu luc B`() {
        // Vector chính thức của RFC 7636 Appendix B: gọi hàm thật, không tự tính lại ở trong test.
        val verifier = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"

        assertEquals(
            "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM",
            PkceUtil.generateCodeChallengeS256(verifier),
        )
    }

    @Test
    fun `generateCodeChallengeS256 khong co padding va chi dung ky tu base64url`() {
        val challenge = PkceUtil.generateCodeChallengeS256(PkceUtil.generateCodeVerifier(64))

        assertEquals(43, challenge.length) // SHA-256 = 32 byte -> 43 ký tự Base64URL không padding
        assertTrue(challenge.matches(Regex("^[A-Za-z0-9_-]+$")))
    }

    @Test
    fun `generateCodeChallengeS256 khac nhau voi verifier khac nhau`() {
        val a = PkceUtil.generateCodeChallengeS256("a".repeat(43))
        val b = PkceUtil.generateCodeChallengeS256("b".repeat(43))

        assertNotEquals(a, b)
    }
}
