package com.lttbdd.socialagent.core.database.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.lttbdd.socialagent.core.database.entity.DraftEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface DraftDao {

    /** Mọi bản nháp, mới sửa nhất lên đầu. Flow tự phát lại khi bảng thay đổi. */
    @Query("SELECT * FROM drafts ORDER BY updatedAtMillis DESC")
    fun observeAll(): Flow<List<DraftEntity>>

    @Query("SELECT * FROM drafts WHERE topicId = :topicId ORDER BY channel")
    fun observeByTopic(topicId: String): Flow<List<DraftEntity>>

    @Query("SELECT * FROM drafts WHERE id = :id")
    suspend fun getById(id: String): DraftEntity?

    @Upsert
    suspend fun upsert(draft: DraftEntity)

    @Upsert
    suspend fun upsertAll(drafts: List<DraftEntity>)

    @Query("DELETE FROM drafts WHERE id = :id")
    suspend fun deleteById(id: String)
}
