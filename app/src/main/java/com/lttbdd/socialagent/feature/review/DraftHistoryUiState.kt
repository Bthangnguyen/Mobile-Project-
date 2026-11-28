package com.lttbdd.socialagent.feature.review

import androidx.annotation.StringRes
import com.lttbdd.socialagent.core.model.ChannelType
import com.lttbdd.socialagent.data.review.DraftVersion

/** Trạng thái hiển thị của từng mục phiên bản trong danh sách lịch sử (F4, Issue #62). */
data class DraftHistoryItemUiState(
    val version: DraftVersion,
    val isCurrent: Boolean,
    val characterCount: Int,
    val canRestore: Boolean,
)

/** Trạng thái UI của màn hình Lịch sử phiên bản bản nháp (F4, Issue #62). */
data class DraftHistoryUiState(
    val isLoading: Boolean = true,
    val isRestoring: Boolean = false,
    val isDraftNotFound: Boolean = false,
    val isLoadError: Boolean = false,
    val isRestoreLockedByPublishing: Boolean = false,
    val draftChannel: ChannelType? = null,
    val versions: List<DraftHistoryItemUiState> = emptyList(),
)

/** Sự kiện dùng một lần phát từ [DraftHistoryViewModel] sang [DraftHistoryFragment]. */
sealed interface DraftHistoryEvent {
    data class ConfirmRestore(val version: DraftVersion) : DraftHistoryEvent
    data class ShowMessage(@param:StringRes val messageRes: Int) : DraftHistoryEvent
    data object RestoredSuccessfully : DraftHistoryEvent
}
