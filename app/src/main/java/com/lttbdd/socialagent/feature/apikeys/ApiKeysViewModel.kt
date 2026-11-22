package com.lttbdd.socialagent.feature.apikeys

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lttbdd.socialagent.core.model.LlmProvider
import com.lttbdd.socialagent.data.apikeys.ApiKeyStorageException
import com.lttbdd.socialagent.data.apikeys.ApiKeysRepository
import com.lttbdd.socialagent.data.apikeys.KeyStorageFailure
import com.lttbdd.socialagent.data.apikeys.KeyStorageStatus
import com.lttbdd.socialagent.data.apikeys.KeyVerification
import com.lttbdd.socialagent.data.apikeys.VerificationStatus
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class ApiKeyAction { LOAD, VERIFY, SAVE, DELETE }
enum class ApiKeyMessage { SAVED, DELETED, STORAGE_ERROR, UNREADABLE, INPUT_ERROR, OPERATION_ERROR }

/** State chỉ có metadata; không có API key, đoạn cuối key hoặc request HTTP. */
data class ApiKeysUiState(
    val selectedProvider: LlmProvider = LlmProvider.OPENAI,
    val providers: Map<LlmProvider, KeyStorageStatus> = emptyMap(),
    val action: ApiKeyAction? = null,
    val verification: KeyVerification? = null,
    val message: ApiKeyMessage? = null,
)

@HiltViewModel
class ApiKeysViewModel @Inject constructor(private val repository: ApiKeysRepository) : ViewModel() {
    private val _uiState = MutableStateFlow(ApiKeysUiState())
    val uiState: StateFlow<ApiKeysUiState> = _uiState.asStateFlow()
    private var operation: Job? = null
    private var operationId = 0L

    fun refresh() {
        if (operation?.isActive == true) return
        runOperation(ApiKeyAction.LOAD) {
            _uiState.update { it.copy(providers = repository.statuses()) }
        }
    }

    fun selectProvider(provider: LlmProvider) {
        if (operation?.isActive == true || provider !in listOf(LlmProvider.OPENAI, LlmProvider.GEMINI)) return
        _uiState.update { it.copy(selectedProvider = provider, verification = null, message = null) }
    }

    /** Nhận quyền sở hữu buffer từ Fragment, xóa cả khi coroutine chưa kịp chạy hoặc bị hủy. */
    fun verify(input: CharArray, save: Boolean) {
        if (operation?.isActive == true) {
            input.fill('\u0000')
            return
        }
        val provider = _uiState.value.selectedProvider
        runOperation(if (save) ApiKeyAction.SAVE else ApiKeyAction.VERIFY, input) {
            val result = repository.verify(provider, input.concatToString(), save)
            val statuses = repository.statuses()
            _uiState.update {
                it.copy(
                    providers = statuses,
                    verification = result,
                    message = if (save && result.status == VerificationStatus.VALID) ApiKeyMessage.SAVED else null,
                )
            }
        }
    }

    fun delete() {
        if (operation?.isActive == true) return
        val provider = _uiState.value.selectedProvider
        runOperation(ApiKeyAction.DELETE) {
            repository.delete(provider)
            _uiState.update { it.copy(providers = repository.statuses(), message = ApiKeyMessage.DELETED) }
        }
    }

    fun cancelOperation() {
        operationId++
        operation?.cancel()
        operation = null
        _uiState.update { it.copy(action = null) }
    }

    private fun runOperation(action: ApiKeyAction, input: CharArray? = null, block: suspend () -> Unit) {
        val id = ++operationId
        _uiState.update { it.copy(action = action, verification = null, message = null) }
        operation = viewModelScope.launch {
            try {
                block()
            } catch (error: CancellationException) {
                throw error
            } catch (error: ApiKeyStorageException) {
                _uiState.update {
                    it.copy(message = when (error.failure) {
                        KeyStorageFailure.UNREADABLE -> ApiKeyMessage.UNREADABLE
                        KeyStorageFailure.INVALID_INPUT -> ApiKeyMessage.INPUT_ERROR
                        else -> ApiKeyMessage.STORAGE_ERROR
                    })
                }
            } catch (_: Exception) {
                _uiState.update { it.copy(message = ApiKeyMessage.OPERATION_ERROR) }
            } finally {
                input?.fill('\u0000')
                if (id == operationId) _uiState.update { it.copy(action = null) }
            }
        }.also { job -> job.invokeOnCompletion { input?.fill('\u0000') } }
    }
}
