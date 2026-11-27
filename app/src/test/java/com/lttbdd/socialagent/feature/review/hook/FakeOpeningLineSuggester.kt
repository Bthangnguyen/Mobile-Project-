package com.lttbdd.socialagent.feature.review.hook

import com.lttbdd.socialagent.core.model.ChannelType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred

/**
 * [OpeningLineSuggester] giả, điều khiển được trong test: ghi lại từng lần gọi, trả kết quả theo hàng đợi,
 * và có thể treo bằng [gate] để kiểm tra trạng thái đang sinh hoặc huỷ giữa chừng.
 */
class FakeOpeningLineSuggester : OpeningLineSuggester {

    data class Call(val draftText: String, val channel: ChannelType, val avoid: List<String>)

    val calls = mutableListOf<Call>()

    /** Kết quả trả lần lượt cho mỗi lần gọi; hết hàng đợi thì dùng [defaultResult]. */
    val results = ArrayDeque<Result<List<String>>>()
    var defaultResult: Result<List<String>> = Result.success(DEFAULT_OPTIONS)

    /** Khác `null` thì mỗi lần gọi treo tới khi [gate] hoàn tất. */
    var gate: CompletableDeferred<Unit>? = null
    var cancelledCalls = 0
        private set

    override suspend fun suggest(
        draftText: String,
        channel: ChannelType,
        avoid: List<String>,
    ): List<String> {
        calls += Call(draftText, channel, avoid)
        try {
            gate?.await()
        } catch (ce: CancellationException) {
            cancelledCalls++
            throw ce
        }
        return (results.removeFirstOrNull() ?: defaultResult).getOrThrow()
    }

    companion object {
        val DEFAULT_OPTIONS = listOf(
            "Bạn đã bao giờ tự hỏi vì sao bài đăng không ai đọc?",
            "Ba dòng đầu quyết định người đọc có ở lại hay không.",
            "Sáng nay, tôi xoá bản nháp thứ năm trong tuần.",
        )
    }
}
