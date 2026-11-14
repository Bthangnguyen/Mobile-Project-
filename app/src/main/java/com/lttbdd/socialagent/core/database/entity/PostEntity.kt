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
 * Xoá bản nháp không xoá bài đăng: [draftId] chuyển thành null, còn link bài và khoá chống trùng được giữ lại.
 */
@Entity(
    tableName = "posts",
    foreignKeys = [
        ForeignKey(
            entity = DraftEntity::class,
            parentColumns = ["id"],
            childColumns = ["draftId"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
    indices = [
        Index("draftId"),
        Index("status"),
        Index(value = ["idempotencyKey"], unique = true),
    ],
)
data class PostEntity(
    @PrimaryKey val id: String,
    /** null khi bản nháp gốc đã bị xoá sau khi đăng xong (chỉ xảy ra với bài [PostStatus.PUBLISHED]). */
    val draftId: String?,
    val channel: ChannelType,
    val status: PostStatus,
    val idempotencyKey: String,
    val remotePostId: String?,
    val url: String?,
    /** Lý do lỗi cuối cùng để hiển thị cho người dùng; repository phải lọc bỏ token/key trước khi lưu. */
    val errorMessage: String?,
    val attemptCount: Int,
    val createdAtMillis: Long,
    val updatedAtMillis: Long,
    /**
     * Theo giao thức chống đăng trùng trong docs/research-social-api.md: worker ghi thời điểm bắt đầu
     * gọi API trước khi gửi, để biết lần thử nào bị gián đoạn giữa chừng.
     */
    val attemptStartedAtMillis: Long? = null,
    /**
     * true khi lần gọi API gần nhất không rõ kết quả (ví dụ timeout sau khi đã gửi): server có thể
     * đã nhận bài, nên phải kiểm tra trên mạng xã hội trước khi thử lại thay vì đăng lại ngay.
     */
    val outcomeUnknown: Boolean = false,
)
