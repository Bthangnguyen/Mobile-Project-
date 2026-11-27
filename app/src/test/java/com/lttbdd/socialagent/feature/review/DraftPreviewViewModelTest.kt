package com.lttbdd.socialagent.feature.review

import androidx.lifecycle.SavedStateHandle
import com.lttbdd.socialagent.R
import com.lttbdd.socialagent.core.model.ChannelType
import com.lttbdd.socialagent.core.model.Draft
import com.lttbdd.socialagent.data.review.PrePublishWarningType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DraftPreviewViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var fakeRepository: FakeDraftEditorRepository

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        fakeRepository = FakeDraftEditorRepository(
            initialDrafts = listOf(
                Draft(
                    id = "draft-fb",
                    topicId = "topic-1",
                    channel = ChannelType.FACEBOOK,
                    text = "Bài nháp ban đầu cho Facebook",
                    approved = true,
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
        val noArgVm = DraftPreviewViewModel(fakeRepository, SavedStateHandle())
        advanceUntilIdle()
        assertTrue(noArgVm.uiState.value.isDraftNotFound)
        assertNull(noArgVm.uiState.value.draft)

        val unknownVm = DraftPreviewViewModel(
            fakeRepository,
            SavedStateHandle(mapOf(DraftPreviewViewModel.ARG_DRAFT_ID to "unknown-id")),
        )
        advanceUntilIdle()
        assertTrue(unknownVm.uiState.value.isDraftNotFound)
    }

    @Test
    fun `switching preview channel does not change draft channel or block valid Facebook save`() = runTest {
        val handle = SavedStateHandle(mapOf(DraftPreviewViewModel.ARG_DRAFT_ID to "draft-fb"))
        val viewModel = DraftPreviewViewModel(fakeRepository, handle)
        advanceUntilIdle()

        val longFacebookText = "a".repeat(3_500)
        viewModel.onTextChanged(longFacebookText)
        viewModel.onChannelPreviewSelected(ChannelType.LINKEDIN)

        val previewState = viewModel.uiState.value
        assertEquals(ChannelType.LINKEDIN, previewState.selectedChannel)
        // Bộ đếm xem trước theo LinkedIn báo vượt 500 ký tự
        assertTrue(previewState.characterCount.isOverLimit)
        assertEquals(500, previewState.characterCount.excessChars)
        // Nhưng vẫn cho phép lưu vì kênh thật của bản nháp là FACEBOOK (giới hạn 63.206)
        assertTrue(previewState.canSave)
        assertNull(previewState.textErrorRes)

        viewModel.onSaveClicked()
        advanceUntilIdle()

        val savedState = viewModel.uiState.value
        assertEquals(ChannelType.FACEBOOK, savedState.draft?.channel)
        assertEquals(longFacebookText, savedState.draft?.text)
        assertFalse(savedState.draft?.approved ?: true)
        assertEquals(
            DraftPreviewEvent.ShowMessage(R.string.preview_save_success),
            viewModel.events.first(),
        )
    }

    @Test
    fun `manual edit, reset, and save update state and revoke approval`() = runTest {
        val handle = SavedStateHandle(mapOf(DraftPreviewViewModel.ARG_DRAFT_ID to "draft-fb"))
        val viewModel = DraftPreviewViewModel(fakeRepository, handle)
        advanceUntilIdle()

        viewModel.onTextChanged("Nội dung sửa tay")
        assertTrue(viewModel.uiState.value.hasUnsavedChanges)
        assertTrue(viewModel.uiState.value.canSave)

        viewModel.onResetEdits()
        assertEquals("Bài nháp ban đầu cho Facebook", viewModel.uiState.value.editedText)
        assertFalse(viewModel.uiState.value.hasUnsavedChanges)

        viewModel.onTextChanged("Nội dung cuối cùng")
        viewModel.onSaveClicked()
        advanceUntilIdle()

        val savedState = viewModel.uiState.value
        assertFalse(savedState.hasUnsavedChanges)
        assertEquals("Nội dung cuối cùng", savedState.draft?.text)
        assertFalse(savedState.draft?.approved ?: true)
    }

    @Test
    fun `storage failure during save emits preview_save_failed without crashing`() = runTest {
        fakeRepository.failNextSaveWithStorageError = true
        val handle = SavedStateHandle(mapOf(DraftPreviewViewModel.ARG_DRAFT_ID to "draft-fb"))
        val viewModel = DraftPreviewViewModel(fakeRepository, handle)
        advanceUntilIdle()

        viewModel.onTextChanged("Nội dung mới")
        viewModel.onSaveClicked()
        advanceUntilIdle()

        assertEquals(
            DraftPreviewEvent.ShowMessage(R.string.preview_save_failed),
            viewModel.events.first(),
        )
    }

    @Test
    fun `draft with active publishing post locks editing`() = runTest {
        fakeRepository.activePublishingDraftIds.add("draft-fb")
        val handle = SavedStateHandle(mapOf(DraftPreviewViewModel.ARG_DRAFT_ID to "draft-fb"))
        val viewModel = DraftPreviewViewModel(fakeRepository, handle)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue(state.isEditingLockedByPublishing)
        assertFalse(state.canSave)

        viewModel.onTextChanged("Thử sửa khi đang khóa")
        assertEquals("Bài nháp ban đầu cho Facebook", viewModel.uiState.value.editedText)
    }

    @Test
    fun `SavedStateHandle restores normal text but skips oversized text to avoid TransactionTooLargeException`() = runTest {
        val handle = SavedStateHandle(mapOf(DraftPreviewViewModel.ARG_DRAFT_ID to "draft-fb"))
        val firstVm = DraftPreviewViewModel(fakeRepository, handle)
        advanceUntilIdle()

        firstVm.onTextChanged("Chưa kịp lưu")
        firstVm.onChannelPreviewSelected(ChannelType.LINKEDIN)

        val restoredVm = DraftPreviewViewModel(fakeRepository, handle)
        advanceUntilIdle()
        assertEquals("Chưa kịp lưu", restoredVm.uiState.value.editedText)
        assertEquals(ChannelType.LINKEDIN, restoredVm.uiState.value.selectedChannel)

        // Nội dung vượt ngưỡng MAX_SAVED_STATE_CHARS không bị đẩy vào SavedStateHandle
        val hugeText = "x".repeat(DraftPreviewViewModel.MAX_SAVED_STATE_CHARS + 500)
        restoredVm.onTextChanged(hugeText)
        val afterProcessDeathVm = DraftPreviewViewModel(fakeRepository, handle)
        advanceUntilIdle()
        assertEquals("Bài nháp ban đầu cho Facebook", afterProcessDeathVm.uiState.value.editedText)
    }

    @Test
    fun `prePublishWarnings updates in real time as user edits draft text`() = runTest {
        val handle = SavedStateHandle(mapOf(DraftPreviewViewModel.ARG_DRAFT_ID to "draft-fb"))
        val viewModel = DraftPreviewViewModel(fakeRepository, handle)
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.prePublishWarnings.isEmpty())

        viewModel.onTextChanged("Tăng 60% lượt xem, nhắn 0912345678")
        assertEquals(
            setOf(PrePublishWarningType.PERSONAL_INFO, PrePublishWarningType.UNSOURCED_STATISTIC),
            viewModel.uiState.value.prePublishWarnings.map { it.type }.toSet(),
        )

        viewModel.onTextChanged("Theo báo cáo Q3, lượt xem tăng 60%.")
        assertTrue(viewModel.uiState.value.prePublishWarnings.isEmpty())
    }

    private class FakeDraftEditorRepository(
        initialDrafts: List<Draft>,
    ) : DraftEditorRepository {
        private val store = initialDrafts.associateBy { it.id }.toMutableMap()
        val activePublishingDraftIds = mutableSetOf<String>()
        var failNextSaveWithStorageError = false

        override suspend fun getDraft(draftId: String): Draft? = store[draftId]

        override suspend fun hasActivePublishingPosts(draftId: String): Boolean =
            draftId in activePublishingDraftIds

        override suspend fun saveEditedDraft(draftId: String, newText: String): DraftSaveResult {
            if (failNextSaveWithStorageError) {
                failNextSaveWithStorageError = false
                return DraftSaveResult.StorageError(IllegalStateException("Simulated DB failure"))
            }
            val current = store[draftId] ?: return DraftSaveResult.NotFound
            if (draftId in activePublishingDraftIds) return DraftSaveResult.BlockedByActivePublish
            if (newText.isBlank()) return DraftSaveResult.BlockedBlankContent
            if (DraftCharacterCounter.evaluate(newText, current.channel).isOverLimit) {
                return DraftSaveResult.BlockedOverLimit
            }
            val updated = current.copy(text = newText, approved = false)
            store[draftId] = updated
            return DraftSaveResult.Saved(updated)
        }
    }
}
