package com.lttbdd.socialagent.data.apikeys

import com.lttbdd.socialagent.core.model.LlmProvider
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class EncryptedApiKeyStoreTest {
    private val raw = MemoryCiphertextStorage()
    private val keys = MemoryEncryptionKeys()
    private val cipher = AesGcmApiKeyCipher(keys)

    @Test
    fun `save read replace and delete are independent for both providers`() = runTest {
        val store = EncryptedApiKeyStore(raw, cipher, StandardTestDispatcher(testScheduler))
        val openAi = fakeKey()
        val gemini = fakeKey()
        assertNull(store.read(LlmProvider.OPENAI))
        assertEquals(KeyStorageStatus.NOT_CONFIGURED, store.status(LlmProvider.GEMINI))
        store.save(LlmProvider.OPENAI, openAi)
        store.save(LlmProvider.GEMINI, gemini)
        assertEquals(openAi, store.read(LlmProvider.OPENAI))
        assertEquals(gemini, store.read(LlmProvider.GEMINI))
        val replacement = fakeKey()
        store.save(LlmProvider.OPENAI, replacement)
        assertEquals(replacement, store.read(LlmProvider.OPENAI))
        assertEquals(gemini, store.read(LlmProvider.GEMINI))
        store.delete(LlmProvider.OPENAI)
        assertNull(store.read(LlmProvider.OPENAI))
        assertEquals(KeyStorageStatus.NOT_CONFIGURED, store.status(LlmProvider.OPENAI))
        assertEquals(KeyStorageStatus.CONFIGURED, store.status(LlmProvider.GEMINI))
        assertNull(keys.existing(LlmProvider.OPENAI))
        store.delete(LlmProvider.OPENAI)
    }

    @Test
    fun `records never contain plaintext and repeated encryption uses fresh IV`() = runTest {
        val store = EncryptedApiKeyStore(raw, cipher, StandardTestDispatcher(testScheduler))
        val secret = fakeKey()
        store.save(LlmProvider.OPENAI, secret)
        val first = raw.records.getValue(LlmProvider.OPENAI).copyOf()
        assertFalse(first.toString(Charsets.ISO_8859_1).contains(secret))
        store.save(LlmProvider.OPENAI, secret)
        val second = raw.records.getValue(LlmProvider.OPENAI)
        assertFalse(first.contentEquals(second))
        assertFalse(first.copyOfRange(1, 13).contentEquals(second.copyOfRange(1, 13)))
        assertEquals(secret, store.read(LlmProvider.OPENAI))
    }

    @Test
    fun `tampered truncated unsupported-version and empty records are unreadable without replacement`() = runTest {
        val store = EncryptedApiKeyStore(raw, cipher, StandardTestDispatcher(testScheduler))
        store.save(LlmProvider.OPENAI, fakeKey())
        val original = raw.records.getValue(LlmProvider.OPENAI)
        val corrupt = original.copyOf().apply { this[lastIndex] = (this[lastIndex].toInt() xor 1).toByte() }
        for (record in listOf(corrupt, original.copyOf(5), original.copyOf().apply { this[0] = 2 }, byteArrayOf())) {
            raw.records[LlmProvider.OPENAI] = record
            assertEquals(KeyStorageStatus.UNREADABLE, store.status(LlmProvider.OPENAI))
            storageFailure(KeyStorageFailure.UNREADABLE) { store.read(LlmProvider.OPENAI) }
            storageFailure(KeyStorageFailure.UNREADABLE) { store.save(LlmProvider.OPENAI, fakeKey()) }
            assertArrayEquals(record, raw.records[LlmProvider.OPENAI])
        }
        assertEquals(1, keys.creates)
    }

    @Test
    fun `missing keystore alias is never recreated while old ciphertext exists`() = runTest {
        val store = EncryptedApiKeyStore(raw, cipher, StandardTestDispatcher(testScheduler))
        store.save(LlmProvider.OPENAI, fakeKey())
        val record = raw.records.getValue(LlmProvider.OPENAI).copyOf()
        keys.entries.clear()
        assertEquals(KeyStorageStatus.UNREADABLE, store.status(LlmProvider.OPENAI))
        storageFailure(KeyStorageFailure.UNREADABLE) { store.read(LlmProvider.OPENAI) }
        storageFailure(KeyStorageFailure.UNREADABLE) { store.save(LlmProvider.OPENAI, fakeKey()) }
        assertEquals(1, keys.creates)
        assertArrayEquals(record, raw.records[LlmProvider.OPENAI])
        store.delete(LlmProvider.OPENAI)
        val replacement = fakeKey()
        store.save(LlmProvider.OPENAI, replacement)
        assertEquals(replacement, store.read(LlmProvider.OPENAI))
        assertEquals(2, keys.creates)
    }

    @Test
    fun `provider AAD rejects swapped ciphertext even when encryption keys are the same`() = runTest {
        val store = EncryptedApiKeyStore(raw, cipher, StandardTestDispatcher(testScheduler))
        store.save(LlmProvider.OPENAI, fakeKey())
        keys.entries[LlmProvider.GEMINI] = keys.entries.getValue(LlmProvider.OPENAI)
        raw.records[LlmProvider.GEMINI] = raw.records.getValue(LlmProvider.OPENAI).copyOf()
        storageFailure(KeyStorageFailure.UNREADABLE) { store.read(LlmProvider.GEMINI) }
        assertEquals(KeyStorageStatus.CONFIGURED, store.status(LlmProvider.OPENAI))
    }

    @Test
    fun `storage failure is sanitized and failed replacement preserves old key`() = runTest {
        val store = EncryptedApiKeyStore(raw, cipher, StandardTestDispatcher(testScheduler))
        val secret = fakeKey()
        store.save(LlmProvider.OPENAI, secret)
        raw.writeError = IOException(secret)
        val error = storageFailure(KeyStorageFailure.UNAVAILABLE) { store.save(LlmProvider.OPENAI, fakeKey()) }
        assertFalse(error.toString().contains(secret))
        assertNull(error.cause)
        assertEquals(secret, store.read(LlmProvider.OPENAI))
        raw.readError = IOException(secret)
        assertEquals(KeyStorageStatus.UNAVAILABLE, store.status(LlmProvider.OPENAI))
        storageFailure(KeyStorageFailure.UNAVAILABLE) { store.read(LlmProvider.OPENAI) }
    }

    @Test
    fun `failed delete does not destroy encryption key or the other provider`() = runTest {
        val store = EncryptedApiKeyStore(raw, cipher, StandardTestDispatcher(testScheduler))
        val secret = fakeKey()
        store.save(LlmProvider.OPENAI, secret)
        raw.deleteError = IOException(fakeKey())
        storageFailure(KeyStorageFailure.UNAVAILABLE) { store.delete(LlmProvider.OPENAI) }
        assertEquals(secret, store.read(LlmProvider.OPENAI))
        assertNotNull(keys.existing(LlmProvider.OPENAI))
    }

    @Test
    fun `empty whitespace newline oversized and unsupported input are rejected before encryption`() = runTest {
        val store = EncryptedApiKeyStore(raw, cipher, StandardTestDispatcher(testScheduler))
        for (key in listOf("", " ", "a\nb", "a\rb", "a b", "a".repeat(4097))) {
            storageFailure(KeyStorageFailure.INVALID_INPUT) { store.save(LlmProvider.OPENAI, key) }
        }
        storageFailure(KeyStorageFailure.UNSUPPORTED_PROVIDER) { store.save(LlmProvider.ANTHROPIC, fakeKey()) }
        assertTrue(raw.records.isEmpty())
        assertEquals(0, keys.creates)
    }

    @Test
    fun `cancellation during storage or crypto remains cancellation and preserves ciphertext`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val store = EncryptedApiKeyStore(raw, cipher, dispatcher)
        store.save(LlmProvider.OPENAI, fakeKey())
        val original = raw.records.getValue(LlmProvider.OPENAI).copyOf()
        val cancellation = CancellationException("cancel")
        raw.readError = cancellation
        try {
            store.status(LlmProvider.OPENAI)
            throw AssertionError("Expected cancellation")
        } catch (_: CancellationException) {
            assertArrayEquals(original, raw.records[LlmProvider.OPENAI])
        }
        raw.readError = null
        val cancelledCipher = object : ApiKeyCipher by cipher {
            override fun decrypt(provider: LlmProvider, record: ByteArray): ByteArray = throw cancellation
        }
        try {
            EncryptedApiKeyStore(raw, cancelledCipher, dispatcher).read(LlmProvider.OPENAI)
            throw AssertionError("Expected cancellation")
        } catch (_: CancellationException) {
            assertArrayEquals(original, raw.records[LlmProvider.OPENAI])
        }
    }

    @Test
    fun `encryption failure never writes plaintext and exposes only a safe code`() = runTest {
        val secret = fakeKey()
        val failedCipher = object : ApiKeyCipher by cipher {
            override fun encrypt(provider: LlmProvider, plaintext: ByteArray): ByteArray = throw IllegalStateException(secret)
        }
        val store = EncryptedApiKeyStore(raw, failedCipher, StandardTestDispatcher(testScheduler))
        val error = storageFailure(KeyStorageFailure.UNAVAILABLE) { store.save(LlmProvider.OPENAI, secret) }
        assertFalse(error.toString().contains(secret))
        assertTrue(raw.records.isEmpty())
    }

    private suspend fun storageFailure(expected: KeyStorageFailure, block: suspend () -> Unit): ApiKeyStorageException {
        val error = try { block(); throw AssertionError("Expected safe storage error") } catch (error: ApiKeyStorageException) { error }
        assertEquals(expected, error.failure)
        assertNull(error.cause)
        return error
    }
}
