package com.lttbdd.socialagent.data.ai

import com.lttbdd.socialagent.core.model.LlmProvider

/** Giá text theo micro-USD cho 1 triệu token (0,10 USD = 100_000). */
data class TokenPrice(
    val inputMicrosPerMillion: Long,
    val outputMicrosPerMillion: Long,
)

/**
 * Giá của một model. Khi số input token vượt [longContextThreshold], cả lượt gọi tính theo [longContext]
 * (cách OpenAI tính bậc "long context").
 */
data class ModelPrice(
    val standard: TokenPrice,
    val longContextThreshold: Int? = null,
    val longContext: TokenPrice? = null,
) {
    fun forInput(inputTokens: Int): TokenPrice =
        if (longContextThreshold != null && longContext != null && inputTokens > longContextThreshold) {
            longContext
        } else {
            standard
        }
}

/**
 * Bảng giá cục bộ để ƯỚC TÍNH chi phí (#74), không phải hóa đơn của nhà cung cấp.
 *
 * Giá chuẩn (standard tier), không tính cache, batch hay giảm giá. Nhà cung cấp đổi giá thì cập nhật bảng
 * và tăng [VERSION]; bản ghi cũ giữ phiên bản giá đã dùng lúc ghi. Model không có ở đây có chi phí
 * "chưa xác định", không bao giờ được coi là 0 USD.
 *
 * Nguồn: trang giá chính thức của OpenAI và Gemini API, đối chiếu ngày [VERSION]
 * (xem `docs/llm-providers.md`, mục #74).
 */
object LlmPricing {
    /** Ngày đối chiếu bảng giá, dạng ISO `yyyy-MM-dd`; UI hiển thị ngày này cạnh số tiền ước tính. */
    const val VERSION = "2026-11-27"

    private const val TOKENS_PER_MILLION = 1_000_000L
    private const val OPENAI_LONG_CONTEXT_THRESHOLD = 272_000

    private val prices: Map<LlmProvider, Map<String, ModelPrice>> = mapOf(
        // Giá đầu ra của Gemini đã gồm thinking token; adapter cũng cộng thinking vào output token.
        LlmProvider.GEMINI to mapOf(
            // Đã deprecated: từ 01/12/2026 Google chuyển request sang gemini-3.5-flash-lite và tính theo giá
            // của model đó. Hai model đang cùng giá text nên ước tính không đổi.
            "gemini-2.5-flash" to ModelPrice(TokenPrice(300_000, 2_500_000)),
            "gemini-2.5-flash-lite" to ModelPrice(TokenPrice(100_000, 400_000)),
            // Model thay thế Google khuyến nghị cho gemini-2.5-flash.
            "gemini-3.5-flash-lite" to ModelPrice(TokenPrice(300_000, 2_500_000)),
        ),
        LlmProvider.OPENAI to mapOf(
            "gpt-6-luna" to ModelPrice(
                standard = TokenPrice(100_000, 500_000),
                longContextThreshold = OPENAI_LONG_CONTEXT_THRESHOLD,
                longContext = TokenPrice(200_000, 750_000),
            ),
            "gpt-6.1-terra" to ModelPrice(
                standard = TokenPrice(1_000_000, 5_000_000),
                longContextThreshold = OPENAI_LONG_CONTEXT_THRESHOLD,
                longContext = TokenPrice(2_000_000, 7_500_000),
            ),
        ),
    )

    /** Model ID dùng để tra giá và nhóm thống kê: Gemini chấp nhận cả `models/x` lẫn `x` cho cùng một model. */
    fun canonicalModelId(provider: LlmProvider, model: String): String = when (provider) {
        LlmProvider.GEMINI -> model.trim().removePrefix("models/")
        else -> model.trim()
    }

    fun priceOf(provider: LlmProvider, model: String): ModelPrice? =
        prices[provider]?.get(canonicalModelId(provider, model))

    /**
     * Chi phí ước tính theo micro-USD, làm tròn nửa lên một lần cho cả lượt gọi.
     * Trả `null` khi model chưa có giá hoặc số token âm, để bên gọi hiển thị "chưa xác định" thay vì 0.
     */
    fun estimateCostMicros(provider: LlmProvider, model: String, inputTokens: Int, outputTokens: Int): Long? {
        if (inputTokens < 0 || outputTokens < 0) return null
        val price = priceOf(provider, model)?.forInput(inputTokens) ?: return null
        val scaled = inputTokens.toLong() * price.inputMicrosPerMillion +
            outputTokens.toLong() * price.outputMicrosPerMillion
        return (scaled + TOKENS_PER_MILLION / 2) / TOKENS_PER_MILLION
    }
}
