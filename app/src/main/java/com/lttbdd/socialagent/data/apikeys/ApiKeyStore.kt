package com.lttbdd.socialagent.data.apikeys

import com.lttbdd.socialagent.core.model.LlmProvider
import javax.crypto.SecretKey

internal val supportedKeyProviders = listOf(LlmProvider.OPENAI, LlmProvider.GEMINI)

enum class KeyStorageStatus { NOT_CONFIGURED, CONFIGURED, UNREADABLE, UNAVAILABLE }

enum class KeyStorageFailure { INVALID_INPUT, UNSUPPORTED_PROVIDER, UNREADABLE, UNAVAILABLE }

/** Chỉ cung cấp mã lỗi an toàn, không giữ exception gốc có thể chứa key. */
class ApiKeyStorageException(val failure: KeyStorageFailure) : Exception(failure.name)

interface ApiKeyStore {
    suspend fun status(provider: LlmProvider): KeyStorageStatus
    suspend fun read(provider: LlmProvider): String?
    suspend fun save(provider: LlmProvider, key: String)
    suspend fun delete(provider: LlmProvider)
}

/** Storage chỉ nhận bản mã; các thao tác đồng bộ được store chạy trên IO. */
interface CiphertextStorage {
    fun read(provider: LlmProvider): ByteArray?
    fun write(provider: LlmProvider, record: ByteArray)
    fun delete(provider: LlmProvider)
}

interface ApiKeyCipher {
    fun encrypt(provider: LlmProvider, plaintext: ByteArray): ByteArray
    fun decrypt(provider: LlmProvider, record: ByteArray): ByteArray
    fun deleteKey(provider: LlmProvider)
}

/** Tách đọc và tạo khóa để giải mã tuyệt đối không tạo lại khóa bị mất. */
interface EncryptionKeyProvider {
    fun existing(provider: LlmProvider): SecretKey?
    fun create(provider: LlmProvider): SecretKey
    fun delete(provider: LlmProvider)
}

internal fun requireKeyProvider(provider: LlmProvider) {
    if (provider !in supportedKeyProviders) {
        throw ApiKeyStorageException(KeyStorageFailure.UNSUPPORTED_PROVIDER)
    }
}

internal fun isSafeApiKey(key: String): Boolean = key.length in 1..4096 && key.all { it.code in 33..126 }
