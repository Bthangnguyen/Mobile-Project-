package com.lttbdd.socialagent.data.apikeys

import com.lttbdd.socialagent.core.model.LlmProvider
import javax.inject.Inject

class ApiKeysRepository @Inject constructor(
    private val store: ApiKeyStore,
    private val verifier: ApiKeyVerifier,
) {
    suspend fun statuses(): Map<LlmProvider, KeyStorageStatus> = supportedKeyProviders.associateWith { store.status(it) }

    /** Lưu cũng kiểm tra lại đúng đầu vào hiện tại, không tái dùng kết quả của key cũ. */
    suspend fun verify(provider: LlmProvider, key: String, save: Boolean): KeyVerification {
        val result = verifier.verify(provider, key)
        if (save && result.status == VerificationStatus.VALID) store.save(provider, key)
        return result
    }

    suspend fun delete(provider: LlmProvider) = store.delete(provider)
}
