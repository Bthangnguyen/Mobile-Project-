package com.lttbdd.socialagent.feature.apikeys

import com.lttbdd.socialagent.core.model.LlmProvider
import com.lttbdd.socialagent.data.apikeys.ApiKeyStorageException
import com.lttbdd.socialagent.data.apikeys.ApiKeyStore
import com.lttbdd.socialagent.data.apikeys.ApiKeyVerifier
import com.lttbdd.socialagent.data.apikeys.ApiKeysRepository
import com.lttbdd.socialagent.data.apikeys.KeyStorageFailure
import com.lttbdd.socialagent.data.apikeys.KeyStorageStatus
import com.lttbdd.socialagent.data.apikeys.KeyVerification
import com.lttbdd.socialagent.data.apikeys.VerificationReason
import com.lttbdd.socialagent.data.apikeys.VerificationStatus
import com.lttbdd.socialagent.data.apikeys.fakeKey
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ApiKeysViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val store = FakeStore()
    private var result = KeyVerification(VerificationStatus.VALID, VerificationReason.ACCEPTED)
    private var waitForVerification = false
    private var verificationError: Exception? = null
    private val verificationStarted = CompletableDeferred<Unit>()
    private var verificationCancelled = false
    private var verifiedProvider: LlmProvider? = null
    private val verifier = ApiKeyVerifier { provider, _ ->
        verifiedProvider = provider
        verificationStarted.complete(Unit)
        verificationError?.let { throw it }
        if (waitForVerification) {
            try { awaitCancellation() } finally { verificationCancelled = true }
        }
        result
    }
    private val viewModel = ApiKeysViewModel(ApiKeysRepository(store, verifier))

    @Before
    fun setUp() { Dispatchers.setMain(dispatcher) }

    @After
    fun tearDown() {
        viewModel.cancelOperation()
        Dispatchers.resetMain()
    }

    @Test
    fun `loads independent statuses and selecting another provider clears previous verification`() = runTest(dispatcher) {
        store.statuses[LlmProvider.GEMINI] = KeyStorageStatus.UNREADABLE
        viewModel.refresh()
        assertEquals(ApiKeyAction.LOAD, viewModel.uiState.value.action)
        advanceUntilIdle()
        assertEquals(KeyStorageStatus.NOT_CONFIGURED, viewModel.uiState.value.providers[LlmProvider.OPENAI])
        assertEquals(KeyStorageStatus.UNREADABLE, viewModel.uiState.value.providers[LlmProvider.GEMINI])
        viewModel.verify(fakeKey().toCharArray(), false)
        advanceUntilIdle()
        assertEquals(VerificationStatus.VALID, viewModel.uiState.value.verification?.status)
        viewModel.selectProvider(LlmProvider.GEMINI)
        assertEquals(LlmProvider.GEMINI, viewModel.uiState.value.selectedProvider)
        assertNull(viewModel.uiState.value.verification)
    }

    @Test
    fun `checking key never saves it or publishes plaintext to state and clears input`() = runTest(dispatcher) {
        val secret = fakeKey()
        val buffer = secret.toCharArray()
        viewModel.verify(buffer, false)
        assertEquals(ApiKeyAction.VERIFY, viewModel.uiState.value.action)
        assertFalse(viewModel.uiState.value.toString().contains(secret))
        advanceUntilIdle()
        assertTrue(buffer.all { it == '\u0000' })
        assertNull(viewModel.uiState.value.action)
        assertTrue(store.keys.isEmpty())
        assertFalse(viewModel.uiState.value.toString().contains(secret))
    }

    @Test
    fun `saving valid key updates only selected provider and deleting removes its configuration`() = runTest(dispatcher) {
        viewModel.selectProvider(LlmProvider.GEMINI)
        val secret = fakeKey()
        viewModel.verify(secret.toCharArray(), true)
        advanceUntilIdle()
        assertEquals(LlmProvider.GEMINI, verifiedProvider)
        assertEquals(secret, store.keys[LlmProvider.GEMINI])
        assertFalse(store.keys.containsKey(LlmProvider.OPENAI))
        assertEquals(ApiKeyMessage.SAVED, viewModel.uiState.value.message)
        assertEquals(KeyStorageStatus.CONFIGURED, viewModel.uiState.value.providers[LlmProvider.GEMINI])
        viewModel.delete()
        advanceUntilIdle()
        assertEquals(ApiKeyMessage.DELETED, viewModel.uiState.value.message)
        assertEquals(KeyStorageStatus.NOT_CONFIGURED, viewModel.uiState.value.providers[LlmProvider.GEMINI])
        assertTrue(store.keys.isEmpty())
    }

    @Test
    fun `invalid unverified or newly edited key cannot overwrite an existing valid key`() = runTest(dispatcher) {
        val existing = fakeKey()
        store.save(LlmProvider.OPENAI, existing)
        for (candidate in listOf(
            KeyVerification(VerificationStatus.INVALID, VerificationReason.AUTHENTICATION),
            KeyVerification(VerificationStatus.UNVERIFIED, VerificationReason.NETWORK),
            KeyVerification(VerificationStatus.UNVERIFIED, VerificationReason.RATE_LIMIT),
        )) {
            result = candidate
            val input = fakeKey().toCharArray()
            viewModel.verify(input, true)
            advanceUntilIdle()
            assertEquals(candidate, viewModel.uiState.value.verification)
            assertEquals(existing, store.keys[LlmProvider.OPENAI])
            assertNull(viewModel.uiState.value.message)
            assertTrue(input.all { it == '\u0000' })
        }
    }

    @Test
    fun `storage errors remain safe and corrupt key is not reported as saved`() = runTest(dispatcher) {
        store.saveError = ApiKeyStorageException(KeyStorageFailure.UNREADABLE)
        val input = fakeKey().toCharArray()
        viewModel.verify(input, true)
        advanceUntilIdle()
        assertEquals(ApiKeyMessage.UNREADABLE, viewModel.uiState.value.message)
        assertTrue(store.keys.isEmpty())
        assertTrue(input.all { it == '\u0000' })
    }

    @Test
    fun `unexpected exception with secret is reduced to a safe message`() = runTest(dispatcher) {
        val secret = fakeKey()
        verificationError = IllegalStateException(secret)
        val input = secret.toCharArray()
        viewModel.verify(input, true)
        advanceUntilIdle()
        assertEquals(ApiKeyMessage.OPERATION_ERROR, viewModel.uiState.value.message)
        assertFalse(viewModel.uiState.value.toString().contains(secret))
        assertTrue(input.all { it == '\u0000' })
    }

    @Test
    fun `cancelling in-flight verification clears input without saving or invalid result`() = runTest(dispatcher) {
        waitForVerification = true
        val input = fakeKey().toCharArray()
        viewModel.verify(input, true)
        runCurrent()
        assertTrue(verificationStarted.isCompleted)
        viewModel.cancelOperation()
        advanceUntilIdle()
        assertTrue(verificationCancelled)
        assertTrue(input.all { it == '\u0000' })
        assertTrue(store.keys.isEmpty())
        assertNull(viewModel.uiState.value.verification)
        assertNull(viewModel.uiState.value.message)
        assertNull(viewModel.uiState.value.action)
    }

    @Test
    fun `cancellation before coroutine starts also clears owned buffer`() = runTest(dispatcher) {
        val input = fakeKey().toCharArray()
        viewModel.verify(input, true)
        viewModel.cancelOperation()
        advanceUntilIdle()
        assertTrue(input.all { it == '\u0000' })
        assertFalse(verificationStarted.isCompleted)
    }

    @Test
    fun `busy operation rejects extra input and prevents provider changes`() = runTest(dispatcher) {
        waitForVerification = true
        viewModel.verify(fakeKey().toCharArray(), false)
        runCurrent()
        val extra = fakeKey().toCharArray()
        viewModel.verify(extra, true)
        viewModel.selectProvider(LlmProvider.GEMINI)
        viewModel.delete()
        assertTrue(extra.all { it == '\u0000' })
        assertEquals(LlmProvider.OPENAI, viewModel.uiState.value.selectedProvider)
        assertEquals(ApiKeyAction.VERIFY, viewModel.uiState.value.action)
        viewModel.cancelOperation()
        advanceUntilIdle()
    }

    @Test
    fun `old cancellation cleanup does not clear a newly started operation`() = runTest(dispatcher) {
        waitForVerification = true
        viewModel.verify(fakeKey().toCharArray(), false)
        runCurrent()
        viewModel.cancelOperation()
        viewModel.verify(fakeKey().toCharArray(), false)
        runCurrent()
        assertEquals(ApiKeyAction.VERIFY, viewModel.uiState.value.action)
        viewModel.cancelOperation()
        advanceUntilIdle()
    }

    private class FakeStore : ApiKeyStore {
        val keys = mutableMapOf<LlmProvider, String>()
        val statuses = mutableMapOf(LlmProvider.OPENAI to KeyStorageStatus.NOT_CONFIGURED, LlmProvider.GEMINI to KeyStorageStatus.NOT_CONFIGURED)
        var saveError: Exception? = null
        override suspend fun status(provider: LlmProvider) = statuses.getValue(provider)
        override suspend fun read(provider: LlmProvider) = keys[provider]
        override suspend fun save(provider: LlmProvider, key: String) {
            saveError?.let { throw it }
            keys[provider] = key
            statuses[provider] = KeyStorageStatus.CONFIGURED
        }
        override suspend fun delete(provider: LlmProvider) {
            keys.remove(provider)
            statuses[provider] = KeyStorageStatus.NOT_CONFIGURED
        }
    }
}
