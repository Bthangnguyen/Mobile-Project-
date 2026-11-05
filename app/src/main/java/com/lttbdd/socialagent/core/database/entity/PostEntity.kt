package com.lttbdd.socialagent.core.database.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.lttbdd.socialagent.core.model.ChannelType
import com.lttbdd.socialagent.core.model.PostStatus

/**
 * Một lần đăng bản nháp lên một kênh (F5).
 * [idempotencyKey] là duy nhất trong bảng: DB tự chặn việc tạo hai bài đăng cho cùng một lần bấm đăng.
 */
@Entity(
    tableName = "posts",
    foreignKeys = [
        ForeignKey(
            entity = DraftEntity::class,
            parentColumns = ["id"],
            childColumns = ["draftId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index("draftId"),
        Index(value = ["idempotencyKey"], unique = true),
    ],
)
data class PostEntity(
    @PrimaryKey val id: String,
    val draftId: String,
    val channel: ChannelType,
    val status: PostStatus,
    val idempotencyKey: String,
    val remotePostId: String?,
    val url: String?,
    /** Lý do lỗi cuối cùng để hiển thị cho người dùng; không chứa token hay key. */
    val errorMessage: String?,
    val attemptCount: Int,
    val createdAtMillis: Long,
    val updatedAtMillis: Long,
)
