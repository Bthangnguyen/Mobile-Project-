package com.lttbdd.socialagent.feature.review

import androidx.lifecycle.SavedStateHandle
import com.lttbdd.socialagent.core.model.ChannelType
import com.lttbdd.socialagent.core.model.Draft
import com.lttbdd.socialagent.feature.review.hook.FakeOpeningLineSuggester
import com.lttbdd.socialagent.feature.review.hook.OpeningParseResult
import com.lttbdd.socialagent.feature.review.hook.OpeningSuggestionException
import com.lttbdd.socialagent.feature.review.hook.OpeningSuggestionUiState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Gợi ý 3 phương án câu mở đầu trên màn xem trước (F4, Issue #63). */
@OptIn(ExperimentalCoroutinesApi::class)
class DraftPreviewOpeningSuggestionTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var repository: RecordingDraftEditorRepository
    private lateinit var suggester: FakeOpeningLineSuggester

    private val facebookText = "Câu mở đầu cũ\n\nThân bài có emoji 🚀 và #HashtagViệt"

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        repository = RecordingDraftEditorRepository(
            listOf(
                Draft(id = "draft-fb", topicId = "t", channel = ChannelType.FACEBOOK, text = facebookText, approved = true),
            ),
        )
        suggester = FakeOpeningLineSuggester()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun TestScope.createViewModel(draftId: String = "draft-fb"): DraftPreviewViewModel {
        val viewModel = DraftPreviewViewModel(
            repository,
            suggester,
            SavedStateHandle(mapOf(DraftPreviewViewModel.ARG_DRAFT_ID to draftId)),
        )
        advanceUntilIdle()
        return viewModel
    }

    private fun DraftPreviewViewModel.ready() = openingSuggestion.value as OpeningSuggestionUiState.Ready

    @Test
    fun `suggest button is enabled only for a non-blank, unlocked draft`() = runTest {
        val viewModel = createViewModel()
        assertTrue(viewModel.uiState.value.canSuggestOpening)

        viewModel.onTextChanged("   ")
        assertFalse(viewModel.uiState.value.canSuggestOpening)

        repository.activePublishingDraftIds += "draft-fb"
        val locked = createViewModel()
        assertFalse(locked.uiState.value.canSuggestOpening)
        locked.onSuggestOpeningClicked()
        advanceUntilIdle()
        assertTrue(suggester.calls.isEmpty())
        assertEquals(OpeningSuggestionUiState.Hidden, locked.openingSuggestion.value)
    }

    @Test
    fun `click shows loading then exactly three options from one request`() = runTest {
        val viewModel = createViewModel()
        suggester.gate = CompletableDeferred()

        viewModel.onSuggestOpeningClicked()
        viewModel.onSuggestOpeningClicked()
        advanceUntilIdle()
        assertEquals(OpeningSuggestionUiState.Loading("Câu mở đầu cũ"), viewModel.openingSuggestion.value)

        suggester.gate?.complete(Unit)
        advanceUntilIdle()

        assertEquals(1, suggester.calls.size)
        val ready = viewModel.ready()
        assertEquals("Câu mở đầu cũ", ready.currentOpening)
        assertEquals(FakeOpeningLineSuggester.DEFAULT_OPTIONS, ready.options.map { it.text })
        assertEquals(
            FakeOpeningLineSuggester.DEFAULT_OPTIONS.map { DraftCharacterCounter.countCharacters(it) },
            ready.options.map { it.characterCount },
        )
        assertTrue(ready.options.none { it.exceedsChannelLimit })
    }

    @Test
    fun `request uses the real draft channel and the current editor text`() = runTest {
        val viewModel = createViewModel()
        viewModel.onChannelPreviewSelected(ChannelType.LINKEDIN)
        viewModel.onTextChanged("Câu đang sửa\nThân bài")

        viewModel.onSuggestOpeningClicked()
        advanceUntilIdle()

        val call = suggester.calls.single()
        assertEquals(ChannelType.FACEBOOK, call.channel)
        assertEquals("Câu đang sửa\nThân bài", call.draftText)
        assertEquals(emptyList<String>(), call.avoid)
    }

    @Test
    fun `regenerate sends previously shown options to avoid`() = runTest {
        val viewModel = createViewModel()
        viewModel.onSuggestOpeningClicked()
        advanceUntilIdle()

        val second = listOf("Câu bốn?", "Câu năm.", "Câu sáu!")
        suggester.results += Result.success(second)
        viewModel.onSuggestOpeningClicked()
        advanceUntilIdle()

        assertEquals(2, suggester.calls.size)
        assertEquals(FakeOpeningLineSuggester.DEFAULT_OPTIONS, suggester.calls[1].avoid)
        assertEquals(second, viewModel.ready().options.map { it.text })
    }

    @Test
    fun `failure shows error, keeps the editor text, and retry works`() = runTest {
        val viewModel = createViewModel()
        suggester.results += Result.failure(OpeningSuggestionException(OpeningParseResult.MalformedResponse))

        viewModel.onSuggestOpeningClicked()
        advanceUntilIdle()

        assertEquals(OpeningSuggestionUiState.Error("Câu mở đầu cũ"), viewModel.openingSuggestion.value)
        assertEquals(facebookText, viewModel.uiState.value.editedText)

        viewModel.onSuggestOpeningClicked()
        advanceUntilIdle()

        assertEquals(2, suggester.calls.size)
        assertEquals(3, viewModel.ready().options.size)
    }

    @Test
    fun `network style exceptions also map to error state`() = runTest {
        val viewModel = createViewModel()
        suggester.results += Result.failure(java.io.IOException("offline"))

        viewModel.onSuggestOpeningClicked()
        advanceUntilIdle()

        assertTrue(viewModel.openingSuggestion.value is OpeningSuggestionUiState.Error)
        assertEquals(facebookText, viewModel.uiState.value.editedText)
    }

    @Test
    fun `dismissing while loading cancels the request and ignores late results`() = runTest {
        val viewModel = createViewModel()
        val gate = CompletableDeferred<Unit>()
        suggester.gate = gate

        viewModel.onSuggestOpeningClicked()
        advanceUntilIdle()
        viewModel.onOpeningSuggestionDismissed()
        advanceUntilIdle()

        assertEquals(1, suggester.cancelledCalls)
        assertEquals(OpeningSuggestionUiState.Hidden, viewModel.openingSuggestion.value)

        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(OpeningSuggestionUiState.Hidden, viewModel.openingSuggestion.value)
        assertEquals(facebookText, viewModel.uiState.value.editedText)
    }

    @Test
    fun `selecting an option replaces only the opening and does not write to the database`() = runTest {
        val viewModel = createViewModel()
        viewModel.onSuggestOpeningClicked()
        advanceUntilIdle()

        viewModel.onOpeningSelected(1)
        advanceUntilIdle()

        val expected = "${FakeOpeningLineSuggester.DEFAULT_OPTIONS[1]}\n\nThân bài có emoji 🚀 và #HashtagViệt"
        val state = viewModel.uiState.value
        assertEquals(expected, state.editedText)
        assertTrue(state.hasUnsavedChanges)
        assertEquals(OpeningSuggestionUiState.Hidden, viewModel.openingSuggestion.value)
        assertEquals(DraftPreviewEvent.OpeningReplaced, viewModel.events.first())

        assertEquals(0, repository.saveCount)
        assertEquals(facebookText, repository.getDraft("draft-fb")?.text)
        assertTrue(repository.getDraft("draft-fb")?.approved == true)
    }

    @Test
    fun `saving after selecting goes through the normal save path and revokes approval`() = runTest {
        val viewModel = createViewModel()
        viewModel.onSuggestOpeningClicked()
        advanceUntilIdle()
        viewModel.onOpeningSelected(0)

        viewModel.onSaveClicked()
        advanceUntilIdle()

        val saved = repository.getDraft("draft-fb")
        assertEquals(1, repository.saveCount)
        assertTrue(saved?.text.orEmpty().startsWith(FakeOpeningLineSuggester.DEFAULT_OPTIONS[0]))
        assertFalse(saved?.approved ?: true)
    }

    @Test
    fun `undo restores the exact text before the replacement once`() = runTest {
        val viewModel = createViewModel()
        viewModel.onTextChanged("  Câu cũ  \nThân bài\n")
        viewModel.onSuggestOpeningClicked()
        advanceUntilIdle()
        viewModel.onOpeningSelected(2)

        viewModel.onUndoOpeningReplace()
        assertEquals("  Câu cũ  \nThân bài\n", viewModel.uiState.value.editedText)

        viewModel.onTextChanged("Sửa tiếp")
        viewModel.onUndoOpeningReplace()
        assertEquals("Sửa tiếp", viewModel.uiState.value.editedText)
    }

    @Test
    fun `undo is ignored when the user edited after the replacement`() = runTest {
        val viewModel = createViewModel()
        viewModel.onSuggestOpeningClicked()
        advanceUntilIdle()
        viewModel.onOpeningSelected(0)
        val edited = viewModel.uiState.value.editedText + " thêm chữ"
        viewModel.onTextChanged(edited)

        viewModel.onUndoOpeningReplace()

        assertEquals(edited, viewModel.uiState.value.editedText)
    }

    @Test
    fun `option that would exceed the channel limit is flagged and cannot be selected`() = runTest {
        // LinkedIn giới hạn 3.000 ký tự: câu mở đầu "Hi" (2 ký tự) + phần thân để tổng đúng 3.000.
        // Phương án dài hơn 2 ký tự sẽ làm bài vượt giới hạn; phương án đúng 2 ký tự thì không.
        val body = "\n" + "b".repeat(ChannelType.LINKEDIN.maxChars - 3)
        val linkedInText = "Hi$body"
        repository.store["draft-li"] = Draft(
            id = "draft-li",
            topicId = "t",
            channel = ChannelType.LINKEDIN,
            text = linkedInText,
        )
        suggester.results += Result.success(listOf("Hey", "Một câu mở đầu dài hơn nhiều so với hai ký tự", "Yo"))
        val viewModel = createViewModel("draft-li")

        viewModel.onSuggestOpeningClicked()
        advanceUntilIdle()
        assertEquals(listOf(true, true, false), viewModel.ready().options.map { it.exceedsChannelLimit })

        viewModel.onOpeningSelected(1)
        assertEquals(linkedInText, viewModel.uiState.value.editedText)
        assertTrue(viewModel.openingSuggestion.value is OpeningSuggestionUiState.Ready)

        viewModel.onOpeningSelected(2)
        assertEquals("Yo$body", viewModel.uiState.value.editedText)
    }

    @Test
    fun `selecting without suggestions or with a bad index does nothing`() = runTest {
        val viewModel = createViewModel()
        viewModel.onOpeningSelected(0)
        assertEquals(facebookText, viewModel.uiState.value.editedText)

        viewModel.onSuggestOpeningClicked()
        advanceUntilIdle()
        viewModel.onOpeningSelected(5)
        assertEquals(facebookText, viewModel.uiState.value.editedText)
        assertTrue(viewModel.openingSuggestion.value is OpeningSuggestionUiState.Ready)
    }

    /** Repository giả đếm số lần ghi để kiểm tra chọn phương án không ghi DB. */
    private class RecordingDraftEditorRepository(initialDrafts: List<Draft>) : DraftEditorRepository {
        val store = initialDrafts.associateBy { it.id }.toMutableMap()
        val activePublishingDraftIds = mutableSetOf<String>()
        var saveCount = 0
            private set

        override suspend fun getDraft(draftId: String): Draft? = store[draftId]

        override suspend fun hasActivePublishingPosts(draftId: String): Boolean = draftId in activePublishingDraftIds

        override suspend fun saveEditedDraft(draftId: String, newText: String): DraftSaveResult {
            saveCount++
            val current = store[draftId] ?: return DraftSaveResult.NotFound
            val updated = current.copy(text = newText, approved = false)
            store[draftId] = updated
            return DraftSaveResult.Saved(updated)
        }
    }
}
