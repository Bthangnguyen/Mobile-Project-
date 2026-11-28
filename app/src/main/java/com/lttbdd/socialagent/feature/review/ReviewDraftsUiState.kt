package com.lttbdd.socialagent.feature.review

import androidx.annotation.StringRes
import com.lttbdd.socialagent.core.model.Draft
import com.lttbdd.socialagent.data.review.PrePublishWarning

/**
 * Trạng thái hiển thị cho một bản nháp trong danh sách duyệt bài (F4).
 */
data class ReviewDraftItemUiState(
    val draft: Draft,
    val canPublish: Boolean,
    val warnings: List<PrePublishWarning> = emptyList(),
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
    /** Mang theo [expectedText] đúng với nội dung người dùng đã xem lúc bấm Duyệt bài. */
    data class ConfirmApprove(val draftId: String, val expectedText: String) : ReviewDraftsEvent
    /** Yêu cầu người dùng xác nhận khi bản nháp còn cảnh báo trước khi đăng (#65). */
    data class ConfirmPublishWithWarnings(
        val draftId: String,
        val warnings: List<PrePublishWarning>,
    ) : ReviewDraftsEvent
    data class ShowMessage(@param:StringRes val messageRes: Int) : ReviewDraftsEvent
}
