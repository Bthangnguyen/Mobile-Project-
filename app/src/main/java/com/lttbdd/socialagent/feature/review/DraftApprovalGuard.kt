package com.lttbdd.socialagent.feature.review

import com.lttbdd.socialagent.core.model.Draft

/**
 * Kết quả kiểm tra điều kiện cho phép đăng bài của một bản nháp (F4 — quy tắc 6).
 */
sealed interface PublishEligibility {
    data object Allowed : PublishEligibility
    data object BlockedNotApproved : PublishEligibility
    data object BlockedEmptyContent : PublishEligibility
}

/**
 * Cổng bảo vệ nghiệp vụ cho yêu cầu F4 (#33):
 * **Agent không bao giờ tự đăng khi chưa có sự phê duyệt tường minh của người dùng.**
 */
object DraftApprovalGuard {

    /**
     * Mọi bản nháp do AI Agent sinh ra luôn bị ép về `approved = false`,
     * kể cả khi nguồn gọi truyền `approved = true`.
     */
    fun sanitizeAgentDraft(draft: Draft): Draft = draft.copy(approved = false)

    /**
     * Khi nội dung bản nháp bị chỉnh sửa sau khi duyệt, trạng thái duyệt bị thu hồi
     * để người dùng bắt buộc duyệt lại nội dung mới trước khi đăng.
     */
    fun onDraftContentEdited(draft: Draft): Draft = draft.copy(approved = false)

    /**
     * Kiểm tra một bản nháp có đủ điều kiện bàn giao sang hàng đợi đăng bài (F5) hay không.
     */
    fun checkPublishEligibility(draft: Draft): PublishEligibility = when {
        !draft.approved -> PublishEligibility.BlockedNotApproved
        draft.text.isBlank() -> PublishEligibility.BlockedEmptyContent
        else -> PublishEligibility.Allowed
    }

    fun canPublish(draft: Draft): Boolean =
        checkPublishEligibility(draft) is PublishEligibility.Allowed
}
