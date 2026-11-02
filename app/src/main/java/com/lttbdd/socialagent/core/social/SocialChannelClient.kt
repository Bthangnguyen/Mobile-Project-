package com.lttbdd.socialagent.core.social

import com.lttbdd.socialagent.core.model.ChannelType
import com.lttbdd.socialagent.core.model.Comment
import com.lttbdd.socialagent.core.model.Draft
import com.lttbdd.socialagent.core.model.PostMetrics
import com.lttbdd.socialagent.core.model.PublishResult

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
}
