package com.lttbdd.socialagent.data.fake

import com.lttbdd.socialagent.core.model.ChannelType
import com.lttbdd.socialagent.core.model.Comment
import com.lttbdd.socialagent.core.model.Draft
import com.lttbdd.socialagent.core.model.PostMetrics
import com.lttbdd.socialagent.core.model.PublishResult
import com.lttbdd.socialagent.core.social.SocialChannelClient
import kotlinx.coroutines.delay
import java.util.concurrent.ConcurrentHashMap

/**
 * Kênh giả, không gọi mạng. Dùng để dựng UI và test trước khi có FacebookClient/LinkedInClient thật.
 * Tuân thủ đúng hợp đồng: cùng idempotencyKey thì trả lại bài đã đăng, không tạo bài mới.
 */
class FakeSocialChannelClient(
    override val type: ChannelType,
    private val latencyMillis: Long = 500,
) : SocialChannelClient {

    private val publishedByKey = ConcurrentHashMap<String, PublishResult.Success>()

    val publishedCount: Int get() = publishedByKey.size

    override suspend fun publish(draft: Draft, idempotencyKey: String): PublishResult {
        publishedByKey[idempotencyKey]?.let { return it }
        delay(latencyMillis)
        val remoteId = "${type.name.lowercase()}-${publishedByKey.size + 1}"
        return publishedByKey.getOrPut(idempotencyKey) {
            PublishResult.Success(remotePostId = remoteId, url = "https://example.com/${type.name.lowercase()}/$remoteId")
        }
    }

    override suspend fun fetchComments(remotePostId: String): List<Comment> {
        delay(latencyMillis)
        val now = System.currentTimeMillis()
        return listOf(
            Comment("$remotePostId-c1", type, remotePostId, "Lan Nguyen", "Bài viết hay quá!", now - 60_000),
            Comment("$remotePostId-c2", type, remotePostId, "Minh Tran", "Cho mình hỏi nguồn số liệu ở đâu vậy?", now - 30_000),
        )
    }

    override suspend fun reply(commentId: String, text: String) {
        delay(latencyMillis)
    }

    override suspend fun fetchMetrics(remotePostId: String): PostMetrics {
        delay(latencyMillis)
        return PostMetrics(
            channel = type,
            remotePostId = remotePostId,
            likes = 42,
            comments = 2,
            shares = 5,
            impressions = 1_200,
            capturedAtMillis = System.currentTimeMillis(),
        )
    }

    override suspend fun fetchFollowerCount(): Int = 1_000
}
