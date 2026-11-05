package com.lttbdd.socialagent.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.lttbdd.socialagent.core.database.entity.MetricsSnapshotEntity
import com.lttbdd.socialagent.core.model.ChannelType
import kotlinx.coroutines.flow.Flow

@Dao
interface MetricsDao {

    @Insert
    suspend fun insert(snapshot: MetricsSnapshotEntity)

    /** Các snapshot của một bài theo thời gian tăng dần, dùng để vẽ đường tăng trưởng (F7). */
    @Query(
        """
        SELECT * FROM metrics_snapshots
        WHERE channel = :channel AND remotePostId = :remotePostId
        ORDER BY capturedAtMillis
        """,
    )
    fun observeHistory(channel: ChannelType, remotePostId: String): Flow<List<MetricsSnapshotEntity>>

    @Query(
        """
        SELECT * FROM metrics_snapshots
        WHERE channel = :channel AND remotePostId = :remotePostId
        ORDER BY capturedAtMillis DESC
        LIMIT 1
        """,
    )
    suspend fun getLatest(channel: ChannelType, remotePostId: String): MetricsSnapshotEntity?
}
