package com.lttbdd.socialagent.feature.review

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lttbdd.socialagent.R
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ViewModel quản lý luồng duyệt bản nháp (F4, Issue #33):
 * đảm bảo không bao giờ bàn giao bản nháp sang bước đăng bài nếu người dùng chưa phê duyệt tường minh.
 */
@HiltViewModel
class ReviewDraftsViewModel @Inject constructor(
    private val repository: DraftReviewRepository,
) : ViewModel() {

    val uiState: StateFlow<ReviewDraftsUiState> = repository.observeDrafts()
        .map { drafts ->
            ReviewDraftsUiState(
                isLoading = false,
                drafts = drafts.map { draft ->
                    ReviewDraftItemUiState(
                        draft = draft,
                        canPublish = DraftApprovalGuard.canPublish(draft),
                    )
                },
            )
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
            initialValue = ReviewDraftsUiState(isLoading = true),
        )

    private val _events = Channel<ReviewDraftsEvent>(Channel.BUFFERED)
    val events: Flow<ReviewDraftsEvent> = _events.receiveAsFlow()

    /** Mở hộp thoại xác nhận trước khi phê duyệt bản nháp (hành động chủ động của người dùng). */
    fun onApproveRequested(draftId: String) {
        _events.trySend(ReviewDraftsEvent.ConfirmApprove(draftId))
    }

    /** Thực thi phê duyệt sau khi người dùng xác nhận trong hộp thoại. */
    fun onApproveConfirmed(draftId: String) {
        viewModelScope.launch {
            val result = repository.approveDraft(draftId)
            val messageRes = if (result.isSuccess) {
                R.string.review_draft_approved_message
            } else {
                R.string.review_draft_approve_failed
            }
            _events.trySend(ReviewDraftsEvent.ShowMessage(messageRes))
        }
    }

    /** Thu hồi trạng thái duyệt để khóa lại nút đăng bài. */
    fun onRevokeApproval(draftId: String) {
        viewModelScope.launch {
            val result = repository.revokeApproval(draftId)
            val messageRes = if (result.isSuccess) {
                R.string.review_draft_revoked_message
            } else {
                R.string.review_draft_action_failed
            }
            _events.trySend(ReviewDraftsEvent.ShowMessage(messageRes))
        }
    }

    /**
     * Yêu cầu đăng bản nháp — luôn đi qua cổng chặn cứng [DraftReviewRepository.requestPublish]
     * để phòng vệ theo chiều sâu kể cả khi bị gọi ngoài UI.
     */
    fun onPublishRequested(draftId: String) {
        viewModelScope.launch {
            when (val gate = repository.requestPublish(draftId)) {
                is PublishGateResult.ReadyToPublish -> {
                    _events.trySend(ReviewDraftsEvent.ReadyToPublish(gate.draft))
                }
                is PublishGateResult.BlockedUnapproved -> {
                    _events.trySend(ReviewDraftsEvent.ShowMessage(R.string.review_publish_blocked_unapproved))
                }
                is PublishGateResult.BlockedEmptyContent -> {
                    _events.trySend(ReviewDraftsEvent.ShowMessage(R.string.review_publish_blocked_empty))
                }
                PublishGateResult.NotFound -> {
                    _events.trySend(ReviewDraftsEvent.ShowMessage(R.string.review_draft_action_failed))
                }
            }
        }
    }

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}
