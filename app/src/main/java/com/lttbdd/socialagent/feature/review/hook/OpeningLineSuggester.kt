package com.lttbdd.socialagent.feature.review.hook

import com.lttbdd.socialagent.core.ai.LlmClient
import com.lttbdd.socialagent.core.model.ChannelType
import javax.inject.Inject

/** Sinh phương án câu mở đầu cho bản nháp (F4, Issue #63). */
interface OpeningLineSuggester {

    /**
     * Trả đúng [OpeningSuggestionParser.OPTION_COUNT] phương án hợp lệ cho [draftText] theo kênh thật [channel].
     * [avoid] là các phương án đã hiển thị lần trước, để lần sinh lại đưa ra câu khác.
     *
     * Ném [OpeningSuggestionException] khi LLM trả kết quả không dùng được; lỗi LLM/mạng được ném tiếp nguyên trạng.
     * Huỷ coroutine đang gọi hàm = huỷ request LLM.
     */
    suspend fun suggest(
        draftText: String,
        channel: ChannelType,
        avoid: List<String> = emptyList(),
    ): List<String>
}

/** LLM trả kết quả không đủ phương án hợp lệ. Không chứa nội dung response để tránh lộ dữ liệu khi bị log. */
class OpeningSuggestionException(val result: OpeningParseResult) : Exception("Invalid opening suggestions")

/**
 * Gọi LLM qua [LlmClient] trong `core/ai` (Quy tắc 3): đúng một request `streamChat` với `LlmTask.WRITE_POST`
 * cho mỗi lần gọi, gom toàn bộ phần stream rồi kiểm tra bằng [OpeningSuggestionParser].
 * Không log prompt, nội dung bài hay response.
 */
class LlmOpeningLineSuggester @Inject constructor(
    private val llm: LlmClient,
) : OpeningLineSuggester {

    override suspend fun suggest(
        draftText: String,
        channel: ChannelType,
        avoid: List<String>,
    ): List<String> {
        val request = OpeningLinePrompt.build(draftText, channel, avoid)
        val raw = StringBuilder()
        llm.streamChat(request).collect { chunk -> raw.append(chunk.delta) }
        return when (val result = OpeningSuggestionParser.parse(raw.toString(), OpeningLine.find(draftText))) {
            is OpeningParseResult.Success -> result.options
            else -> throw OpeningSuggestionException(result)
        }
    }
}
