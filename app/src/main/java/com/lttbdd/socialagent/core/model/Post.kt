package com.lttbdd.socialagent.core.model

/** Bản nháp cho MỘT kênh. Một topic sinh ra nhiều Draft, mỗi kênh một bản. */
data class Draft(
    val id: String,
    val topicId: String,
    val channel: ChannelType,
    val text: String,
    val imageUri: String? = null,
    val imageAltText: String? = null,
    val approved: Boolean = false,
)

/** Vòng đời một bài đăng (F5): nháp → chờ → đang đăng → đã đăng / lỗi / đã bàn giao. */
enum class PostStatus { DRAFT, QUEUED, PUBLISHING, PUBLISHED, FAILED, HANDED_OFF }

/** Kết quả gọi API đăng bài của một kênh. */
sealed interface PublishResult {
    data class Success(val remotePostId: String, val url: String) : PublishResult

    /** Lỗi tạm thời (mất mạng, rate limit): hàng đợi được phép thử lại. */
    data class RetryableError(val reason: String, val retryAfterMillis: Long? = null) : PublishResult

    /** Lỗi không thử lại được (thiếu quyền, nội dung bị từ chối): báo rõ cho người dùng. */
    data class PermanentError(val reason: String) : PublishResult
}

/**
 * Kết quả đối soát bài không rõ kết quả (#94): bài có thật sự đã lên mạng xã hội chưa.
 * Hàng đợi dùng để quyết định đánh dấu đã đăng, đăng lại, hay chờ người dùng xác nhận.
 */
sealed interface ReconcileResult {
    /** Tìm thấy bài trên kênh: hàng đợi đánh dấu đã đăng và lưu link. */
    data class Found(val remotePostId: String, val url: String) : ReconcileResult

    /**
     * Đã kiểm tra và chắc chắn chưa có bài. Hàng đợi chỉ dùng kết quả này khi đã qua thời gian chờ feed
     * kể từ lần gọi API gần nhất, và tự đăng lại đúng một lần với cùng idempotencyKey; lần sau chờ người dùng.
     */
    data object NotFound : ReconcileResult

    /** Kênh không hỗ trợ hoặc không kiểm tra được: hàng đợi chuyển sang chờ người dùng xác nhận. */
    data class Unavailable(val reason: String) : ReconcileResult
}
