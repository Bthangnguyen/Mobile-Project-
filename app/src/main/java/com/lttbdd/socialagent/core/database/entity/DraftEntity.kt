package com.lttbdd.socialagent.core.database.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.lttbdd.socialagent.core.model.ChannelType

/** Bản nháp cho một kênh, lưu lại để xem và sửa khi offline (F4, F9). Mỗi topic có tối đa một bản nháp cho mỗi kênh. */
@Entity(
    tableName = "drafts",
    indices = [Index(value = ["topicId", "channel"], unique = true)],
)
data class DraftEntity(
    @PrimaryKey val id: String,
    val topicId: String,
    val channel: ChannelType,
    val text: String,
    val imageUri: String?,
    val imageAltText: String?,
    val approved: Boolean,
    val updatedAtMillis: Long,
)
