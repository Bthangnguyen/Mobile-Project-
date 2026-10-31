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
