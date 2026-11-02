package com.lttbdd.socialagent.core.ai

import com.lttbdd.socialagent.core.model.ChatChunk
import com.lttbdd.socialagent.core.model.ChatRequest
import com.lttbdd.socialagent.core.model.Comment
import com.lttbdd.socialagent.core.model.CommentCategory
import kotlinx.coroutines.flow.Flow

/**
 * HỢP ĐỒNG gọi LLM. Chủ sở hữu: C (mảng AI).
 * Implementation tự chọn provider + model theo cấu hình BYOK (F8) và ghi lại token/chi phí.
 * Người dùng: ContentAgent (C), F6 phân loại + soạn trả lời comment (B).
 */
interface LlmClient {

    /** Stream câu trả lời. Huỷ coroutine đang collect = huỷ request. */
    fun streamChat(request: ChatRequest): Flow<ChatChunk>

    suspend fun classifyComment(comment: Comment): CommentCategory

    /** Soạn câu trả lời cho comment, dựa trên nội dung bài [postText]. */
    suspend fun draftReply(comment: Comment, postText: String): String
}
