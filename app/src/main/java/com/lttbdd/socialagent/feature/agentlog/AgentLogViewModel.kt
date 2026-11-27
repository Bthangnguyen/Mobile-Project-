package com.lttbdd.socialagent.feature.agentlog

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lttbdd.socialagent.R
import com.lttbdd.socialagent.core.model.ChannelType
import com.lttbdd.socialagent.data.ai.AgentLogStore
import com.lttbdd.socialagent.data.ai.AgentRunLog
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ViewModel cho màn hình Nhật ký agent (F2, Issue #31):
 * lọc theo kênh, mở rộng từng lượt chạy để xem chi tiết bước/prompt/kết quả/token/chi phí, và xóa nhật ký.
 */
@HiltViewModel
class AgentLogViewModel @Inject constructor(
    private val store: AgentLogStore,
) : ViewModel() {

    private val _uiState = MutableStateFlow(AgentLogUiState())
    val uiState: StateFlow<AgentLogUiState> = _uiState.asStateFlow()

    private val _events = Channel<AgentLogEvent>(Channel.BUFFERED)
    val events: Flow<AgentLogEvent> = _events.receiveAsFlow()

    private var cachedRuns: List<AgentRunLog> = emptyList()
    private var selectedChannel: ChannelType? = null
    private val expandedRunIds = mutableSetOf<String>()

    init {
        observeLogs()
    }

    private fun observeLogs() {
        viewModelScope.launch {
            try {
                store.observeLogs().collect { runs ->
                    cachedRuns = runs
                    // Tự mở rộng lượt chạy mới nhất nếu người dùng chưa chọn mở thẻ nào.
                    if (expandedRunIds.isEmpty() && runs.isNotEmpty()) {
                        expandedRunIds.add(runs.first().id)
                    }
                    publishState(isLoading = false, isLoadError = false)
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (_: Exception) {
                _uiState.value = AgentLogUiState(
                    isLoading = false,
                    isLoadError = true,
                    selectedChannel = selectedChannel,
                )
                _events.trySend(AgentLogEvent.ShowMessage(R.string.agent_log_load_failed))
            }
        }
    }

    /** Lọc danh sách nhật ký theo kênh (`null` = tất cả kênh). */
    fun onChannelFilterSelected(channel: ChannelType?) {
        if (channel == selectedChannel) return
        selectedChannel = channel
        publishState(isLoading = false, isLoadError = _uiState.value.isLoadError)
    }

    /** Mở rộng hoặc thu gọn chi tiết các bước của một lượt chạy. */
    fun onToggleRunExpanded(runId: String) {
        if (!expandedRunIds.add(runId)) {
            expandedRunIds.remove(runId)
        }
        publishState(isLoading = false, isLoadError = _uiState.value.isLoadError)
    }

    /** Yêu cầu xác nhận trước khi xóa toàn bộ nhật ký. */
    fun onClearRequested() {
        val state = _uiState.value
        if (state.isClearing || state.totalRunCount == 0) return
        _events.trySend(AgentLogEvent.ConfirmClearAll)
    }

    /** Xóa toàn bộ nhật ký sau khi người dùng xác nhận. */
    fun onClearConfirmed() {
        if (_uiState.value.isClearing) return
        _uiState.update { it.copy(isClearing = true) }
        viewModelScope.launch {
            try {
                store.clearAll()
                expandedRunIds.clear()
                _uiState.update { it.copy(isClearing = false) }
                _events.trySend(AgentLogEvent.ShowMessage(R.string.agent_log_cleared))
            } catch (ce: CancellationException) {
                throw ce
            } catch (_: Exception) {
                _uiState.update { it.copy(isClearing = false) }
                _events.trySend(AgentLogEvent.ShowMessage(R.string.agent_log_clear_failed))
            }
        }
    }

    private fun publishState(isLoading: Boolean, isLoadError: Boolean) {
        val filtered = selectedChannel?.let { channel ->
            cachedRuns.filter { it.channel == channel }
        } ?: cachedRuns
        _uiState.update { current ->
            current.copy(
                isLoading = isLoading,
                isLoadError = isLoadError,
                selectedChannel = selectedChannel,
                totalRunCount = cachedRuns.size,
                items = filtered.map { run ->
                    AgentLogItemUiState(
                        run = run,
                        isExpanded = run.id in expandedRunIds,
                    )
                },
            )
        }
    }
}
