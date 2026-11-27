package com.lttbdd.socialagent.feature.review.hook

/**
 * Trạng thái hộp chọn câu mở đầu (Issue #63). [currentOpening] là câu mở đầu của bài lúc bấm gợi ý.
 * Tách khỏi `DraftPreviewUiState` để việc dựng lại state xem trước không làm mất trạng thái gợi ý.
 */
sealed interface OpeningSuggestionUiState {

    data object Hidden : OpeningSuggestionUiState

    data class Loading(val currentOpening: String?) : OpeningSuggestionUiState

    data class Ready(
        val currentOpening: String?,
        val options: List<OpeningOptionUi>,
    ) : OpeningSuggestionUiState

    data class Error(val currentOpening: String?) : OpeningSuggestionUiState
}

/** Một phương án kèm số ký tự và việc bài có vượt giới hạn kênh thật hay không nếu dùng phương án này. */
data class OpeningOptionUi(
    val text: String,
    val characterCount: Int,
    val exceedsChannelLimit: Boolean,
)
