package com.lttbdd.socialagent.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.lttbdd.socialagent.core.database.entity.DraftEntity
import com.lttbdd.socialagent.core.model.PostStatus
import kotlinx.coroutines.flow.Flow

@Dao
abstract class DraftDao {

    /** Mọi bản nháp, mới sửa nhất lên đầu. Flow tự phát lại khi bảng thay đổi. */
    @Query("SELECT * FROM drafts ORDER BY updatedAtMillis DESC")
    abstract fun observeAll(): Flow<List<DraftEntity>>

    @Query("SELECT * FROM drafts WHERE topicId = :topicId ORDER BY channel")
    abstract fun observeByTopic(topicId: String): Flow<List<DraftEntity>>

    @Query("SELECT * FROM drafts WHERE id = :id")
    abstract suspend fun getById(id: String): DraftEntity?

    /**
     * Cập nhật bản nháp theo `id`, chưa có thì thêm mới.
     * Mỗi topic chỉ có một bản nháp cho mỗi kênh (unique `topicId, channel`): khi sinh lại bản nháp hãy
     * dùng lại `id` cũ của kênh đó. Thêm bản nháp khác `id` cho cùng topic và kênh sẽ ném
     * `SQLiteConstraintException` (không dùng `@Upsert` vì nó âm thầm bỏ qua trường hợp này).
     */
    @Transaction
    open suspend fun upsert(draft: DraftEntity) {
        if (update(draft) == 0) insert(draft)
    }

    @Transaction
    open suspend fun upsertAll(drafts: List<DraftEntity>) {
        drafts.forEach { upsert(it) }
    }

    /**
     * Xoá bản nháp, chỉ khi mọi bài đăng của nó đã [PostStatus.PUBLISHED]. Bảng `posts` không lưu nội dung,
     * nên bài còn ở trạng thái khác (QUEUED, PUBLISHING, FAILED chờ thử lại, HANDED_OFF chờ người dùng tự đăng…)
     * vẫn cần bản nháp. Trả về số dòng đã xoá: 0 nghĩa là không tồn tại hoặc bị chặn.
     */
    open suspend fun deleteById(id: String): Int = deleteIfAllPostsIn(id, PostStatus.PUBLISHED)

    /** Truyền trạng thái bằng tham số thay vì viết cứng chuỗi trong SQL, để đổi tên enum không làm mất tác dụng. */
    @Query(
        """
        DELETE FROM drafts
        WHERE id = :id
          AND NOT EXISTS (
            SELECT 1 FROM posts
            WHERE posts.draftId = :id AND posts.status != :finishedStatus
          )
        """,
    )
    protected abstract suspend fun deleteIfAllPostsIn(id: String, finishedStatus: PostStatus): Int

    @Insert(onConflict = OnConflictStrategy.ABORT)
    protected abstract suspend fun insert(draft: DraftEntity)

    @Update
    protected abstract suspend fun update(draft: DraftEntity): Int
}
