package com.lttbdd.socialagent.data.usage

import com.lttbdd.socialagent.core.model.LlmProvider
import com.lttbdd.socialagent.core.model.LlmTask
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

/**
 * Tổng usage của một nhóm lượt gọi.
 *
 * [knownCostMicros] chỉ cộng các lượt đã có giá; [unknownCostCalls] đếm lượt chưa rõ giá để UI nói rõ
 * tổng tiền chưa gồm những lượt đó. Token của mọi lượt vẫn được cộng.
 */
data class UsageTotals(
    val calls: Int = 0,
    val inputTokens: Long = 0,
    val outputTokens: Long = 0,
    val knownCostMicros: Long = 0,
    val unknownCostCalls: Int = 0,
) {
    val totalTokens: Long get() = inputTokens + outputTokens

    /** Có lượt gọi và tất cả đều chưa rõ giá: không có con số tiền nào để hiển thị. */
    val isCostUnknown: Boolean get() = calls > 0 && unknownCostCalls == calls
}

/** Kỳ thống kê tính theo múi giờ thiết bị. */
enum class UsagePeriod { TODAY, THIS_MONTH }

data class DailyUsage(val date: LocalDate, val totals: UsageTotals)

data class MonthlyUsage(val month: YearMonth, val totals: UsageTotals)

/** Tổng theo một bộ provider + model + loại việc. */
data class UsageBreakdownItem(
    val provider: LlmProvider,
    val model: String,
    val task: LlmTask,
    val totals: UsageTotals,
)

/**
 * Dữ liệu cho màn "Token & chi phí".
 *
 * [days] là các ngày có lượt gọi trong tháng này, [months] là các tháng có lượt gọi trong [UsageAggregator.MONTHS]
 * tháng gần nhất, [breakdown] là chi tiết tháng này. Các danh sách xếp mới trước cũ sau.
 */
data class UsageDashboard(
    val today: UsageTotals,
    val thisMonth: UsageTotals,
    val days: List<DailyUsage>,
    val months: List<MonthlyUsage>,
    val breakdown: List<UsageBreakdownItem>,
) {
    /** Chưa có lượt gọi nào trong khoảng thống kê. */
    val isEmpty: Boolean get() = months.isEmpty()
}

/** Thời điểm hiện tại và múi giờ thiết bị; tách ra để test dùng giá trị cố định. */
interface UsageTimeSource {
    fun nowMillis(): Long

    fun zone(): ZoneId
}

/** Đọc múi giờ mỗi lần gọi, vì người dùng có thể đổi múi giờ khi app đang chạy. */
object SystemUsageTimeSource : UsageTimeSource {
    override fun nowMillis(): Long = System.currentTimeMillis()

    override fun zone(): ZoneId = ZoneId.systemDefault()
}
