package com.lttbdd.socialagent.data.network

import java.net.URLEncoder

/**
 * Tham số của luồng LinkedIn Native PKCE (public client, không có client_secret).
 * Nguồn: docs/research-social-api.md (quyết định 3, Luồng A), RFC 7636, RFC 8252.
 */
object LinkedInNativePkce {

    const val AUTHORIZATION_ENDPOINT = "https://www.linkedin.com/oauth/native-pkce/authorization"
    const val TOKEN_ENDPOINT = "https://www.linkedin.com/oauth/v2/accessToken"

    /** Quyền xin khi đăng nhập; scope thật nhận về nằm trong phản hồi token và được lưu theo token. */
    const val SCOPE = "openid profile w_member_social"

    fun authorizationUrl(
        clientId: String,
        redirectUri: String,
        state: String,
        codeChallenge: String,
    ): String = AUTHORIZATION_ENDPOINT + "?" + listOf(
        "response_type" to "code",
        "client_id" to clientId,
        "redirect_uri" to redirectUri,
        "scope" to SCOPE,
        "state" to state,
        "code_challenge" to codeChallenge,
        "code_challenge_method" to "S256",
    ).joinToString("&") { (k, v) -> "$k=${URLEncoder.encode(v, "UTF-8")}" }
}
