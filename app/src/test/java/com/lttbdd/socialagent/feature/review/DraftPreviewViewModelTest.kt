package com.lttbdd.socialagent.feature.review

import androidx.lifecycle.SavedStateHandle
import com.lttbdd.socialagent.R
import com.lttbdd.socialagent.core.model.ChannelType
import com.lttbdd.socialagent.core.model.Draft
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
    fun `loads draft by id and computes initial character count`() = runTest {
        val handle = SavedStateHandle(mapOf(DraftPreviewViewModel.ARG_DRAFT_ID to "draft-fb"))
        val viewModel = DraftPreviewViewModel(fakeRepository, handle)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse(state.isLoading)
        assertEquals("draft-fb", state.draft?.id)
        assertEquals(ChannelType.FACEBOOK, state.selectedChannel)
        assertEquals("Bài nháp ban đầu cho Facebook", state.editedText)
        assertEquals(29, state.characterCount.currentLength)
        assertEquals(ChannelType.FACEBOOK.maxChars, state.characterCount.maxChars)
        assertFalse(state.hasUnsavedChanges)
        assertNull(state.textErrorRes)
    }

    @Test
    fun `switching channel recalculates character limit and flags over-limit text`() = runTest {
        val handle = SavedStateHandle(mapOf(DraftPreviewViewModel.ARG_DRAFT_ID to "draft-fb"))
        val viewModel = DraftPreviewViewModel(fakeRepository, handle)
        advanceUntilIdle()

        val longText = "a".repeat(3_500)
        viewModel.onTextChanged(longText)
        assertFalse(viewModel.uiState.value.characterCount.isOverLimit)
        assertTrue(viewModel.uiState.value.canSave)

        viewModel.onChannelPreviewSelected(ChannelType.LINKEDIN)
        val liState = viewModel.uiState.value
        assertEquals(ChannelType.LINKEDIN, liState.selectedChannel)
        assertEquals(ChannelType.LINKEDIN.maxChars, liState.characterCount.maxChars)
        assertTrue(liState.characterCount.isOverLimit)
        assertEquals(500, liState.characterCount.excessChars)
        assertEquals(R.string.preview_error_over_limit, liState.textErrorRes)
        assertFalse(liState.canSave)
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
        assertEquals(
            DraftPreviewEvent.ShowMessage(R.string.preview_save_success),
            viewModel.events.first(),
        )
    }

    @Test
    fun `clearing text shows empty error and disables save`() = runTest {
        val handle = SavedStateHandle(mapOf(DraftPreviewViewModel.ARG_DRAFT_ID to "draft-fb"))
        val viewModel = DraftPreviewViewModel(fakeRepository, handle)
        advanceUntilIdle()

        viewModel.onTextChanged("   ")
        assertEquals(R.string.preview_error_empty, viewModel.uiState.value.textErrorRes)
        assertFalse(viewModel.uiState.value.canSave)
    }

    @Test
    fun `SavedStateHandle restores editedText and selectedChannel after process death`() = runTest {
        val handle = SavedStateHandle(mapOf(DraftPreviewViewModel.ARG_DRAFT_ID to "draft-fb"))
        val firstVm = DraftPreviewViewModel(fakeRepository, handle)
        advanceUntilIdle()

        firstVm.onTextChanged("Chưa kịp lưu")
        firstVm.onChannelPreviewSelected(ChannelType.LINKEDIN)

        val restoredVm = DraftPreviewViewModel(fakeRepository, handle)
        advanceUntilIdle()

        val restoredState = restoredVm.uiState.value
        assertEquals("Chưa kịp lưu", restoredState.editedText)
        assertEquals(ChannelType.LINKEDIN, restoredState.selectedChannel)
        assertTrue(restoredState.hasUnsavedChanges)
    }

    private class FakeDraftEditorRepository(
        initialDrafts: List<Draft>,
    ) : DraftEditorRepository {
        private val store = initialDrafts.associateBy { it.id }.toMutableMap()

        override suspend fun getDraft(draftId: String): Draft? = store[draftId]

        override suspend fun getLatestDraft(): Draft? = store.values.firstOrNull()

        override suspend fun saveEditedDraft(draft: Draft, newText: String): Result<Draft> {
            if (newText.isBlank()) return Result.failure(IllegalArgumentException("blank"))
            if (DraftCharacterCounter.evaluate(newText, draft.channel).isOverLimit) {
                return Result.failure(IllegalArgumentException("over limit"))
            }
            val updated = draft.copy(text = newText, approved = false)
            store[updated.id] = updated
            return Result.success(updated)
        }
    }
}
