package com.lttbdd.socialagent.data.ai

import com.lttbdd.socialagent.core.model.ChannelType
import com.lttbdd.socialagent.core.model.TokenUsage

/**
 * Điểm nối bước Sinh ảnh ([com.lttbdd.socialagent.core.model.AgentStep.IMAGE]) của sub-agent với luồng sinh ảnh
 * theo kênh. Kích thước, phong cách ảnh và phương án dựng ảnh từ template thuộc Issue #28; sub-agent (#27) chỉ
 * cung cấp mô tả ảnh và nhận lại URI.
 *
 * Quy ước:
 * - Trả `null` khi không có cách sinh ảnh; bản nháp được đóng gói không kèm ảnh (F4 có nút sinh lại ảnh).
 * - Lỗi thì ném exception; orchestrator báo [com.lttbdd.socialagent.core.model.AgentEvent.ChannelFailed] ở bước IMAGE.
 * - Không bắt [kotlinx.coroutines.CancellationException].
 */
fun interface ChannelImageGenerator {
    suspend fun generate(request: ChannelImageRequest): GeneratedImage?

    companion object {
        /** Chưa có luồng sinh ảnh: không sinh ảnh, không tốn token. */
        val NONE = ChannelImageGenerator { null }
    }
}

/** Đầu vào sinh ảnh cho một kênh: [description] do bước Mô tả ảnh tạo, [postText] là bài đã qua bước Tự rà. */
data class ChannelImageRequest(
    val topicId: String,
    val channel: ChannelType,
    val description: String,
    val postText: String,
)

/** Ảnh đã sinh: [uri] để gắn vào bản nháp, [usage] là token/chi phí của bước sinh ảnh (nếu có). */
data class GeneratedImage(
    val uri: String,
    val usage: TokenUsage = TokenUsage.ZERO,
) {
    init {
        require(uri.isNotBlank()) { "URI ảnh không được rỗng" }
    }
}
