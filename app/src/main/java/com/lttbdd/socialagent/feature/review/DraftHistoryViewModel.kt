package com.lttbdd.socialagent.feature.review

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lttbdd.socialagent.R
import com.lttbdd.socialagent.core.model.Draft
import com.lttbdd.socialagent.data.review.DraftRestoreResult
import com.lttbdd.socialagent.data.review.DraftVersion
import com.lttbdd.socialagent.data.review.DraftVersionRepository
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
 * ViewModel quản lý lịch sử phiên bản bản nháp và thao tác khôi phục (F4, Issue #62).
 */
@HiltViewModel
class DraftHistoryViewModel @Inject constructor(
    private val repository: DraftVersionRepository,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val draftId: String? = savedStateHandle.get<String>(ARG_DRAFT_ID)

    private val _uiState = MutableStateFlow(DraftHistoryUiState())
    val uiState: StateFlow<DraftHistoryUiState> = _uiState.asStateFlow()

    private val _events = Channel<DraftHistoryEvent>(Channel.BUFFERED)
    val events: Flow<DraftHistoryEvent> = _events.receiveAsFlow()

    private var currentDraft: Draft? = null
    private var latestVersions: List<DraftVersion> = emptyList()

    init {
        loadHistory()
    }

    private fun loadHistory() {
        val targetId = draftId
        if (targetId.isNullOrBlank()) {
            _uiState.value = DraftHistoryUiState(
                isLoading = false,
                isDraftNotFound = true,
            )
            return
        }

        viewModelScope.launch {
            val draft = repository.getDraft(targetId)
            if (draft == null) {
                _uiState.value = DraftHistoryUiState(
                    isLoading = false,
                    isDraftNotFound = true,
                )
                return@launch
            }
            currentDraft = draft
            val isLocked = repository.hasActivePublishingPosts(targetId)

            repository.observeVersions(targetId).collect { versions ->
                latestVersions = versions
                val activeDraft = repository.getDraft(targetId) ?: currentDraft
                currentDraft = activeDraft
                val lockedNow = repository.hasActivePublishingPosts(targetId)
                _uiState.value = buildUiState(
                    draft = activeDraft,
                    versions = versions,
                    isLocked = lockedNow || isLocked && activeDraft == null,
                )
            }
        }
    }

    /** Người dùng bấm Khôi phục trên một phiên bản cũ → yêu cầu xác nhận trước khi ghi đè. */
    fun onRestoreRequested(version: DraftVersion) {
        val state = _uiState.value
        if (state.isRestoreLockedByPublishing) {
            _events.trySend(DraftHistoryEvent.ShowMessage(R.string.history_publishing_locked_warning))
            return
        }
        val item = state.versions.firstOrNull { it.version.id == version.id } ?: return
        if (item.isCurrent || !item.canRestore) return
        _events.trySend(DraftHistoryEvent.ConfirmRestore(version))
    }

    /** Xác nhận khôi phục phiên bản [versionId] (luôn thu hồi trạng thái duyệt qua repository). */
    fun onRestoreConfirmed(versionId: String) {
        val targetId = draftId ?: return
        viewModelScope.launch {
            when (val result = repository.restoreVersion(targetId, versionId)) {
                is DraftRestoreResult.Restored -> {
                    currentDraft = result.draft
                    _uiState.update {
                        buildUiState(
                            draft = result.draft,
                            versions = latestVersions,
                            isLocked = false,
                        )
                    }
                    _events.trySend(DraftHistoryEvent.RestoredSuccessfully)
                    _events.trySend(DraftHistoryEvent.ShowMessage(R.string.history_restore_success))
                }
                DraftRestoreResult.BlockedByActivePublish -> {
                    _uiState.update {
                        buildUiState(
                            draft = currentDraft,
                            versions = latestVersions,
                            isLocked = true,
                        )
                    }
                    _events.trySend(DraftHistoryEvent.ShowMessage(R.string.history_publishing_locked_warning))
                }
                DraftRestoreResult.DraftNotFound -> {
                    _uiState.value = DraftHistoryUiState(
                        isLoading = false,
                        isDraftNotFound = true,
                    )
                }
                DraftRestoreResult.VersionNotFound,
                is DraftRestoreResult.StorageError,
                -> {
                    _events.trySend(DraftHistoryEvent.ShowMessage(R.string.history_restore_failed))
                }
            }
        }
    }

    private fun buildUiState(
        draft: Draft?,
        versions: List<DraftVersion>,
        isLocked: Boolean,
    ): DraftHistoryUiState {
        if (draft == null) {
            return DraftHistoryUiState(
                isLoading = false,
                isDraftNotFound = true,
            )
        }
        val currentVersionId = versions.firstOrNull { it.text == draft.text }?.id
        val items = versions.map { version ->
            val isCurrent = version.id == currentVersionId
            DraftHistoryItemUiState(
                version = version,
                isCurrent = isCurrent,
                characterCount = DraftCharacterCounter.countCharacters(version.text),
                canRestore = !isCurrent && !isLocked,
            )
        }
        return DraftHistoryUiState(
            isLoading = false,
            isDraftNotFound = false,
            isRestoreLockedByPublishing = isLocked,
            draftChannel = draft.channel,
            versions = items,
        )
    }

    companion object {
        const val ARG_DRAFT_ID = "draftId"
    }
}
