package com.lttbdd.socialagent.data.apikeys

import com.lttbdd.socialagent.core.model.LlmProvider

enum class VerificationStatus { VALID, INVALID, UNVERIFIED }

enum class VerificationReason { ACCEPTED, INPUT, AUTHENTICATION, PERMISSION, RATE_LIMIT, SERVICE, NETWORK, TIMEOUT, RESPONSE }

/** Chỉ metadata an toàn, không giữ request, key hoặc response body. */
data class KeyVerification(val status: VerificationStatus, val reason: VerificationReason)

fun interface ApiKeyVerifier {
    suspend fun verify(provider: LlmProvider, key: String): KeyVerification
}
