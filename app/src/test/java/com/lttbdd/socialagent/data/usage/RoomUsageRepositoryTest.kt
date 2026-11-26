package com.lttbdd.socialagent.data.usage

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RoomUsageRepositoryTest {
    private val dao = FakeLlmUsageDao()
    private val time = FixedTimeSource(millisAt("2026-11-27T10:00:00"))

    @Test
    fun `summary today reads only the current local day`() = runTest {
        val repository = RoomUsageRepository(dao, time, StandardTestDispatcher(testScheduler))
        dao.rows.value = listOf(
            usageRecord(millisAt("2026-11-26T23:59:59"), input = 1),
            usageRecord(millisAt("2026-11-27T00:00:00"), input = 2),
            usageRecord(millisAt("2026-11-27T23:59:59"), input = 4),
        )

        val today = repository.summary(UsagePeriod.TODAY).first()

        assertEquals(2, today.calls)
        assertEquals(6, today.inputTokens)
        assertEquals(millisAt("2026-11-27T00:00:00") to millisAt("2026-11-28T00:00:00"), dao.queries.single())
    }

    @Test
    fun `summary this month covers the whole local month`() = runTest {
        val repository = RoomUsageRepository(dao, time, StandardTestDispatcher(testScheduler))
        dao.rows.value = listOf(
            usageRecord(millisAt("2026-10-31T23:59:59"), costMicros = 100),
            usageRecord(millisAt("2026-11-01T00:00:00"), costMicros = 200),
            usageRecord(millisAt("2026-11-15T12:00:00"), costMicros = null),
        )

        val month = repository.summary(UsagePeriod.THIS_MONTH).first()

        assertEquals(2, month.calls)
        assertEquals(200, month.knownCostMicros)
        assertEquals(1, month.unknownCostCalls)
    }

    @Test
    fun `dashboard updates when a new call is recorded`() = runTest {
        val repository = RoomUsageRepository(dao, time, StandardTestDispatcher(testScheduler))
        assertTrue(repository.dashboard().first().isEmpty)

        dao.insert(usageRecord(time.now, input = 9))

        val dashboard = repository.dashboard().first()
        assertEquals(9, dashboard.today.inputTokens)
        assertEquals(9, dashboard.thisMonth.inputTokens)
    }

    @Test
    fun `period boundaries are computed when collection starts`() = runTest {
        val repository = RoomUsageRepository(dao, time, StandardTestDispatcher(testScheduler))
        val summary = repository.summary(UsagePeriod.TODAY)
        dao.rows.value = listOf(usageRecord(millisAt("2026-11-28T00:30:00")))
        assertEquals(0, summary.first().calls)

        // Qua nửa đêm: collect lại cùng Flow thì "hôm nay" là ngày mới.
        time.now = millisAt("2026-11-28T01:00:00")
        assertEquals(1, summary.first().calls)
    }

    @Test
    fun `clear deletes every record`() = runTest {
        val repository = RoomUsageRepository(dao, time, StandardTestDispatcher(testScheduler))
        dao.rows.value = listOf(usageRecord(time.now), usageRecord(time.now))

        repository.clear()

        assertTrue(dao.rows.value.isEmpty())
        assertTrue(repository.dashboard().first().isEmpty)
    }
}
