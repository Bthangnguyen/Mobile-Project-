package com.lttbdd.socialagent.data.usage

import com.lttbdd.socialagent.core.database.dao.LlmUsageDao
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map

/**
 * Đọc thống kê token/chi phí LLM (F8, #74). Trần ngân sách (#71) dùng [summary], không tự truy vấn Room.
 *
 * Ranh giới "hôm nay"/"tháng này" được tính khi bắt đầu collect; qua nửa đêm thì collect lại để lấy kỳ mới.
 */
interface UsageRepository {
    fun summary(period: UsagePeriod): Flow<UsageTotals>

    fun dashboard(): Flow<UsageDashboard>

    /** Xóa toàn bộ thống kê trên máy. */
    suspend fun clear()
}

class RoomUsageRepository(
    private val dao: LlmUsageDao,
    private val time: UsageTimeSource,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
) : UsageRepository {

    override fun summary(period: UsagePeriod): Flow<UsageTotals> = flow {
        val range = UsageAggregator.rangeOf(period, time.nowMillis(), time.zone())
        emitAll(dao.observeBetween(range.startMillis, range.endMillis).map(UsageAggregator::totals))
    }.flowOn(dispatcher)

    override fun dashboard(): Flow<UsageDashboard> = flow {
        val now = time.nowMillis()
        val zone = time.zone()
        val range = UsageAggregator.dashboardRange(now, zone)
        emitAll(
            dao.observeBetween(range.startMillis, range.endMillis).map { UsageAggregator.dashboard(it, now, zone) },
        )
    }.flowOn(dispatcher)

    override suspend fun clear() {
        dao.deleteAll()
    }
}
