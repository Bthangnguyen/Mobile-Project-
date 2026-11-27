package com.lttbdd.socialagent.feature.usage

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lttbdd.socialagent.data.ai.LlmPricing
import com.lttbdd.socialagent.data.usage.UsageDashboard
import com.lttbdd.socialagent.data.usage.UsageRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Xem lịch sử theo từng ngày trong tháng này hay theo từng tháng. */
enum class UsageHistoryMode { DAY, MONTH }

enum class UsageMessage { CLEARED, CLEAR_FAILED }

data class UsageUiState(
    /** Ngày đối chiếu bảng giá (ISO `yyyy-MM-dd`), hiển thị cạnh chi phí ước tính. */
    val pricingVersion: String = LlmPricing.VERSION,
    val loading: Boolean = true,
    val loadFailed: Boolean = false,
    val dashboard: UsageDashboard? = null,
    val mode: UsageHistoryMode = UsageHistoryMode.DAY,
    val clearing: Boolean = false,
    /** Thông báo hiện một lần; Fragment gọi [UsageViewModel.onMessageShown] ngay sau khi hiện. */
    val message: UsageMessage? = null,
)

/** Màn "Token & chi phí" (F8, #74): chỉ đọc thống kê và cho xóa; không chặn hay cảnh báo ngân sách (#71). */
@HiltViewModel
class UsageViewModel @Inject constructor(
    private val repository: UsageRepository,
) : ViewModel() {
    private val _uiState = MutableStateFlow(UsageUiState())
    val uiState: StateFlow<UsageUiState> = _uiState.asStateFlow()

    private var loadJob: Job? = null

    /**
     * Gọi mỗi lần màn hình hiện lại. Collect lại từ đầu để "hôm nay"/"tháng này" được tính lại
     * nếu đã qua nửa đêm; dữ liệu cũ vẫn hiển thị trong lúc chờ.
     */
    fun refresh() {
        loadJob?.cancel()
        _uiState.update { it.copy(loading = it.dashboard == null, loadFailed = false) }
        loadJob = viewModelScope.launch {
            repository.dashboard()
                .catch { _uiState.update { it.copy(loading = false, loadFailed = true) } }
                .collect { dashboard ->
                    _uiState.update { it.copy(loading = false, loadFailed = false, dashboard = dashboard) }
                }
        }
    }

    fun onModeSelected(mode: UsageHistoryMode) {
        _uiState.update { it.copy(mode = mode) }
    }

    /** Xóa toàn bộ thống kê; Fragment đã hỏi xác nhận trước khi gọi. */
    fun clear() {
        if (_uiState.value.clearing) return
        _uiState.update { it.copy(clearing = true, message = null) }
        viewModelScope.launch {
            val message = try {
                repository.clear()
                UsageMessage.CLEARED
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                UsageMessage.CLEAR_FAILED
            }
            _uiState.update { it.copy(clearing = false, message = message) }
        }
    }

    /** Thông báo đã hiện: bỏ khỏi state để xoay màn hình hay quay lại màn không hiện lại lần nữa. */
    fun onMessageShown() {
        _uiState.update { it.copy(message = null) }
    }
}
