package com.lttbdd.socialagent.data.apikeys

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.lttbdd.socialagent.core.model.LlmProvider
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Chỉ dùng alias/thư mục ngẫu nhiên cho test, không đụng key hay dữ liệu thật của người dùng. */
@RunWith(AndroidJUnit4::class)
class AndroidKeystoreApiKeyStoreTest {
    private lateinit var directory: File
    private lateinit var keys: AndroidKeystoreKeyProvider
    private lateinit var store: ApiKeyStore

    @Before
    fun setUp() {
        val id = UUID.randomUUID().toString()
        directory = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "byok-test-$id")
        keys = AndroidKeystoreKeyProvider("socialagent.test.$id.")
        store = EncryptedApiKeyStore(FileCiphertextStorage(directory), AesGcmApiKeyCipher(keys))
    }

    @After
    fun tearDown() {
        keys.delete(LlmProvider.OPENAI)
        keys.delete(LlmProvider.GEMINI)
        directory.deleteRecursively()
    }

    @Test
    fun realKeystoreRoundTripReopenReplaceAndDelete() = runBlocking {
        val openAi = UUID.randomUUID().toString()
        val gemini = UUID.randomUUID().toString()
        store.save(LlmProvider.OPENAI, openAi)
        store.save(LlmProvider.GEMINI, gemini)
        assertFalse(File(directory, "OPENAI.bin").readBytes().toString(Charsets.ISO_8859_1).contains(openAi))
        assertFalse(File(directory, "GEMINI.bin").readBytes().toString(Charsets.ISO_8859_1).contains(gemini))
        val reopened = EncryptedApiKeyStore(FileCiphertextStorage(directory), AesGcmApiKeyCipher(keys))
        assertEquals(openAi, reopened.read(LlmProvider.OPENAI))
        assertEquals(gemini, reopened.read(LlmProvider.GEMINI))
        val replacement = UUID.randomUUID().toString()
        reopened.save(LlmProvider.OPENAI, replacement)
        assertEquals(replacement, store.read(LlmProvider.OPENAI))
        reopened.delete(LlmProvider.OPENAI)
        assertNull(store.read(LlmProvider.OPENAI))
        assertEquals(KeyStorageStatus.NOT_CONFIGURED, store.status(LlmProvider.OPENAI))
        assertEquals(gemini, store.read(LlmProvider.GEMINI))
    }

    @Test
    fun missingAliasAndTamperingRequireExplicitDeletion() = runBlocking {
        store.save(LlmProvider.OPENAI, UUID.randomUUID().toString())
        val file = File(directory, "OPENAI.bin")
        val original = file.readBytes()
        keys.delete(LlmProvider.OPENAI)
        assertEquals(KeyStorageStatus.UNREADABLE, store.status(LlmProvider.OPENAI))
        try {
            store.save(LlmProvider.OPENAI, UUID.randomUUID().toString())
            throw AssertionError("Expected unreadable storage")
        } catch (error: ApiKeyStorageException) {
            assertEquals(KeyStorageFailure.UNREADABLE, error.failure)
        }
        assertArrayEquals(original, file.readBytes())
        assertNull(keys.existing(LlmProvider.OPENAI))
        store.delete(LlmProvider.OPENAI)
        store.save(LlmProvider.OPENAI, UUID.randomUUID().toString())
        file.writeBytes(file.readBytes().apply { this[lastIndex] = (this[lastIndex].toInt() xor 1).toByte() })
        assertEquals(KeyStorageStatus.UNREADABLE, store.status(LlmProvider.OPENAI))
    }
}
