package com.lttbdd.socialagent.data.ai

import com.lttbdd.socialagent.core.ai.ContentAgent
import com.lttbdd.socialagent.core.model.AgentEvent
import com.lttbdd.socialagent.core.model.AgentStep
import com.lttbdd.socialagent.core.model.ChannelType
import com.lttbdd.socialagent.core.model.Draft
import com.lttbdd.socialagent.core.model.RewriteInstruction
import com.lttbdd.socialagent.core.model.TopicBrief
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * Orchestrator của F2 (Issue #26): lập brief chung rồi chạy song song mỗi kênh một [ChannelSubAgent].
 *
 * Bảo đảm:
 * - Mỗi kênh phát đúng một sự kiện kết thúc ([AgentEvent.ChannelDone] hoặc [AgentEvent.ChannelFailed]).
 * - Lỗi của một kênh không làm dừng kênh khác; lý do lỗi chỉ là mã an toàn, không chứa key/prompt.
 * - Huỷ việc collect Flow huỷ toàn bộ sub-agent; [CancellationException] không bị đổi thành lỗi nghiệp vụ.
 * - Không tự đăng bài: chỉ sinh bản nháp, việc duyệt/đăng thuộc F4/F5.
 *
 * Mỗi lần gọi [generate]/[regenerate] là một Flow lạnh độc lập nên luồng cũ không ghi đè kết quả lần chạy mới.
 *
 * [rewriteParagraph] và [regenerateImage] chưa thuộc Issue #26 (thuộc #35 và #28) nên ném
 * [UnsupportedOperationException]; vì vậy lớp này chưa được gắn vào DI thay cho `FakeContentAgent`.
 */
class ContentAgentImpl(
    private val subAgents: Map<ChannelType, ChannelSubAgent>,
    private val maxConcurrentChannels: Int = DEFAULT_MAX_CONCURRENT_CHANNELS,
) : ContentAgent {

    init {
        require(maxConcurrentChannels > 0) { "maxConcurrentChannels phải > 0" }
    }

    override fun generate(brief: TopicBrief): Flow<AgentEvent> = channelFlow {
        val summary = buildSharedBriefSummary(brief)
        send(AgentEvent.BriefReady(summary))
        val gate = Semaphore(maxConcurrentChannels)
        supervisorScope {
            brief.channels.sortedBy { it.ordinal }.forEach { channel ->
                launch { gate.withPermit { runChannel(brief, channel, summary) } }
            }
        }
    }

    override fun regenerate(brief: TopicBrief, channel: ChannelType): Flow<AgentEvent> = channelFlow {
        if (channel !in brief.channels) {
            send(AgentEvent.ChannelFailed(channel, AgentStep.OUTLINE, ChannelFailureReason.CHANNEL_NOT_IN_BRIEF))
        } else {
            runChannel(brief, channel, buildSharedBriefSummary(brief))
        }
    }

    override suspend fun rewriteParagraph(
        draft: Draft,
        paragraph: String,
        instruction: RewriteInstruction,
    ): String = throw UnsupportedOperationException("Viết lại đoạn thuộc Issue #35")

    override suspend fun regenerateImage(draft: Draft): String =
        throw UnsupportedOperationException("Sinh lại ảnh thuộc Issue #28")

    /** Trạng thái riêng của một lần chạy kênh; @Volatile vì sub-agent có thể phát từ nhiều coroutine con. */
    private class ChannelRun {
        @Volatile var step = AgentStep.OUTLINE

        @Volatile var finished = false
    }

    private suspend fun ProducerScope<AgentEvent>.runChannel(
        brief: TopicBrief,
        channel: ChannelType,
        summary: String,
    ) {
        val subAgent = subAgents[channel]
        if (subAgent == null) {
            send(AgentEvent.ChannelFailed(channel, AgentStep.OUTLINE, ChannelFailureReason.UNSUPPORTED_CHANNEL))
            return
        }
        val run = ChannelRun()
        try {
            subAgent.run(ChannelRunContext(brief, channel, summary)) { event ->
                // Orchestrator sở hữu BriefReady; sự kiện của kênh khác hoặc sau khi đã kết thúc bị bỏ để cách ly kênh.
                if (run.finished || !event.belongsTo(channel)) return@run
                when (event) {
                    is AgentEvent.StepStarted -> run.step = event.step
                    is AgentEvent.ChannelDone, is AgentEvent.ChannelFailed -> run.finished = true
                    else -> Unit
                }
                send(event)
            }
            if (!run.finished) {
                run.finished = true
                send(AgentEvent.ChannelFailed(channel, run.step, ChannelFailureReason.NO_RESULT))
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            if (!run.finished) {
                run.finished = true
                val step = (error as? ChannelSubAgentException)?.step ?: run.step
                send(AgentEvent.ChannelFailed(channel, step, error.toSafeReason()))
            }
        }
    }

    private fun AgentEvent.belongsTo(channel: ChannelType): Boolean = when (this) {
        is AgentEvent.BriefReady -> false
        is AgentEvent.StepStarted -> this.channel == channel
        is AgentEvent.TextDelta -> this.channel == channel
        is AgentEvent.StepFinished -> this.channel == channel
        is AgentEvent.ChannelDone -> draft.channel == channel
        is AgentEvent.ChannelFailed -> this.channel == channel
    }

    /** Không dùng `message` của exception lạ vì có thể chứa URL/key/prompt. */
    private fun Exception.toSafeReason(): String = when (this) {
        is ChannelSubAgentException -> reason
        is LlmException -> ChannelFailureReason.fromLlm(failure)
        else -> ChannelFailureReason.UNKNOWN
    }

    companion object {
        /** Đủ để Facebook + LinkedIn chạy cùng lúc; hạn chế bùng nổ request/rate limit khi thêm kênh. */
        const val DEFAULT_MAX_CONCURRENT_CHANNELS = 2
    }
}
