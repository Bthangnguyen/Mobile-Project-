package com.lttbdd.socialagent.feature.publishing

import androidx.annotation.StringRes
import com.lttbdd.socialagent.core.publishing.ChannelPublishState

/** Việc người dùng làm được với một kênh trên màn Trạng thái đăng. */
enum class ChannelAction {
    /** Đã duyệt, chưa đăng hoặc lần trước lỗi: bấm để đưa vào hàng đợi. */
    PUBLISH,

    /** Chưa duyệt: phải duyệt ở màn Duyệt bài trước (F4), không đăng từ đây. */
    NEEDS_APPROVAL,

    /** Không rõ bài đã lên chưa: người dùng kiểm tra trên mạng xã hội rồi xác nhận. */
    CONFIRM_OUTCOME,

    /** Đang chờ, đang đăng, đã đăng hoặc đã bàn giao: không có thao tác. */
    NONE,
}

data class ChannelPublishItem(
    val state: ChannelPublishState,
    val action: ChannelAction,
)

data class TopicPublishItem(
    val topicId: String,
    val previewText: String,
    val channels: List<ChannelPublishItem>,
) {
    /** Còn ít nhất một kênh đã duyệt chưa vào hàng đợi. */
    val canPublishAll: Boolean get() = channels.any { it.action == ChannelAction.PUBLISH }
}

data class PublishStatusUiState(
    val isLoading: Boolean = true,
    val topics: List<TopicPublishItem> = emptyList(),
)

sealed interface PublishStatusEvent {
    data class ShowMessage(@param:StringRes val messageRes: Int) : PublishStatusEvent

    /** Hỏi lại trước khi đưa bài không rõ kết quả vào hàng đợi, vì có thể làm bài bị đăng hai lần. */
    data class ConfirmRequeue(val postId: String) : PublishStatusEvent
}
