package com.lttbdd.socialagent.feature.review

import androidx.lifecycle.SavedStateHandle
import com.lttbdd.socialagent.R
import com.lttbdd.socialagent.core.model.ChannelType
import com.lttbdd.socialagent.core.model.Draft
import com.lttbdd.socialagent.data.review.DraftRestoreResult
import com.lttbdd.socialagent.data.review.DraftVersion
import com.lttbdd.socialagent.data.review.DraftVersionRepository
import com.lttbdd.socialagent.data.review.DraftVersionSource
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DraftHistoryViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var fakeRepository: FakeDraftVersionRepository

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        fakeRepository = FakeDraftVersionRepository(
            draft = Draft(
                id = "draft-1",
                topicId = "topic-1",
                channel = ChannelType.FACEBOOK,
                text = "Bản v2 hiện tại",
                approved = true,
            ),
            initialVersions = listOf(
                DraftVersion(
                    id = "v-2",
                    draftId = "draft-1",
                    versionNumber = 2,
                    text = "Bản v2 hiện tại",
                    source = DraftVersionSource.MANUAL_EDIT,
                    createdAtMillis = 2_000L,
                ),
                DraftVersion(
                    id = "v-1",
                    draftId = "draft-1",
                    versionNumber = 1,
                    text = "Bản v1 gốc",
                    source = DraftVersionSource.AI_GENERATED,
                    createdAtMillis = 1_000L,
                ),
            ),
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `missing or unknown draftId sets isDraftNotFound true`() = runTest {
        val noArgVm = DraftHistoryViewModel(fakeRepository, SavedStateHandle())
        advanceUntilIdle()
        assertTrue(noArgVm.uiState.value.isDraftNotFound)

        val unknownVm = DraftHistoryViewModel(
            fakeRepository,
            SavedStateHandle(mapOf(DraftHistoryViewModel.ARG_DRAFT_ID to "unknown")),
        )
        advanceUntilIdle()
        assertTrue(unknownVm.uiState.value.isDraftNotFound)
    }

    @Test
    fun `loads versions, marks current version, and restores older version after confirmation`() = runTest {
        val vm = DraftHistoryViewModel(
            fakeRepository,
            SavedStateHandle(mapOf(DraftHistoryViewModel.ARG_DRAFT_ID to "draft-1")),
        )
        advanceUntilIdle()

        val initialState = vm.uiState.value
        assertFalse(initialState.isLoading)
        assertEquals(2, initialState.versions.size)
        assertTrue(initialState.versions[0].isCurrent)
        assertFalse(initialState.versions[0].canRestore)
        assertFalse(initialState.versions[1].isCurrent)
        assertTrue(initialState.versions[1].canRestore)

        val olderVersion = initialState.versions[1].version
        vm.onRestoreRequested(olderVersion)
        assertEquals(DraftHistoryEvent.ConfirmRestore(olderVersion), vm.events.first())

        vm.onRestoreConfirmed(olderVersion.id)
        advanceUntilIdle()

        val emittedEvents = vm.events.take(2).toList()
        assertEquals(
            listOf(
                DraftHistoryEvent.RestoredSuccessfully,
                DraftHistoryEvent.ShowMessage(R.string.history_restore_success),
            ),
            emittedEvents,
        )
        assertEquals("Bản v1 gốc", fakeRepository.currentDraft?.text)
        assertFalse(fakeRepository.currentDraft?.approved ?: true)
    }

    @Test
    fun `locked draft prevents restore and emits warning`() = runTest {
        fakeRepository.isLocked = true
        val vm = DraftHistoryViewModel(
            fakeRepository,
            SavedStateHandle(mapOf(DraftHistoryViewModel.ARG_DRAFT_ID to "draft-1")),
        )
        advanceUntilIdle()

        val state = vm.uiState.value
        assertTrue(state.isRestoreLockedByPublishing)
        assertTrue(state.versions.none { it.canRestore })

        vm.onRestoreRequested(state.versions[1].version)
        assertEquals(
            DraftHistoryEvent.ShowMessage(R.string.history_publishing_locked_warning),
            vm.events.first(),
        )
    }

    @Test
    fun `storage failure during restore emits history_restore_failed`() = runTest {
        fakeRepository.failNextRestore = true
        val vm = DraftHistoryViewModel(
            fakeRepository,
            SavedStateHandle(mapOf(DraftHistoryViewModel.ARG_DRAFT_ID to "draft-1")),
        )
        advanceUntilIdle()

        vm.onRestoreConfirmed("v-1")
        advanceUntilIdle()

        assertEquals(
            DraftHistoryEvent.ShowMessage(R.string.history_restore_failed),
            vm.events.first(),
        )
        assertFalse(vm.uiState.value.isRestoring)
    }

    @Test
    fun `initial load failure sets isLoadError and emits history_load_failed`() = runTest {
        fakeRepository.failOnLoad = true
        val vm = DraftHistoryViewModel(
            fakeRepository,
            SavedStateHandle(mapOf(DraftHistoryViewModel.ARG_DRAFT_ID to "draft-1")),
        )
        advanceUntilIdle()

        assertFalse(vm.uiState.value.isLoading)
        assertTrue(vm.uiState.value.isLoadError)
        assertEquals(
            DraftHistoryEvent.ShowMessage(R.string.history_load_failed),
            vm.events.first(),
        )
    }

    @Test
    fun `isRestoring disables buttons and ignores duplicate restore calls while in progress`() = runTest {
        val gate = CompletableDeferred<Unit>()
        fakeRepository.restoreGate = gate
        val vm = DraftHistoryViewModel(
            fakeRepository,
            SavedStateHandle(mapOf(DraftHistoryViewModel.ARG_DRAFT_ID to "draft-1")),
        )
        advanceUntilIdle()

        vm.onRestoreConfirmed("v-1")
        runCurrent()

        assertTrue(vm.uiState.value.isRestoring)
        assertTrue(vm.uiState.value.versions.none { it.canRestore })

        // Gửi thêm yêu cầu trùng khi đang khôi phục sẽ bị bỏ qua
        vm.onRestoreConfirmed("v-1")
        runCurrent()
        assertEquals(1, fakeRepository.restoreCalls)

        gate.complete(Unit)
        advanceUntilIdle()
        assertFalse(vm.uiState.value.isRestoring)
    }

    @Test
    fun `restore without history still signals restored and shows partial warning`() = runTest {
        fakeRepository.returnWithoutHistory = true
        val vm = DraftHistoryViewModel(
            fakeRepository,
            SavedStateHandle(mapOf(DraftHistoryViewModel.ARG_DRAFT_ID to "draft-1")),
        )
        advanceUntilIdle()

        vm.onRestoreConfirmed("v-1")
        advanceUntilIdle()

        assertEquals(
            listOf(
                DraftHistoryEvent.RestoredSuccessfully,
                DraftHistoryEvent.ShowMessage(R.string.history_restore_saved_without_history),
            ),
            vm.events.take(2).toList(),
        )
        assertFalse(vm.uiState.value.isRestoring)
    }

    private class FakeDraftVersionRepository(
        draft: Draft?,
        initialVersions: List<DraftVersion>,
    ) : DraftVersionRepository {
        var currentDraft: Draft? = draft
        var isLocked: Boolean = false
        var failNextRestore: Boolean = false
        var failOnLoad: Boolean = false
        var returnWithoutHistory: Boolean = false
        var restoreGate: CompletableDeferred<Unit>? = null
        var restoreCalls: Int = 0
        private val versionsFlow = MutableStateFlow(initialVersions)

        override fun observeVersions(draftId: String): Flow<List<DraftVersion>> = versionsFlow

        override suspend fun getDraft(draftId: String): Draft? {
            if (failOnLoad) throw IllegalStateException("Simulated load error")
            return currentDraft?.takeIf { it.id == draftId }
        }

        override suspend fun hasActivePublishingPosts(draftId: String): Boolean = isLocked

        override suspend fun restoreVersion(draftId: String, versionId: String): DraftRestoreResult {
            restoreCalls++
            restoreGate?.await()
            if (failNextRestore) {
                failNextRestore = false
                return DraftRestoreResult.StorageError(IllegalStateException("Simulated error"))
            }
            val draft = getDraft(draftId) ?: return DraftRestoreResult.DraftNotFound
            if (isLocked) return DraftRestoreResult.BlockedByActivePublish
            val target = versionsFlow.value.firstOrNull { it.id == versionId }
                ?: return DraftRestoreResult.VersionNotFound
            val updatedDraft = draft.copy(text = target.text, approved = false)
            currentDraft = updatedDraft
            if (returnWithoutHistory) {
                returnWithoutHistory = false
                return DraftRestoreResult.RestoredWithoutHistory(
                    updatedDraft,
                    IllegalStateException("Simulated history write error"),
                )
            }
            val newVersion = DraftVersion(
                id = "v-restored",
                draftId = draftId,
                versionNumber = (versionsFlow.value.maxOfOrNull { it.versionNumber } ?: 0) + 1,
                text = target.text,
                source = DraftVersionSource.RESTORED,
                createdAtMillis = 3_000L,
            )
            versionsFlow.value = listOf(newVersion) + versionsFlow.value
            return DraftRestoreResult.Restored(updatedDraft, newVersion)
        }
    }
}
