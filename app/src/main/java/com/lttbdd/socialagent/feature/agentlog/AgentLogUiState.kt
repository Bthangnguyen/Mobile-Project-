package com.lttbdd.socialagent.feature.agentlog

import androidx.annotation.StringRes
import com.lttbdd.socialagent.core.model.ChannelType
import com.lttbdd.socialagent.data.ai.AgentRunLog

/** Trạng thái hiển thị của một thẻ nhật ký lượt chạy agent (F2, Issue #31). */
data class AgentLogItemUiState(
    val run: AgentRunLog,
    val isExpanded: Boolean,
)

/** Trạng thái UI của màn hình Nhật ký agent (F2, Issue #31). */
data class AgentLogUiState(
    val isLoading: Boolean = true,
    val isLoadError: Boolean = false,
    val isClearing: Boolean = false,
    val selectedChannel: ChannelType? = null,
    val items: List<AgentLogItemUiState> = emptyList(),
    val totalRunCount: Int = 0,
)

/** Sự kiện dùng một lần phát từ [AgentLogViewModel] sang [AgentLogFragment]. */
sealed interface AgentLogEvent {
    data object ConfirmClearAll : AgentLogEvent
    data class ShowMessage(@param:StringRes val messageRes: Int) : AgentLogEvent
}
