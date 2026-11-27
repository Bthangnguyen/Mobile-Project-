package com.lttbdd.socialagent.data.ai

import com.lttbdd.socialagent.core.ai.LlmClient
import com.lttbdd.socialagent.core.model.ChatChunk
import com.lttbdd.socialagent.core.model.ChatRequest
import com.lttbdd.socialagent.core.model.Comment
import com.lttbdd.socialagent.core.model.CommentCategory
import com.lttbdd.socialagent.core.model.LlmTask
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow

/**
 * Cấu hình nhà cung cấp phụ cho một loại việc (#72): provider được hỗ trợ KHÁC provider chính, đã có key,
 * dùng model gợi ý đầu tiên của provider đó cho loại việc. `null` khi không có provider phụ phù hợp.
 */
class FallbackConfigurationSource(
    private val primary: LlmConfigurationSource,
    private val keys: ProviderKeyAvailability,
) : LlmConfigurationSource {

    override suspend fun configurationFor(task: LlmTask): LlmModelConfiguration? {
        val main = primary.configurationFor(task) ?: return null
        for (provider in LlmModelCatalog.supportedProviders) {
            if (provider == main.provider || !keys.isConfigured(provider)) continue
            val model = LlmModelCatalog.firstSuggestion(provider, task) ?: continue
            return LlmModelConfiguration(provider, model)
        }
        return null
    }
}

/**
 * Tự chuyển sang nhà cung cấp phụ khi nhà cung cấp chính lỗi (F8 [N], #72). Chỉ chạy khi người dùng bật.
 *
 * - [primary] là router theo lựa chọn F8; [fallback] là router đọc cấu hình từ [fallbackConfigurations].
 * - Chỉ chuyển với lỗi phía nhà cung cấp ([FALLBACK_FAILURES]); lỗi do request/cấu hình, nội dung bị chặn,
 *   mất mạng trên thiết bị hay người dùng hủy đều giữ nguyên.
 * - Stream chỉ chuyển khi CHƯA nhận chunk nào, để không ghép nội dung của hai nhà cung cấp.
 * - Mỗi lệnh gọi chuyển tối đa một lần; nhà cung cấp phụ lỗi thì báo lỗi của nhà cung cấp phụ.
 * - Không áp dụng cho sinh ảnh. Không log key, prompt hay nội dung.
 */
class FallbackLlmClient(
    private val primary: LlmClient,
    private val fallback: LlmClient,
    private val fallbackConfigurations: LlmConfigurationSource,
    private val preference: FallbackPreferenceStore,
) : LlmClient {

    override fun streamChat(request: ChatRequest): Flow<ChatChunk> = flow {
        var emitted = false
        try {
            primary.streamChat(request).collect { chunk ->
                emitted = true
                emit(chunk)
            }
        } catch (error: LlmException) {
            if (emitted || !shouldFallback(request.task, error)) throw error
            emitAll(fallback.streamChat(request))
        }
    }

    override suspend fun classifyComment(comment: Comment): CommentCategory = try {
        primary.classifyComment(comment)
    } catch (error: LlmException) {
        if (!shouldFallback(LlmTask.CLASSIFY_COMMENT, error)) throw error
        fallback.classifyComment(comment)
    }

    override suspend fun draftReply(comment: Comment, postText: String): String = try {
        primary.draftReply(comment, postText)
    } catch (error: LlmException) {
        if (!shouldFallback(LlmTask.WRITE_POST, error)) throw error
        fallback.draftReply(comment, postText)
    }

    private suspend fun shouldFallback(task: LlmTask, error: LlmException): Boolean =
        task != LlmTask.GENERATE_IMAGE &&
            error.failure in FALLBACK_FAILURES &&
            preference.isEnabled() &&
            fallbackConfigurations.configurationFor(task) != null

    companion object {
        /** Lỗi thuộc phía nhà cung cấp chính: nhà cung cấp khác có thể vẫn trả lời được. */
        val FALLBACK_FAILURES: Set<LlmFailure> = setOf(
            LlmFailure.SERVER,
            LlmFailure.RATE_LIMIT,
            LlmFailure.TIMEOUT,
            LlmFailure.AUTHENTICATION,
            LlmFailure.PERMISSION,
            LlmFailure.PROVIDER,
        )
    }
}
