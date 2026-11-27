package com.lttbdd.socialagent.data.usage

import com.lttbdd.socialagent.core.model.LlmProvider
import com.lttbdd.socialagent.core.model.LlmTask
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UsageAggregatorTest {
    private val now = millisAt("2026-11-27T10:00:00")

    @Test
    fun `totals add tokens and keep unknown cost separate from zero`() {
        val totals = UsageAggregator.totals(
            listOf(
                usageRecord(now, input = 100, output = 50, costMicros = 300),
                usageRecord(now, input = 10, output = 5, costMicros = null),
                usageRecord(now, input = 1, output = 1, costMicros = 0),
            ),
        )

        assertEquals(
            UsageTotals(calls = 3, inputTokens = 111, outputTokens = 56, knownCostMicros = 300, unknownCostCalls = 1),
            totals,
        )
        assertEquals(167, totals.totalTokens)
        assertFalse(totals.isCostUnknown)
    }

    @Test
    fun `cost is unknown only when every call lacks a price`() {
        assertTrue(UsageAggregator.totals(listOf(usageRecord(now, costMicros = null))).isCostUnknown)
        assertFalse(UsageTotals().isCostUnknown)
    }

    @Test
    fun `totals do not overflow int token counts`() {
        val totals = UsageAggregator.totals(List(3) { usageRecord(now, input = Int.MAX_VALUE, output = Int.MAX_VALUE) })
        assertEquals(3L * Int.MAX_VALUE, totals.inputTokens)
    }

    @Test
    fun `day boundary follows device time zone not UTC`() {
        val lastMoment = millisAt("2026-11-26T23:59:59.999")
        val midnight = millisAt("2026-11-27T00:00:00")
        val records = listOf(usageRecord(lastMoment, input = 1), usageRecord(midnight, input = 2))

        val dashboard = UsageAggregator.dashboard(records, now, VIETNAM)

        assertEquals(2, dashboard.today.inputTokens)
        assertEquals(
            listOf(LocalDate.of(2026, 11, 27) to 2L, LocalDate.of(2026, 11, 26) to 1L),
            dashboard.days.map { it.date to it.totals.inputTokens },
        )
        // Cùng hai thời điểm nhưng xem theo UTC: cả hai rơi vào ngày 26/11 (UTC) và không có gì "hôm nay".
        val utcZone = ZoneId.of("UTC")
        val utc = UsageAggregator.dashboard(records, millisAt("2026-11-27T10:00:00", utcZone), utcZone)
        assertEquals(0, utc.today.calls)
    }

    @Test
    fun `month boundary separates last moment of month from first moment of next`() {
        val nowInDecember = millisAt("2026-12-01T08:00:00")
        val records = listOf(
            usageRecord(millisAt("2026-11-30T23:59:59.999"), input = 5),
            usageRecord(millisAt("2026-12-01T00:00:00"), input = 7),
        )

        val dashboard = UsageAggregator.dashboard(records, nowInDecember, VIETNAM)

        assertEquals(7, dashboard.thisMonth.inputTokens)
        assertEquals(listOf(LocalDate.of(2026, 12, 1)), dashboard.days.map { it.date })
        assertEquals(
            listOf(YearMonth.of(2026, 12) to 7L, YearMonth.of(2026, 11) to 5L),
            dashboard.months.map { it.month to it.totals.inputTokens },
        )
    }

    @Test
    fun `ranges are half open and cover whole local day or month`() {
        val today = UsageAggregator.rangeOf(UsagePeriod.TODAY, now, VIETNAM)
        assertEquals(UsageRange(millisAt("2026-11-27T00:00:00"), millisAt("2026-11-28T00:00:00")), today)

        val month = UsageAggregator.rangeOf(UsagePeriod.THIS_MONTH, now, VIETNAM)
        assertEquals(UsageRange(millisAt("2026-11-01T00:00:00"), millisAt("2026-12-01T00:00:00")), month)
    }

    @Test
    fun `a day with daylight saving change is still one whole day`() {
        val newYork = ZoneId.of("America/New_York")
        // 08/03/2026 ở New York chỉ có 23 giờ.
        val range = UsageAggregator.rangeOf(UsagePeriod.TODAY, millisAt("2026-03-08T12:00:00", newYork), newYork)
        assertEquals(23L * 60 * 60 * 1000, range.endMillis - range.startMillis)
    }

    @Test
    fun `dashboard keeps twelve months including the current one`() {
        val range = UsageAggregator.dashboardRange(now, VIETNAM)
        assertEquals(millisAt("2025-12-01T00:00:00"), range.startMillis)
        assertEquals(millisAt("2026-12-01T00:00:00"), range.endMillis)

        val records = listOf(
            usageRecord(millisAt("2025-11-30T12:00:00")),
            usageRecord(millisAt("2025-12-01T12:00:00")),
        )
        val months = UsageAggregator.dashboard(records, now, VIETNAM).months
        assertEquals(listOf(YearMonth.of(2025, 12)), months.map { it.month })
    }

    @Test
    fun `breakdown groups this month by provider model and task sorted by tokens`() {
        val records = listOf(
            usageRecord(now, input = 10, output = 0, model = "gemini-2.5-flash-lite", task = LlmTask.CLASSIFY_COMMENT),
            usageRecord(now, input = 10, output = 0, model = "gemini-2.5-flash-lite", task = LlmTask.CLASSIFY_COMMENT),
            usageRecord(now, input = 100, output = 0, provider = LlmProvider.OPENAI, model = "gpt-6-luna"),
            usageRecord(now, input = 50, output = 0, costMicros = null),
            // Tháng trước: không vào chi tiết tháng này.
            usageRecord(millisAt("2026-10-31T12:00:00"), input = 999),
        )

        val breakdown = UsageAggregator.dashboard(records, now, VIETNAM).breakdown

        assertEquals(
            listOf(
                Triple(LlmProvider.OPENAI, "gpt-6-luna", LlmTask.WRITE_POST) to 100L,
                Triple(LlmProvider.GEMINI, "gemini-2.5-flash", LlmTask.WRITE_POST) to 50L,
                Triple(LlmProvider.GEMINI, "gemini-2.5-flash-lite", LlmTask.CLASSIFY_COMMENT) to 20L,
            ),
            breakdown.map { Triple(it.provider, it.model, it.task) to it.totals.totalTokens },
        )
        assertEquals(2, breakdown.last().totals.calls)
        assertEquals(1, breakdown[1].totals.unknownCostCalls)
    }

    @Test
    fun `no records gives an empty dashboard with zero totals`() {
        val dashboard = UsageAggregator.dashboard(emptyList(), now, VIETNAM)
        assertTrue(dashboard.isEmpty)
        assertEquals(UsageTotals(), dashboard.today)
        assertEquals(UsageTotals(), dashboard.thisMonth)
        assertTrue(dashboard.days.isEmpty() && dashboard.breakdown.isEmpty())
    }
}
