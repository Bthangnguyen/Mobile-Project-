package com.lttbdd.socialagent.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.lttbdd.socialagent.core.database.entity.PostEntity
import com.lttbdd.socialagent.core.model.PostStatus
import kotlinx.coroutines.flow.Flow

@Dao
interface PostDao {

    @Query("SELECT * FROM posts ORDER BY updatedAtMillis DESC")
    fun observeAll(): Flow<List<PostEntity>>

    @Query("SELECT * FROM posts WHERE draftId = :draftId ORDER BY createdAtMillis DESC")
    fun observeByDraft(draftId: String): Flow<List<PostEntity>>

    @Query("SELECT * FROM posts WHERE status IN (:statuses) ORDER BY createdAtMillis")
    suspend fun getByStatus(statuses: List<PostStatus>): List<PostEntity>

    @Query("SELECT * FROM posts WHERE idempotencyKey = :idempotencyKey")
    suspend fun getByIdempotencyKey(idempotencyKey: String): PostEntity?

    /**
     * Thêm bài đăng mới; chặn tạo **bản ghi** trùng (chặn đăng trùng thật sự cần thêm `outcomeUnknown`, xem #38).
     * Trả về -1 khi [PostEntity.idempotencyKey] hoặc [PostEntity.id] đã tồn tại. Khi nhận -1, gọi
     * [getByIdempotencyKey] để xác nhận đây đúng là lần bấm đăng cũ trước khi coi là trùng.
     * `IGNORE` không áp dụng cho khoá ngoại: [PostEntity.draftId] trỏ tới bản nháp không tồn tại
     * vẫn ném `SQLiteConstraintException`, nên phải lưu bản nháp trước.
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfAbsent(post: PostEntity): Long

    @Update
    suspend fun update(post: PostEntity)

    @Query("SELECT * FROM posts WHERE id = :id")
    suspend fun getById(id: String): PostEntity?

    @Query("SELECT * FROM posts WHERE draftId = :draftId ORDER BY createdAtMillis DESC")
    suspend fun getByDraft(draftId: String): List<PostEntity>

    /**
     * Giữ chỗ một bài để đăng (#38): chỉ chuyển [PostStatus.QUEUED] → [PostStatus.PUBLISHING] trong MỘT câu lệnh,
     * ghi `attemptStartedAtMillis` **trước** khi gọi API. Trả về 1 nếu giữ chỗ thành công, 0 nếu bài đã bị
     * worker khác lấy hoặc không còn ở hàng đợi — nhờ vậy hai worker chạy song song không gọi API hai lần.
     */
    @Query(
        """
        UPDATE posts
        SET status = :publishing, attemptStartedAtMillis = :nowMillis, outcomeUnknown = 0,
            attemptCount = attemptCount + 1, updatedAtMillis = :nowMillis
        WHERE id = :id AND status = :queued
        """,
    )
    suspend fun claimForPublishing(
        id: String,
        nowMillis: Long,
        queued: PostStatus = PostStatus.QUEUED,
        publishing: PostStatus = PostStatus.PUBLISHING,
    ): Int

    /**
     * Bài kẹt ở [PostStatus.PUBLISHING] quá [startedBeforeMillis] mà chưa có `remotePostId` (process bị huỷ giữa
     * chừng, hoặc gọi API xong nhưng ghi DB lỗi) bị đánh dấu `outcomeUnknown`: không biết đã lên mạng xã hội chưa,
     * nên không được tự đăng lại. Trả về số bài bị đánh dấu.
     */
    @Query(
        """
        UPDATE posts
        SET outcomeUnknown = 1, updatedAtMillis = :nowMillis
        WHERE status = :publishing AND remotePostId IS NULL AND outcomeUnknown = 0
          AND attemptStartedAtMillis < :startedBeforeMillis
        """,
    )
    suspend fun markStalePublishingAsUnknown(
        startedBeforeMillis: Long,
        nowMillis: Long,
        publishing: PostStatus = PostStatus.PUBLISHING,
    ): Int

    /** Số bài đang [PostStatus.PUBLISHING] mà chưa bị coi là không rõ kết quả (có thể worker khác đang đăng). */
    @Query("SELECT COUNT(*) FROM posts WHERE status = :publishing AND outcomeUnknown = 0 AND remotePostId IS NULL")
    suspend fun countInFlight(publishing: PostStatus = PostStatus.PUBLISHING): Int

    /**
     * Người dùng xác nhận bài không rõ kết quả ĐÃ lên mạng xã hội. Một câu lệnh có điều kiện nên hai thao tác
     * xác nhận/đưa lại hàng đợi bấm cùng lúc không ghi đè nhau. Trả về 1 nếu thành công, 0 nếu bài không còn chờ xác nhận.
     */
    @Query(
        """
        UPDATE posts
        SET status = :published, url = COALESCE(:url, url), outcomeUnknown = 0, errorMessage = NULL,
            updatedAtMillis = :nowMillis
        WHERE id = :id AND status = :publishing AND outcomeUnknown = 1 AND remotePostId IS NULL
        """,
    )
    suspend fun resolveUnknownAsPublished(
        id: String,
        url: String?,
        nowMillis: Long,
        publishing: PostStatus = PostStatus.PUBLISHING,
        published: PostStatus = PostStatus.PUBLISHED,
    ): Int

    /** Người dùng xác nhận bài CHƯA được đăng: đưa lại hàng đợi. Cùng điều kiện với [resolveUnknownAsPublished]. */
    @Query(
        """
        UPDATE posts
        SET status = :queued, outcomeUnknown = 0, errorMessage = NULL, updatedAtMillis = :nowMillis
        WHERE id = :id AND status = :publishing AND outcomeUnknown = 1 AND remotePostId IS NULL
        """,
    )
    suspend fun requeueUnknown(
        id: String,
        nowMillis: Long,
        publishing: PostStatus = PostStatus.PUBLISHING,
        queued: PostStatus = PostStatus.QUEUED,
    ): Int
}
