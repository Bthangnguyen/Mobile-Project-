package com.lttbdd.socialagent.data.usage

import com.lttbdd.socialagent.core.database.dao.LlmUsageDao
import com.lttbdd.socialagent.core.database.entity.LlmUsageEntity
import com.lttbdd.socialagent.core.model.LlmTask
import com.lttbdd.socialagent.core.model.TokenUsage
import com.lttbdd.socialagent.data.ai.LlmModelConfiguration
import com.lttbdd.socialagent.data.ai.LlmPricing
import com.lttbdd.socialagent.data.ai.LlmUsageRecorder
import java.util.UUID

/**
 * Lưu usage của từng lượt gọi LLM vào Room và tính chi phí ước tính theo [LlmPricing].
 *
 * Không dùng `TokenUsage.costUsd` của adapter: giá trị đó là NaN (chưa biết) theo quy ước của #52.
 */
class RoomLlmUsageRecorder(
    private val dao: LlmUsageDao,
    private val time: UsageTimeSource,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) : LlmUsageRecorder {

    override suspend fun record(task: LlmTask, configuration: LlmModelConfiguration, usage: TokenUsage) {
        // Adapter đã kiểm tra counters không âm; vẫn chặn ở đây để số âm không làm sai tổng.
        if (usage.inputTokens < 0 || usage.outputTokens < 0) return
        val provider = configuration.provider
        val model = LlmPricing.canonicalModelId(provider, configuration.model)
        val cost = LlmPricing.estimateCostMicros(provider, model, usage.inputTokens, usage.outputTokens)
        dao.insert(
            LlmUsageEntity(
                id = newId(),
                occurredAtMillis = time.nowMillis(),
                provider = provider,
                model = model,
                task = task,
                inputTokens = usage.inputTokens,
                outputTokens = usage.outputTokens,
                costMicros = cost,
                pricingVersion = cost?.let { LlmPricing.VERSION },
            ),
        )
    }
}
