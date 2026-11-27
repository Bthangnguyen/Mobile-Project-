package com.lttbdd.socialagent.core.database.dao

import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import com.lttbdd.socialagent.core.database.entity.CommentEntity
import com.lttbdd.socialagent.core.model.ChannelType
import com.lttbdd.socialagent.core.model.CommentCategory
import com.lttbdd.socialagent.core.model.CommentStatus
import com.lttbdd.socialagent.core.model.PostStatus
import kotlinx.coroutines.flow.Flow

/** Khóa bài viết trong hộp thư. ID bài có thể giống nhau ở hai kênh. */
data class CommentPostKey(val channel: ChannelType, val remotePostId: String)

data class CommentPostPreview(@Embedded val key: CommentPostKey, val excerpt: String?)

internal const val POST_EXCERPT_LENGTH = 120
private const val POST_EXCERPT_LOOKAHEAD = POST_EXCERPT_LENGTH + 1
private const val WHITESPACE_SCAN_SIZE = 128

@Dao
abstract class CommentDao {

    /** Hộp thư comment hợp nhất (F6). Tham số null nghĩa là không lọc theo tiêu chí đó. */
    @Query(
        """
        SELECT * FROM comments
        WHERE (:channel IS NULL OR channel = :channel)
          AND (:remotePostId IS NULL OR remotePostId = :remotePostId)
          AND (:status IS NULL OR status = :status)
        ORDER BY createdAtMillis DESC
        """,
    )
    abstract fun observe(
        channel: ChannelType? = null,
        remotePostId: String? = null,
        status: CommentStatus? = null,
    ): Flow<List<CommentEntity>>

    /** Đọc các bình luận đứng sau khóa theo thứ tự hiển thị, kể cả khi dòng làm mốc đã bị xóa. */
    @Query(
        """
        SELECT * FROM comments
        WHERE (:channel IS NULL OR channel = :channel)
          AND (:remotePostId IS NULL OR (:channel IS NOT NULL AND remotePostId = :remotePostId))
          AND (:status IS NULL OR status = :status)
          AND (
              :afterCreatedAtMillis IS NULL
              OR createdAtMillis < :afterCreatedAtMillis
              OR (createdAtMillis = :afterCreatedAtMillis AND (
                  channel > :afterChannel OR (channel = :afterChannel AND id > :afterId)
              ))
          )
        ORDER BY createdAtMillis DESC, channel ASC, id ASC
        LIMIT :limit
        """,
    )
    abstract fun observePageAfter(
        channel: ChannelType?,
        remotePostId: String?,
        status: CommentStatus?,
        limit: Int,
        afterCreatedAtMillis: Long?,
        afterChannel: ChannelType?,
        afterId: String?,
    ): Flow<List<CommentEntity>>

    /**
     * Lấy tối đa 121 code point đã chuẩn hóa từ bản nháp của lần đăng thành công.
     * Ký tự thứ 121 giúp UI biết có cần thêm dấu ba chấm.
     */
    @Query(
        """
        WITH whitespace(chars) AS (
            VALUES (char(9, 10, 11, 12, 13, 32, 133, 160, 5760, 8192, 8193, 8194, 8195, 8196,
                         8197, 8198, 8199, 8200, 8201, 8202, 8232, 8233, 8239, 8287, 12288))
        )
        SELECT related.channel, related.remotePostId,
               (
                   SELECT (
                       WITH RECURSIVE preview(position, text, scanSize) AS (
                           SELECT 1, '', $WHITESPACE_SCAN_SIZE
                           UNION ALL
                           SELECT CASE
                                      WHEN instr(chars, substr(draft.text, position, 1)) > 0
                                       AND instr(chars, substr(draft.text, position + 1, 1)) > 0
                                      THEN position + length(substr(draft.text, position, scanSize))
                                           - length(ltrim(substr(draft.text, position, scanSize), chars))
                                      ELSE position + 1
                                  END,
                                  CASE
                                      WHEN instr(chars, substr(draft.text, position, 1)) = 0
                                      THEN text || substr(draft.text, position, 1)
                                      WHEN text = '' OR substr(text, -1) = ' ' THEN text
                                      ELSE text || ' '
                                  END,
                                  -- Chỉ tăng kích thước khi cả khối vẫn là khoảng trắng.
                                  CASE
                                      WHEN instr(chars, substr(draft.text, position, 1)) > 0
                                       AND ltrim(substr(draft.text, position, scanSize), chars) = ''
                                      THEN scanSize * 2
                                      ELSE $WHITESPACE_SCAN_SIZE
                                  END
                           FROM preview
                           WHERE substr(draft.text, position, 1) <> ''
                             AND (length(text) < $POST_EXCERPT_LOOKAHEAD OR substr(text, -1) = ' ')
                       )
                       SELECT nullif(substr(rtrim(text), 1, $POST_EXCERPT_LOOKAHEAD), '')
                       FROM preview
                       ORDER BY position DESC
                       LIMIT 1
                   )
                   FROM posts AS post
                   JOIN drafts AS draft ON draft.id = post.draftId AND draft.channel = post.channel
                   CROSS JOIN whitespace
                   WHERE post.channel = related.channel
                     AND post.remotePostId = related.remotePostId
                     AND post.status = :published
                     AND ltrim(draft.text, chars) <> ''
                   ORDER BY post.updatedAtMillis DESC, post.id ASC
                   LIMIT 1
               ) AS excerpt
        FROM (
            SELECT channel, remotePostId, MAX(createdAtMillis) AS newestComment
            FROM comments
            WHERE (:channel IS NULL OR channel = :channel)
            GROUP BY channel, remotePostId
        ) AS related
        ORDER BY related.newestComment DESC, related.channel ASC, related.remotePostId ASC
        """,
    )
    abstract fun observePostPreviews(
        channel: ChannelType?,
        published: PostStatus = PostStatus.PUBLISHED,
    ): Flow<List<CommentPostPreview>>

    /** Ghi đè toàn bộ comment, kể cả `status` và `category`. Đồng bộ từ mạng thì dùng [syncFromRemote]. */
    @Upsert
    abstract suspend fun upsertAll(comments: List<CommentEntity>)

    /**
     * Lưu comment vừa tải từ mạng xã hội: comment mới được thêm, comment đã có chỉ cập nhật
     * nội dung và tên người viết, giữ nguyên `status` (đã trả lời…) và `category` do AI phân loại.
     */
    @Transaction
    open suspend fun syncFromRemote(comments: List<CommentEntity>) {
        insertIgnoringExisting(comments)
        comments.forEach { updateContent(it.channel, it.id, it.authorName, it.text) }
    }

    @Query("UPDATE comments SET status = :status WHERE channel = :channel AND id = :id")
    abstract suspend fun updateStatus(channel: ChannelType, id: String, status: CommentStatus)

    @Query("UPDATE comments SET category = :category WHERE channel = :channel AND id = :id")
    abstract suspend fun updateCategory(channel: ChannelType, id: String, category: CommentCategory)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    protected abstract suspend fun insertIgnoringExisting(comments: List<CommentEntity>)

    @Query("UPDATE comments SET authorName = :authorName, text = :text WHERE channel = :channel AND id = :id")
    protected abstract suspend fun updateContent(channel: ChannelType, id: String, authorName: String, text: String)

    @Query("DELETE FROM comments WHERE channel = :channel")
    abstract suspend fun deleteByChannel(channel: ChannelType)
}
