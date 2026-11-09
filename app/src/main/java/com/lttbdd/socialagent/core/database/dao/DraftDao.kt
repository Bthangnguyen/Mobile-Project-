package com.lttbdd.socialagent.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.lttbdd.socialagent.core.database.entity.DraftEntity
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
     * Xoá bản nháp, trừ khi nó đang có bài chờ đăng hoặc đang đăng (sẽ mất nội dung cần đăng).
     * Trả về số dòng đã xoá: 0 nghĩa là không tồn tại hoặc bị chặn.
     */
    @Query(
        """
        DELETE FROM drafts
        WHERE id = :id
          AND NOT EXISTS (
            SELECT 1 FROM posts
            WHERE posts.draftId = :id AND posts.status IN ('QUEUED', 'PUBLISHING')
          )
        """,
    )
    abstract suspend fun deleteById(id: String): Int

    @Insert(onConflict = OnConflictStrategy.ABORT)
    protected abstract suspend fun insert(draft: DraftEntity)

    @Update
    protected abstract suspend fun update(draft: DraftEntity): Int
}
