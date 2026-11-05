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
     * Thêm bài đăng mới. Trả về -1 khi [PostEntity.idempotencyKey] đã tồn tại,
     * tức lần bấm đăng này đã được ghi trước đó và không được tạo bài trùng.
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfAbsent(post: PostEntity): Long

    @Update
    suspend fun update(post: PostEntity)
}
