package com.lttbdd.socialagent.core.database.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.lttbdd.socialagent.core.model.ChannelType

/** Số liệu của một bài tại một thời điểm (F7). Mỗi lần đồng bộ thêm một dòng để vẽ đường tăng trưởng. */
@Entity(
    tableName = "metrics_snapshots",
    indices = [Index(value = ["channel", "remotePostId", "capturedAtMillis"])],
)
data class MetricsSnapshotEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val channel: ChannelType,
    val remotePostId: String,
    val likes: Int,
    val comments: Int,
    val shares: Int,
    val impressions: Int?,
    val capturedAtMillis: Long,
)
