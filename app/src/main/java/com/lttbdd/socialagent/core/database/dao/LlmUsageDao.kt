package com.lttbdd.socialagent.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.lttbdd.socialagent.core.database.entity.LlmUsageEntity
import kotlinx.coroutines.flow.Flow

/** Usage LLM theo từng lượt gọi (F8, #74). */
@Dao
interface LlmUsageDao {

    /** Id đã tồn tại thì bỏ qua, để ghi lặp cùng một lượt gọi không bị đếm hai lần. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(record: LlmUsageEntity)

    /** Các lượt gọi trong khoảng `[startMillis, endMillis)`, cũ trước mới sau. */
    @Query(
        """
        SELECT * FROM llm_usage
        WHERE occurredAtMillis >= :startMillis AND occurredAtMillis < :endMillis
        ORDER BY occurredAtMillis
        """,
    )
    fun observeBetween(startMillis: Long, endMillis: Long): Flow<List<LlmUsageEntity>>

    /** Xóa toàn bộ thống kê theo yêu cầu người dùng. Trả về số dòng đã xóa. */
    @Query("DELETE FROM llm_usage")
    suspend fun deleteAll(): Int
}
