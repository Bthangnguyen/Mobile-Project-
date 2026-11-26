package com.lttbdd.socialagent.data.ai

import com.lttbdd.socialagent.core.model.LlmTask
import com.lttbdd.socialagent.core.model.TokenUsage

/**
 * Router báo usage cuối của một lệnh gọi LLM đã hoàn tất để thống kê token/chi phí (#74).
 *
 * Chỉ nhận loại việc, provider/model và số token: không nhận prompt, nội dung trả về hay API key.
 */
fun interface LlmUsageRecorder {
    suspend fun record(task: LlmTask, configuration: LlmModelConfiguration, usage: TokenUsage)

    companion object {
        /** Không ghi gì; dùng khi chưa nối nơi lưu (ví dụ trong test của router). */
        val NONE = LlmUsageRecorder { _, _, _ -> }
    }
}
