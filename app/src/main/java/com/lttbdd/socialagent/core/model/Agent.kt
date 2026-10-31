package com.lttbdd.socialagent.core.model

enum class ContentLanguage { VI, EN }

/** Topic + brief người dùng nhập ở F3. */
data class TopicBrief(
    val id: String,
    val topic: String,
    val goal: String,
    val audience: String,
    val tone: String,
    val language: ContentLanguage,
    val channels: Set<ChannelType>,
)

/** Các bước của một sub-agent (F2), theo đúng thứ tự trong đề. */
enum class AgentStep { OUTLINE, DRAFT, SELF_REVIEW, IMAGE_PROMPT, IMAGE, PACKAGE }

/** Sự kiện agent phát ra để UI hiển thị tiến độ và stream nội dung. */
sealed interface AgentEvent {
    /** Orchestrator đã lập xong brief chung. */
    data class BriefReady(val summary: String) : AgentEvent

    data class StepStarted(val channel: ChannelType, val step: AgentStep) : AgentEvent

    /** Một đoạn chữ vừa được sinh ra (stream). */
    data class TextDelta(val channel: ChannelType, val step: AgentStep, val delta: String) : AgentEvent

    data class StepFinished(val channel: ChannelType, val step: AgentStep, val usage: TokenUsage) : AgentEvent

    /** Sub-agent của kênh này xong. */
    data class ChannelDone(val draft: Draft) : AgentEvent

    /** Sub-agent của kênh này lỗi. Các kênh khác vẫn chạy tiếp. */
    data class ChannelFailed(val channel: ChannelType, val step: AgentStep, val reason: String) : AgentEvent
}

/** Kiểu viết lại một đoạn ở F4. */
enum class RewriteInstruction { SHORTER, LONGER, MORE_FORMAL, MORE_CASUAL, NEW_HOOK }
