package com.lttbdd.socialagent.feature.review

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lttbdd.socialagent.R
import com.lttbdd.socialagent.core.model.ChannelType
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
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
 * theo giới hạn [ChannelType.maxChars] (F4, Issue #34).
 */
@HiltViewModel
class DraftPreviewViewModel @Inject constructor(
    private val repository: DraftEditorRepository,
    private val savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val _uiState = MutableStateFlow(DraftPreviewUiState())
    val uiState: StateFlow<DraftPreviewUiState> = _uiState.asStateFlow()

    private val _events = Channel<DraftPreviewEvent>(Channel.BUFFERED)
    val events: Flow<DraftPreviewEvent> = _events.receiveAsFlow()

    init {
        loadDraft()
    }

    private fun loadDraft() {
        viewModelScope.launch {
            val draftId = savedStateHandle.get<String>(ARG_DRAFT_ID)
            val draft = if (!draftId.isNullOrBlank()) {
                repository.getDraft(draftId)
            } else {
                repository.getLatestDraft()
            }

            val restoredText = savedStateHandle.get<String>(KEY_EDITED_TEXT)
            val restoredChannel = savedStateHandle.get<String>(KEY_SELECTED_CHANNEL)
                ?.let { name -> ChannelType.entries.firstOrNull { it.name == name } }

            val activeChannel = restoredChannel ?: draft?.channel ?: ChannelType.FACEBOOK
            val activeText = restoredText ?: draft?.text.orEmpty()

            _uiState.value = buildState(
                isLoading = false,
                draft = draft,
                selectedChannel = activeChannel,
                editedText = activeText,
                showBlankError = false,
            )
        }
    }

    /** Cập nhật nội dung khi người dùng sửa tay và tính lại bộ đếm ký tự theo thời gian thực. */
    fun onTextChanged(newText: String) {
        _uiState.update { current ->
            if (newText == current.editedText) return@update current
            savedStateHandle[KEY_EDITED_TEXT] = newText
            buildState(
                isLoading = current.isLoading,
                draft = current.draft,
                selectedChannel = current.selectedChannel,
                editedText = newText,
                showBlankError = true,
            )
        }
    }

    /** Chuyển kênh xem trước (Facebook Page / LinkedIn) và tính lại giới hạn ký tự theo kênh. */
    fun onChannelPreviewSelected(channel: ChannelType) {
        _uiState.update { current ->
            if (channel == current.selectedChannel) return@update current
            savedStateHandle[KEY_SELECTED_CHANNEL] = channel.name
            buildState(
                isLoading = current.isLoading,
                draft = current.draft,
                selectedChannel = channel,
                editedText = current.editedText,
                showBlankError = current.textErrorRes == R.string.preview_error_empty,
            )
        }
    }

    /** Khôi phục nội dung về bản nháp đã lưu gần nhất. */
    fun onResetEdits() {
        _uiState.update { current ->
            val originalText = current.draft?.text.orEmpty()
            savedStateHandle[KEY_EDITED_TEXT] = originalText
            buildState(
                isLoading = current.isLoading,
                draft = current.draft,
                selectedChannel = current.selectedChannel,
                editedText = originalText,
                showBlankError = false,
            )
        }
    }

    /** Lưu nội dung đã chỉnh sửa xuống Room (chỉ cho phép khi không rỗng và không vượt giới hạn). */
    fun onSaveClicked() {
        val current = _uiState.value
        val draft = current.draft ?: return

        val count = DraftCharacterCounter.evaluate(current.editedText, current.selectedChannel)
        if (current.editedText.isBlank()) {
            _uiState.update { it.copy(textErrorRes = R.string.preview_error_empty, canSave = false) }
            return
        }
        if (count.isOverLimit) {
            _uiState.update { it.copy(textErrorRes = R.string.preview_error_over_limit, canSave = false) }
            return
        }

        viewModelScope.launch {
            val targetDraft = draft.copy(channel = current.selectedChannel)
            val result = repository.saveEditedDraft(targetDraft, current.editedText)
            if (result.isSuccess) {
                val saved = result.getOrThrow()
                savedStateHandle[KEY_EDITED_TEXT] = saved.text
                _uiState.value = buildState(
                    isLoading = false,
                    draft = saved,
                    selectedChannel = current.selectedChannel,
                    editedText = saved.text,
                    showBlankError = false,
                )
                _events.trySend(DraftPreviewEvent.ShowMessage(R.string.preview_save_success))
            } else {
                _events.trySend(DraftPreviewEvent.ShowMessage(R.string.preview_save_failed))
            }
        }
    }

    private fun buildState(
        isLoading: Boolean,
        draft: com.lttbdd.socialagent.core.model.Draft?,
        selectedChannel: ChannelType,
        editedText: String,
        showBlankError: Boolean,
    ): DraftPreviewUiState {
        val count = DraftCharacterCounter.evaluate(editedText, selectedChannel)
        val errorRes = when {
            count.isOverLimit -> R.string.preview_error_over_limit
            showBlankError && editedText.isBlank() -> R.string.preview_error_empty
            else -> null
        }
        val hasUnsavedChanges = draft != null && editedText != draft.text
        val canSave = draft != null && DraftCharacterCounter.canSave(editedText, selectedChannel)

        return DraftPreviewUiState(
            isLoading = isLoading,
            draft = draft,
            selectedChannel = selectedChannel,
            editedText = editedText,
            characterCount = count,
            hasUnsavedChanges = hasUnsavedChanges,
            canSave = canSave,
            textErrorRes = errorRes,
        )
    }

    companion object {
        const val ARG_DRAFT_ID = "draftId"
        private const val KEY_EDITED_TEXT = "preview_edited_text"
        private const val KEY_SELECTED_CHANNEL = "preview_selected_channel"
    }
}
