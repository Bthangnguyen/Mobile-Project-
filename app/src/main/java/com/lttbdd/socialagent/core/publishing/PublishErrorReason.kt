package com.lttbdd.socialagent.core.publishing

/**
 * Mã lý do lỗi đăng bài (#39) mà client mạng xã hội nên trả trong `PublishResult.RetryableError.reason`
 * và `PublishResult.PermanentError.reason`. Hàng đợi lưu mã vào `PostEntity.errorMessage`, màn Trạng thái đăng
 * đổi mã sang câu VI/EN dễ hiểu. Mã lạ vẫn được lưu nhưng chỉ hiện câu chung "Kênh báo lỗi", không hiện nguyên văn,
 * vì nội dung lỗi từ API có thể chứa URL kèm token.
 */
object PublishErrorReason {
    // Lỗi tạm thời: trả trong RetryableError, hàng đợi tự thử lại.

    /** Không gửi được request (mất mạng, DNS, TLS). Hàng đợi tự gán khi bắt được lỗi kết nối. */
    const val NETWORK_UNAVAILABLE = "network_unavailable"

    /** Kênh giới hạn tần suất (HTTP 429). Nên kèm `retryAfterMillis` lấy từ header `Retry-After`. */
    const val RATE_LIMITED = "rate_limited"

    /** Kênh lỗi tạm thời (HTTP 5xx). */
    const val SERVER_ERROR = "server_error"

    // Lỗi không thử lại: trả trong PermanentError, người dùng phải xử lý.

    /** Token thiếu quyền đăng bài (ví dụ Facebook thiếu `pages_manage_posts`, LinkedIn thiếu `w_member_social`). */
    const val PERMISSION_DENIED = "permission_denied"

    /** Token hết hạn hoặc bị thu hồi: người dùng cần kết nối lại kênh. */
    const val AUTH_EXPIRED = "auth_expired"

    /** Kênh từ chối nội dung (vi phạm chính sách, quá dài, định dạng sai…). */
    const val CONTENT_REJECTED = "content_rejected"

    /** Kênh từ chối vì trùng với bài đã đăng trước đó. */
    const val DUPLICATE_CONTENT = "duplicate_content"
}
