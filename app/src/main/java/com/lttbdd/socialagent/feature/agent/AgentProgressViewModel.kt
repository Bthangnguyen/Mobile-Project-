package com.lttbdd.socialagent.feature.agent

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lttbdd.socialagent.core.ai.ContentAgent
import com.lttbdd.socialagent.core.model.AgentEvent
import com.lttbdd.socialagent.core.model.AgentStep
import com.lttbdd.socialagent.core.model.ChannelType
import com.lttbdd.socialagent.core.model.TopicBrief
import com.lttbdd.socialagent.core.ui.components.ai.AgentStepStatus
import com.lttbdd.socialagent.data.ai.ChannelFailureReason
import com.lttbdd.socialagent.data.review.DraftReviewRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * ViewModel điều phối tiến độ từng bước và stream nội dung theo thời gian thực của AI Agent (F2, Issue #29).
 *
 * - Giao tiếp với AI qua hợp đồng [ContentAgent] trong `core/ai/`.
 * - Lưu bản nháp hoàn chỉnh qua [DraftReviewRepository.saveAgentDraft] (luôn đảm bảo `approved = false`).
 * - Cách ly bộ đệm stream theo từng kênh và từng bước: khi bước `SELF_REVIEW` bắt đầu phát chữ mới,
 *   nội dung bài viết chuyển mượt sang bản đang rà soát; bước `IMAGE_PROMPT` ghi vào vùng xem trước mô tả ảnh
 *   riêng để không ghi đè nội dung bài viết.
 */
@HiltViewModel
class AgentProgressViewModel @Inject constructor(
    private val contentAgent: ContentAgent,
    private val draftReviewRepository: DraftReviewRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(AgentProgressUiState())
    val uiState: StateFlow<AgentProgressUiState> = _uiState.asStateFlow()

    private var generationJob: Job? = null
    private var activeBriefId: String? = null

    /** Bộ đệm stream theo (kênh -> (bước -> StringBuilder)) giúp cộng dồn delta chính xác và nhanh. */
    private val stepBuffers = mutableMapOf<ChannelType, MutableMap<AgentStep, StringBuilder>>()

    /**
     * Khởi chạy phiên sinh nội dung cho [brief].
     * Bỏ qua nếu [brief.id] đang được xử lý hoặc đã hoàn tất trong cùng ViewModel (an toàn khi xoay màn hình).
     */
    fun startGeneration(brief: TopicBrief) {
        if (brief.channels.isEmpty()) {
            onMissingBrief()
            return
        }
        if (activeBriefId == brief.id && _uiState.value.runStatus != AgentRunStatus.IDLE) {
            return
        }

        generationJob?.cancel()
        stepBuffers.clear()
        activeBriefId = brief.id

        val orderedChannels = brief.channels.sortedBy { it.ordinal }
        _uiState.value = AgentProgressUiState(
            brief = brief,
            briefStatus = AgentStepStatus.IN_PROGRESS,
            briefSummary = "",
            channels = orderedChannels.map { channel ->
                ChannelProgressUiState(channel = channel)
            },
            runStatus = AgentRunStatus.RUNNING,
            missingBrief = false,
        )

        generationJob = viewModelScope.launch {
            try {
                contentAgent.generate(brief).collect { event ->
                    handleEvent(event)
                }
                finalizeRun()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                markUnfinishedChannelsFailed(ChannelFailureReason.UNKNOWN)
                finalizeRun()
            }
        }
    }

    /** Đánh dấu trạng thái thiếu dữ liệu đầu vào khi mở màn hình không có brief hợp lệ. */
    fun onMissingBrief() {
        if (_uiState.value.brief != null) return
        _uiState.update { it.copy(missingBrief = true, runStatus = AgentRunStatus.IDLE) }
    }

    private suspend fun handleEvent(event: AgentEvent) {
        when (event) {
            is AgentEvent.BriefReady -> {
                _uiState.update { current ->
                    current.copy(
                        briefStatus = AgentStepStatus.COMPLETED,
                        briefSummary = event.summary,
                    )
                }
            }

            is AgentEvent.StepStarted -> {
                val channelBuffers = stepBuffers.getOrPut(event.channel) { mutableMapOf() }
                channelBuffers[event.step] = StringBuilder()

                updateChannel(event.channel) { channelState ->
                    val updatedSteps = channelState.stepStatuses.toMutableMap().apply {
                        put(event.step, AgentStepStatus.IN_PROGRESS)
                    }
                    channelState.copy(
                        status = ChannelRunStatus.RUNNING,
                        stepStatuses = updatedSteps,
                        activeStep = event.step,
                    )
                }
            }

            is AgentEvent.TextDelta -> {
                if (event.delta.isEmpty()) return
                val channelBuffers = stepBuffers.getOrPut(event.channel) { mutableMapOf() }
                val stepText = channelBuffers.getOrPut(event.step) { StringBuilder() }
                    .append(event.delta)
                    .toString()

                updateChannel(event.channel) { channelState ->
                    when (event.step) {
                        AgentStep.IMAGE_PROMPT -> channelState.copy(
                            status = ChannelRunStatus.RUNNING,
                            imageDescriptionPreview = stepText,
                        )

                        else -> channelState.copy(
                            status = ChannelRunStatus.RUNNING,
                            streamedContent = stepText,
                            streamingStep = event.step,
                        )
                    }
                }
            }

            is AgentEvent.StepFinished -> {
                val stepTokens = event.usage.inputTokens + event.usage.outputTokens
                updateChannel(event.channel) { channelState ->
                    val updatedSteps = channelState.stepStatuses.toMutableMap().apply {
                        put(event.step, AgentStepStatus.COMPLETED)
                    }
                    channelState.copy(
                        stepStatuses = updatedSteps,
                        totalTokens = channelState.totalTokens + stepTokens,
                    )
                }
            }

            is AgentEvent.ChannelDone -> {
                val saveResult = draftReviewRepository.saveAgentDraft(event.draft)
                val savedDraft = saveResult.getOrNull()
                updateChannel(event.draft.channel) { channelState ->
                    channelState.copy(
                        status = ChannelRunStatus.DONE,
                        activeStep = null,
                        streamedContent = event.draft.text,
                        streamingStep = AgentStep.PACKAGE,
                        savedDraftId = savedDraft?.id,
                        draftSaveError = saveResult.isFailure,
                        failureReasonCode = null,
                        failedStep = null,
                    )
                }
            }

            is AgentEvent.ChannelFailed -> {
                updateChannel(event.channel) { channelState ->
                    val updatedSteps = channelState.stepStatuses.toMutableMap().apply {
                        put(event.step, AgentStepStatus.FAILED)
                    }
                    channelState.copy(
                        status = ChannelRunStatus.FAILED,
                        stepStatuses = updatedSteps,
                        activeStep = null,
                        failureReasonCode = event.reason,
                        failedStep = event.step,
                    )
                }
            }
        }
    }

    private fun markUnfinishedChannelsFailed(reason: String) {
        _uiState.update { current ->
            val updatedChannels = current.channels.map { channelState ->
                if (channelState.status == ChannelRunStatus.DONE || channelState.status == ChannelRunStatus.FAILED) {
                    channelState
                } else {
                    val failedStep = channelState.activeStep ?: AgentStep.OUTLINE
                    val updatedSteps = channelState.stepStatuses.toMutableMap().apply {
                        put(failedStep, AgentStepStatus.FAILED)
                    }
                    channelState.copy(
                        status = ChannelRunStatus.FAILED,
                        stepStatuses = updatedSteps,
                        activeStep = null,
                        failureReasonCode = reason,
                        failedStep = failedStep,
                    )
                }
            }
            val updatedBriefStatus = if (current.briefStatus == AgentStepStatus.COMPLETED) {
                AgentStepStatus.COMPLETED
            } else {
                AgentStepStatus.FAILED
            }
            current.copy(
                briefStatus = updatedBriefStatus,
                channels = updatedChannels,
            )
        }
    }

    private fun finalizeRun() {
        _uiState.update { current ->
            val doneCount = current.channels.count { it.status == ChannelRunStatus.DONE }
            val failedCount = current.channels.count { it.status == ChannelRunStatus.FAILED }
            val finalStatus = when {
                current.channels.isEmpty() -> AgentRunStatus.FAILED
                failedCount == 0 && doneCount == current.channels.size -> AgentRunStatus.COMPLETED
                doneCount > 0 -> AgentRunStatus.PARTIAL_FAILURE
                else -> AgentRunStatus.FAILED
            }
            val updatedBriefStatus = if (current.briefStatus == AgentStepStatus.IN_PROGRESS) {
                if (doneCount > 0) AgentStepStatus.COMPLETED else AgentStepStatus.FAILED
            } else {
                current.briefStatus
            }
            current.copy(
                briefStatus = updatedBriefStatus,
                runStatus = finalStatus,
            )
        }
    }

    private fun updateChannel(
        channel: ChannelType,
        transform: (ChannelProgressUiState) -> ChannelProgressUiState,
    ) {
        _uiState.update { current ->
            current.copy(
                channels = current.channels.map { item ->
                    if (item.channel == channel) transform(item) else item
                },
            )
        }
    }
}
