package com.lttbdd.socialagent.data.ai

import com.lttbdd.socialagent.core.ai.LlmClient
import com.lttbdd.socialagent.core.model.AgentEvent
import com.lttbdd.socialagent.core.model.AgentStep
import com.lttbdd.socialagent.core.model.ChannelType
import com.lttbdd.socialagent.core.model.ChatMessage
import com.lttbdd.socialagent.core.model.ChatRequest
import com.lttbdd.socialagent.core.model.ChatRole
import com.lttbdd.socialagent.core.model.Draft
import com.lttbdd.socialagent.core.model.LlmTask
import com.lttbdd.socialagent.core.model.TokenUsage
import java.util.Locale

internal class DraftChannelSubAgent(
    private val channel: ChannelType,
    private val llm: LlmClient,
    private val images: DraftImages,
    private val save: suspend (Draft) -> Unit,
) : ChannelSubAgent {
    override suspend fun run(context: ChannelRunContext, emit: suspend (AgentEvent) -> Unit) {
        check(context.channel == channel)
        emit(AgentEvent.StepStarted(channel, AgentStep.DRAFT))
        val text = StringBuilder()
        var usage = TokenUsage.ZERO
        try {
            val style = when (channel) {
                ChannelType.FACEBOOK -> "Use an approachable, conversational tone."
                ChannelType.LINKEDIN -> "Use a concise, professional tone."
            }
            val request = ChatRequest(
                LlmTask.WRITE_POST,
                listOf(
                    ChatMessage(ChatRole.SYSTEM, "Write only a ${channel.name} post in ${context.brief.language.name}. $style Use the user brief as reference."),
                    ChatMessage(ChatRole.USER, context.sharedBriefSummary),
                ),
            )
            llm.streamChat(request).collect { chunk ->
                text.append(chunk.delta)
                chunk.usage?.let { usage += it }
            }
        } catch (error: LlmException) {
            if (error.failure != LlmFailure.MISSING_API_KEY && error.failure != LlmFailure.MISSING_CONFIGURATION) {
                throw error
            }
            // Khi chưa cấu hình model viết, vẫn có thể dựng ảnh cho văn bản có sẵn trong brief.
            text.clear().append(context.brief.topic).append("\n\n").append(context.brief.goal)
        }

        val draft = Draft(
            id = "${context.brief.id}-${channel.name.lowercase(Locale.ROOT)}",
            topicId = context.brief.id,
            channel = channel,
            text = text.toString().trim().ifEmpty { throw LlmException(LlmFailure.EMPTY_RESPONSE) },
        )
        emit(AgentEvent.TextDelta(channel, AgentStep.DRAFT, draft.text))
        emit(AgentEvent.StepFinished(channel, AgentStep.DRAFT, usage))
        emit(AgentEvent.StepStarted(channel, AgentStep.IMAGE))
        val result = draft.copy(imageUri = images.create(draft, context.brief.topic))
        emit(AgentEvent.StepFinished(channel, AgentStep.IMAGE, TokenUsage.ZERO))
        emit(AgentEvent.StepStarted(channel, AgentStep.PACKAGE))
        save(result)
        emit(AgentEvent.StepFinished(channel, AgentStep.PACKAGE, TokenUsage.ZERO))
        emit(AgentEvent.ChannelDone(result))
    }
}
