package com.lttbdd.socialagent.core.database.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.lttbdd.socialagent.core.model.LlmProvider
import com.lttbdd.socialagent.core.model.LlmTask

/**
 * Usage của một lệnh gọi LLM đã hoàn tất (F8, #74), dùng để thống kê token và chi phí theo ngày/tháng.
 *
 * Chỉ chứa số liệu: không lưu prompt, nội dung trả về, comment hay API key.
 */
@Entity(
    tableName = "llm_usage",
    indices = [Index(value = ["occurredAtMillis"])],
)
data class LlmUsageEntity(
    /** Id duy nhất của lượt gọi, để một lượt không bị đếm hai lần. */
    @PrimaryKey val id: String,
    /** Thời điểm ghi nhận (epoch millis, UTC); ngày/tháng được tính theo múi giờ thiết bị khi đọc. */
    val occurredAtMillis: Long,
    val provider: LlmProvider,
    val model: String,
    val task: LlmTask,
    val inputTokens: Int,
    val outputTokens: Int,
    /** Chi phí ước tính theo micro-USD (1 USD = 1.000.000). `null` là chưa xác định, KHÔNG phải 0 USD. */
    val costMicros: Long?,
    /** Phiên bản bảng giá đã dùng để tính [costMicros]; `null` khi model chưa có giá. */
    val pricingVersion: String?,
)
