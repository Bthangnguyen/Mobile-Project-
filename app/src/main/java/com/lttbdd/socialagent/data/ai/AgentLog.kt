package com.lttbdd.socialagent.data.ai

import com.lttbdd.socialagent.core.model.AgentStep
import com.lttbdd.socialagent.core.model.ChannelType
import kotlinx.serialization.Serializable

/** Trạng thái thực thi của một bước trong nhật ký agent (F2, Issue #31). */
@Serializable
enum class AgentStepStatus {
    RUNNING,
    SUCCEEDED,
    FAILED,
    CANCELLED,
}

/**
 * Bản ghi chi tiết cho MỘT bước của sub-agent (Dàn ý, Bản nháp, Tự rà, Mô tả ảnh, Sinh ảnh, Đóng gói).
 * [costUsd] là `null` khi chưa xác định giá (không ép thành `0.0`).
 */
@Serializable
data class AgentStepLogEntry(
    val step: AgentStep,
    val status: AgentStepStatus,
    val prompt: String = "",
    val output: String = "",
    val inputTokens: Int = 0,
    val outputTokens: Int = 0,
    val costUsd: Double? = null,
    val errorCode: String? = null,
    val startedAtMillis: Long,
    val finishedAtMillis: Long? = null,
) {
    val totalTokens: Int get() = inputTokens + outputTokens
}

/**
 * Nhật ký của một lần chạy sub-agent cho MỘT kênh (F2, Issue #31).
 */
@Serializable
data class AgentRunLog(
    val id: String,
    val topicId: String,
    val topic: String,
    val channel: ChannelType,
    val sharedBriefSummary: String = "",
    val startedAtMillis: Long,
    val finishedAtMillis: Long? = null,
    val steps: List<AgentStepLogEntry> = emptyList(),
) {
    val totalInputTokens: Int get() = steps.sumOf { it.inputTokens }
    val totalOutputTokens: Int get() = steps.sumOf { it.outputTokens }
    val totalTokens: Int get() = totalInputTokens + totalOutputTokens
    val knownCostUsd: Double get() = steps.mapNotNull { it.costUsd }.sum()
    val hasUnknownCost: Boolean
        get() = steps.any { it.status == AgentStepStatus.SUCCEEDED && it.costUsd == null && it.totalTokens > 0 }
    val hasFailure: Boolean get() = steps.any { it.status == AgentStepStatus.FAILED }
}

/**
 * Làm sạch chuỗi trước khi lưu nhật ký để không bao giờ ghi lộ API key hoặc Bearer token (Quy tắc 5).
 */
object AgentLogSanitizer {

    private const val REDACTED = "[REDACTED]"
    private const val MAX_FIELD_CHARS = 8_000

    private val SECRET_PATTERNS: List<Regex> = listOf(
        Regex("""(?i)\bBearer\s+[A-Za-z0-9._~+/=-]{8,}"""),
        Regex("""\bsk-(?:proj-)?[A-Za-z0-9_-]{16,}"""),
        Regex("""\bAIza[0-9A-Za-z_-]{20,}"""),
        Regex("""(?i)(?:api[_-]?key|access[_-]?token)\s*[:=]\s*[^\s,;]+"""),
    )

    fun sanitizeText(raw: String, maxChars: Int = MAX_FIELD_CHARS): String {
        if (raw.isEmpty()) return ""
        var cleaned = raw
        for (pattern in SECRET_PATTERNS) {
            cleaned = pattern.replace(cleaned, REDACTED)
        }
        return if (cleaned.length > maxChars) {
            cleaned.take(maxChars) + "…"
        } else {
            cleaned
        }
    }

    fun sanitizeRunLog(log: AgentRunLog): AgentRunLog = log.copy(
        topic = sanitizeText(log.topic, 500),
        sharedBriefSummary = sanitizeText(log.sharedBriefSummary, 2_000),
        steps = log.steps.map { step ->
            step.copy(
                prompt = sanitizeText(step.prompt),
                output = sanitizeText(step.output),
                inputTokens = step.inputTokens.coerceAtLeast(0),
                outputTokens = step.outputTokens.coerceAtLeast(0),
                costUsd = step.costUsd?.takeIf { it.isFinite() && it >= 0.0 },
                errorCode = step.errorCode?.let { sanitizeText(it, 120) },
            )
        },
    )
}
