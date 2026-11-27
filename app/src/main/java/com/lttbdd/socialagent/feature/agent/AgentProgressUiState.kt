package com.lttbdd.socialagent.feature.agent

import com.lttbdd.socialagent.core.model.AgentStep
import com.lttbdd.socialagent.core.model.ChannelType
import com.lttbdd.socialagent.core.model.TopicBrief
import com.lttbdd.socialagent.core.ui.components.ai.AgentStepStatus

/** Trạng thái tổng thể của phiên chạy AI Agent. */
enum class AgentRunStatus {
    IDLE,
    RUNNING,
    COMPLETED,
    PARTIAL_FAILURE,
    FAILED,
}

/** Trạng thái chạy của sub-agent trên một kênh cụ thể. */
enum class ChannelRunStatus {
    WAITING,
    RUNNING,
    DONE,
    FAILED,
}

/**
 * Trạng thái tiến độ từng bước và nội dung đang stream của một kênh (F2, Issue #29).
 */
data class ChannelProgressUiState(
    val channel: ChannelType,
    val status: ChannelRunStatus = ChannelRunStatus.WAITING,
    val stepStatuses: Map<AgentStep, AgentStepStatus> = AgentStep.entries.associateWith { AgentStepStatus.WAITING },
    val activeStep: AgentStep? = null,
    /** Nội dung đang stream hoặc nội dung cuối cùng của bài viết sau khi hoàn tất. */
    val streamedContent: String = "",
    /** Bước tương ứng với [streamedContent] để hiển thị nhãn ngữ cảnh cho người dùng. */
    val streamingStep: AgentStep? = null,
    /** Mô tả ảnh được stream ở bước [AgentStep.IMAGE_PROMPT], tách riêng để không ghi đè nội dung bài viết. */
    val imageDescriptionPreview: String = "",
    /** ID bản nháp đã lưu vào Room (luôn ở trạng thái chưa duyệt) để mở màn hình duyệt bài. */
    val savedDraftId: String? = null,
    val draftSaveError: Boolean = false,
    val failureReasonCode: String? = null,
    val failedStep: AgentStep? = null,
    val totalTokens: Int = 0,
)

/**
 * Trạng thái UI tổng thể cho màn hình tiến độ và stream nội dung của Agent (F2, Issue #29).
 */
data class AgentProgressUiState(
    val brief: TopicBrief? = null,
    val briefStatus: AgentStepStatus = AgentStepStatus.WAITING,
    val briefSummary: String = "",
    val channels: List<ChannelProgressUiState> = emptyList(),
    val runStatus: AgentRunStatus = AgentRunStatus.IDLE,
    val missingBrief: Boolean = false,
) {
    val isRunning: Boolean
        get() = runStatus == AgentRunStatus.RUNNING

    val completedChannelCount: Int
        get() = channels.count { it.status == ChannelRunStatus.DONE }

    val failedChannelCount: Int
        get() = channels.count { it.status == ChannelRunStatus.FAILED }

    val hasAnySavedDraft: Boolean
        get() = channels.any { it.savedDraftId != null }
}
