package com.lttbdd.socialagent.feature.usage

import com.lttbdd.socialagent.data.usage.MonthlyUsage
import com.lttbdd.socialagent.data.usage.UsageDashboard
import com.lttbdd.socialagent.data.usage.UsagePeriod
import com.lttbdd.socialagent.data.usage.UsageRepository
import com.lttbdd.socialagent.data.usage.UsageTotals
import java.io.IOException
import java.time.YearMonth
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class UsageViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val repository = FakeUsageRepository()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `starts loading then shows dashboard`() = runTest(dispatcher) {
        val viewModel = UsageViewModel(repository)
        assertTrue(viewModel.uiState.value.loading)

        viewModel.refresh()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse(state.loading)
        assertFalse(state.loadFailed)
        assertEquals(dashboard(calls = 2), state.dashboard)
    }

    @Test
    fun `new usage updates the screen while it is open`() = runTest(dispatcher) {
        val viewModel = UsageViewModel(repository)
        viewModel.refresh()
        advanceUntilIdle()

        repository.dashboards.value = dashboard(calls = 5)
        advanceUntilIdle()

        assertEquals(5, viewModel.uiState.value.dashboard?.today?.calls)
    }

    @Test
    fun `load failure shows error and refresh recovers`() = runTest(dispatcher) {
        repository.failLoad = true
        val viewModel = UsageViewModel(repository)
        viewModel.refresh()
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.loadFailed)
        assertFalse(viewModel.uiState.value.loading)
        assertNull(viewModel.uiState.value.dashboard)

        repository.failLoad = false
        viewModel.refresh()
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.loadFailed)
        assertEquals(2, viewModel.uiState.value.dashboard?.today?.calls)
    }

    @Test
    fun `refresh restarts collection so day boundaries are recomputed`() = runTest(dispatcher) {
        val viewModel = UsageViewModel(repository)
        viewModel.refresh()
        advanceUntilIdle()
        viewModel.refresh()
        advanceUntilIdle()

        assertEquals(2, repository.dashboardCollections)
        assertEquals(1, repository.activeCollections)
        // Đang có dữ liệu thì không hiện lại trạng thái tải toàn màn.
        assertFalse(viewModel.uiState.value.loading)
    }

    @Test
    fun `mode selection switches between days and months`() = runTest(dispatcher) {
        val viewModel = UsageViewModel(repository)
        assertEquals(UsageHistoryMode.DAY, viewModel.uiState.value.mode)

        viewModel.onModeSelected(UsageHistoryMode.MONTH)

        assertEquals(UsageHistoryMode.MONTH, viewModel.uiState.value.mode)
    }

    @Test
    fun `clear removes statistics and reports success`() = runTest(dispatcher) {
        val viewModel = UsageViewModel(repository)
        viewModel.refresh()
        advanceUntilIdle()

        viewModel.clear()
        assertTrue(viewModel.uiState.value.clearing)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse(state.clearing)
        assertEquals(UsageMessage.CLEARED, state.message)
        assertEquals(1, repository.clearCount)
        assertTrue(state.dashboard?.isEmpty == true)
    }

    @Test
    fun `clear failure is reported and keeps data`() = runTest(dispatcher) {
        repository.failClear = true
        val viewModel = UsageViewModel(repository)
        viewModel.refresh()
        advanceUntilIdle()

        viewModel.clear()
        advanceUntilIdle()

        assertEquals(UsageMessage.CLEAR_FAILED, viewModel.uiState.value.message)
        assertFalse(viewModel.uiState.value.clearing)
        assertEquals(2, viewModel.uiState.value.dashboard?.today?.calls)
    }

    @Test
    fun `message is shown only once`() = runTest(dispatcher) {
        val viewModel = UsageViewModel(repository)
        viewModel.refresh()
        advanceUntilIdle()
        viewModel.clear()
        advanceUntilIdle()
        assertEquals(UsageMessage.CLEARED, viewModel.uiState.value.message)

        viewModel.onMessageShown()

        // Fragment dựng lại sau khi xoay màn hình nhận state này: không còn thông báo để hiện lại.
        val state = viewModel.uiState.value
        assertNull(state.message)
        assertTrue(state.dashboard?.isEmpty == true)
        assertFalse(state.clearing)
    }

    @Test
    fun `second clear while clearing is ignored`() = runTest(dispatcher) {
        val gate = CompletableDeferred<Unit>()
        repository.clearGate = gate
        val viewModel = UsageViewModel(repository)

        viewModel.clear()
        advanceUntilIdle()
        viewModel.clear()
        gate.complete(Unit)
        advanceUntilIdle()

        assertEquals(1, repository.clearCount)
    }

    private class FakeUsageRepository : UsageRepository {
        val dashboards = MutableStateFlow<UsageDashboard?>(dashboard(calls = 2))
        var failLoad = false
        var failClear = false
        var clearGate: CompletableDeferred<Unit>? = null
        var clearCount = 0
        var dashboardCollections = 0
        var activeCollections = 0

        override fun summary(period: UsagePeriod): Flow<UsageTotals> =
            dashboards.filterNotNull().map { if (period == UsagePeriod.TODAY) it.today else it.thisMonth }

        override fun dashboard(): Flow<UsageDashboard> = flow {
            dashboardCollections++
            activeCollections++
            try {
                if (failLoad) throw IOException("db")
                dashboards.filterNotNull().collect { emit(it) }
            } finally {
                activeCollections--
            }
        }

        override suspend fun clear() {
            clearCount++
            clearGate?.await()
            if (failClear) throw IOException("db")
            dashboards.value = dashboard(calls = 0)
        }
    }

    private companion object {
        fun dashboard(calls: Int): UsageDashboard {
            val totals = UsageTotals(calls = calls, inputTokens = calls * 10L, outputTokens = calls * 5L)
            return UsageDashboard(
                today = totals,
                thisMonth = totals,
                days = emptyList(),
                months = if (calls == 0) emptyList() else listOf(MonthlyUsage(YearMonth.of(2026, 11), totals)),
                breakdown = emptyList(),
            )
        }
    }
}
