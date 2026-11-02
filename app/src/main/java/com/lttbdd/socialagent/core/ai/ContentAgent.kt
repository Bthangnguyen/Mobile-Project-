package com.lttbdd.socialagent.core.ai

import com.lttbdd.socialagent.core.model.AgentEvent
import com.lttbdd.socialagent.core.model.ChannelType
import com.lttbdd.socialagent.core.model.Draft
import com.lttbdd.socialagent.core.model.RewriteInstruction
import com.lttbdd.socialagent.core.model.TopicBrief
import kotlinx.coroutines.flow.Flow

/**
 * HỢP ĐỒNG agent viết bài (F2). Chủ sở hữu: C (mảng AI).
 * Người dùng: F3 nhập topic và F4 duyệt bài (D).
 */
interface ContentAgent {

    /**
     * Orchestrator lập brief rồi chạy song song mỗi kênh một sub-agent.
     * Một kênh lỗi phát [AgentEvent.ChannelFailed] và KHÔNG làm dừng kênh khác.
     * Huỷ việc collect Flow = huỷ toàn bộ.
     */
    fun generate(brief: TopicBrief): Flow<AgentEvent>

    /** Chạy lại sub-agent cho riêng một kênh. */
    fun regenerate(brief: TopicBrief, channel: ChannelType): Flow<AgentEvent>

    /** Viết lại một đoạn [paragraph] trong bản nháp. */
    suspend fun rewriteParagraph(draft: Draft, paragraph: String, instruction: RewriteInstruction): String

    /** Sinh lại ảnh, trả về URI ảnh mới. */
    suspend fun regenerateImage(draft: Draft): String
}
