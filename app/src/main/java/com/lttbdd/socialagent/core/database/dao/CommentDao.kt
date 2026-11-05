package com.lttbdd.socialagent.core.database.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.lttbdd.socialagent.core.database.entity.CommentEntity
import com.lttbdd.socialagent.core.model.ChannelType
import com.lttbdd.socialagent.core.model.CommentStatus
import kotlinx.coroutines.flow.Flow

@Dao
interface CommentDao {

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
    fun observe(
        channel: ChannelType? = null,
        remotePostId: String? = null,
        status: CommentStatus? = null,
    ): Flow<List<CommentEntity>>

    @Upsert
    suspend fun upsertAll(comments: List<CommentEntity>)

    @Query("UPDATE comments SET status = :status WHERE channel = :channel AND id = :id")
    suspend fun updateStatus(channel: ChannelType, id: String, status: CommentStatus)
}
