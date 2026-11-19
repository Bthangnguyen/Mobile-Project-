package com.lttbdd.socialagent.data.apikeys

import com.lttbdd.socialagent.core.model.LlmProvider
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec

/** Bản ghi v1: phiên bản (1 byte), IV (12 byte), ciphertext kèm tag GCM (128 bit). */
class AesGcmApiKeyCipher(private val keys: EncryptionKeyProvider) : ApiKeyCipher {
    override fun encrypt(provider: LlmProvider, plaintext: ByteArray): ByteArray {
        requireKeyProvider(provider)
        val key = keys.existing(provider) ?: keys.create(provider)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        // Provider tạo IV mới bằng nguồn ngẫu nhiên an toàn cho mỗi lần mã hóa.
        cipher.init(Cipher.ENCRYPT_MODE, key)
        require(cipher.iv.size == IV_SIZE)
        cipher.updateAAD(aad(provider))
        return byteArrayOf(VERSION) + cipher.iv + cipher.doFinal(plaintext)
    }

    override fun decrypt(provider: LlmProvider, record: ByteArray): ByteArray {
        requireKeyProvider(provider)
        require(record.size in (1 + IV_SIZE + TAG_SIZE + 1)..MAX_RECORD_SIZE && record[0] == VERSION)
        val key = keys.existing(provider) ?: throw ApiKeyStorageException(KeyStorageFailure.UNREADABLE)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, record.copyOfRange(1, 1 + IV_SIZE)))
        // Ràng buộc provider và phiên bản, không thể đổi chỗ bản mã giữa hai nhà cung cấp.
        cipher.updateAAD(aad(provider))
        return cipher.doFinal(record, 1 + IV_SIZE, record.size - 1 - IV_SIZE)
    }

    override fun deleteKey(provider: LlmProvider) = keys.delete(provider)

    private fun aad(provider: LlmProvider) = "socialagent:llm-api-key:v1:${provider.name}".toByteArray(Charsets.UTF_8)

    companion object {
        private const val VERSION: Byte = 1
        private const val IV_SIZE = 12
        private const val TAG_SIZE = 16
        internal const val MAX_RECORD_SIZE = 8192
    }
}
