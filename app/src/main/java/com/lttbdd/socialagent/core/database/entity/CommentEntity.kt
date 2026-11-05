package com.lttbdd.socialagent.core.database.entity

import androidx.room.Entity
import androidx.room.Index
import com.lttbdd.socialagent.core.model.ChannelType
import com.lttbdd.socialagent.core.model.CommentCategory
import com.lttbdd.socialagent.core.model.CommentStatus

/**
 * Comment đã đồng bộ về máy (F6). Khoá chính gồm cả [channel] vì id comment
 * chỉ duy nhất trong phạm vi một mạng xã hội.
 */
@Entity(
    tableName = "comments",
    primaryKeys = ["channel", "id"],
    indices = [
        Index(value = ["channel", "remotePostId"]),
        Index("status"),
    ],
)
data class CommentEntity(
    val id: String,
    val channel: ChannelType,
    val remotePostId: String,
    val authorName: String,
    val text: String,
    val createdAtMillis: Long,
    val category: CommentCategory?,
    val status: CommentStatus,
)
