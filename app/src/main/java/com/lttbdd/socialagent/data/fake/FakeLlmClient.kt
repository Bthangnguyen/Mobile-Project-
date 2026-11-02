package com.lttbdd.socialagent.data.fake

import com.lttbdd.socialagent.core.ai.LlmClient
import com.lttbdd.socialagent.core.model.ChatChunk
import com.lttbdd.socialagent.core.model.ChatRequest
import com.lttbdd.socialagent.core.model.Comment
import com.lttbdd.socialagent.core.model.CommentCategory
import com.lttbdd.socialagent.core.model.TokenUsage
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/** LLM giả: stream một câu cố định, không cần API key. Nền tảng cho chế độ Demo (F8 nâng cao). */
class FakeLlmClient(private val chunkDelayMillis: Long = 50) : LlmClient {

    override fun streamChat(request: ChatRequest): Flow<ChatChunk> = flow {
        val words = "Đây là nội dung mẫu do Fake LLM sinh ra để dựng giao diện.".split(" ")
        words.forEach { word ->
            delay(chunkDelayMillis)
            emit(ChatChunk("$word "))
        }
        emit(ChatChunk(delta = "", usage = TokenUsage(inputTokens = 100, outputTokens = words.size, costUsd = 0.0)))
    }

    override suspend fun classifyComment(comment: Comment): CommentCategory = when {
        comment.text.contains("?") -> CommentCategory.QUESTION
        else -> CommentCategory.PRAISE
    }

    override suspend fun draftReply(comment: Comment, postText: String): String =
        "Cảm ơn ${comment.authorName} đã quan tâm!"
}
