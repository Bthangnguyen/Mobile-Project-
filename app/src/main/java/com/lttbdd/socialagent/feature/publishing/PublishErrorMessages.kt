package com.lttbdd.socialagent.feature.publishing

import androidx.annotation.StringRes
import com.lttbdd.socialagent.R
import com.lttbdd.socialagent.core.publishing.PublishErrorReason

/**
 * Đổi mã lý do lỗi mà hàng đợi lưu (`errorMessage`) sang câu VI/EN cho người dùng (#39).
 * Không bao giờ hiện nguyên văn lý do: mã lạ từ API có thể chứa URL kèm token, nên chỉ hiện câu chung.
 */
object PublishErrorMessages {

    /**
     * Hàng đợi có thể lưu thêm chi tiết sau mã, ví dụ `retry_limit_reached (rate_limited)` hoặc
     * `outcome_unknown (SocketTimeoutException)`; chỉ phần trước dấu ngoặc là mã.
     * Trả về null khi không có lý do, hoặc lý do đã có hướng dẫn riêng (bài không rõ kết quả).
     */
    @StringRes
    fun messageFor(reason: String?): Int? {
        val code = reason?.substringBefore('(')?.trim()?.lowercase().orEmpty()
        if (code.isEmpty()) return null
        return when (code) {
            PublishErrorReason.NETWORK_UNAVAILABLE -> R.string.publish_error_network_unavailable
            PublishErrorReason.RATE_LIMITED -> R.string.publish_error_rate_limited
            PublishErrorReason.SERVER_ERROR -> R.string.publish_error_server_error
            PublishErrorReason.PERMISSION_DENIED -> R.string.publish_error_permission_denied
            PublishErrorReason.AUTH_EXPIRED -> R.string.publish_error_auth_expired
            PublishErrorReason.CONTENT_REJECTED -> R.string.publish_error_content_rejected
            PublishErrorReason.DUPLICATE_CONTENT -> R.string.publish_error_duplicate_content
            QueueReason.RETRY_LIMIT -> R.string.publish_error_retry_limit
            QueueReason.DRAFT_MISSING -> R.string.publish_error_draft_missing
            QueueReason.NOT_APPROVED -> R.string.publish_error_not_approved
            QueueReason.NO_CLIENT -> R.string.publish_error_channel_not_supported
            QueueReason.STORAGE -> R.string.publish_error_storage
            // Đã có hướng dẫn "Cần kiểm tra" và hai nút xác nhận.
            QueueReason.OUTCOME_UNKNOWN -> null
            else -> R.string.publish_error_unknown
        }
    }

    /**
     * Mã do chính hàng đợi gán (trùng giá trị với `PublishQueueRepository.REASON_*`). Viết lại ở đây để
     * `feature/` không import `data/publishing`; test `PublishErrorMessagesTest` kiểm tra hai bên còn khớp.
     */
    internal object QueueReason {
        const val STORAGE = "storage_error"
        const val OUTCOME_UNKNOWN = "outcome_unknown"
        const val DRAFT_MISSING = "draft_missing"
        const val NOT_APPROVED = "draft_not_approved"
        const val NO_CLIENT = "channel_not_supported"
        const val RETRY_LIMIT = "retry_limit_reached"
    }
}
