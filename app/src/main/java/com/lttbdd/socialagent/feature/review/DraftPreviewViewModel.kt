package com.lttbdd.socialagent.feature.review

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lttbdd.socialagent.R
import com.lttbdd.socialagent.core.model.ChannelType
import com.lttbdd.socialagent.core.model.Draft
import com.lttbdd.socialagent.feature.review.hook.OpeningLine
import com.lttbdd.socialagent.feature.review.hook.OpeningLineSuggester
import com.lttbdd.socialagent.feature.review.hook.OpeningOptionUi
import com.lttbdd.socialagent.feature.review.hook.OpeningSuggestionUiState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ViewModel quản lý xem trước theo giao diện từng kênh, chỉnh sửa thủ công và đếm ký tự
 * theo giới hạn [ChannelType.maxChars] (F4, Issue #34), và gợi ý 3 phương án câu mở đầu (F4, Issue #63).
 */
@HiltViewModel
class DraftPreviewViewModel @Inject constructor(
    private val repository: DraftEditorRepository,
    private val openingLineSuggester: OpeningLineSuggester,
    private val savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val _uiState = MutableStateFlow(DraftPreviewUiState())
    val uiState: StateFlow<DraftPreviewUiState> = _uiState.asStateFlow()

    private val _openingSuggestion = MutableStateFlow<OpeningSuggestionUiState>(OpeningSuggestionUiState.Hidden)

    /** Trạng thái hộp chọn câu mở đầu (#63); giữ trong ViewModel nên xoay màn hình không gọi lại LLM. */
    val openingSuggestion: StateFlow<OpeningSuggestionUiState> = _openingSuggestion.asStateFlow()

    private val _events = Channel<DraftPreviewEvent>(Channel.BUFFERED)
    val events: Flow<DraftPreviewEvent> = _events.receiveAsFlow()

    private var openingJob: Job? = null

    /** Các phương án đã hiển thị, gửi kèm khi sinh lại để LLM đưa ra câu khác. */
    private var shownOpeningOptions: List<String> = emptyList()

    /** Nội dung trước và sau lần thay câu mở đầu gần nhất, dùng cho nút Hoàn tác. */
    private var openingUndo: OpeningUndo? = null

    private data class OpeningUndo(val textBefore: String, val textAfter: String)

    init {
        loadDraft()
    }

    private fun loadDraft() {
        viewModelScope.launch {
            val draftId = savedStateHandle.get<String>(ARG_DRAFT_ID)
            val draft = if (!draftId.isNullOrBlank()) {
                repository.getDraft(draftId)
            } else {
                null
            }

            if (draft == null) {
                _uiState.value = DraftPreviewUiState(
                    isLoading = false,
                    isDraftNotFound = true,
                )
                return@launch
            }

            val isLocked = repository.hasActivePublishingPosts(draft.id)
            val restoredText = savedStateHandle.get<String>(KEY_EDITED_TEXT)
            val restoredChannel = savedStateHandle.get<String>(KEY_SELECTED_CHANNEL)
                ?.let { name -> ChannelType.entries.firstOrNull { it.name == name } }

            val activeChannel = restoredChannel ?: draft.channel
            val activeText = if (isLocked) draft.text else (restoredText ?: draft.text)

            _uiState.value = buildState(
                isLoading = false,
                isEditingLockedByPublishing = isLocked,
                draft = draft,
                selectedChannel = activeChannel,
                editedText = activeText,
                showBlankError = false,
            )
        }
    }

    /** Cập nhật nội dung khi người dùng sửa tay và tính lại bộ đếm ký tự theo thời gian thực. */
    fun onTextChanged(newText: String) {
        var didUpdate = false
        _uiState.update { current ->
            if (current.isEditingLockedByPublishing || newText == current.editedText) {
                return@update current
            }
            didUpdate = true
            buildState(
                isLoading = current.isLoading,
                isEditingLockedByPublishing = current.isEditingLockedByPublishing,
                draft = current.draft,
                selectedChannel = current.selectedChannel,
                editedText = newText,
                showBlankError = true,
            )
        }
        if (didUpdate) {
            persistEditedTextSafely(newText)
        }
    }

    /**
     * Chuyển kênh xem trước (Facebook Page / LinkedIn): chỉ đổi giao diện xem trước và bộ đếm hiển thị,
     * KHÔNG làm thay đổi kênh thật (`draft.channel`) của bản nháp khi lưu.
     */
    fun onChannelPreviewSelected(channel: ChannelType) {
        var didUpdate = false
        _uiState.update { current ->
            if (channel == current.selectedChannel) return@update current
            didUpdate = true
            buildState(
                isLoading = current.isLoading,
                isEditingLockedByPublishing = current.isEditingLockedByPublishing,
                draft = current.draft,
                selectedChannel = channel,
                editedText = current.editedText,
                showBlankError = current.textErrorRes == R.string.preview_error_empty,
            )
        }
        if (didUpdate) {
            savedStateHandle[KEY_SELECTED_CHANNEL] = channel.name
        }
    }

    /** Khôi phục nội dung về bản nháp đã lưu gần nhất. */
    fun onResetEdits() {
        var restoredText: String? = null
        _uiState.update { current ->
            if (current.isEditingLockedByPublishing) return@update current
            val originalText = current.draft?.text.orEmpty()
            restoredText = originalText
            buildState(
                isLoading = current.isLoading,
                isEditingLockedByPublishing = current.isEditingLockedByPublishing,
                draft = current.draft,
                selectedChannel = current.selectedChannel,
                editedText = originalText,
                showBlankError = false,
            )
        }
        restoredText?.let { persistEditedTextSafely(it) }
    }

    /**
     * Lưu nội dung đã chỉnh sửa xuống Room theo kênh thật của bản nháp (`draft.channel`).
     */
    fun onSaveClicked() {
        val current = _uiState.value
        val draft = current.draft ?: return
        if (current.isEditingLockedByPublishing) {
            _events.trySend(DraftPreviewEvent.ShowMessage(R.string.preview_publishing_locked_warning))
            return
        }

        if (current.editedText.isBlank()) {
            _uiState.update { it.copy(textErrorRes = R.string.preview_error_empty, canSave = false) }
            return
        }
        if (DraftCharacterCounter.evaluate(current.editedText, draft.channel).isOverLimit) {
            _uiState.update { it.copy(textErrorRes = R.string.preview_error_over_limit, canSave = false) }
            return
        }

        viewModelScope.launch {
            when (val result = repository.saveEditedDraft(draft.id, current.editedText)) {
                is DraftSaveResult.Saved -> {
                    val saved = result.draft
                    persistEditedTextSafely(saved.text)
                    _uiState.value = buildState(
                        isLoading = false,
                        isEditingLockedByPublishing = false,
                        draft = saved,
                        selectedChannel = current.selectedChannel,
                        editedText = saved.text,
                        showBlankError = false,
                    )
                    _events.trySend(DraftPreviewEvent.ShowMessage(R.string.preview_save_success))
                }
                DraftSaveResult.BlockedByActivePublish -> {
                    _uiState.update {
                        buildState(
                            isLoading = false,
                            isEditingLockedByPublishing = true,
                            draft = it.draft,
                            selectedChannel = it.selectedChannel,
                            editedText = it.editedText,
                            showBlankError = false,
                        )
                    }
                    _events.trySend(DraftPreviewEvent.ShowMessage(R.string.preview_publishing_locked_warning))
                }
                DraftSaveResult.BlockedBlankContent -> {
                    _uiState.update { it.copy(textErrorRes = R.string.preview_error_empty, canSave = false) }
                }
                DraftSaveResult.BlockedOverLimit -> {
                    _uiState.update { it.copy(textErrorRes = R.string.preview_error_over_limit, canSave = false) }
                }
                DraftSaveResult.NotFound -> {
                    _uiState.value = DraftPreviewUiState(isLoading = false, isDraftNotFound = true)
                }
                is DraftSaveResult.StorageError -> {
                    _events.trySend(DraftPreviewEvent.ShowMessage(R.string.preview_save_failed))
                }
            }
        }
    }

    /**
     * Sinh 3 phương án câu mở đầu cho nội dung đang soạn (#63). Dùng cho cả nút Gợi ý, Thử lại
     * và Sinh 3 phương án khác. Mỗi lần gọi đúng một request LLM; đang có request chạy thì bỏ qua.
     * Prompt theo kênh thật của bản nháp (`draft.channel`), không theo tab xem trước.
     */
    fun onSuggestOpeningClicked() {
        val current = _uiState.value
        val draft = current.draft ?: return
        if (!current.canSuggestOpening || openingJob?.isActive == true) return

        val sourceText = current.editedText
        val currentOpening = OpeningLine.find(sourceText)
        val avoid = shownOpeningOptions
        _openingSuggestion.value = OpeningSuggestionUiState.Loading(currentOpening)
        openingJob = viewModelScope.launch {
            _openingSuggestion.value = try {
                val options = openingLineSuggester.suggest(sourceText, draft.channel, avoid)
                // Đã đóng hộp chọn trong lúc chờ thì không cập nhật state nữa.
                ensureActive()
                shownOpeningOptions = options
                OpeningSuggestionUiState.Ready(
                    currentOpening = currentOpening,
                    options = options.map { option -> toOptionUi(sourceText, draft, option) },
                )
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                // Không hiển thị chi tiết lỗi: thông điệp có thể chứa dữ liệu nhà cung cấp.
                OpeningSuggestionUiState.Error(currentOpening)
            }
        }
    }

    /**
     * Thay câu mở đầu trong ô soạn thảo bằng phương án thứ [index]. KHÔNG ghi DB, không duyệt, không đăng:
     * người dùng vẫn phải bấm Lưu, và lưu đi qua [DraftEditorRepository.saveEditedDraft] như sửa tay.
     */
    fun onOpeningSelected(index: Int) {
        val ready = _openingSuggestion.value as? OpeningSuggestionUiState.Ready ?: return
        val option = ready.options.getOrNull(index) ?: return
        val current = _uiState.value
        val draft = current.draft ?: return
        if (current.isEditingLockedByPublishing) return

        val newText = OpeningLine.replace(current.editedText, option.text)
        if (DraftCharacterCounter.evaluate(newText, draft.channel).isOverLimit) return

        openingUndo = OpeningUndo(textBefore = current.editedText, textAfter = newText)
        hideOpeningSuggestions()
        onTextChanged(newText)
        _events.trySend(DraftPreviewEvent.OpeningReplaced)
    }

    /**
     * Hoàn tác lần thay câu mở đầu gần nhất. Nếu người dùng đã sửa tiếp sau khi thay thì bỏ qua,
     * để không ghi đè phần vừa sửa.
     */
    fun onUndoOpeningReplace() {
        val undo = openingUndo ?: return
        openingUndo = null
        if (_uiState.value.editedText != undo.textAfter) return
        onTextChanged(undo.textBefore)
    }

    /** Đóng hộp chọn (Giữ nguyên, vuốt xuống, bấm ra ngoài): huỷ request LLM đang chạy nếu có. */
    fun onOpeningSuggestionDismissed() {
        openingJob?.cancel()
        openingJob = null
        hideOpeningSuggestions()
    }

    private fun hideOpeningSuggestions() {
        _openingSuggestion.value = OpeningSuggestionUiState.Hidden
        shownOpeningOptions = emptyList()
    }

    private fun toOptionUi(sourceText: String, draft: Draft, option: String): OpeningOptionUi =
        OpeningOptionUi(
            text = option,
            characterCount = DraftCharacterCounter.countCharacters(option),
            exceedsChannelLimit = DraftCharacterCounter
                .evaluate(OpeningLine.replace(sourceText, option), draft.channel)
                .isOverLimit,
        )

    /**
     * Giới hạn dung lượng lưu vào [SavedStateHandle] để tránh `TransactionTooLargeException`
     * khi người dùng soạn bài Facebook rất dài (tới 63.206 ký tự).
     */
    private fun persistEditedTextSafely(text: String) {
        if (text.length <= MAX_SAVED_STATE_CHARS) {
            savedStateHandle[KEY_EDITED_TEXT] = text
        } else {
            savedStateHandle.remove<String>(KEY_EDITED_TEXT)
        }
    }

    private fun buildState(
        isLoading: Boolean,
        isEditingLockedByPublishing: Boolean,
        draft: Draft?,
        selectedChannel: ChannelType,
        editedText: String,
        showBlankError: Boolean,
    ): DraftPreviewUiState {
        val previewCount = DraftCharacterCounter.evaluate(editedText, selectedChannel)
        val draftChannelOverLimit = draft != null &&
            DraftCharacterCounter.evaluate(editedText, draft.channel).isOverLimit
        val errorRes = when {
            draftChannelOverLimit -> R.string.preview_error_over_limit
            showBlankError && editedText.isBlank() -> R.string.preview_error_empty
            else -> null
        }
        val hasUnsavedChanges = draft != null && !isEditingLockedByPublishing && editedText != draft.text
        val canSave = draft != null &&
            !isEditingLockedByPublishing &&
            DraftCharacterCounter.canSave(editedText, draft.channel)

        return DraftPreviewUiState(
            isLoading = isLoading,
            isDraftNotFound = draft == null && !isLoading,
            isEditingLockedByPublishing = isEditingLockedByPublishing,
            draft = draft,
            selectedChannel = selectedChannel,
            editedText = editedText,
            characterCount = previewCount,
            hasUnsavedChanges = hasUnsavedChanges,
            canSave = canSave,
            textErrorRes = errorRes,
            canSuggestOpening = draft != null && !isEditingLockedByPublishing && editedText.isNotBlank(),
        )
    }

    companion object {
        const val ARG_DRAFT_ID = "draftId"
        const val MAX_SAVED_STATE_CHARS = 8_000
        private const val KEY_EDITED_TEXT = "preview_edited_text"
        private const val KEY_SELECTED_CHANNEL = "preview_selected_channel"
    }
}
