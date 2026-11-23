package com.lttbdd.socialagent.feature.review

import androidx.annotation.StringRes
import com.lttbdd.socialagent.core.model.Draft

/**
 * Trạng thái hiển thị cho một bản nháp trong danh sách duyệt bài (F4).
 */
data class ReviewDraftItemUiState(
    val draft: Draft,
    val canPublish: Boolean,
)

/**
 * Trạng thái UI tổng thể của màn hình duyệt bản nháp (F4, Issue #33).
 */
data class ReviewDraftsUiState(
    val isLoading: Boolean = true,
    val drafts: List<ReviewDraftItemUiState> = emptyList(),
)

/**
 * Sự kiện dùng một lần (one-shot event) phát từ [ReviewDraftsViewModel] sang [ReviewDraftsFragment].
 */
sealed interface ReviewDraftsEvent {
    data class ConfirmApprove(val draftId: String) : ReviewDraftsEvent
    data class ShowMessage(@param:StringRes val messageRes: Int) : ReviewDraftsEvent
    data class ReadyToPublish(val draft: Draft) : ReviewDraftsEvent
}
