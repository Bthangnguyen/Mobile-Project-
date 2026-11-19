package com.lttbdd.socialagent.data.apikeys

import com.lttbdd.socialagent.core.model.LlmProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Không cache plaintext; mutex tuần tự hóa đọc/thay/xóa, kể cả khi nguồn key được router gọi. */
class EncryptedApiKeyStore(
    private val storage: CiphertextStorage,
    private val cipher: ApiKeyCipher,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ApiKeyStore {
    private val mutex = Mutex()

    override suspend fun status(provider: LlmProvider): KeyStorageStatus = try {
        locked(provider) {
            val record = readRecord(provider)
            if (record == null) KeyStorageStatus.NOT_CONFIGURED else {
                decrypt(provider, record).fill(0)
                KeyStorageStatus.CONFIGURED
            }
        }
    } catch (error: ApiKeyStorageException) {
        if (error.failure == KeyStorageFailure.UNREADABLE) KeyStorageStatus.UNREADABLE else KeyStorageStatus.UNAVAILABLE
    }

    override suspend fun read(provider: LlmProvider): String? = locked(provider) {
        val record = readRecord(provider) ?: return@locked null
        val plaintext = decrypt(provider, record)
        try {
            plaintext.toString(Charsets.UTF_8)
        } finally {
            plaintext.fill(0)
        }
    }

    override suspend fun save(provider: LlmProvider, key: String) = locked(provider) {
        if (!isSafeApiKey(key)) throw ApiKeyStorageException(KeyStorageFailure.INVALID_INPUT)
        // Không âm thầm thay dữ liệu hỏng hoặc tạo lại khóa bị mất: người dùng phải xóa trước.
        readRecord(provider)?.let { decrypt(provider, it).fill(0) }
        val plaintext = key.toByteArray(Charsets.UTF_8)
        try {
            storage.write(provider, cipher.encrypt(provider, plaintext))
        } finally {
            plaintext.fill(0)
        }
    }

    override suspend fun delete(provider: LlmProvider) = locked(provider) {
        // Chỉ xóa alias sau khi bản mã đã được xóa thành công.
        storage.delete(provider)
        cipher.deleteKey(provider)
    }

    private fun readRecord(provider: LlmProvider): ByteArray? = try {
        storage.read(provider)
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        throw ApiKeyStorageException(KeyStorageFailure.UNAVAILABLE)
    }

    private fun decrypt(provider: LlmProvider, record: ByteArray): ByteArray = try {
        cipher.decrypt(provider, record).also { plaintext ->
            if (plaintext.isEmpty() || plaintext.size > 4096 || plaintext.any { it.toInt() !in 33..126 }) {
                plaintext.fill(0)
                throw ApiKeyStorageException(KeyStorageFailure.UNREADABLE)
            }
        }
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        throw ApiKeyStorageException(KeyStorageFailure.UNREADABLE)
    }

    private suspend fun <T> locked(provider: LlmProvider, block: () -> T): T = withContext(ioDispatcher) {
        mutex.withLock {
            try {
                requireKeyProvider(provider)
                block()
            } catch (error: CancellationException) {
                throw error
            } catch (error: ApiKeyStorageException) {
                throw error
            } catch (_: Exception) {
                throw ApiKeyStorageException(KeyStorageFailure.UNAVAILABLE)
            }
        }
    }
}
