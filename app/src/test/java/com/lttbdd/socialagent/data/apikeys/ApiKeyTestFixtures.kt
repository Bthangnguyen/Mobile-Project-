package com.lttbdd.socialagent.data.apikeys

import com.lttbdd.socialagent.core.model.LlmProvider
import java.util.UUID
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

internal fun fakeKey(): String = UUID.randomUUID().toString()

internal class MemoryCiphertextStorage : CiphertextStorage {
    val records = mutableMapOf<LlmProvider, ByteArray>()
    var readError: Exception? = null
    var writeError: Exception? = null
    var deleteError: Exception? = null

    override fun read(provider: LlmProvider): ByteArray? {
        readError?.let { throw it }
        return records[provider]?.copyOf()
    }

    override fun write(provider: LlmProvider, record: ByteArray) {
        writeError?.let { throw it }
        records[provider] = record.copyOf()
    }

    override fun delete(provider: LlmProvider) {
        deleteError?.let { throw it }
        records.remove(provider)
    }
}

/** Chạy AES-GCM thật trên JVM, chỉ giả nơi quản lý khóa; không phải Android Keystore. */
internal class MemoryEncryptionKeys : EncryptionKeyProvider {
    val entries = mutableMapOf<LlmProvider, SecretKey>()
    var creates = 0

    override fun existing(provider: LlmProvider): SecretKey? = entries[provider]
    override fun create(provider: LlmProvider): SecretKey {
        creates++
        return KeyGenerator.getInstance("AES").apply { init(256) }.generateKey().also { entries[provider] = it }
    }
    override fun delete(provider: LlmProvider) { entries.remove(provider) }
}
