package com.lttbdd.socialagent.core.social

import com.lttbdd.socialagent.core.model.ChannelType
import com.lttbdd.socialagent.core.model.Comment
import com.lttbdd.socialagent.core.model.Draft
import com.lttbdd.socialagent.core.model.PostMetrics
import com.lttbdd.socialagent.core.model.PublishResult
import com.lttbdd.socialagent.core.model.ReconcileResult

/**
 * HỢP ĐỒNG gọi API một mạng xã hội. Chủ sở hữu: B (mảng Mạng xã hội).
 * Mỗi kênh có một implementation (FacebookClient, LinkedInClient) và được Hilt gom vào
 * `Set<SocialChannelClient>`. Người dùng: F5 đăng bài (A), F6 comment (B), F7 số liệu (D).
 *
 * Không đổi chữ ký khi chưa có issue `contract` được cả nhóm duyệt — xem docs/architecture.md.
 */
interface SocialChannelClient {

    val type: ChannelType

    /**
     * Đăng bài. Gọi lại với cùng [idempotencyKey] KHÔNG được tạo bài thứ hai:
     * phải trả về kết quả của lần đăng trước (yêu cầu "không đăng trùng" của F5).
     */
    suspend fun publish(draft: Draft, idempotencyKey: String): PublishResult

    suspend fun fetchComments(remotePostId: String): List<Comment>

    suspend fun reply(commentId: String, text: String)

    suspend fun fetchMetrics(remotePostId: String): PostMetrics

    /** Số follower hiện tại của tài khoản/Page, dùng cho dashboard F7. */
    suspend fun fetchFollowerCount(): Int

    /**
     * Đối soát (#94): tìm bài đã đăng từ [draft] sau thời điểm [sinceMillis] (so khớp nội dung + thời gian tạo),
     * dùng cho bài không rõ kết quả trước khi hàng đợi quyết định đăng lại. KHÔNG được tạo bài mới.
     *
     * Chỉ trả [ReconcileResult.NotFound] khi đã kiểm tra đủ và chắc chắn chưa có bài, vì hàng đợi sẽ đăng lại.
     * Mặc định trả [ReconcileResult.Unavailable] để Fake và client cũ không phải sửa.
     */
    suspend fun findPublishedPost(draft: Draft, sinceMillis: Long): ReconcileResult =
        ReconcileResult.Unavailable(RECONCILE_NOT_SUPPORTED)

    companion object {
        /** Lý do mặc định của [findPublishedPost] khi kênh chưa hỗ trợ đối soát. */
        const val RECONCILE_NOT_SUPPORTED = "not_supported"
    }
}
