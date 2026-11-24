package com.lttbdd.socialagent.data.publishing

import androidx.room.withTransaction
import com.lttbdd.socialagent.core.database.AppDatabase
import com.lttbdd.socialagent.core.database.dao.DraftDao
import com.lttbdd.socialagent.core.database.dao.PostDao
import com.lttbdd.socialagent.core.database.entity.PostEntity
import com.lttbdd.socialagent.core.database.toModel
import com.lttbdd.socialagent.core.model.PostStatus
import com.lttbdd.socialagent.core.model.PublishResult
import com.lttbdd.socialagent.core.social.SocialChannelClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.Flow
import java.net.ConnectException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** Nguồn thời gian, tách ra để test điều khiển được. */
fun interface PublishClock {
    fun nowMillis(): Long
}

/** Kết quả khi người dùng bấm Đăng một bản nháp. */
sealed interface EnqueueResult {
    /** Đã thêm vào hàng đợi; sẽ tự đăng khi có mạng. */
    data class Queued(val postId: String) : EnqueueResult

    /** Bấm lặp: bản nháp đã có bài đang chờ, đang đăng, chờ xác nhận hoặc đã bàn giao — không tạo bài mới. */
    data class AlreadyActive(val postId: String, val status: PostStatus) : EnqueueResult

    /** Bản nháp đã được đăng thành công trước đó. */
    data class AlreadyPublished(val postId: String, val url: String?) : EnqueueResult

    data object DraftNotFound : EnqueueResult

    /** Bản nháp chưa được duyệt: agent không được tự đăng (yêu cầu F4, quy tắc 6 của AGENTS.md). */
    data object NotApproved : EnqueueResult
}

/** Tổng kết một lượt xử lý hàng đợi, để worker quyết định có cần chạy lại không. */
data class QueueRunResult(
    val published: Int = 0,
    val retryLater: Int = 0,
    val failed: Int = 0,
    val needsConfirmation: Int = 0,
    /** Bài còn đang PUBLISHING chưa quá hạn sau lượt này — đã hẹn lượt kiểm tra trễ. */
    val inFlight: Int = 0,
) {
    val shouldRetry: Boolean get() = retryLater > 0
}

/**
 * Hàng đợi đăng bài F5 (#37 hàng đợi offline, #38 chống đăng trùng).
 *
 * - Bấm Đăng chỉ ghi bài [PostStatus.QUEUED] vào Room rồi hẹn [PublishScheduler]; worker chạy khi có mạng,
 *   nên mất mạng vẫn bấm đăng được.
 * - Chống trùng theo giao thức trong `docs/research-social-api.md`: giữ chỗ bằng [PostDao.claimForPublishing]
 *   (ghi `attemptStartedAtMillis` trước khi gọi API), chỉ tự thử lại khi chắc chắn request chưa được gửi,
 *   còn lỗi không rõ kết quả thì đánh dấu `outcomeUnknown` và chờ người dùng xác nhận — không tự đăng lại.
 * - Hợp đồng [SocialChannelClient] chưa có hàm đối soát feed, nên bước đối soát tự động chưa làm ở đây;
 *   người dùng xác nhận qua [confirmPublishedManually] hoặc [requeueAfterUserCheck].
 */
@Singleton
class PublishQueueRepository @Inject constructor(
    private val database: AppDatabase,
    private val postDao: PostDao,
    private val draftDao: DraftDao,
    private val clients: Set<@JvmSuppressWildcards SocialChannelClient>,
    private val scheduler: PublishScheduler,
    private val clock: PublishClock,
) {

    fun observePosts(): Flow<List<PostEntity>> = postDao.observeAll()

    fun observePostsForDraft(draftId: String): Flow<List<PostEntity>> = postDao.observeByDraft(draftId)

    /**
     * Thêm bản nháp vào hàng đợi. Gọi nhiều lần (bấm hai lần) chỉ tạo một bài: kiểm tra và thêm nằm trong
     * cùng một transaction. Bài cũ [PostStatus.FAILED] không chặn — người dùng được đăng lại với khoá mới.
     */
    suspend fun enqueue(draftId: String): EnqueueResult {
        val result = database.withTransaction {
            val draft = draftDao.getById(draftId) ?: return@withTransaction EnqueueResult.DraftNotFound
            if (!draft.approved) return@withTransaction EnqueueResult.NotApproved

            val existing = postDao.getByDraft(draftId)
            existing.firstOrNull { it.status in ACTIVE_STATUSES }?.let {
                return@withTransaction EnqueueResult.AlreadyActive(it.id, it.status)
            }
            existing.firstOrNull { it.status == PostStatus.PUBLISHED }?.let {
                return@withTransaction EnqueueResult.AlreadyPublished(it.id, it.url)
            }

            val now = clock.nowMillis()
            val post = PostEntity(
                id = UUID.randomUUID().toString(),
                draftId = draftId,
                channel = draft.channel,
                status = PostStatus.QUEUED,
                idempotencyKey = UUID.randomUUID().toString(),
                remotePostId = null,
                url = null,
                errorMessage = null,
                attemptCount = 0,
                createdAtMillis = now,
                updatedAtMillis = now,
            )
            postDao.insertIfAbsent(post)
            EnqueueResult.Queued(post.id)
        }
        if (result is EnqueueResult.Queued) scheduler.schedule()
        return result
    }

    /**
     * Xử lý mọi bài [PostStatus.QUEUED]. Worker gọi hàm này; an toàn khi hai lượt chạy song song vì mỗi bài
     * phải được giữ chỗ thành công mới gọi API.
     */
    suspend fun processQueue(): QueueRunResult {
        val now = clock.nowMillis()
        postDao.markStalePublishingAsUnknown(startedBeforeMillis = now - STALE_PUBLISHING_MILLIS, nowMillis = now)

        var result = QueueRunResult()
        for (post in postDao.getByStatus(listOf(PostStatus.QUEUED))) {
            result = when (publishOne(post.id)) {
                Outcome.PUBLISHED -> result.copy(published = result.published + 1)
                Outcome.RETRY_LATER -> result.copy(retryLater = result.retryLater + 1)
                Outcome.FAILED -> result.copy(failed = result.failed + 1)
                Outcome.NEEDS_CONFIRMATION -> result.copy(needsConfirmation = result.needsConfirmation + 1)
                Outcome.SKIPPED -> result
            }
        }
        // Bài kẹt PUBLISHING chưa quá hạn (worker trước bị dừng giữa chừng) sẽ không còn ai xử lý nếu không hẹn
        // lượt sau: hẹn kiểm tra lại khi đã quá STALE_PUBLISHING_MILLIS để chuyển nó sang "chờ xác nhận".
        val inFlight = postDao.countInFlight()
        if (inFlight > 0) scheduler.scheduleStaleCheck(STALE_PUBLISHING_MILLIS + STALE_CHECK_MARGIN_MILLIS)
        return result.copy(inFlight = inFlight)
    }

    /** Gọi khi app khởi động: hẹn worker để xử lý bài QUEUED/PUBLISHING còn sót (app bị tắt trước khi kịp hẹn). */
    fun resumePendingWork() {
        scheduler.schedule()
    }

    /** Người dùng xác nhận bài không rõ kết quả thật ra ĐÃ lên mạng xã hội. */
    suspend fun confirmPublishedManually(postId: String, url: String? = null): Boolean =
        postDao.resolveUnknownAsPublished(postId, url, clock.nowMillis()) == 1

    /**
     * Người dùng đã kiểm tra trên mạng xã hội và bài CHƯA được đăng: đưa lại vào hàng đợi với cùng
     * idempotencyKey (client vẫn phải chặn trùng theo hợp đồng).
     */
    suspend fun requeueAfterUserCheck(postId: String): Boolean {
        if (postDao.requeueUnknown(postId, clock.nowMillis()) == 0) return false
        scheduler.schedule()
        return true
    }

    private suspend fun publishOne(postId: String): Outcome {
        if (postDao.claimForPublishing(postId, clock.nowMillis()) == 0) return Outcome.SKIPPED
        val post = postDao.getById(postId) ?: return Outcome.SKIPPED

        val draft = post.draftId?.let { draftDao.getById(it) }
        if (draft == null) return post.fail(REASON_DRAFT_MISSING)
        // Bản nháp có thể bị sửa và mất trạng thái duyệt sau khi vào hàng đợi: không được đăng.
        if (!draft.approved) return post.fail(REASON_NOT_APPROVED)
        val client = clients.firstOrNull { it.type == post.channel } ?: return post.fail(REASON_NO_CLIENT)

        val result = try {
            client.publish(draft.toModel(), post.idempotencyKey)
        } catch (e: TimeoutCancellationException) {
            // withTimeout hết giờ khi đang chờ response: request có thể đã tới server.
            return post.markOutcomeUnknown(e)
        } catch (e: CancellationException) {
            // Bài vẫn ở PUBLISHING; lượt sau sẽ coi là không rõ kết quả sau STALE_PUBLISHING_MILLIS.
            throw e
        } catch (e: UnknownHostException) {
            return post.requeueNotSent()
        } catch (e: ConnectException) {
            return post.requeueNotSent()
        } catch (e: SSLHandshakeException) {
            // Bắt tay TLS lỗi xảy ra trước khi ghi request: nội dung bài chưa rời máy.
            return post.requeueNotSent()
        } catch (e: Exception) {
            // Timeout, mất kết nối khi đang đọc response…: server có thể đã nhận bài.
            return post.markOutcomeUnknown(e)
        }

        return when (result) {
            is PublishResult.Success -> {
                postDao.update(
                    post.copy(
                        status = PostStatus.PUBLISHED,
                        remotePostId = result.remotePostId,
                        url = result.url,
                        errorMessage = null,
                        outcomeUnknown = false,
                        updatedAtMillis = clock.nowMillis(),
                    ),
                )
                Outcome.PUBLISHED
            }
            is PublishResult.RetryableError ->
                if (post.attemptCount >= MAX_RETRYABLE_ATTEMPTS) {
                    post.fail("$REASON_RETRY_LIMIT (${result.reason})")
                } else {
                    post.requeue(result.reason)
                }
            is PublishResult.PermanentError -> post.fail(result.reason)
        }
    }

    private suspend fun PostEntity.requeue(reason: String): Outcome {
        postDao.update(copy(status = PostStatus.QUEUED, errorMessage = reason, updatedAtMillis = clock.nowMillis()))
        return Outcome.RETRY_LATER
    }

    /**
     * Request chắc chắn chưa tới server: hoàn lại lượt mà [PostDao.claimForPublishing] đã cộng, để mạng chập chờn
     * không làm bài chạm [MAX_RETRYABLE_ATTEMPTS] trước khi kênh thật sự trả lỗi.
     */
    private suspend fun PostEntity.requeueNotSent(): Outcome =
        copy(attemptCount = (attemptCount - 1).coerceAtLeast(0)).requeue(REASON_NOT_SENT)

    private suspend fun PostEntity.fail(reason: String): Outcome {
        postDao.update(copy(status = PostStatus.FAILED, errorMessage = reason, updatedAtMillis = clock.nowMillis()))
        return Outcome.FAILED
    }

    private suspend fun PostEntity.markOutcomeUnknown(error: Exception): Outcome {
        // Chỉ lưu tên lớp lỗi: message của exception có thể chứa URL kèm token.
        postDao.update(
            copy(
                outcomeUnknown = true,
                errorMessage = "$REASON_OUTCOME_UNKNOWN (${error::class.java.simpleName})",
                updatedAtMillis = clock.nowMillis(),
            ),
        )
        return Outcome.NEEDS_CONFIRMATION
    }

    private enum class Outcome { PUBLISHED, RETRY_LATER, FAILED, NEEDS_CONFIRMATION, SKIPPED }

    companion object {
        /** Worker của WorkManager bị dừng sau tối đa 10 phút; quá mốc này mà còn PUBLISHING thì lượt đó đã chết. */
        const val STALE_PUBLISHING_MILLIS = 15 * 60 * 1000L
        private const val STALE_CHECK_MARGIN_MILLIS = 60 * 1000L

        /**
         * Số lần gọi API tối đa khi kênh trả lỗi tạm thời (5xx, rate limit). Lần chưa gửi được request
         * (mất mạng, DNS, lỗi bắt tay TLS) không được tính vào `attemptCount`. Đọc `retryAfterMillis`: #39.
         */
        const val MAX_RETRYABLE_ATTEMPTS = 8

        /** Trạng thái chặn tạo bài mới cho cùng bản nháp. */
        private val ACTIVE_STATUSES = setOf(PostStatus.QUEUED, PostStatus.PUBLISHING, PostStatus.HANDED_OFF)

        // Lý do lưu trong errorMessage; UI (#39) đổi sang chuỗi VI/EN, không hiển thị nguyên văn.
        const val REASON_NOT_SENT = "network_unavailable"
        const val REASON_OUTCOME_UNKNOWN = "outcome_unknown"
        const val REASON_DRAFT_MISSING = "draft_missing"
        const val REASON_NOT_APPROVED = "draft_not_approved"
        const val REASON_NO_CLIENT = "channel_not_supported"
        const val REASON_RETRY_LIMIT = "retry_limit_reached"
    }
}
