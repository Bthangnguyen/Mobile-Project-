package com.lttbdd.socialagent.core.database.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.PrimaryKey

/** Bài được giữ lại tạm thời khi dọn dữ liệu của kênh. */
@Entity(
    tableName = "deferred_channel_post_cleanup",
    foreignKeys = [
        ForeignKey(
            entity = PostEntity::class,
            parentColumns = ["id"],
            childColumns = ["postId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class DeferredChannelPostCleanup(
    @PrimaryKey val postId: String,
    val draftRevision: Long?,
)
