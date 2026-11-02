package com.lttbdd.socialagent.data.fake

import com.lttbdd.socialagent.core.ai.ContentAgent
import com.lttbdd.socialagent.core.model.AgentEvent
import com.lttbdd.socialagent.core.model.AgentStep
import com.lttbdd.socialagent.core.model.ChannelType
import com.lttbdd.socialagent.core.model.Draft
import com.lttbdd.socialagent.core.model.RewriteInstruction
import com.lttbdd.socialagent.core.model.TokenUsage
import com.lttbdd.socialagent.core.model.TopicBrief
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow

/** Agent giả: chạy lần lượt các bước và trả về bài mẫu, để D dựng UI F3/F4 khi C chưa xong agent thật. */
class FakeContentAgent(private val stepDelayMillis: Long = 300) : ContentAgent {

    override fun generate(brief: TopicBrief): Flow<AgentEvent> = flow {
        emit(AgentEvent.BriefReady("Brief chung cho topic: ${brief.topic}"))
        brief.channels.forEach { channel -> emitChannel(brief, channel) }
    }

    override fun regenerate(brief: TopicBrief, channel: ChannelType): Flow<AgentEvent> = flow {
        emitChannel(brief, channel)
    }

    override suspend fun rewriteParagraph(draft: Draft, paragraph: String, instruction: RewriteInstruction): String =
        "[$instruction] $paragraph"

    override suspend fun regenerateImage(draft: Draft): String = "https://picsum.photos/seed/${draft.id}/1200/630"

    private suspend fun FlowCollector<AgentEvent>.emitChannel(
        brief: TopicBrief,
        channel: ChannelType,
    ) {
        AgentStep.entries.forEach { step ->
            emit(AgentEvent.StepStarted(channel, step))
            delay(stepDelayMillis)
            if (step == AgentStep.DRAFT) {
                emit(AgentEvent.TextDelta(channel, step, "Bài mẫu về ${brief.topic} cho ${channel.name}."))
            }
            emit(AgentEvent.StepFinished(channel, step, TokenUsage.ZERO))
        }
        emit(
            AgentEvent.ChannelDone(
                Draft(
                    id = "${brief.id}-${channel.name.lowercase()}",
                    topicId = brief.id,
                    channel = channel,
                    text = "Bài mẫu về ${brief.topic} cho ${channel.name}.",
                ),
            ),
        )
    }
}
