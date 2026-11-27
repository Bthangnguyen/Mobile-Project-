package com.lttbdd.socialagent.data.ai

import com.lttbdd.socialagent.core.model.AgentEvent
import com.lttbdd.socialagent.core.model.AgentStep
import com.lttbdd.socialagent.core.model.ChannelType
import com.lttbdd.socialagent.core.model.TopicBrief
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * Bộ ghi nhật ký tự động theo dòng sự kiện [AgentEvent] (F2, Issue #31).
 * Có thể bọc bất kỳ `Flow<AgentEvent>` nào từ `ContentAgent.generate`/`regenerate` mà không cần đổi `core/`.
 * Mọi lỗi khi lưu nhật ký được nuốt an toàn để không bao giờ làm hỏng luồng sinh bài.
 */
class AgentLogRecorder(
    private val store: AgentLogStore,
    private val clock: () -> Long = System::currentTimeMillis,
    private val idGenerator: () -> String = { UUID.randomUUID().toString() },
) {

    /**
     * Bọc dòng [events] của [brief] để tự động thu thập từng bước, prompt/brief, kết quả stream,
     * số token, chi phí và lưu xuống [store] khi kênh hoàn tất, lỗi hoặc bị hủy.
     */
    fun recordStream(
        brief: TopicBrief,
        events: Flow<AgentEvent>,
    ): Flow<AgentEvent> {
        val runStartedAt = clock()
        var sharedBrief = buildSharedBriefSummary(brief)
        val channelStates = linkedMapOf<ChannelType, MutableChannelLogState>()

        fun stateOf(channel: ChannelType): MutableChannelLogState =
            channelStates.getOrPut(channel) {
                MutableChannelLogState(
                    id = idGenerator(),
                    startedAtMillis = runStartedAt,
                )
            }

        return events
            .onEach { event ->
                val now = clock()
                when (event) {
                    is AgentEvent.BriefReady -> {
                        sharedBrief = event.summary
                    }
                    is AgentEvent.StepStarted -> {
                        val state = stateOf(event.channel)
                        state.steps[event.step] = MutableStepState(
                            step = event.step,
                            prompt = sharedBrief,
                            startedAtMillis = now,
                        )
                    }
                    is AgentEvent.TextDelta -> {
                        val state = stateOf(event.channel)
                        val stepState = state.steps.getOrPut(event.step) {
                            MutableStepState(
                                step = event.step,
                                prompt = sharedBrief,
                                startedAtMillis = now,
                            )
                        }
                        stepState.output.append(event.delta)
                    }
                    is AgentEvent.StepFinished -> {
                        val state = stateOf(event.channel)
                        val stepState = state.steps.getOrPut(event.step) {
                            MutableStepState(
                                step = event.step,
                                prompt = sharedBrief,
                                startedAtMillis = now,
                            )
                        }
                        stepState.status = AgentStepStatus.SUCCEEDED
                        stepState.inputTokens = event.usage.inputTokens.coerceAtLeast(0)
                        stepState.outputTokens = event.usage.outputTokens.coerceAtLeast(0)
                        stepState.costUsd = event.usage.costUsd.takeIf { it.isFinite() && it >= 0.0 }
                        stepState.finishedAtMillis = now
                    }
                    is AgentEvent.ChannelDone -> {
                        val state = stateOf(event.draft.channel)
                        val packageStep = state.steps[AgentStep.PACKAGE]
                        if (packageStep != null && packageStep.output.isEmpty()) {
                            packageStep.output.append(event.draft.text)
                        }
                        state.finishedAtMillis = now
                        persistBestEffort(state.toRunLog(brief, event.draft.channel, sharedBrief))
                        state.persisted = true
                    }
                    is AgentEvent.ChannelFailed -> {
                        val state = stateOf(event.channel)
                        val stepState = state.steps.getOrPut(event.step) {
                            MutableStepState(
                                step = event.step,
                                prompt = sharedBrief,
                                startedAtMillis = now,
                            )
                        }
                        stepState.status = AgentStepStatus.FAILED
                        stepState.errorCode = event.reason
                        stepState.finishedAtMillis = now
                        state.finishedAtMillis = now
                        persistBestEffort(state.toRunLog(brief, event.channel, sharedBrief))
                        state.persisted = true
                    }
                }
            }
            .onCompletion { cause ->
                val now = clock()
                channelStates.forEach { (channel, state) ->
                    if (!state.persisted && state.steps.isNotEmpty()) {
                        state.steps.values.forEach { step ->
                            if (step.status == AgentStepStatus.RUNNING) {
                                step.status = if (cause != null) AgentStepStatus.CANCELLED else AgentStepStatus.FAILED
                                step.finishedAtMillis = now
                            }
                        }
                        state.finishedAtMillis = now
                        persistBestEffort(state.toRunLog(brief, channel, sharedBrief))
                        state.persisted = true
                    }
                }
            }
    }

    /** Ghi trực tiếp một [AgentRunLog] đã có đầy đủ prompt và kết quả chi tiết từng bước. */
    suspend fun recordRun(log: AgentRunLog) {
        persistBestEffort(log)
    }

    private suspend fun persistBestEffort(log: AgentRunLog) {
        try {
            withContext(NonCancellable) {
                store.recordRun(log)
            }
        } catch (_: Exception) {
            // Lỗi ghi nhật ký không bao giờ được làm hỏng luồng sinh bài của agent.
        }
    }

    private class MutableChannelLogState(
        val id: String,
        val startedAtMillis: Long,
        var finishedAtMillis: Long? = null,
        var persisted: Boolean = false,
        val steps: LinkedHashMap<AgentStep, MutableStepState> = linkedMapOf(),
    ) {
        fun toRunLog(brief: TopicBrief, channel: ChannelType, sharedBrief: String): AgentRunLog =
            AgentRunLog(
                id = id,
                topicId = brief.id,
                topic = brief.topic,
                channel = channel,
                sharedBriefSummary = sharedBrief,
                startedAtMillis = startedAtMillis,
                finishedAtMillis = finishedAtMillis,
                steps = steps.values.map { it.toEntry() },
            )
    }

    private class MutableStepState(
        val step: AgentStep,
        val prompt: String,
        val startedAtMillis: Long,
        var finishedAtMillis: Long? = null,
        var status: AgentStepStatus = AgentStepStatus.RUNNING,
        val output: StringBuilder = StringBuilder(),
        var inputTokens: Int = 0,
        var outputTokens: Int = 0,
        var costUsd: Double? = null,
        var errorCode: String? = null,
    ) {
        fun toEntry(): AgentStepLogEntry = AgentStepLogEntry(
            step = step,
            status = status,
            prompt = prompt,
            output = output.toString().trim(),
            inputTokens = inputTokens,
            outputTokens = outputTokens,
            costUsd = costUsd,
            errorCode = errorCode,
            startedAtMillis = startedAtMillis,
            finishedAtMillis = finishedAtMillis,
        )
    }
}
