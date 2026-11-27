package com.lttbdd.socialagent.data.ai

import com.lttbdd.socialagent.core.ai.ContentAgent
import com.lttbdd.socialagent.core.model.AgentEvent
import com.lttbdd.socialagent.core.model.AgentStep
import com.lttbdd.socialagent.core.model.ChannelType
import com.lttbdd.socialagent.core.model.Draft
import com.lttbdd.socialagent.core.model.TopicBrief
import com.lttbdd.socialagent.data.review.DraftHistoryWriteException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Lưu bản nháp trước khi báo kênh hoàn tất, dùng chung cho lần sinh đầu và lần chạy lại. */
internal class SavingContentAgent(
    private val agent: ContentAgent,
    private val save: suspend (Draft) -> Draft,
) : ContentAgent by agent {
    override fun generate(brief: TopicBrief): Flow<AgentEvent> = agent.generate(brief).saveResults()

    override fun regenerate(brief: TopicBrief, channel: ChannelType): Flow<AgentEvent> =
        agent.regenerate(brief, channel).saveResults()

    private fun Flow<AgentEvent>.saveResults(): Flow<AgentEvent> = map { event ->
        if (event is AgentEvent.ChannelDone) {
            try {
                AgentEvent.ChannelDone(save(event.draft))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (historyError: DraftHistoryWriteException) {
                AgentEvent.ChannelDone(historyError.savedDraft, historyWriteFailed = true)
            } catch (_: Exception) {
                AgentEvent.ChannelFailed(event.draft.channel, AgentStep.PACKAGE, ChannelFailureReason.DRAFT_SAVE_FAILED)
            }
        } else {
            event
        }
    }
}
