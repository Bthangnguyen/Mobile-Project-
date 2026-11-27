package com.lttbdd.socialagent.feature.publishing

import androidx.annotation.StringRes
import com.lttbdd.socialagent.core.model.PostStatus
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
    /** Link bài gốc đã kiểm tra là http/https, chỉ có khi bài đã đăng (#40); null thì ẩn nút Mở bài. */
    val openUrl: String? = null,
    /** Lý do lỗi dễ hiểu (#39): bài lỗi, hoặc bài đang chờ tự thử lại sau lỗi tạm thời. */
    @param:StringRes val reasonRes: Int? = null,
    /** Lần bấm đăng gần nhất không đưa được kênh này vào hàng đợi (#111). */
    val enqueueFailed: Boolean = false,
) {
    /** Bài vẫn ở hàng đợi sau lỗi tạm thời: hàng đợi sẽ tự thử lại. */
    val isRetrying: Boolean get() = state.status == PostStatus.QUEUED && reasonRes != null

    /** Nút đăng của dòng này là "Thử lại": từng đăng lỗi, hoặc vừa không vào được hàng đợi. */
    val isRetryAction: Boolean get() = action == ChannelAction.PUBLISH && (state.postId != null || enqueueFailed)
}

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

    /** Hỏi lại trước khi đánh dấu "Bài đã lên", vì sau đó không đăng lại bài này từ app được nữa (#111). */
    data class ConfirmPublished(val postId: String) : PublishStatusEvent

    /** "Đăng tất cả" có kênh lỗi (#111): [queued] kênh đang ở hàng đợi, [failed] kênh không vào được. */
    data class PublishAllPartial(val queued: Int, val failed: Int) : PublishStatusEvent
}
