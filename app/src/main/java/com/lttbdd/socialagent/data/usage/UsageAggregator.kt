package com.lttbdd.socialagent.data.usage

import com.lttbdd.socialagent.core.database.entity.LlmUsageEntity
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

/** Khoảng thời gian `[startMillis, endMillis)`. */
data class UsageRange(val startMillis: Long, val endMillis: Long)

/**
 * Cộng usage theo ngày/tháng. Ranh giới tính bằng `java.time` theo [ZoneId] truyền vào,
 * nên đúng cả với múi giờ có giờ mùa hè (ngày không đủ 24 giờ).
 */
object UsageAggregator {
    /** Số tháng gần nhất (gồm tháng này) hiển thị ở chế độ xem theo tháng. */
    const val MONTHS = 12

    fun totals(records: List<LlmUsageEntity>): UsageTotals = records.fold(UsageTotals()) { sum, record ->
        val cost = record.costMicros
        sum.copy(
            calls = sum.calls + 1,
            inputTokens = sum.inputTokens + record.inputTokens,
            outputTokens = sum.outputTokens + record.outputTokens,
            knownCostMicros = sum.knownCostMicros + (cost ?: 0),
            unknownCostCalls = sum.unknownCostCalls + if (cost == null) 1 else 0,
        )
    }

    fun rangeOf(period: UsagePeriod, nowMillis: Long, zone: ZoneId): UsageRange {
        val today = dateOf(nowMillis, zone)
        return when (period) {
            UsagePeriod.TODAY -> UsageRange(startOf(today, zone), startOf(today.plusDays(1), zone))
            UsagePeriod.THIS_MONTH -> monthRange(YearMonth.from(today), zone)
        }
    }

    /** Khoảng cần đọc cho [dashboard]: từ đầu tháng xa nhất trong [MONTHS] tháng tới hết tháng này. */
    fun dashboardRange(nowMillis: Long, zone: ZoneId): UsageRange {
        val month = YearMonth.from(dateOf(nowMillis, zone))
        return UsageRange(
            startMillis = monthRange(month.minusMonths(MONTHS - 1L), zone).startMillis,
            endMillis = monthRange(month, zone).endMillis,
        )
    }

    fun dashboard(records: List<LlmUsageEntity>, nowMillis: Long, zone: ZoneId): UsageDashboard {
        val today = dateOf(nowMillis, zone)
        val thisMonth = YearMonth.from(today)
        val firstMonth = thisMonth.minusMonths(MONTHS - 1L)
        val dated = records.map { dateOf(it.occurredAtMillis, zone) to it }
        val inMonth = dated.filter { (date, _) -> YearMonth.from(date) == thisMonth }

        val days = inMonth.groupBy({ it.first }, { it.second })
            .map { (date, items) -> DailyUsage(date, totals(items)) }
            .sortedByDescending { it.date }
        val months = dated
            .filter { (date, _) -> YearMonth.from(date) in firstMonth..thisMonth }
            .groupBy({ YearMonth.from(it.first) }, { it.second })
            .map { (month, items) -> MonthlyUsage(month, totals(items)) }
            .sortedByDescending { it.month }
        val breakdown = inMonth.map { it.second }
            .groupBy { Triple(it.provider, it.model, it.task) }
            .map { (key, items) -> UsageBreakdownItem(key.first, key.second, key.third, totals(items)) }
            .sortedWith(
                compareByDescending<UsageBreakdownItem> { it.totals.totalTokens }
                    .thenBy { it.provider }
                    .thenBy { it.model }
                    .thenBy { it.task },
            )
        return UsageDashboard(
            today = totals(dated.filter { it.first == today }.map { it.second }),
            thisMonth = totals(inMonth.map { it.second }),
            days = days,
            months = months,
            breakdown = breakdown,
        )
    }

    private fun dateOf(millis: Long, zone: ZoneId): LocalDate = Instant.ofEpochMilli(millis).atZone(zone).toLocalDate()

    private fun startOf(date: LocalDate, zone: ZoneId): Long = date.atStartOfDay(zone).toInstant().toEpochMilli()

    private fun monthRange(month: YearMonth, zone: ZoneId) =
        UsageRange(startOf(month.atDay(1), zone), startOf(month.plusMonths(1).atDay(1), zone))
}
