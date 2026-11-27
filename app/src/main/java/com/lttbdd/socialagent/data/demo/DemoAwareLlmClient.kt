package com.lttbdd.socialagent.data.demo

import com.lttbdd.socialagent.core.ai.LlmClient
import com.lttbdd.socialagent.core.model.ChatChunk
import com.lttbdd.socialagent.core.model.ChatRequest
import com.lttbdd.socialagent.core.model.Comment
import com.lttbdd.socialagent.core.model.CommentCategory
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow

/**
 * Chọn nguồn LLM theo chế độ Demo (#73) ở MỖI lệnh gọi:
 * - Bật: dùng [demo] (nội dung mẫu, không đọc key, không gọi mạng, không ghi usage/chi phí).
 * - Tắt: dùng [real] (router thật, cần key).
 *
 * Lệnh đang stream giữ nguyên nguồn đã chọn lúc bắt đầu collect; đổi chế độ có tác dụng từ lệnh kế tiếp,
 * không cần khởi động lại app.
 */
class DemoAwareLlmClient(
    private val real: LlmClient,
    private val demo: LlmClient,
    private val demoMode: DemoModeStore,
) : LlmClient {

    override fun streamChat(request: ChatRequest): Flow<ChatChunk> = flow {
        emitAll(current().streamChat(request))
    }

    override suspend fun classifyComment(comment: Comment): CommentCategory = current().classifyComment(comment)

    override suspend fun draftReply(comment: Comment, postText: String): String =
        current().draftReply(comment, postText)

    private suspend fun current(): LlmClient = if (demoMode.isEnabled()) demo else real
}
