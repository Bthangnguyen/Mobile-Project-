package com.lttbdd.socialagent.data.apikeys

import com.lttbdd.socialagent.core.model.LlmProvider
import com.lttbdd.socialagent.core.model.LlmTask
import com.lttbdd.socialagent.data.ai.ApiKeySource
import com.lttbdd.socialagent.data.ai.FakeSseTransport
import com.lttbdd.socialagent.data.ai.GeminiClient
import com.lttbdd.socialagent.data.ai.InMemoryLlmConfiguration
import com.lttbdd.socialagent.data.ai.LlmFailure
import com.lttbdd.socialagent.data.ai.LlmModelConfiguration
import com.lttbdd.socialagent.data.ai.OpenAiClient
import com.lttbdd.socialagent.data.ai.RoutingLlmClient
import com.lttbdd.socialagent.data.ai.chatRequest
import com.lttbdd.socialagent.data.ai.expectFailure
import com.lttbdd.socialagent.data.ai.geminiFrames
import com.lttbdd.socialagent.data.ai.openAiFrames
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class SecureKeyRoutingTest {
    @Test
    fun `real adapters get fresh provider-specific secure keys and never send requests after deletion`() = runTest {
        val raw = MemoryCiphertextStorage()
        val store = EncryptedApiKeyStore(raw, AesGcmApiKeyCipher(MemoryEncryptionKeys()), StandardTestDispatcher(testScheduler))
        val settings = InMemoryLlmConfiguration()
        val transport = FakeSseTransport()
        val client = RoutingLlmClient(settings, ApiKeySource { store.read(it) }, setOf(OpenAiClient(transport), GeminiClient(transport)))
        val secrets = mapOf(LlmProvider.OPENAI to fakeKey(), LlmProvider.GEMINI to fakeKey())
        for ((provider, key) in secrets) store.save(provider, key)
        for (provider in secrets.keys) {
            settings.set(LlmTask.WRITE_POST, LlmModelConfiguration(provider, "selected-model"))
            transport.frames = if (provider == LlmProvider.OPENAI) openAiFrames else geminiFrames
            client.streamChat(chatRequest()).toList()
            val firstRequest = transport.requests.last()
            val auth = if (provider == LlmProvider.OPENAI) "Authorization" else "x-goog-api-key"
            val prefix = if (provider == LlmProvider.OPENAI) "Bearer " else ""
            assertEquals(prefix + secrets[provider], firstRequest.header(auth))
            val replacement = fakeKey()
            store.save(provider, replacement)
            client.streamChat(chatRequest()).toList()
            assertEquals(prefix + replacement, transport.requests.last().header(auth))
            val count = transport.requests.size
            store.delete(provider)
            expectFailure(LlmFailure.MISSING_API_KEY) { client.streamChat(chatRequest()).toList() }
            assertEquals(count, transport.requests.size)
        }
        assertTrue(raw.records.isEmpty())
    }

    @Test
    fun `missing or unreadable encrypted key prevents all provider requests without leaking secrets`() = runTest {
        val raw = MemoryCiphertextStorage()
        val keys = MemoryEncryptionKeys()
        val store = EncryptedApiKeyStore(raw, AesGcmApiKeyCipher(keys), StandardTestDispatcher(testScheduler))
        val settings = InMemoryLlmConfiguration().apply {
            set(LlmTask.WRITE_POST, LlmModelConfiguration(LlmProvider.OPENAI, "selected-model"))
        }
        val transport = FakeSseTransport(openAiFrames)
        val client = RoutingLlmClient(settings, ApiKeySource { store.read(it) }, setOf(OpenAiClient(transport)))
        expectFailure(LlmFailure.MISSING_API_KEY) { client.streamChat(chatRequest()).toList() }
        val secret = fakeKey()
        store.save(LlmProvider.OPENAI, secret)
        keys.entries.clear()
        val error = expectFailure(LlmFailure.MISSING_API_KEY) { client.streamChat(chatRequest()).toList() }
        assertFalse(error.toString().contains(secret))
        assertNull(error.cause)
        assertTrue(transport.requests.isEmpty())
    }
}
