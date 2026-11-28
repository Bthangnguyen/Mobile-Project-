package com.lttbdd.socialagent.feature.publishing

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lttbdd.socialagent.R
import com.lttbdd.socialagent.core.model.PostStatus
import com.lttbdd.socialagent.core.publishing.ChannelPublishState
import com.lttbdd.socialagent.core.publishing.EnqueueResult
import com.lttbdd.socialagent.core.publishing.PublishQueue
import com.lttbdd.socialagent.core.publishing.PublishTracker
import com.lttbdd.socialagent.core.publishing.TopicPublishState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.net.URI
import java.net.URISyntaxException
import javax.inject.Inject

/**
 * Màn Trạng thái đăng (F5, #36): đăng một lúc lên mọi kênh đã duyệt của một topic và theo dõi từng kênh
 * qua nháp → chờ → đang đăng → đã đăng / lỗi / đã bàn giao.
 *
 * Chỉ đưa vào hàng đợi bản nháp đã duyệt (F4); hàng đợi kiểm tra lại lần nữa nên không có đường đăng bài chưa duyệt.
 */
@HiltViewModel
class PublishStatusViewModel @Inject constructor(
    private val tracker: PublishTracker,
    private val publishQueue: PublishQueue,
) : ViewModel() {

    /**
     * Bản nháp vừa đưa vào hàng đợi không thành công (#111): dòng của kênh đó hiện nút "Thử lại" dù chưa từng
     * có bài đăng. Bỏ khỏi tập khi đưa vào hàng đợi được.
     */
    private val enqueueFailedDraftIds = MutableStateFlow<Set<String>>(emptySet())

    val uiState: StateFlow<PublishStatusUiState> = combine(
        tracker.observeTopics(),
        enqueueFailedDraftIds,
    ) { topics, failed ->
        PublishStatusUiState(isLoading = false, topics = topics.map { it.toItem(failed) })
    }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
            initialValue = PublishStatusUiState(isLoading = true),
        )

    private val _events = Channel<PublishStatusEvent>(Channel.BUFFERED)
    val events: Flow<PublishStatusEvent> = _events.receiveAsFlow()

    /**
     * Đưa mọi kênh đã duyệt chưa đăng của topic vào hàng đợi. Mỗi kênh vào hàng đợi riêng: một kênh lỗi
     * không chặn kênh khác. Dùng trạng thái đang hiển thị; hàng đợi tự chặn trùng nếu trạng thái vừa đổi.
     */
    fun onPublishAll(topicId: String) {
        val topic = uiState.value.topics.firstOrNull { it.topicId == topicId } ?: return
        val targets = topic.channels.filter { it.action == ChannelAction.PUBLISH }
        if (targets.isEmpty()) {
            send(R.string.publish_status_nothing_to_publish)
            return
        }
        val skippedUnapproved = topic.channels.any { it.action == ChannelAction.NEEDS_APPROVAL }
        viewModelScope.launch {
            var queued = 0
            var alreadyActive = 0
            var failed = 0
            var notApproved = 0
            for (target in targets) {
                when (trackedEnqueue(target.state.draftId)) {
                    is EnqueueResult.Queued -> queued++
                    // Đã ở hàng đợi (bấm hai lần, hoặc màn hình chưa kịp cập nhật): không phải lỗi.
                    is EnqueueResult.AlreadyActive -> alreadyActive++
                    is EnqueueResult.AlreadyPublished -> Unit
                    // Bản nháp vừa mất duyệt sau khi màn hình hiển thị: dòng đó chuyển sang "Cần duyệt",
                    // không có nút "Thử lại", nên không tính là kênh lỗi (review #117).
                    EnqueueResult.NotApproved -> notApproved++
                    else -> failed++
                }
            }
            when {
                // #111: nói rõ bao nhiêu kênh đã nằm trong hàng đợi và bao nhiêu kênh lỗi; kênh lỗi hiện "Thử lại".
                failed > 0 -> _events.trySend(PublishStatusEvent.PublishAllPartial(queued + alreadyActive, failed))
                queued == 0 && alreadyActive == 0 && notApproved > 0 -> send(R.string.review_publish_blocked_unapproved)
                queued == 0 -> send(R.string.review_publish_already_queued)
                skippedUnapproved || notApproved > 0 -> send(R.string.publish_status_queued_some_unapproved)
                else -> send(R.string.publish_status_all_queued)
            }
        }
    }

    /** Đăng (hoặc đăng lại sau khi lỗi) một kênh. */
    fun onPublishChannel(draftId: String) {
        viewModelScope.launch {
            send(
                when (val result = trackedEnqueue(draftId)) {
                    is EnqueueResult.Queued -> R.string.review_publish_queued
                    is EnqueueResult.AlreadyActive ->
                        if (result.status == PostStatus.QUEUED) {
                            R.string.review_publish_already_queued
                        } else {
                            R.string.review_publish_in_progress
                        }
                    is EnqueueResult.AlreadyPublished -> R.string.review_publish_already_published
                    EnqueueResult.NotApproved -> R.string.review_publish_blocked_unapproved
                    EnqueueResult.DraftNotFound, null -> R.string.publish_status_action_failed
                },
            )
        }
    }

    /** Hỏi lại trước khi đánh dấu "Bài đã lên" (#111): sau đó hàng đợi không cho đăng lại bài này nữa. */
    fun onConfirmPublishedRequested(postId: String) {
        _events.trySend(PublishStatusEvent.ConfirmPublished(postId))
    }

    /** Người dùng đã đồng ý trong hộp thoại xác nhận. */
    fun onConfirmPublished(postId: String) {
        viewModelScope.launch {
            send(
                when (safeCall { tracker.confirmPublished(postId) }) {
                    true -> R.string.publish_status_marked_published
                    false -> R.string.publish_status_state_changed
                    null -> R.string.publish_status_action_failed
                },
            )
        }
    }

    fun onRequeueRequested(postId: String) {
        _events.trySend(PublishStatusEvent.ConfirmRequeue(postId))
    }

    fun onRequeueConfirmed(postId: String) {
        viewModelScope.launch {
            send(
                when (safeCall { tracker.requeueAfterCheck(postId) }) {
                    true -> R.string.publish_status_requeued
                    false -> R.string.publish_status_state_changed
                    null -> R.string.publish_status_action_failed
                },
            )
        }
    }

    /**
     * Đưa vào hàng đợi và ghi nhớ kênh không vào được (lỗi lưu trữ hoặc không tìm thấy bản nháp) để hiện "Thử lại".
     * Trả null khi lỗi lưu trữ; huỷ coroutine vẫn được truyền tiếp.
     */
    private suspend fun trackedEnqueue(draftId: String): EnqueueResult? {
        val result = safeCall { publishQueue.enqueue(draftId) }
        val failed = result == null || result == EnqueueResult.DraftNotFound
        enqueueFailedDraftIds.update { if (failed) it + draftId else it - draftId }
        return result
    }

    private suspend fun <T> safeCall(block: suspend () -> T): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }

    private fun send(@StringRes messageRes: Int) {
        _events.trySend(PublishStatusEvent.ShowMessage(messageRes))
    }

    private fun TopicPublishState.toItem(enqueueFailed: Set<String>) = TopicPublishItem(
        topicId = topicId,
        previewText = previewText,
        channels = channels.map {
            ChannelPublishItem(
                state = it,
                action = actionFor(it),
                openUrl = openableUrl(it),
                reasonRes = reasonFor(it),
                enqueueFailed = it.draftId in enqueueFailed,
            )
        },
    )

    companion object {
        private const val STOP_TIMEOUT_MILLIS = 5_000L

        /**
         * Link bài gốc mở được (#40): chỉ bài [PostStatus.PUBLISHED] và chỉ `http`/`https`. Link do API mạng xã hội
         * trả về hoặc người dùng nhập, nên chặn scheme khác (`intent:`, `javascript:`, `file:`…) để không mở nhầm
         * ứng dụng hay tệp trên máy.
         */
        fun openableUrl(state: ChannelPublishState): String? {
            if (state.status != PostStatus.PUBLISHED) return null
            // Không trim: AC #40 chặn link có khoảng trắng ở bất kỳ đâu, kể cả đầu/cuối (review của Minh ở #106).
            val url = state.url ?: return null
            if (url.isEmpty() || url.any(Char::isWhitespace)) return null
            // Parse thay vì chỉ so tiền tố, để chặn link thiếu host như `https:///post/1` hay `https://?q=1`.
            val uri = try {
                URI(url)
            } catch (_: URISyntaxException) {
                return null
            }
            val scheme = uri.scheme?.lowercase()
            return url.takeIf { scheme in WEB_SCHEMES && !uri.host.isNullOrEmpty() }
        }

        private val WEB_SCHEMES = setOf("https", "http")

        /** Lý do hiển thị (#39): chỉ cho bài lỗi và bài đang chờ thử lại; bài đã đăng có thể còn lý do cũ. */
        @StringRes
        fun reasonFor(state: ChannelPublishState): Int? = when (state.status) {
            PostStatus.FAILED, PostStatus.QUEUED -> PublishErrorMessages.messageFor(state.errorReason)
            else -> null
        }

        /** Thao tác cho một kênh; tách hàm thuần để test. */
        fun actionFor(state: ChannelPublishState): ChannelAction = when {
            state.needsConfirmation && state.postId != null -> ChannelAction.CONFIRM_OUTCOME
            state.status == PostStatus.DRAFT || state.status == PostStatus.FAILED ->
                if (state.approved) ChannelAction.PUBLISH else ChannelAction.NEEDS_APPROVAL
            else -> ChannelAction.NONE
        }
    }
}
