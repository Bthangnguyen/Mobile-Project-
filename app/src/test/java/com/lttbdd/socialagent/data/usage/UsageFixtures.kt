package com.lttbdd.socialagent.data.usage

import com.lttbdd.socialagent.core.database.dao.LlmUsageDao
import com.lttbdd.socialagent.core.database.entity.LlmUsageEntity
import com.lttbdd.socialagent.core.model.LlmProvider
import com.lttbdd.socialagent.core.model.LlmTask
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/** Múi giờ Việt Nam (UTC+7) để ranh giới ngày khác hẳn UTC. */
internal val VIETNAM: ZoneId = ZoneId.of("Asia/Ho_Chi_Minh")

internal fun millisAt(text: String, zone: ZoneId = VIETNAM): Long =
    LocalDateTime.parse(text).atZone(zone).toInstant().toEpochMilli()

private val ids = AtomicInteger()

internal fun usageRecord(
    at: Long,
    input: Int = 100,
    output: Int = 50,
    costMicros: Long? = 10,
    provider: LlmProvider = LlmProvider.GEMINI,
    model: String = "gemini-2.5-flash",
    task: LlmTask = LlmTask.WRITE_POST,
) = LlmUsageEntity(
    id = "usage-${ids.incrementAndGet()}",
    occurredAtMillis = at,
    provider = provider,
    model = model,
    task = task,
    inputTokens = input,
    outputTokens = output,
    costMicros = costMicros,
    pricingVersion = costMicros?.let { "2026-11-27" },
)

internal class FixedTimeSource(var now: Long, var zone: ZoneId = VIETNAM) : UsageTimeSource {
    override fun nowMillis(): Long = now

    override fun zone(): ZoneId = zone
}

/** DAO giả trong bộ nhớ, cùng ngữ nghĩa khoảng `[start, end)` và bỏ qua id trùng như bản Room. */
internal class FakeLlmUsageDao : LlmUsageDao {
    val rows = MutableStateFlow<List<LlmUsageEntity>>(emptyList())
    val queries = mutableListOf<Pair<Long, Long>>()
    var failOnDelete: Exception? = null

    override suspend fun insert(record: LlmUsageEntity) {
        if (rows.value.none { it.id == record.id }) rows.value = rows.value + record
    }

    override fun observeBetween(startMillis: Long, endMillis: Long): Flow<List<LlmUsageEntity>> {
        queries += startMillis to endMillis
        return rows.map { all ->
            all.filter { it.occurredAtMillis in startMillis until endMillis }.sortedBy { it.occurredAtMillis }
        }
    }

    override suspend fun deleteAll(): Int {
        failOnDelete?.let { throw it }
        val count = rows.value.size
        rows.value = emptyList()
        return count
    }
}
