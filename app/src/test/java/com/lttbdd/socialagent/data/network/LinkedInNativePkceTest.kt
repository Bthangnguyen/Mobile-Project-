package com.lttbdd.socialagent.data.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LinkedInNativePkceTest {

    @Test
    fun `URL dung endpoint native-pkce va du tham so PKCE`() {
        val url = LinkedInNativePkce.authorizationUrl(
            clientId = "abc",
            redirectUri = "http://127.0.0.1:5000/callback",
            state = "st",
            codeChallenge = "ch",
        )

        assertTrue(url.startsWith("https://www.linkedin.com/oauth/native-pkce/authorization?"))
        assertTrue(url.contains("response_type=code"))
        assertTrue(url.contains("client_id=abc"))
        assertTrue(url.contains("redirect_uri=http%3A%2F%2F127.0.0.1%3A5000%2Fcallback"))
        assertTrue(url.contains("code_challenge=ch"))
        assertTrue(url.contains("code_challenge_method=S256"))
        assertTrue(url.contains("state=st"))
    }

    @Test
    fun `scope xin dang bai va khong co client_secret`() {
        assertTrue(LinkedInNativePkce.SCOPE.contains("w_member_social"))
        assertFalse(LinkedInNativePkce.authorizationUrl("a", "r", "s", "c").contains("secret"))
        assertEquals("https://www.linkedin.com/oauth/v2/accessToken", LinkedInNativePkce.TOKEN_ENDPOINT)
    }
}
