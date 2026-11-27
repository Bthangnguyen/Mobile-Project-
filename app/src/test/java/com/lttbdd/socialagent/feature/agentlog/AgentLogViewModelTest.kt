package com.lttbdd.socialagent.feature.agentlog

import com.lttbdd.socialagent.R
import com.lttbdd.socialagent.core.model.AgentStep
import com.lttbdd.socialagent.core.model.ChannelType
import com.lttbdd.socialagent.data.ai.AgentLogStore
import com.lttbdd.socialagent.data.ai.AgentRunLog
import com.lttbdd.socialagent.data.ai.AgentStepLogEntry
import com.lttbdd.socialagent.data.ai.AgentStepStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AgentLogViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `loads runs, auto-expands newest, filters by channel, and toggles expansion`() = runTest {
        val store = FakeAgentLogStore(
            listOf(
                runLog("run-li", ChannelType.LINKEDIN, 2_000L),
                runLog("run-fb", ChannelType.FACEBOOK, 1_000L),
            ),
        )
        val vm = AgentLogViewModel(store)
        advanceUntilIdle()

        val initial = vm.uiState.value
        assertFalse(initial.isLoading)
        assertEquals(2, initial.items.size)
        assertTrue(initial.items[0].isExpanded)
        assertFalse(initial.items[1].isExpanded)

        vm.onToggleRunExpanded("run-li")
        assertFalse(vm.uiState.value.items[0].isExpanded)

        vm.onChannelFilterSelected(ChannelType.FACEBOOK)
        assertEquals(listOf("run-fb"), vm.uiState.value.items.map { it.run.id })
        assertEquals(2, vm.uiState.value.totalRunCount)

        vm.onChannelFilterSelected(null)
        assertEquals(2, vm.uiState.value.items.size)
    }

    @Test
    fun `clear requires confirmation and clears store on confirm`() = runTest {
        val store = FakeAgentLogStore(listOf(runLog("run-1", ChannelType.FACEBOOK, 1_000L)))
        val vm = AgentLogViewModel(store)
        advanceUntilIdle()

        vm.onClearRequested()
        assertEquals(AgentLogEvent.ConfirmClearAll, vm.events.first())
        assertEquals(1, store.getLogs().size)

        vm.onClearConfirmed()
        advanceUntilIdle()

        assertEquals(AgentLogEvent.ShowMessage(R.string.agent_log_cleared), vm.events.first())
        assertTrue(vm.uiState.value.items.isEmpty())
        assertEquals(0, vm.uiState.value.totalRunCount)
    }

    @Test
    fun `load error and clear error emit safe error states without crashing`() = runTest {
        val failingLoadStore = object : AgentLogStore {
            override fun observeLogs(): Flow<List<AgentRunLog>> = flow { throw IllegalStateException("IO") }
            override suspend fun getLogs(): List<AgentRunLog> = emptyList()
            override suspend fun recordRun(log: AgentRunLog) = Unit
            override suspend fun clearAll() = Unit
        }
        val loadVm = AgentLogViewModel(failingLoadStore)
        advanceUntilIdle()
        assertTrue(loadVm.uiState.value.isLoadError)
        assertEquals(AgentLogEvent.ShowMessage(R.string.agent_log_load_failed), loadVm.events.first())

        val failingClearStore = FakeAgentLogStore(listOf(runLog("r1", ChannelType.FACEBOOK, 1_000L)))
        failingClearStore.failOnClear = true
        val clearVm = AgentLogViewModel(failingClearStore)
        advanceUntilIdle()

        clearVm.onClearConfirmed()
        advanceUntilIdle()
        assertEquals(AgentLogEvent.ShowMessage(R.string.agent_log_clear_failed), clearVm.events.first())
        assertFalse(clearVm.uiState.value.isClearing)
    }

    private fun runLog(id: String, channel: ChannelType, startedAt: Long) = AgentRunLog(
        id = id,
        topicId = "topic-$id",
        topic = "Chủ đề $id",
        channel = channel,
        startedAtMillis = startedAt,
        steps = listOf(
            AgentStepLogEntry(
                step = AgentStep.DRAFT,
                status = AgentStepStatus.SUCCEEDED,
                prompt = "Brief $id",
                output = "Kết quả $id",
                inputTokens = 100,
                outputTokens = 50,
                costUsd = 0.001,
                startedAtMillis = startedAt,
                finishedAtMillis = startedAt + 100L,
            ),
        ),
    )

    private class FakeAgentLogStore(initial: List<AgentRunLog>) : AgentLogStore {
        private val flow = MutableStateFlow(initial)
        var failOnClear = false

        override fun observeLogs(): Flow<List<AgentRunLog>> = flow
        override suspend fun getLogs(): List<AgentRunLog> = flow.value
        override suspend fun recordRun(log: AgentRunLog) {
            flow.value = listOf(log) + flow.value
        }

        override suspend fun clearAll() {
            if (failOnClear) throw IllegalStateException("Clear IO error")
            flow.value = emptyList()
        }
    }
}
