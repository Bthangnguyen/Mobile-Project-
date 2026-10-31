package com.lttbdd.socialagent.core.model

enum class LlmProvider { OPENAI, ANTHROPIC, GEMINI, OPENROUTER }

/** Loại việc — người dùng chọn model riêng cho từng loại (F8). */
enum class LlmTask { WRITE_POST, CLASSIFY_COMMENT, GENERATE_IMAGE }

enum class ChatRole { SYSTEM, USER, ASSISTANT }

data class ChatMessage(val role: ChatRole, val content: String)

data class ChatRequest(
    val task: LlmTask,
    val messages: List<ChatMessage>,
    val temperature: Double = 0.7,
)

data class TokenUsage(
    val inputTokens: Int,
    val outputTokens: Int,
    val costUsd: Double,
) {
    operator fun plus(other: TokenUsage) = TokenUsage(
        inputTokens = inputTokens + other.inputTokens,
        outputTokens = outputTokens + other.outputTokens,
        costUsd = costUsd + other.costUsd,
    )

    companion object {
        val ZERO = TokenUsage(0, 0, 0.0)
    }
}

/** Một mẩu trả về khi stream. Mẩu cuối cùng có [usage] khác null. */
data class ChatChunk(val delta: String, val usage: TokenUsage? = null)
